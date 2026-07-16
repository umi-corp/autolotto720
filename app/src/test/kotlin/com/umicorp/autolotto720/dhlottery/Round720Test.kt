package com.umicorp.autolotto720.dhlottery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class Round720Test {
    private val kst = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, kst)

    @Test fun anchor_draw_date_is_verified_may_7_2020() =
        assertEquals(LocalDate.of(2020, 5, 7), Round720.getDrawDate(1)) // 1회차, 검증된 앵커

    @Test fun anchor_round_is_one() =
        assertEquals(1, Round720.getUpcomingDrawRound(at(2020, 5, 5, 10, 0))) // Tue before first Thu draw

    // Live anchor pairs captured from dhlottery (selectPstPt720WnList.do, Task 0) — drift net.
    @Test fun live_pair_319() = assertEquals(LocalDate.of(2026, 6, 11), Round720.getDrawDate(319))
    @Test fun live_pair_323() = assertEquals(LocalDate.of(2026, 7, 9), Round720.getDrawDate(323))

    @Test fun wednesday_points_to_upcoming_thursday_round() {
        val r = Round720.getUpcomingDrawRound(at(2026, 7, 15, 10, 0)) // Wed
        assertEquals(LocalDate.of(2026, 7, 16), Round720.getDrawDate(r))
    }

    @Test fun thursday_before_1905_still_upcoming_round() {
        val r = Round720.getUpcomingDrawRound(at(2026, 7, 16, 12, 0))
        assertEquals(LocalDate.of(2026, 7, 16), Round720.getDrawDate(r))
    }

    @Test fun thursday_at_exactly_1905_rolls_to_next_round() {
        val r = Round720.getUpcomingDrawRound(at(2026, 7, 16, 19, 5))
        assertEquals(LocalDate.of(2026, 7, 23), Round720.getDrawDate(r)) // !isBefore → 19:05:00 counts as drawn
    }

    @Test fun thursday_1904_still_upcoming() {
        val r = Round720.getUpcomingDrawRound(at(2026, 7, 16, 19, 4))
        assertEquals(LocalDate.of(2026, 7, 16), Round720.getDrawDate(r))
    }

    @Test fun sunday_floormod_trap_does_not_go_negative() {
        val r = Round720.getUpcomingDrawRound(at(2026, 7, 19, 10, 0)) // Sun
        assertEquals(LocalDate.of(2026, 7, 23), Round720.getDrawDate(r))
    }

    // Result-check accessor: the round JUST drawn, not the one now on sale.
    @Test fun latest_completed_thursday_2100_is_todays_draw() {
        val r = Round720.getLatestCompletedRound(at(2026, 7, 16, 21, 0)) // Thu 21:00, after 19:05 draw
        assertEquals(LocalDate.of(2026, 7, 16), Round720.getDrawDate(r))
    }

    @Test fun latest_completed_wednesday_is_last_week() {
        val r = Round720.getLatestCompletedRound(at(2026, 7, 15, 10, 0)) // Wed, before this week's draw
        assertEquals(LocalDate.of(2026, 7, 9), Round720.getDrawDate(r))
    }

    @Test fun drawdate_is_always_thursday() {
        for (round in 1..300) assertEquals(java.time.DayOfWeek.THURSDAY, Round720.getDrawDate(round).dayOfWeek)
    }

    // FIX 6: getDrawDate 하한 가드.
    @Test(expected = IllegalArgumentException::class)
    fun getdrawdate_rejects_round_below_one() { Round720.getDrawDate(0) }

    // FIX 5: 720 판매마감 가드(목 17:00~19:05 죽은 창) — 워커·VM 입력검증이 공유하는 단일 판정.
    @Test fun sales_open_thursday_1659() = assertFalse(Round720.isSalesClosed(at(2026, 7, 16, 16, 59)))
    @Test fun sales_closed_thursday_1700() = assertTrue(Round720.isSalesClosed(at(2026, 7, 16, 17, 0)))
    @Test fun sales_open_wednesday() = assertFalse(Round720.isSalesClosed(at(2026, 7, 15, 10, 0)))
    @Test fun sales_open_friday() = assertFalse(Round720.isSalesClosed(at(2026, 7, 17, 10, 0)))
    @Test fun sales_open_thursday_after_1905_draw_rollover() = assertFalse(Round720.isSalesClosed(at(2026, 7, 16, 19, 6)))

    // R2 N1: 후보 스케줄(요일,시,분) 검증 — 목 17:00~금 00:00(목 17:00~23:59) 설정 불가. VM 입력검증이 공유하는 순수 판정.
    @Test fun schedule_thursday_1659_valid() = assertFalse(Round720.isScheduleBlocked(4, 16, 59))
    @Test fun schedule_thursday_1700_blocked() = assertTrue(Round720.isScheduleBlocked(4, 17, 0))
    @Test fun schedule_thursday_2000_blocked() = assertTrue(Round720.isScheduleBlocked(4, 20, 0))
    @Test fun schedule_thursday_2359_blocked() = assertTrue(Round720.isScheduleBlocked(4, 23, 59))
    @Test fun schedule_friday_0000_valid() = assertFalse(Round720.isScheduleBlocked(5, 0, 0))
    @Test fun schedule_friday_1000_valid() = assertFalse(Round720.isScheduleBlocked(5, 10, 0))
    @Test fun schedule_wednesday_1800_valid() = assertFalse(Round720.isScheduleBlocked(3, 18, 0))
    @Test fun schedule_monday_1700_valid() = assertFalse(Round720.isScheduleBlocked(1, 17, 0))
}
