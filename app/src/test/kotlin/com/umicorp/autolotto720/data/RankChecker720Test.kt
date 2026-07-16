package com.umicorp.autolotto720.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RankChecker720Test {
    private val win = WinningNumbers720(round = 1, jo = 3, number = "123456", bonusNumber = "999888", date = "2026-07-16")
    private fun rank(jo: Int, n: String) = RankChecker720.rankOf(jo, n, win)

    @Test fun first_needs_group_and_all_six() = assertEquals(Rank720.FIRST, rank(3, "123456"))
    @Test fun second_is_six_match_wrong_group() = assertEquals(Rank720.SECOND, rank(1, "123456"))
    @Test fun bonus_is_full_bonus_match() = assertEquals(Rank720.BONUS, rank(5, "999888"))
    @Test fun third_last_five() = assertEquals(Rank720.THIRD, rank(1, "023456"))
    @Test fun fourth_last_four() = assertEquals(Rank720.FOURTH, rank(1, "003456"))
    @Test fun fifth_last_three() = assertEquals(Rank720.FIFTH, rank(1, "000456"))
    @Test fun sixth_last_two() = assertEquals(Rank720.SIXTH, rank(1, "000056"))
    @Test fun seventh_last_one() = assertEquals(Rank720.SEVENTH, rank(1, "000006"))
    @Test fun none_when_last_digit_differs() = assertEquals(Rank720.NONE, rank(1, "000007"))
    // R1 F8/claude#9: malformed input must fail fast, not silently pad into a suffix match
    @Test(expected = IllegalArgumentException::class) fun rejects_wrong_length() = run { rank(1, "12345"); Unit }
    @Test(expected = IllegalArgumentException::class) fun rejects_bad_group() = run { rank(0, "123456"); Unit }
    @Test fun bonus_beats_suffix_when_full_bonus() {
        val w2 = WinningNumbers720(1, 3, "123456", "023456", "2026-07-16") // bonus shares last5 with a 3등 candidate
        // ticket "023456": full bonus match → BONUS (not 3등)
        assertEquals(Rank720.BONUS, RankChecker720.rankOf(1, "023456", w2))
    }
    @Test fun group_ignored_for_suffix_ranks() = assertEquals(Rank720.THIRD, rank(4, "023456"))
}
