package com.umicorp.autolotto720.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetGuardTest {

    private fun e(round: Int, day: Long, amt: Int) = SpendEntry(round, day, amt)

    @Test fun `empty ledger allows any attempt within limits`() {
        assertTrue(BudgetGuard.check(emptyList(), today = 100, round = 325, attempt = 5000, daily = 5000, weekly = 5000))
    }

    @Test fun `attempt exactly at daily limit is allowed`() {
        val led = listOf(e(324, 99, 0))
        assertTrue(BudgetGuard.check(led, today = 100, round = 325, attempt = 5000, daily = 5000, weekly = 5000))
    }

    @Test fun `attempt exceeding daily is blocked`() {
        val led = listOf(e(325, 100, 1000))
        assertFalse(BudgetGuard.check(led, today = 100, round = 325, attempt = 5000, daily = 5000, weekly = 5000))
    }

    @Test fun `attempt exceeding weekly-per-round is blocked`() {
        val led = listOf(e(325, 100, 5000))
        assertFalse(BudgetGuard.check(led, today = 101, round = 325, attempt = 1000, daily = 150000, weekly = 5000))
    }

    @Test fun `unsettled pending amount counts toward the limit`() {
        // 프로세스 사망으로 원장엔 없지만 PENDING이 남은 경우 — 예산에 반영돼 재사용을 막는다.
        val pending = e(325, 100, 5000)
        assertFalse(BudgetGuard.check(emptyList(), today = 100, round = 325, attempt = 1000, daily = 5000, weekly = 5000, pending = pending))
    }

    @Test fun `parseLedger drops entries with negative amount`() {
        assertEquals(emptyList<SpendEntry>(), BudgetGuard.parseLedger("""[{"round":1,"epochDay":1,"amount":-5}]"""))
    }

    @Test fun `record appends and prunes entries older than 7 days`() {
        val old = e(300, 90, 5000)          // 90 < 100-7 → 정리 대상
        val recent = e(324, 98, 1000)
        val next = BudgetGuard.record(listOf(old, recent), e(325, 100, 5000), today = 100)
        assertEquals(listOf(recent, e(325, 100, 5000)), next)
    }

    @Test fun `ledger json round-trips`() {
        val led = listOf(e(325, 100, 5000), e(325, 100, 1000))
        assertEquals(led, BudgetGuard.parseLedger(BudgetGuard.toJson(led)))
    }

    @Test fun `parseLedger tolerates corrupt json as empty`() {
        assertEquals(emptyList<SpendEntry>(), BudgetGuard.parseLedger("not json"))
        assertEquals(emptyList<SpendEntry>(), BudgetGuard.parseLedger(null))
    }
}
