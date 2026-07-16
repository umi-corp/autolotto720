package com.umicorp.autolotto720.dhlottery

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 연금복권720+ 회차/추첨일 계산 (KST 고정).
 *
 * 추첨: 매주 목요일 19:05. 판매 마감: 목 17:00. 다음 회차 판매는 추첨 후 개시.
 * 회차 롤오버 경계는 추첨시각(목 19:05)으로 둔다 — 그 시점 이후엔 이번 회차가 끝나 다음 회차를 표시.
 *
 * 🔴 모듈로 함정: Kotlin/Java `%`는 부호가 피제수를 따르므로 일/금/토요일에 `(4 - dow) % 7`이 음수가
 *    되어 과거로 간다. `Math.floorMod`로 비음수 강제(autolotto의 645 함정과 동일 패턴).
 */
object Round720 {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** 1회차 추첨일 = 2020-05-07(목). 위키백과/나무위키 확인(520→720+ 개편·회차 재시작). Task0가 라이브 (회차,날짜)로 재확인. */
    private val FIRST_ROUND: LocalDate = LocalDate.of(2020, 5, 7)
    private const val THURSDAY = 4 // DayOfWeek: 월=1 .. 목=4 .. 일=7

    /** 지금 판매 중(=다가오는 추첨)인 회차. 구매·홈 카운트다운용. 추첨(목 19:05) 시점에 다음 회차로 롤오버. */
    fun getUpcomingDrawRound(now: ZonedDateTime = ZonedDateTime.now(KST)): Int {
        val nowKst = now.withZoneSameInstant(KST)   // 입력 존 무관 KST 정규화(R1 F14)
        val today = nowKst.toLocalDate()
        val dow = today.dayOfWeek.value
        val daysUntilThursday = Math.floorMod(THURSDAY - dow, 7).toLong()
        val thisThursday = today.plusDays(daysUntilThursday)

        val weeks = ChronoUnit.DAYS.between(FIRST_ROUND, thisThursday) / 7
        var round = (1 + weeks).toInt()

        if (dow == THURSDAY) {
            val drawTime = today.atTime(19, 5).atZone(KST)
            if (!nowKst.isBefore(drawTime)) round += 1   // 정확히 19:05:00부터 다음 회차
        }
        return round.coerceAtLeast(1)
    }

    /** 방금 추첨이 끝난 최신 회차. 목 21:00 결과확인용. 추첨(목 19:05)이 지난 가장 큰 회차. */
    fun getLatestCompletedRound(now: ZonedDateTime = ZonedDateTime.now(KST)): Int {
        val nowKst = now.withZoneSameInstant(KST)
        val today = nowKst.toLocalDate()
        val dow = today.dayOfWeek.value
        // 이번 주(또는 과거) 가장 가까운 목요일
        val daysSinceThursday = Math.floorMod(dow - THURSDAY, 7).toLong()
        val lastThursday = today.minusDays(daysSinceThursday)
        val drawn = if (dow == THURSDAY && nowKst.isBefore(today.atTime(19, 5).atZone(KST)))
            lastThursday.minusWeeks(1)   // 목요일이지만 아직 추첨 전 → 지난주가 최신 완료
        else lastThursday
        val weeks = ChronoUnit.DAYS.between(FIRST_ROUND, drawn) / 7
        return (1 + weeks).toInt().coerceAtLeast(1)
    }

    /** 회차 → 추첨일(그 회차의 목요일). FIRST_ROUND가 목요일이므로 항상 목요일. */
    fun getDrawDate(round: Int): LocalDate {
        require(round >= 1) { "회차는 1 이상이어야 합니다: $round" }
        return FIRST_ROUND.plusWeeks((round - 1).toLong())
    }

    /** 판매마감 시각(목 17:00). 이후 19:05 추첨까지가 판매정지 창. */
    private val SALES_CLOSE: LocalTime = LocalTime.of(17, 0)

    /**
     * 판매마감 가드(720 규칙): 다가오는 회차의 추첨일이 오늘(KST)이고 17:00 이후면 true(마감).
     * 목 19:05 추첨 후엔 다가오는 회차가 다음 주로 롤오버되므로(추첨일≠오늘) false로 다시 열린다.
     * AutoPurchaseWorker(구매 가드)와 PurchaseSetup/Settings VM(입력 검증) 양쪽이 이 단일 판정을 공유해
     * 드리프트를 막는다(645 토요일 규칙 잔재 제거).
     */
    internal fun isSalesClosed(now: ZonedDateTime = ZonedDateTime.now(KST)): Boolean {
        val kstNow = now.withZoneSameInstant(KST)   // 입력 존 무관 KST 정규화(R1 F14)
        return getDrawDate(getUpcomingDrawRound(kstNow)) == kstNow.toLocalDate() && kstNow.toLocalTime() >= SALES_CLOSE
    }
}
