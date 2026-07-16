package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.WinningNumbers720
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 연금복권720+ 당첨 확인 서비스. 로그인 불필요(720-api-contract.md §1).
 *
 * 엔드포인트는 회차 쿼리파라미터가 없다 — 전 회차(`data.result[]`, 최신순)를 반환하므로
 * `psltEpsd`로 필터링한다. [ResultService]와 동일한 세션-방문 + 리다이렉트 추적 구조.
 */
class ResultService720(private val session: DhlotterySession = DhlotterySession()) {

    /**
     * 당첨번호 조회. [round] null이면 최신 회차(result[0]).
     * 실패(비200 / HTML 센티넬 / 빈 목록 / 예외) 시 null.
     */
    suspend fun getWinningNumbers(round: Int? = null): WinningNumbers720? = withContext(Dispatchers.IO) {
        try {
            // 쿠키 획득용 메인 방문
            session.get(session.baseUrl, follow = true).close()

            session.get(
                session.base(ApiConstants.WINNING_NUMBER_720),
                mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
                follow = true,
            ).use { resp ->
                if (resp.code != 200) return@withContext null
                val body = resp.body?.string().orEmpty()
                // HTML(로그인/에러 센티넬) 가드 — JSON이 아니면 null.
                if (!body.trimStart().startsWith("{")) return@withContext null

                val items = JSONObject(body).optJSONObject("data")?.optJSONArray("result")
                if (items == null || items.length() == 0) return@withContext null

                // 명시 회차 조회: optJSONObject로 null/비객체 원소를 건너뛰며 타깃을 찾는다 — 앞쪽 malformed
                // 원소 하나가 유효한 타깃 조회 전체를 실패시키지 않도록(R4). 선택된 타깃 자체의 파싱 실패는 여전히 null.
                // 최신 회차 조회(round==null): index 0만 엄격 파싱한다(R2 N5) — malformed면 null.
                // skip-and-search로 폴백하면 result[0]이 malformed일 때 조용히 더 오래된 회차를 "최신"으로 반환하게 된다.
                val item = if (round != null) {
                    (0 until items.length()).asSequence()
                        .mapNotNull { items.optJSONObject(it) }
                        .find { it.optInt("psltEpsd") == round }
                } else {
                    items.optJSONObject(0)
                } ?: return@withContext null

                WinningNumbers720(
                    round = item.getInt("psltEpsd"),
                    jo = item.getString("wnBndNo").toInt(),
                    number = item.getString("wnRnkVl"),
                    bonusNumber = item.getString("bnsRnkVl"),
                    date = parseDate(item.optString("psltRflYmd", "")),
                )
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e   // 취소는 삼키지 않는다(R3).
            null
        }
    }

    companion object {
        /** yyyyMMdd(8자리) → yyyy-MM-dd, 그 외는 원문 그대로. */
        private fun parseDate(raw: String): String =
            if (raw.length == 8) "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}" else raw
    }
}
