package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.LedgerRow
import com.umicorp.autolotto720.data.Rank720
import com.umicorp.autolotto720.data.RankChecker720
import com.umicorp.autolotto720.data.RowStatus
import com.umicorp.autolotto720.data.Window
import com.umicorp.autolotto720.data.WindowFetcher
import com.umicorp.autolotto720.data.WinningNumbers720
import com.umicorp.autolotto720.data.lumpPrize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 원장 목록의 한 창을 모든 페이지까지 받아 720 티켓 행(키 `ntslOrdrNo|조:번호`)으로 바꾸고,
 * 당첨번호로 등수를 다시 판정한다. 판정하지 못한 행은 `u=true`로 남겨 [resolveUnresolved]가 다음 동기화에 고친다.
 */
class LedgerService720(
    private val session: DhlotterySession,
    private val results: ResultService720,
) : WindowFetcher {

    private var visited = false

    override suspend fun fetch(w: Window): List<LedgerRow> {
        val tickets = withContext(Dispatchers.IO) {
            // 원장 페이지 방문(Referer·쿠키)은 프리필 창마다가 아니라 실행당 한 번이면 된다.
            if (!visited) {
                session.get(session.base(ApiConstants.MYPAGE_LEDGER)).close()
                visited = true
            }
            fetchLedgerPages(session, w).filter { it.optString("ltGdsCd") == "LP72" }.flatMap(::toTickets)
        }
        val winning = results.getWinningNumbersMap(tickets.mapTo(mutableSetOf()) { it.row.r })
        return tickets.map { t -> winning[t.row.r]?.let { judged(t.row, it) } ?: t.fallback() }
    }

    /** 원장 행 하나(주문)의 gmInfo "조:번호[,조:번호…]"를 티켓마다 나눈다. 손상된 쌍은 건너뛴다. */
    private fun toTickets(o: JSONObject): List<Ticket> {
        val order = o.optString("ntslOrdrNo").ifEmpty { return emptyList() }
        val d = purchaseDate(o) ?: return emptyList()
        val r = o.optString("ltEpsd").toIntOrNull() ?: return emptyList()
        val st = rowStatus(o.optString("ltWnResult"))
        return o.optString("gmInfo").split(",").mapNotNull { pair ->
            val (jo, number) = parseTicket(pair.trim()) ?: return@mapNotNull null
            Ticket(LedgerRow("$order|$jo:$number", d, r, 1, st, 0), HistoryService720.rankOf(o.optString("wnRnk")))
        }
    }

    private data class Ticket(val row: LedgerRow, val ledgerRank: Rank720) {
        /** 당첨번호가 없을 때: 추첨 전은 그대로, 추첨 후는 원장 등수로 잠정 기록(연금식 등수는 금액 0). */
        fun fallback(): LedgerRow =
            if (row.st == RowStatus.PENDING) row
            else row.copy(rk = ledgerRank.name, amt = ledgerRank.lumpPrize, u = true)
    }
}

/** `u=true` 행을 당첨번호로 다시 판정한다. 당첨번호가 아직 없는 회차는 그대로 둔다. */
suspend fun resolveUnresolved(rows: List<LedgerRow>, results: ResultService720): List<LedgerRow> {
    val rounds = rows.filter { it.u }.mapTo(mutableSetOf()) { it.r }
    if (rounds.isEmpty()) return rows
    val winning = results.getWinningNumbersMap(rounds)
    return rows.map { row -> winning[row.r]?.takeIf { row.u }?.let { judged(row, it) } ?: row }
}

/** 키의 `조:번호`로 등수를 정한다. 일시금은 등수에서 파생하고 연금식(1·2등·보너스)은 0이다. */
private fun judged(row: LedgerRow, win: WinningNumbers720): LedgerRow {
    val (jo, number) = parseTicket(row.k.substringAfter('|')) ?: return row
    val rank = RankChecker720.rankOf(jo, number, win)
    return row.copy(
        st = if (rank == Rank720.NONE) RowStatus.LOSE else RowStatus.WIN,
        amt = rank.lumpPrize,
        rk = rank.name,
        u = false,
    )
}

private fun parseTicket(s: String): Pair<Int, String>? {
    val jo = s.substringBefore(':').trim().toIntOrNull()?.takeIf { it in 1..5 } ?: return null
    val number = s.substringAfter(':', "").trim().takeIf { it.matches(HistoryService720.SIX_DIGITS) } ?: return null
    return jo to number
}

private const val PAGE_SIZE = 100
private const val MAX_PAGES = 10
private val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

private fun rowStatus(ltWnResult: String) = when (ltWnResult) {
    "당첨" -> RowStatus.WIN
    "낙첨" -> RowStatus.LOSE
    else -> RowStatus.PENDING
}

/** 구매일 `eltOrdrDt`는 "yyyy-MM-dd"로 온다(2026-11-22 실측). 뒤에 시각이 붙어도 날짜만 읽는다. */
private fun purchaseDate(o: JSONObject): LocalDate? = runCatching { LocalDate.parse(o.optString("eltOrdrDt").take(10)) }.getOrNull()

/**
 * 창 하나의 원장 목록 행 전부(상품 구분 없이). 일부 페이지만 받은 창을 반영하면 행이 사라지므로
 * 페이지 도중 실패·[MAX_PAGES] 초과는 모두 [IOException]이다.
 */
private fun fetchLedgerPages(session: DhlotterySession, w: Window): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    for (pageNum in 1..MAX_PAGES) {
        val body = session.get(
            session.base(ApiConstants.PURCHASE_HISTORY) +
                "?srchStrDt=${YMD.format(w.from)}&srchEndDt=${YMD.format(w.to)}" +
                "&pageNum=$pageNum&recordCountPerPage=$PAGE_SIZE&_=${System.currentTimeMillis()}",
            mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to session.base(ApiConstants.MYPAGE_LEDGER),
            ),
        ).use {
            if (!it.isSuccessful) throw IOException("ledger_list_http_${it.code}")
            it.body?.string().orEmpty()
        }
        if (!body.trimStart().startsWith("{")) throw IOException("ledger_list_not_json")
        // 기간 거부("조회기간은 최대 3개월…")는 data=null로 온다.
        val data = JSONObject(body).optJSONObject("data") ?: throw IOException("ledger_list_no_data")
        val list = data.optJSONArray("list")
        val rows = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }
        out += rows
        if (out.size >= data.optInt("total", 0)) return out
        if (rows.isEmpty()) throw IOException("ledger_list_short")
    }
    throw IOException("ledger_list_page_cap")
}
