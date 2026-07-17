package com.umicorp.autolotto720

import com.umicorp.autolotto720.dhlottery.DhlotteryException
import com.umicorp.autolotto720.ui.vm.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 즉시 구매 순수 로직: 720 판매시간 게이트 경계 + 구매 게이트(purchaseGate).
 *
 * 720 판매 규칙은 645(토 20시~일 06시·매일 00~06시 정지)와 다르다 — 목 17:00 판매마감,
 * 목 17:00~23:59 구매 금지, 금 00:00 다음 회차 개시. 즉시 구매의 "지금 판매 중인가" 판정은
 * [SettingsViewModel.isValidPurchaseTime] → Round720.isScheduleBlocked 단일 판정을 재사용한다.
 */
class InstantPurchaseLogicTest {

    @Test
    fun `판매시간 게이트 - 목요일은 16시대까지, 17시부터 판매 금지`() {
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 16, minute = 59))
        assertFalse(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 17, minute = 0))
        assertFalse(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 23, minute = 59))
    }

    @Test
    fun `판매시간 게이트 - 금 00시부터 다음 회차 판매, 목 외 요일은 종일 허용`() {
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 5, hour = 0, minute = 0))   // 금 00:00 개시
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 6, hour = 20, minute = 0))  // 토 20시 — 645와 달리 허용
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 7, hour = 3, minute = 0))   // 일 03시 — 645와 달리 허용
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 3, hour = 5, minute = 0))   // 수 05시 — 645와 달리 허용
    }

    @Test
    fun `purchaseGate - 회차 불일치는 모드 무관 중단`() {
        assertEquals(
            PurchaseGate.ROUND_CHANGED,
            purchaseGate(extra = false, recordedRound = 0, currentRound = 326, expectedRound = 325, saleOpen = true),
        )
        assertEquals(
            PurchaseGate.ROUND_CHANGED,
            purchaseGate(extra = true, recordedRound = 325, currentRound = 326, expectedRound = 325, saleOpen = true),
        )
    }

    @Test
    fun `purchaseGate - 첫 구매만 회차 가드, 추가 구매는 통과`() {
        assertEquals(
            PurchaseGate.ALREADY_PURCHASED,
            purchaseGate(extra = false, recordedRound = 325, currentRound = 325, expectedRound = 325, saleOpen = true),
        )
        assertEquals(
            PurchaseGate.PROCEED,
            purchaseGate(extra = true, recordedRound = 325, currentRound = 325, expectedRound = 325, saleOpen = true),
        )
        assertEquals(
            PurchaseGate.PROCEED,
            purchaseGate(extra = false, recordedRound = 324, currentRound = 325, expectedRound = 325, saleOpen = true),
        )
    }

    @Test
    fun `purchaseGate - 락 대기 중 판매 종료면 모드 무관 중단`() {
        assertEquals(
            PurchaseGate.SALE_CLOSED,
            purchaseGate(extra = true, recordedRound = 325, currentRound = 325, expectedRound = 325, saleOpen = false),
        )
        assertEquals(
            PurchaseGate.SALE_CLOSED,
            purchaseGate(extra = false, recordedRound = 0, currentRound = 325, expectedRound = 325, saleOpen = false),
        )
    }

    // === 실패 2분류: 720 서비스는 결과 불명도 DhlotteryException으로 던진다(645와 다름) ===

    @Test
    fun `isUnknownResultMessage - 서버 확정 거절은 결과 불명이 아니다(재시도 안전)`() {
        assertFalse(isUnknownResultMessage("구매 실패(code=200): 주간구매금액 초과"))
        assertFalse(isUnknownResultMessage("구매 실패(code=300): 예치금이 부족합니다"))
        assertFalse(isUnknownResultMessage(null))
    }

    /** 서비스의 실제 문구(PurchaseService720Test가 고정)로 표식 계약을 검증 — 오분류 = 중복 결제 위험. */
    @Test
    fun `isUnknownResultMessage - 비-JSON 응답·미지 코드는 결과 불명으로 분류`() {
        assertTrue(isUnknownResultMessage("서버 응답 오류 (/olotto/game/pension720/execPurchase.do) — 결과 불명, 재시도 금지"))
        assertTrue(isUnknownResultMessage("구매 실패(code=999): 결과 불명 — 내역으로 대조하세요"))
    }

    // === classifyPurchaseFailure: Rejected(재시도 안전) vs Unknown(재시도 금지) — 3분기(F14) ===

    @Test
    fun `classifyPurchaseFailure - 비-Dhlottery 예외는 결과 불명(Unknown)`() {
        assertTrue(classifyPurchaseFailure(IOException("timeout")) is PurchaseFailure.Unknown)
    }

    @Test
    fun `classifyPurchaseFailure - 결과 불명 표식이 있는 Dhlottery 예외는 Unknown`() {
        val e = DhlotteryException("서버 응답 오류 (/olotto/game/pension720/execPurchase.do) — 결과 불명, 재시도 금지")
        assertTrue(classifyPurchaseFailure(e) is PurchaseFailure.Unknown)
    }

    @Test
    fun `classifyPurchaseFailure - 표식 없는 Dhlottery 예외는 서버 확정 거절(Rejected)`() {
        assertTrue(classifyPurchaseFailure(DhlotteryException("구매 실패(code=300): 예치금이 부족합니다")) is PurchaseFailure.Rejected)
    }

    // === accountScopedRound: 계정 스코프 회차 가드(순수, F4) ===

    @Test
    fun `accountScopedRound - 소유자가 현재 계정이면 기록 회차 유지`() {
        assertEquals(325, accountScopedRound(325, recordedOwner = "userA", currentUserId = "userA"))
    }

    @Test
    fun `accountScopedRound - 다른 계정 기록이면 0(이 계정 미구매)으로 본다`() {
        assertEquals(0, accountScopedRound(325, recordedOwner = "userA", currentUserId = "userB"))
    }

    @Test
    fun `accountScopedRound - 레거시(owner null) 기록은 현재 계정 것으로 신뢰(G1 마이그레이션 중복결제 방지)`() {
        assertEquals(325, accountScopedRound(325, recordedOwner = null, currentUserId = "userA"))
    }

    @Test
    fun `accountScopedRound - 미로그인(현재계정 null)이면 0`() {
        assertEquals(0, accountScopedRound(325, recordedOwner = "userA", currentUserId = null))
    }

    // === shouldBackfillOwner: 레거시 회차 기록에 소유 계정 1회 스탬프(G1) ===

    @Test
    fun `shouldBackfillOwner - 레거시 회차 기록 + 로그인 시 스탬프`() {
        assertTrue(shouldBackfillOwner(325, recordedOwner = null, currentUserId = "userA"))
    }

    @Test
    fun `shouldBackfillOwner - 이미 owner 있거나·기록 없거나·미로그인이면 스탬프 안 함`() {
        assertFalse(shouldBackfillOwner(325, recordedOwner = "userA", currentUserId = "userA"))
        assertFalse(shouldBackfillOwner(0, recordedOwner = null, currentUserId = "userA"))
        assertFalse(shouldBackfillOwner(325, recordedOwner = null, currentUserId = null))
    }
}
