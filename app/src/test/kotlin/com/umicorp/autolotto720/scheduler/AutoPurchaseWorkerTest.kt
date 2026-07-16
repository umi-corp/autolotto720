package com.umicorp.autolotto720.scheduler

import com.umicorp.autolotto720.dhlottery.Round720
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * AutoPurchaseWorker의 순수 가드/분류 로직 테스트.
 *
 * Worker 본체(doWork/executeAutoPurchase)는 android.content.Context·SecureStore·WorkManager에
 * 의존해 이 프로젝트의 JVM 단위테스트(Robolectric·mockito 미사용)로는 인스턴스화할 수 없다
 * (645 레이어도 워커 단위테스트가 없다 — 동일한 제약). 대신 Worker가 위임하는 순수 판정 함수
 * (판매마감 가드, 회차 멱등 가드, 모호한 실패 분류)를 컴패니언에 internal로 노출해 직접 검증한다.
 *
 * "플래그 꺼짐(Feature720.PURCHASE_ENABLED=false)→구매 미시도"는 컴파일 상수라 테스트에서 스위칭할
 * 수 없다 — executeAutoPurchase의 `if (!Feature720.PURCHASE_ENABLED) return`(구매 호출 전 조기 반환)
 * 코드 리뷰로 확인했다. 여기서는 그 뒤에 실행될 순수 가드 로직만 독립적으로 검증한다.
 */
class AutoPurchaseWorkerTest {

    private val kst = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, kst)

    // ---------- 판매마감 가드 (목 17:00→19:05 죽은 창) ----------

    @Test fun `thursday 1659 sales still open`() {
        val now = at(2026, 7, 16, 16, 59) // Thu, before draw
        assertFalse(AutoPurchaseWorker.isSalesClosed(now))
    }

    @Test fun `thursday 1700 sales closed`() {
        val now = at(2026, 7, 16, 17, 0)
        assertTrue(AutoPurchaseWorker.isSalesClosed(now))
    }

    @Test fun `thursday 1906 after draw rolls to next round so sales open again`() {
        val now = at(2026, 7, 16, 19, 6) // after 19:05 draw
        assertFalse(AutoPurchaseWorker.isSalesClosed(now))
        val upcoming = Round720.getUpcomingDrawRound(now)
        assertEquals(LocalDate.of(2026, 7, 23), Round720.getDrawDate(upcoming)) // buys NEXT round
    }

    @Test fun `non-thursday is never sales closed`() {
        val now = at(2026, 7, 14, 23, 0) // Tue
        assertFalse(AutoPurchaseWorker.isSalesClosed(now))
    }

    // ---------- 회차 멱등 가드 ----------

    @Test fun `round guard blocks re-buy when last purchased round is the upcoming round`() {
        val now = at(2026, 7, 14, 10, 0) // Tue
        val upcoming = Round720.getUpcomingDrawRound(now)
        assertTrue(AutoPurchaseWorker.isRoundAlreadyPurchased(now, lastPurchasedRound = upcoming))
    }

    @Test fun `round guard allows buy when last purchased round is older`() {
        val now = at(2026, 7, 14, 10, 0)
        val upcoming = Round720.getUpcomingDrawRound(now)
        assertFalse(AutoPurchaseWorker.isRoundAlreadyPurchased(now, lastPurchasedRound = upcoming - 1))
    }

    @Test fun `round guard allows buy when nothing purchased yet`() {
        val now = at(2026, 7, 14, 10, 0)
        assertFalse(AutoPurchaseWorker.isRoundAlreadyPurchased(now, lastPurchasedRound = 0))
    }

    // ---------- 모호한 실패 분류 ([purchase]/[notify] 비재시도) ----------

    @Test fun `purchase-tagged failure is ambiguous and non-retryable`() {
        assertTrue(AutoPurchaseWorker.isAmbiguousFailure("[purchase] 구매 실패: 잔액 부족"))
    }

    @Test fun `notify-tagged failure is ambiguous and non-retryable`() {
        assertTrue(AutoPurchaseWorker.isAmbiguousFailure("[notify] 알림 실패"))
    }

    // R2 N6: 결제 성공 후 회차 기록 실패([commit])는 재시도하면 재구매(double-charge) 위험 → 비재시도.
    @Test fun `commit-tagged failure is ambiguous and non-retryable`() {
        assertTrue(AutoPurchaseWorker.isAmbiguousFailure("[commit] 기록 저장 실패"))
    }

    @Test fun `login-tagged failure is retryable`() {
        assertFalse(AutoPurchaseWorker.isAmbiguousFailure("[login] 로그인 실패"))
    }

    @Test fun `round_guard-tagged failure is retryable`() {
        assertFalse(AutoPurchaseWorker.isAmbiguousFailure("[round_guard] 알 수 없는 오류"))
    }
}
