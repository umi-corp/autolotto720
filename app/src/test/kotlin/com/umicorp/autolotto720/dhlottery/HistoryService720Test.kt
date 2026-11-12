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
import java.io.IOException

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

    /** [resultJson] null이면 당첨번호 엔드포인트 404 — 원장 값 폴백 경로를 재현한다. */
    private fun ledgerDispatcher(listJson: String, resultJson: String? = null) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse = when {
            req.path?.startsWith("/mypage/mylotteryledger") == true -> MockResponse().setBody("ok")
            req.path?.startsWith("/mypage/selectMyLotteryledger.do") == true ->
                MockResponse().setBody("""{"data":{"list":[$listJson]}}""")
            resultJson != null && req.path?.startsWith("/pt720/selectPstPt720WnList.do") == true ->
                MockResponse().setBody("""{"data":{"result":[$resultJson]}}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    // 당첨번호 미제공(폴백) — 주문 단위 wnRnk/ltWnAmt가 게임별로 복사되는 기존 동작 유지를 문서화.
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

    // 핵심 버그 재현: 주문(gmInfo 3게임)의 대표 wnRnk="2"·ltWnAmt를 전 게임에 복사하던 것을,
    // 당첨번호(3조 604270, 보너스 111333)로 게임별 재판정 — 공식 규칙(2등=조무관 6자리, 7등=끝 1자리).
    @Test
    fun `order-level rank is recomputed per game with winning numbers`() = runBlocking {
        server.dispatcher = ledgerDispatcher(
            """{"ltGdsCd":"LP72","ltEpsd":320,"gmInfo":"2:604270,3:111220,1:999999","ltWnResult":"2등","wnRnk":"2","ltWnAmt":"120,000,000","eltOrdrDt":"2026-06-19"}""",
            """{"psltEpsd":320,"wnBndNo":"3","wnRnkVl":"604270","bnsRnkVl":"111333","psltRflYmd":"20260618"}""",
        )
        val tickets = service.fetchRecentPurchases()
        assertEquals(3, tickets.size)

        val second = tickets.first { it.number == "604270" }   // 6자리 일치·조 불일치(2≠3) → 2등, 연금식이라 prize 0
        assertEquals(Rank720.SECOND, second.rank)
        assertEquals(0L, second.prize)

        val seventh = tickets.first { it.number == "111220" }  // 끝 1자리('0')만 일치 → 7등 1,000원
        assertEquals(Rank720.SEVENTH, seventh.rank)
        assertEquals(1_000L, seventh.prize)

        val none = tickets.first { it.number == "999999" }     // 주문 대표 등수 복사가 아니라 게임별 낙첨 판정
        assertEquals(Rank720.NONE, none.rank)
        assertEquals(0L, none.prize)
    }

    // 원장이 "미추첨"으로 뒤처져도 당첨번호가 게시됐으면 확정 반영(워커 R2 N4와 동일 규칙).
    @Test
    fun `pending ledger entry resolves when winning numbers are published`() = runBlocking {
        server.dispatcher = ledgerDispatcher(
            """{"ltGdsCd":"LP72","ltEpsd":321,"gmInfo":"4:012345","ltWnResult":"미추첨","wnRnk":null,"ltWnAmt":null,"eltOrdrDt":"2026-06-25"}""",
            """{"psltEpsd":321,"wnBndNo":"4","wnRnkVl":"912345","bnsRnkVl":"888888","psltRflYmd":"20260625"}""",
        )
        val t = service.fetchRecentPurchases().single()
        assertEquals(Rank720.THIRD, t.rank)   // 끝 5자리 "12345" 일치 → 3등 100만원
        assertEquals(1_000_000L, t.prize)
        assertTrue(t.checked)
    }

    // === 연결 실패는 빈 목록으로 위장하지 않는다 (사용자 리포트: 구매는 됐는데 "기록이 없습니다") ===

    /**
     * 이전 계약은 "세션 만료 HTML → 빈 목록"이었다. 그게 곧 사용자가 본 거짓말이다 —
     * 화면이 실패를 "구매 기록이 없습니다"로 바꿔 보여줬다. 이제는 던진다.
     */
    @Test(expected = IOException::class)
    fun `session expired html throws instead of yielding empty list`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest) = MockResponse().setBody("<html>login required</html>")
        }
        service.fetchRecentPurchases()
        Unit
    }

    /** 타임아웃과 같은 IOException 경로를 5xx로 재현한다. */
    @Test(expected = IOException::class)
    fun `list 5xx throws instead of yielding empty list`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest) = when {
                req.path?.startsWith("/mypage/selectMyLotteryledger.do") == true ->
                    MockResponse().setResponseCode(503).setBody("<html>maintenance</html>")
                else -> MockResponse().setBody("ok")
            }
        }
        service.fetchRecentPurchases()
        Unit
    }

    /** 서버가 정상 JSON으로 "0건"을 준 경우는 실패가 아니다 — 빈 목록 그대로. */
    @Test
    fun `empty list json is a legitimate zero-record result`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest) = when {
                req.path?.startsWith("/mypage/selectMyLotteryledger.do") == true ->
                    MockResponse().setBody("""{"data":{"list":[]}}""")
                else -> MockResponse().setBody("ok")
            }
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
