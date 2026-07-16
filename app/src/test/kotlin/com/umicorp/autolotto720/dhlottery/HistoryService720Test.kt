package com.umicorp.autolotto720.dhlottery

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HistoryService720 테스트 — 720 원장 상세 엔드포인트·상품명(`ltGdsNm`)이 미확보라
 * (720-api-contract.md §5) [Feature720.PURCHASE_ENABLED]=false 게이트만 검증한다.
 */
class HistoryService720Test {

    private lateinit var server: MockWebServer
    private lateinit var service: HistoryService720

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val session = DhlotterySession(baseUrl = baseUrl, olottoUrl = baseUrl)
        service = HistoryService720(session, ResultService720(session))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `fetchRecentPurchases returns empty list and makes no network calls while gated`() = runBlocking {
        val purchases = service.fetchRecentPurchases()
        assertTrue(purchases.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
