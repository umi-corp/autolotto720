package com.umicorp.autolotto720.data

import org.junit.Assert.assertEquals
import org.junit.Test

class Pension720Test {
    @Test fun number_is_six_char_zero_padded() {
        val t = Ticket720(round = 100, jo = 3, number = "000727", purchaseDate = java.time.LocalDateTime.of(2026, 7, 16, 10, 0))
        assertEquals(6, t.number.length)
        assertEquals("000727", t.number)
        assertEquals(3, t.jo)
    }
    @Test fun winning_holds_group_number_and_bonus() {
        val w = WinningNumbers720(round = 100, jo = 2, number = "119392", bonusNumber = "667975", date = "2026-07-16")
        assertEquals("119392", w.number)
        assertEquals("667975", w.bonusNumber)
    }

    // Invariants (R1 F8 — money-adjacent: reject malformed tickets at construction, don't let padStart hide them)
    @Test(expected = IllegalArgumentException::class) fun rejects_seven_digit_number() {
        Ticket720(round = 1, jo = 3, number = "1234567", purchaseDate = java.time.LocalDateTime.of(2026, 7, 16, 10, 0))
    }
    @Test(expected = IllegalArgumentException::class) fun rejects_non_numeric_number() {
        Ticket720(round = 1, jo = 3, number = "12a456", purchaseDate = java.time.LocalDateTime.of(2026, 7, 16, 10, 0))
    }
    @Test(expected = IllegalArgumentException::class) fun rejects_group_out_of_range() {
        Ticket720(round = 1, jo = 6, number = "000727", purchaseDate = java.time.LocalDateTime.of(2026, 7, 16, 10, 0))
    }
    @Test(expected = IllegalArgumentException::class) fun rejects_non_positive_round() {
        Ticket720(round = 0, jo = 3, number = "000727", purchaseDate = java.time.LocalDateTime.of(2026, 7, 16, 10, 0))
    }
    @Test(expected = IllegalArgumentException::class) fun winning_rejects_bad_number() {
        WinningNumbers720(round = 1, jo = 3, number = "12345", bonusNumber = "667975", date = "2026-07-16")
    }
}
