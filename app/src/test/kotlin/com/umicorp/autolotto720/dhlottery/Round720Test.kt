package com.umicorp.autolotto720.dhlottery

import org.junit.Assert.assertEquals
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
}
