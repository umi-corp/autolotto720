package com.umicorp.autolotto720.dhlottery

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant

/**
 * PurchaseService720 테스트 — 실 구매 계약(AES, contract §4)이 미확보라 [Feature720.PURCHASE_ENABLED]
 * =false 게이트만 검증한다. 게임 수 검증과 기능 게이트가 네트워크/로그인보다 항상 먼저 실행되는지가
 * 핵심 불변식(머니패스 안전) — 3개 테스트 모두 `server.requestCount == 0`을 함께 확인한다.
 */
class PurchaseService720Test {

    private lateinit var server: MockWebServer
    private lateinit var auth: AuthService
    private lateinit var session: DhlotterySession
    private lateinit var clock: Clock

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        session = DhlotterySession(baseUrl = baseUrl, olottoUrl = baseUrl)
        auth = AuthService(session)
        clock = Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), Round720.KST)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `purchase rejects zero games before any network call`() = runBlocking {
        val ex = try {
            PurchaseService720(auth, session, clock).purchase(games = 0); null
        } catch (e: Exception) {
            e
        }
        assertTrue(ex is DhlotteryException && ex.message!!.contains("게임 수"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `purchase rejects six games before any network call`() = runBlocking {
        val ex = try {
            PurchaseService720(auth, session, clock).purchase(games = 6); null
        } catch (e: Exception) {
            e
        }
        assertTrue(ex is DhlotteryException && ex.message!!.contains("게임 수"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `purchase with valid game count still throws gated not-ready before any network call`() = runBlocking {
        val ex = try {
            PurchaseService720(auth, session, clock).purchase(games = 1); null
        } catch (e: Exception) {
            e
        }
        assertTrue(ex is DhlotteryException && ex.message!!.contains("준비 중"))
        assertEquals(0, server.requestCount)
    }
}
