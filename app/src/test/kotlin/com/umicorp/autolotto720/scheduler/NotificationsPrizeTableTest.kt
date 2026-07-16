package com.umicorp.autolotto720.scheduler

import com.umicorp.autolotto720.data.Rank720
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3~7등 일시금 표의 단일 출처 검증 — 숫자 총액([Notifications.lumpSumPrizeOf])과
 * 문구([Notifications.rank720PrizeText])가 서로 어긋나지 않는지 핀한다.
 */
class NotificationsPrizeTableTest {

    @Test fun `lump-sum amount and label text stay in sync for ranks 3 to 7`() {
        // (등수) → (일시금 원, 문구에 반드시 포함될 조각)
        val expected = mapOf(
            Rank720.THIRD to (1_000_000L to "100만"),
            Rank720.FOURTH to (100_000L to "10만"),
            Rank720.FIFTH to (50_000L to "5만"),
            Rank720.SIXTH to (5_000L to "5천"),
            Rank720.SEVENTH to (1_000L to "1천"),
        )
        for ((rank, pair) in expected) {
            val (amount, textFragment) = pair
            assertEquals("$rank 일시금", amount, Notifications.lumpSumPrizeOf(rank))
            assertTrue(
                "$rank 문구는 '$textFragment' 포함해야 함",
                Notifications.rank720PrizeText(rank).contains(textFragment),
            )
        }
    }
}
