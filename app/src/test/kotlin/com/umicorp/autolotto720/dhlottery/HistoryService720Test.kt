package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.Rank720
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HistoryService720 테스트 — 라이브 캡처로 확정한 원장 형식(data.list[], ltGdsCd="LP72",
 * gmInfo="조:번호", ltWnResult/wnRnk)을 MockWebServer로 재현해 파싱·필터·등수 매핑을 검증한다.
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

    private fun ledgerDispatcher(listJson: String) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse = when {
            req.path?.startsWith("/mypage/mylotteryledger") == true -> MockResponse().setBody("ok")
            req.path?.startsWith("/mypage/selectMyLotteryledger.do") == true ->
                MockResponse().setBody("""{"data":{"list":[$listJson]}}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Test
    fun `parses LP72 entries into per-game tickets and filters other products`() = runBlocking {
        server.dispatcher = ledgerDispatcher(
            """
            {"ltGdsCd":"LP72","ltEpsd":325,"ltEpsdView":"325","gmInfo":"1:176982","ltWnResult":"미추첨","wnRnk":null,"ltWnAmt":null,"eltOrdrDt":"2026-07-17","epsdRflDt":"2026-07-23"},
            {"ltGdsCd":"LP45","ltEpsd":1200,"gmInfo":"1,2,3,4,5,6","ltWnResult":"미추첨"},
            {"ltGdsCd":"LP72","ltEpsd":320,"gmInfo":"2:604270,3:111222","ltWnResult":"3등","wnRnk":"3","ltWnAmt":"1,000,000","eltOrdrDt":"2026-06-19"}
            """.trimIndent(),
        )
        val tickets = service.fetchRecentPurchases()
        assertEquals(3, tickets.size)   // LP45 제외, LP72 1게임 + 2게임

        val pending = tickets.first { it.round == 325 }
        assertEquals(1, pending.jo); assertEquals("176982", pending.number)
        assertEquals(Rank720.PENDING, pending.rank); assertTrue(!pending.checked)

        val third = tickets.first { it.round == 320 && it.number == "111222" }
        assertEquals(3, third.jo); assertEquals(Rank720.THIRD, third.rank)
        assertTrue(third.checked); assertEquals(1_000_000L, third.prize)
    }

    @Test
    fun `session expired html yields empty list`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest) = MockResponse().setBody("<html>login required</html>")
        }
        assertTrue(service.fetchRecentPurchases().isEmpty())
    }

    @Test
    fun `drawn with no win maps to NONE`() = runBlocking {
        server.dispatcher = ledgerDispatcher(
            """{"ltGdsCd":"LP72","ltEpsd":300,"gmInfo":"4:012345","ltWnResult":"미당첨","wnRnk":null,"eltOrdrDt":"2026-01-01"}""",
        )
        val t = service.fetchRecentPurchases().single()
        assertEquals(Rank720.NONE, t.rank); assertTrue(t.checked)
        assertEquals("012345", t.number)   // leading zero 보존
    }
}
