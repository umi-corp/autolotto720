package com.umicorp.autolotto720.scheduler

import com.umicorp.autolotto720.data.Rank720
import com.umicorp.autolotto720.data.Ticket720
import com.umicorp.autolotto720.data.WinningNumbers720
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * CheckResultWorker의 순수 판정/알림문구 로직 테스트.
 *
 * Worker 본체는 android.content.Context·SecureStore·네트워크 로그인에 의존해 이 프로젝트의 JVM
 * 단위테스트(Robolectric·mockito 미사용)로는 인스턴스화할 수 없다(645 레이어도 동일 제약). 대신
 * [resolveCheckOutcome]/[buildMatchedNotification]/[buildFallbackNotification]로 I/O에서 분리해
 * 노출한 순수 결정 로직을 직접 검증한다 — 결과 미게시 재시도, 매칭 없음 폴백, PENDING 로컬 재계산,
 * 다중 티켓 알림 문구를 모두 여기서 핀한다.
 */
class CheckResultWorkerTest {

    private val winning = WinningNumbers720(round = 319, jo = 3, number = "201327", bonusNumber = "632035", date = "2026-06-11")

    private fun ticket(jo: Int, number: String, rank: Rank720?, round: Int = 319, prize: Long = 0) = Ticket720(
        round = round,
        jo = jo,
        number = number,
        rank = rank,
        prize = prize,
        purchaseDate = LocalDateTime.of(2026, 6, 4, 10, 0),
    )

    // ---------- 결과 미게시 → 재시도 ----------

    @Test fun `unposted winning numbers retries`() {
        val outcome = resolveCheckOutcome(winning = null, allTickets = emptyList(), round = 319)
        assertEquals(CheckOutcome.Retry, outcome)
    }

    // ---------- FIX 1: 미게시 마지막 시도 종료(무한 재시도 방지) ----------

    @Test fun `unposted before last attempt retries (no notification)`() {
        assertNull(unpostedNotification(319, lastAttempt = false))
    }

    @Test fun `unposted on last attempt terminates with a failure notice`() {
        val (title, body) = unpostedNotification(319, lastAttempt = true)!!
        assertTrue(title.contains("319"))
        assertTrue(body.contains("당첨번호 미게시"))
    }

    // ---------- 매칭 구매 없음 → 폴백(당첨번호만) ----------

    @Test fun `no matching ticket for round falls back to winning-numbers-only`() {
        val otherRoundTicket = ticket(jo = 1, number = "111111", rank = Rank720.NONE, round = 300)
        val outcome = resolveCheckOutcome(winning, allTickets = listOf(otherRoundTicket), round = 319)
        assertTrue(outcome is CheckOutcome.NoMatch)
        assertEquals(winning, (outcome as CheckOutcome.NoMatch).winning)
    }

    @Test fun `empty ticket ledger falls back to winning-numbers-only`() {
        val outcome = resolveCheckOutcome(winning, allTickets = emptyList(), round = 319)
        assertTrue(outcome is CheckOutcome.NoMatch)
    }

    // ---------- PENDING 티켓 로컬 재계산 ----------

    @Test fun `pending ticket recomputes rank locally instead of staying pending`() {
        val pending = ticket(jo = 3, number = "201327", rank = Rank720.PENDING) // matches winning exactly → 1등
        val outcome = resolveCheckOutcome(winning, allTickets = listOf(pending), round = 319)
        val matched = outcome as CheckOutcome.Matched
        assertEquals(Rank720.FIRST, matched.tickets.single().rank)
    }

    @Test fun `already-resolved rank is left untouched`() {
        val resolved = ticket(jo = 1, number = "999999", rank = Rank720.NONE)
        val outcome = resolveCheckOutcome(winning, allTickets = listOf(resolved), round = 319)
        val matched = outcome as CheckOutcome.Matched
        assertEquals(Rank720.NONE, matched.tickets.single().rank)
    }

    // FIX 2: null 등수(미설정)도 재계산 — 낙첨(NONE)으로 오보하지 않는다.
    @Test fun `null-rank ticket is recomputed not left unset`() {
        val unset = ticket(jo = 3, number = "201327", rank = null) // matches winning exactly → 1등
        val outcome = resolveCheckOutcome(winning, allTickets = listOf(unset), round = 319)
        assertEquals(Rank720.FIRST, (outcome as CheckOutcome.Matched).tickets.single().rank)
    }

    // FIX 2: 재계산 시 prize도 세팅 → 3~7등이 총액 합산에 반영(수정 전엔 prize=0으로 누락).
    @Test fun `recomputed lump-sum ticket contributes its prize to the total`() {
        val pending = ticket(jo = 1, number = "901327", rank = Rank720.PENDING) // last-5 "01327" → 3등, 입력 prize=0
        val matched = resolveCheckOutcome(winning, allTickets = listOf(pending), round = 319) as CheckOutcome.Matched
        assertEquals(Rank720.THIRD, matched.tickets.single().rank)
        assertEquals(1_000_000L, matched.tickets.single().prize)
        val (_, body) = buildMatchedNotification(319, winning, matched.tickets)
        assertTrue(body.contains("총 당첨금(일시금): ₩1,000,000"))
    }

    // ---------- 다중 티켓 알림 문구 ----------

    @Test fun `multi-ticket notification lists every matched ticket with its rank`() {
        val tickets = listOf(
            ticket(jo = 3, number = "201327", rank = Rank720.FIRST),
            ticket(jo = 1, number = "999999", rank = Rank720.NONE),
        )
        val (title, body) = buildMatchedNotification(319, winning, tickets)
        assertTrue(title.contains("319"))
        assertTrue(title.contains("당첨"))
        assertTrue(body.contains("3조 201327"))
        assertTrue(body.contains("1조 999999"))
        assertTrue(body.contains("1등"))
        assertTrue(body.contains("월 700만원×20년"))
        assertTrue(body.contains("미당첨"))
    }

    @Test fun `all-losing notification uses the losing title`() {
        val tickets = listOf(ticket(jo = 1, number = "999999", rank = Rank720.NONE))
        val (title, _) = buildMatchedNotification(319, winning, tickets)
        assertTrue(title.contains("낙첨"))
    }

    @Test fun `lump-sum prize is summed into the notification body`() {
        val tickets = listOf(ticket(jo = 1, number = "111111", rank = Rank720.THIRD, prize = 1_000_000))
        val (_, body) = buildMatchedNotification(319, winning, tickets)
        assertTrue(body.contains("1,000,000"))
    }

    // ---------- 폴백(당첨번호만) 문구 ----------

    @Test fun `fallback notification shows winning numbers only`() {
        val (title, body) = buildFallbackNotification(319, winning)
        assertTrue(title.contains("319"))
        assertTrue(body.contains("3조 201327"))
        assertTrue(body.contains("632035"))
    }
}
