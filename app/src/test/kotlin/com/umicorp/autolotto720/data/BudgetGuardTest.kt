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

    @Test fun `attempt exceeding daily but within weekly is blocked`() {
        // daily만 트립·weekly는 여유 → daily 부등식 격리(부등식을 지우면 이 테스트만 깨진다).
        val led = listOf(e(325, 100, 4000))
        assertFalse(BudgetGuard.check(led, today = 100, round = 325, attempt = 2000, daily = 5000, weekly = 150000))
    }

    @Test fun `pending matching only round blocks via weekly`() {
        // pending이 round만 매칭(다른 날) → pRound 분기 격리.
        val pending = e(325, 99, 5000)   // 다른 날(99), 같은 회차(325)
        assertFalse(BudgetGuard.check(emptyList(), today = 100, round = 325, attempt = 1000, daily = 150000, weekly = 5000, pending = pending))
    }

    @Test fun `pending matching only day blocks via daily`() {
        // pending이 날짜만 매칭(다른 회차) → pToday 분기 격리.
        val pending = e(300, 100, 5000)   // 같은 날(100), 다른 회차(300)
        assertFalse(BudgetGuard.check(emptyList(), today = 100, round = 325, attempt = 1000, daily = 5000, weekly = 150000, pending = pending))
    }

    @Test fun `non-matching pending does not affect the check`() {
        // 다른 날·다른 회차 pending은 0 기여 → 경계값(=한도) 허용 유지.
        val pending = e(300, 90, 5000)   // 다른 날·다른 회차
        assertTrue(BudgetGuard.check(emptyList(), today = 100, round = 325, attempt = 5000, daily = 5000, weekly = 5000, pending = pending))
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
