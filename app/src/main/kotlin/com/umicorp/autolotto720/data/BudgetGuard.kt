package com.umicorp.autolotto720.data

import org.json.JSONArray
import org.json.JSONObject

/** 지출 원장 1건 — 시도 단위. amount=시도 금액(성공/부분/불명 공통, 보수 기록). */
data class SpendEntry(val round: Int, val epochDay: Long, val amount: Int)

/**
 * 예산 가드(순수) — 일/회차(주간) 시도 금액 합산으로 결제 진입을 허용/차단한다.
 * 720은 주 1회 추첨이라 "주간" = 회차(round) 집계. 보수 원칙: 시도 금액을 미리 더해 검사한다.
 * epochDay는 KST 기준으로 호출부가 산출(Round720.KST) — 서버 자정 경계와 정렬.
 */
object BudgetGuard {

    fun spentToday(entries: List<SpendEntry>, today: Long): Int =
        entries.filter { it.epochDay == today }.sumOf { it.amount }

    fun spentInRound(entries: List<SpendEntry>, round: Int): Int =
        entries.filter { it.round == round }.sumOf { it.amount }

    /**
     * true=허용. `기존 지출 + 시도 금액 ≤ 한도`가 일·회차 양쪽에서 성립할 때만.
     * [pending]=미결 PENDING(확정 전 결제) — 프로세스 사망으로 원장에 아직 없는 시도 금액을 예산에 포함해
     * 같은 예산의 재사용을 막는다(원장은 확정 후에만 기록되므로 이중 계산되지 않는다).
     */
    fun check(entries: List<SpendEntry>, today: Long, round: Int, attempt: Int, daily: Int, weekly: Int, pending: SpendEntry? = null): Boolean {
        val pToday = if (pending?.epochDay == today) pending.amount else 0
        val pRound = if (pending?.round == round) pending.amount else 0
        return spentToday(entries, today) + pToday + attempt <= daily &&
            spentInRound(entries, round) + pRound + attempt <= weekly
    }

    /** 시도 금액을 원장에 추가하고 today-7 이전 항목을 정리해 반환(순수 — 저장은 호출부). */
    fun record(entries: List<SpendEntry>, entry: SpendEntry, today: Long): List<SpendEntry> =
        (entries + entry).filter { it.epochDay > today - 7 }

    /** 손상·음수·필드 누락 항목은 스킵(빈 원장으로 fail-open — 손상 시 결제 봉쇄는 사용성 훼손이 커 미채택). */
    fun parseLedger(json: String?): List<SpendEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                val amount = it.optInt("amount", -1)
                if (amount < 0) null else SpendEntry(it.optInt("round"), it.optLong("epochDay"), amount)
            }
        }
    }

    fun toJson(entries: List<SpendEntry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("round", it.round).put("epochDay", it.epochDay).put("amount", it.amount)) }
    }.toString()
}
