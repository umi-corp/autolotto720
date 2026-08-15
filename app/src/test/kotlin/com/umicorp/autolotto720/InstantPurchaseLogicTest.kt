package com.umicorp.autolotto720

import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.Slot720
import com.umicorp.autolotto720.data.SpendEntry
import com.umicorp.autolotto720.data.Ticket720
import com.umicorp.autolotto720.dhlottery.DhlotteryException
import com.umicorp.autolotto720.ui.vm.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime

/**
 * 즉시 구매 순수 로직: 720 판매시간 게이트 경계 + 구매 게이트(purchaseGate).
 *
 * 720 판매 규칙은 645(토 20시~일 06시·매일 00~06시 정지)와 다르다 — 목 17:00 판매마감,
 * 목 17:00~23:59 구매 금지, 금 00:00 다음 회차 개시. 즉시 구매의 "지금 판매 중인가" 판정은
 * [SettingsViewModel.isValidPurchaseTime] → Round720.isScheduleBlocked 단일 판정을 재사용한다.
 */
class InstantPurchaseLogicTest {

    @Test
    fun `판매시간 게이트 - 목 17시부터 판매 금지, 22시에 재개`() {
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 16, minute = 59))
        assertFalse(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 17, minute = 0))
        assertFalse(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 21, minute = 59))  // 정지창 끝자락
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 22, minute = 0))    // 22:00 재개
        assertTrue(SettingsViewModel.isValidPurchaseTime(day = 4, hour = 23, minute = 59))   // 재개 후 허용
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

    // === extraAttemptAmount: 추가 구매 시도 금액(순수, 예산 가드 입력) ===

    @Test
    fun `extraAttemptAmount - 모든조 세트는 게임수와 무관하게 5,000원`() {
        assertEquals(5000, extraAttemptAmount(extraSet = true, autoGames = 0))
        assertEquals(5000, extraAttemptAmount(extraSet = true, autoGames = 3))
    }

    @Test
    fun `extraAttemptAmount - 완전자동은 게임수 x 1,000원`() {
        assertEquals(1000, extraAttemptAmount(extraSet = false, autoGames = 1))
        assertEquals(5000, extraAttemptAmount(extraSet = false, autoGames = 5))
    }

    // === isLegacyAutoMigrationArtifact: 구 매수-마이그레이션 산출물만 정리, 세트 설정은 보존(순수) ===

    private fun cfg(setMode: Boolean, revision: Long, slots: List<Slot720> = List(5) { Slot720.Unset }) =
        NumberConfig720(slots, FallbackPolicy.REASSIGN_ALL, if (setMode) 2 else 1, revision, setMode)

    @Test
    fun `legacy artifact - rev1 빈슬롯 비세트는 정리 대상`() {
        assertTrue(isLegacyAutoMigrationArtifact(cfg(setMode = false, revision = 1L)))
    }

    @Test
    fun `legacy artifact - rev1 세트 모드는 정리 대상 아님(세트 보존)`() {
        // 세트를 첫 저장하면 rev=1·빈 슬롯이 되지만, 앱 재시작 시 초기화되면 안 된다(사용자 보고 버그).
        assertFalse(isLegacyAutoMigrationArtifact(cfg(setMode = true, revision = 1L)))
    }

    @Test
    fun `legacy artifact - rev2 이상은 정리 대상 아님`() {
        assertFalse(isLegacyAutoMigrationArtifact(cfg(setMode = false, revision = 2L)))
    }

    @Test
    fun `legacy artifact - 수동 슬롯이 있으면 실제 사용자 저장(정리 안 함)`() {
        val slots = listOf(
            Slot720.Manual(1, listOf(1, 2, 3, 4, 5, 6)),
            Slot720.Unset, Slot720.Unset, Slot720.Unset, Slot720.Unset,
        )
        assertFalse(isLegacyAutoMigrationArtifact(cfg(setMode = false, revision = 1L, slots = slots)))
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

    // === isRoundCoveredByHistory: 결과 불명을 서버 내역으로 확증(순수) ===
    //
    // 버그 리포트("구매 결과 확인 필요")의 근본 원인 지점. 앱이 결제 성립을 스스로 판정하지 못하면
    // 사용자에게 확인을 떠넘기고 그 회차 재구매를 봉쇄하는 것 말고 할 수 있는 게 없다. 서버 내역 대조가
    // 유일한 확증 수단이고, 그 판정 계약을 여기서 못 박는다(오판 = 이중결제).
    //
    // expectedAmount = "이 회차에 청구된 것으로 우리가 계산한 총액" — 호출부가 원장 기록분 + 아직
    // 기록 전인 시도분을 더해 넘긴다(AppContainer.instantPurchase / settlePending).

    private fun ticket(round: Int, jo: Int = 1, number: String = "000727") =
        Ticket720(round = round, jo = jo, number = number, purchaseDate = LocalDateTime.of(2026, 7, 16, 10, 0))

    @Test
    fun `내역 확증 - 서버 내역이 기대 금액 전액을 설명하면 확증`() {
        val tickets = List(5) { ticket(325, jo = it + 1) }   // 5게임 = 5,000원
        assertTrue(isRoundCoveredByHistory(round = 325, expectedAmount = 5000, tickets = tickets))
    }

    @Test
    fun `내역 확증 - 조회 실패·세션 만료(빈 목록)는 확증 금지(fail-closed)`() {
        // HistoryService720은 세션 만료(HTML 응답) 시 빈 목록을 준다 — "구매 없음"과 구분 불가.
        assertFalse(isRoundCoveredByHistory(round = 325, expectedAmount = 5000, tickets = emptyList()))
    }

    @Test
    fun `내역 확증 - 다른 회차 티켓은 증거가 아니다`() {
        assertFalse(isRoundCoveredByHistory(round = 325, expectedAmount = 1000, tickets = listOf(ticket(324))))
    }

    /** 이중결제 방어의 핵심 케이스 — 티켓 "존재"만 보면 뚫린다. */
    @Test
    fun `내역 확증 - 성공한 첫 구매 티켓이 결과 불명 추가 구매를 대신 증명하면 안 된다`() {
        // 첫 구매 5게임(성공, 원장 5,000) + 추가 1게임 결과 불명 → 기대 6,000.
        // 서버에는 첫 구매 5장뿐 → 추가 1게임은 미확인. 확증하면 같은 결제를 또 시도할 창이 열린다.
        val tickets = List(5) { ticket(325, jo = it + 1) }
        assertFalse(isRoundCoveredByHistory(round = 325, expectedAmount = 6000, tickets = tickets))
    }

    @Test
    fun `내역 확증 - 부분 성공으로 티켓이 모자라면 미확증`() {
        // 5게임 시도(기대 5,000) 중 3게임만 서버에 반영 → 2게임 미확정.
        val tickets = List(3) { ticket(325, jo = it + 1) }
        assertFalse(isRoundCoveredByHistory(round = 325, expectedAmount = 5000, tickets = tickets))
    }

    @Test
    fun `내역 확증 - 기대 금액이 0 이하면 확증 금지(원장·시도 금액 소실 fail-closed)`() {
        // 호출부가 기대 금액을 못 구한 상태(원장 유실 등)에서 티켓 존재만으로 통과시키면 안 된다.
        assertFalse(isRoundCoveredByHistory(round = 325, expectedAmount = 0, tickets = listOf(ticket(325))))
    }

    // === expectedRoundAmount: 확증에 쓸 기대 금액 계산(순수) ===
    //
    // 교차검증에서 드러난 결함 지점. 이전 구현은 max(원장, 시도금액)이라 "첫 구매는 원장에 기록됐고
    // 이번 시도만 기록 실패"인 혼합 상태에서 시도 금액을 흡수해 버렸다 — 첫 구매 티켓만으로 미확인
    // 시도가 오확정되어, 판정 함수가 막으려던 이중결제 창이 호출부 계산에서 그대로 열렸다.

    @Test
    fun `기대 금액 - 시도가 원장에 이미 반영됐으면 원장 값이 곧 총액`() {
        assertEquals(5000, expectedRoundAmount(roundSpend = 5000, pendingAmount = 1000, pendingInLedger = true))
    }

    @Test
    fun `기대 금액 - 시도가 원장에 없으면 더한다(혼합 상태 오확정 차단)`() {
        // 첫 구매 5,000 성공(원장 기록) + 추가 1,000 결과 불명(원장 기록 실패) → 총액 6,000.
        // 5,000으로 계산하면 서버의 첫 구매 5장만으로 추가 구매가 오확정된다.
        assertEquals(6000, expectedRoundAmount(roundSpend = 5000, pendingAmount = 1000, pendingInLedger = false))
    }

    @Test
    fun `기대 금액 - 원장이 회차를 통째로 놓쳐도 시도 금액은 살아남는다`() {
        assertEquals(1000, expectedRoundAmount(roundSpend = 0, pendingAmount = 1000, pendingInLedger = false))
    }

    /** 마커가 없는 레거시·손상 PENDING은 미반영으로 읽혀 보수적으로(더 큰 기대 금액) 평가돼야 한다. */
    @Test
    fun `기대 금액 - 마커 파싱은 없거나 손상이면 미반영(fail-closed)`() {
        assertFalse(parsePendingLedgerOk(null))
        assertFalse(parsePendingLedgerOk(""))
        assertFalse(parsePendingLedgerOk("{}"))
        assertFalse(parsePendingLedgerOk("""{"round":325,"epochDay":100,"amount":1000}"""))
        assertTrue(parsePendingLedgerOk(pendingJson(325, 100, 1000, ledgerOk = true)))
        assertFalse(parsePendingLedgerOk(pendingJson(325, 100, 1000)))
    }

    /** 마커를 붙여도 기존 PENDING 파싱 계약(round·epochDay·amount)이 깨지면 안 된다. */
    @Test
    fun `기대 금액 - 마커가 붙은 PENDING도 기존 필드로 정상 파싱`() {
        assertEquals(
            SpendEntry(325, 100, 1000),
            parsePending(pendingJson(325, 100, 1000, ledgerOk = true, owner = "userA")),
        )
    }

    // === canSettlePendingAs: PENDING 소유 계정 대조(순수) ===
    //
    // PENDING은 계정 무관 단일 슬롯이라 A의 미결이 남은 채 B로 전환될 수 있다. B의 내역으로 A의 PENDING을
    // 풀면 회차 가드가 setLastPurchase(round, B)로 스탬프되고, A 복귀 시 accountScopedRound가 소유자
    // 불일치로 가드를 0으로 봐 A가 같은 회차를 재구매한다 — 이 변경이 막으려던 이중결제가 계정 전환으로 재개된다.

    @Test
    fun `소유자 대조 - 같은 계정이면 해소 허용`() {
        assertTrue(canSettlePendingAs(pendingOwner = "userA", currentUserId = "userA"))
    }

    @Test
    fun `소유자 대조 - 다른 계정 PENDING은 해소 금지(교차계정 이중결제 차단)`() {
        assertFalse(canSettlePendingAs(pendingOwner = "userA", currentUserId = "userB"))
    }

    /**
     * 레거시(owner 없는) PENDING은 해소하지 않는다 — accountScopedRound의 owner=null 백필과 방향이 반대다.
     * 그쪽은 "못 막으면 재구매"라 신뢰가 안전 측이고, 여기는 "잘못 풀면 재구매"라 불신이 안전 측이다.
     */
    @Test
    fun `소유자 대조 - 레거시(owner 없음)는 해소 금지(fail-closed)`() {
        assertFalse(canSettlePendingAs(pendingOwner = null, currentUserId = "userA"))
        assertFalse(canSettlePendingAs(pendingOwner = "userA", currentUserId = null))
        assertFalse(canSettlePendingAs(pendingOwner = null, currentUserId = null))
    }

    @Test
    fun `소유자 대조 - owner 파싱은 없거나 손상이면 null`() {
        assertNull(parsePendingOwner(null))
        assertNull(parsePendingOwner(""))
        assertNull(parsePendingOwner("{}"))
        assertNull(parsePendingOwner("""{"round":325,"epochDay":100,"amount":1000}"""))
        assertEquals("userA", parsePendingOwner(pendingJson(325, 100, 1000, owner = "userA")))
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
