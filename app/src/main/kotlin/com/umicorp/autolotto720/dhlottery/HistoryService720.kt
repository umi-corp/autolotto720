package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.Rank720
import com.umicorp.autolotto720.data.RankChecker720
import com.umicorp.autolotto720.data.Ticket720
import com.umicorp.autolotto720.data.lumpPrize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 연금복권720+ 구매 내역 조회 (라이브 캡처로 계약 확정).
 *
 * 645와 달리 720은 **원장 목록 항목 자체에 티켓 정보가 모두 담긴다** — 별도 상세 엔드포인트가 필요 없다.
 * 원장 항목(`data.list[]`, `ltGdsCd == "LP72"`) 필드:
 *   gmInfo="조:번호[,조:번호…]" · ltEpsd(회차) · eltOrdrDt(구매일) · epsdRflDt(추첨일) ·
 *   ltWnResult("미추첨"=추첨 전) · wnRnk(당첨 등수 1~7) · ltWnAmt(당첨금) — 뒤 둘은 **주문 단위 대표값**,
 *   게임별 등수·당첨금은 [resolveRanks]가 당첨번호로 재판정한다.
 *
 * 흐름: 마이페이지 원장 방문(Referer용) → selectMyLotteryledger.do(GET, 기간·페이징) →
 *       LP72 항목의 gmInfo를 게임별 [Ticket720]으로 평탄화. 세션 만료(HTML)면 빈 목록.
 *
 * 읽기전용 — 머니패스가 아니므로 [Feature720.PURCHASE_ENABLED] 게이트와 무관하게 항상 조회한다.
 */
class HistoryService720(
    private val session: DhlotterySession,
    private val resultService: ResultService720,
) {

    /** 최근 구매 내역(최근 90일, 최대 [count]건) — 결과확인 워커/내역 화면용. */
    suspend fun fetchRecentPurchases(count: Int = 10): List<Ticket720> {
        val today = LocalDate.now(Round720.KST)
        return fetchPurchases(today.minusDays(90), today, count)
    }

    /** [from]~[to](포함) 기간의 구매 내역. 동행복권 조회창 한도(최대 3개월)는 호출자가 지킨다. */
    suspend fun fetchPurchases(from: LocalDate, to: LocalDate, count: Int = Int.MAX_VALUE): List<Ticket720> =
        withContext(Dispatchers.IO) {
            // 1. 마이페이지 원장 방문 (Referer용)
            session.get(session.base(ApiConstants.MYPAGE_LEDGER)).close()

            // 2. 구매 목록 조회 (GET, 기간·페이징) — 캐시 무효화 _ 파라미터는 645와 동일 관례.
            val listBody = session.get(
                session.base(ApiConstants.PURCHASE_HISTORY) +
                    "?srchStrDt=${DATE_FMT.format(from)}&srchEndDt=${DATE_FMT.format(to)}" +
                    "&pageNum=1&recordCountPerPage=100&_=${System.currentTimeMillis()}",
                mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to session.base(ApiConstants.MYPAGE_LEDGER),
                ),
            ).use { it.body?.string().orEmpty() }

            // HTML(세션 만료)이면 JSONObject가 던진다 → 빈 목록으로 안전화.
            val items = runCatching { JSONObject(listBody).optJSONObject("data")?.optJSONArray("list") }.getOrNull()
                ?: return@withContext emptyList()

            val tickets = mutableListOf<Ticket720>()
            outer@ for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("ltGdsCd") != "LP72") continue  // 연금복권720+ 만

                val round = item.optString("ltEpsd").toIntOrNull()
                    ?: item.optString("ltEpsdView").filter { it.isDigit() }.toIntOrNull() ?: continue
                val drawn = item.optString("ltWnResult") != "미추첨"
                val rank = if (!drawn) Rank720.PENDING else rankOf(item.optString("wnRnk"))
                val prize = item.optString("ltWnAmt").filter { it.isDigit() }.toLongOrNull() ?: 0L
                val purchaseDt = parseDate(item.optString("eltOrdrDt"))

                // gmInfo="조:번호[,조:번호…]" — 게임별 티켓으로 평탄화. 손상 항목은 건너뛴다.
                for (pair in item.optString("gmInfo").split(",")) {
                    val (joStr, numStr) = pair.split(":").let { it.getOrNull(0).orEmpty().trim() to it.getOrNull(1).orEmpty().trim() }
                    val jo = joStr.toIntOrNull() ?: continue
                    if (jo !in 1..5 || !numStr.matches(SIX_DIGITS)) continue
                    tickets += Ticket720(
                        round = round, jo = jo, number = numStr,
                        rank = rank, prize = prize, checked = drawn, purchaseDate = purchaseDt,
                    )
                    if (tickets.size >= count) break@outer
                }
            }
            resolveRanks(tickets)
        }

    /**
     * 원장 `wnRnk`/`ltWnAmt`는 **주문 단위**(gmInfo 최대 5게임 묶음의 대표값)라 게임별 등수가 아니다 —
     * 그대로 쓰면 주문 내 1게임만 당첨돼도 전 게임이 그 등수·당첨금으로 표시된다. 당첨번호가 조회되는
     * 회차는 게임별로 [RankChecker720] 재판정(공식 규칙: 1등=조+6자리, 2등=조무관 6자리, 보너스=별도
     * 6자리, 3~7등=끝 5~1자리)하고 일시금([lumpPrize])도 등수에서 파생한다. 원장이 "미추첨"으로
     * 뒤처져도 당첨번호가 있으면 확정 반영한다(워커 R2 N4와 동일 규칙). 당첨번호 조회 실패
     * ([ResultService720.getWinningNumbersMap]은 던지지 않고 빈 맵)는 원장 값 유지 — 다음 로드에서 자기치유.
     */
    private suspend fun resolveRanks(tickets: List<Ticket720>): List<Ticket720> {
        if (tickets.isEmpty()) return tickets
        val winning = resultService.getWinningNumbersMap(tickets.mapTo(mutableSetOf()) { it.round })
        return tickets.map { t ->
            val w = winning[t.round] ?: return@map t
            val r = RankChecker720.rankOf(t.jo, t.number, w)
            t.copy(rank = r, prize = r.lumpPrize, checked = true)
        }
    }

    private companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val SIX_DIGITS = Regex("\\d{6}")

        /** wnRnk(1~7=1~7등, 8=보너스) → [Rank720]. 추첨됐으나 미당첨(null/0/범위밖) → NONE. */
        fun rankOf(wnRnk: String): Rank720 = when (wnRnk.trim()) {
            "1" -> Rank720.FIRST; "2" -> Rank720.SECOND; "3" -> Rank720.THIRD; "4" -> Rank720.FOURTH
            "5" -> Rank720.FIFTH; "6" -> Rank720.SIXTH; "7" -> Rank720.SEVENTH; "8" -> Rank720.BONUS
            else -> Rank720.NONE
        }

        /** yyyy-MM-dd / yyyyMMdd → 자정 LocalDateTime. 실패 시 now(KST). */
        fun parseDate(raw: String): LocalDateTime {
            runCatching { return LocalDate.parse(raw).atStartOfDay() }
            runCatching { return LocalDate.parse(raw, DATE_FMT).atStartOfDay() }
            return LocalDateTime.now(Round720.KST)
        }
    }
}
