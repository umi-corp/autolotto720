package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.Slot720
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant

/**
 * PurchaseService720 테스트 — 라이브 계약(makeAutoNo→makeOrderNo→connPro, AES q 왕복)을 MockWebServer로
 * 재현한다. 서버 응답은 [Crypto720]로 실제 암호화(같은 JSESSIONID passphrase)해 서비스의 복호화·파싱을
 * 실경로로 검증한다. 핵심 불변식: 게임 수 검증이 네트워크보다 먼저, connPro는 단발(재시도 없음),
 * 결제 단계(connPro) 이전 실패는 connPro에 닿지 않는다.
 */
class PurchaseService720Test {

    private lateinit var server: MockWebServer
    private lateinit var auth: AuthService
    private lateinit var session: DhlotterySession
    private lateinit var clock: Clock

    // 32자 이상 — passphrase = substring(0,32).
    private val jses = "TESTSESSION0123456789ABCDEFGHIJKLMNOP"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        session = DhlotterySession(baseUrl = baseUrl, olottoUrl = baseUrl, elUrl = baseUrl)
        auth = AuthService(session)
        clock = Clock.fixed(Instant.parse("2026-07-16T14:00:00Z"), Round720.KST) // 목 23시 KST → 판매회차
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enc(json: String) = MockResponse().setBody(JSONObject().put("q", Crypto720.encrypt(json, jses)).toString())

    // 성공 응답: data.prchsLtNoInfoLstCn = "번호|주문번호|일련번호|회차|조" (라이브 실측).
    private fun prchsInfo(number: String, jo: Int) = """{"resultCode":"100","data":{"ltPrchsQty":1,"prchsLtNoInfoLstCn":"$number|202607170194572|100000920543794|325|$jo"}}"""

    /** 정상 4단계 디스패처. */
    private fun happyDispatcher(number: String = "574067", jo: Int = 1) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse = when {
            req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
            req.path == "/game/pension720/game.jsp" ->
                MockResponse().setBody("""<input type="hidden" name="USER_ID" value="tester99"/>""")
                    .addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
            req.path == "/makeAutoNo.do" ->
                enc("""{"resultCode":"100","selClsNo":"$jo","selLotNo":"$number","autoSelSet":"S","round":"325"}""")
            req.path == "/makeOrderNo.do" ->
                enc("""{"resultCode":"100","orderNo":"202607160181144","orderDate":"2026-07-16 23:03:23"}""")
            req.path == "/connPro.do" -> enc(prchsInfo(number, jo))
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun service() = PurchaseService720(auth, session, clock)

    @Test
    fun `rejects zero games before any network call`() = runBlocking {
        val ex = runCatching { service().purchase(0) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException && ex.message!!.contains("게임 수"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rejects six games before any network call`() = runBlocking {
        val ex = runCatching { service().purchase(6) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException && ex.message!!.contains("게임 수"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `single game happy path parses sold ticket`() = runBlocking {
        server.dispatcher = happyDispatcher(number = "574067", jo = 1)
        val result = service().purchase(1)
        assertEquals(Round720.getUpcomingDrawRound(java.time.ZonedDateTime.now(clock)), result.round)
        assertEquals(1, result.tickets.size)
        assertEquals(1000, result.amount)
        assertEquals(1, result.tickets[0].jo)
        assertEquals("574067", result.tickets[0].number)
    }

    @Test
    fun `connPro failure throws and is not retried`() = runBlocking {
        var connProCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
                req.path == "/game/pension720/game.jsp" ->
                    MockResponse().setBody("ok").addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
                req.path == "/makeAutoNo.do" ->
                    enc("""{"resultCode":"100","selClsNo":"1","selLotNo":"574067","round":"325"}""")
                req.path == "/makeOrderNo.do" ->
                    enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
                req.path == "/connPro.do" -> { connProCount++; enc("""{"resultCode":"E002","resultMsg":"구매 진행중인 복권 존재"}""") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val ex = runCatching { service().purchase(1) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException && ex.message!!.contains("구매 실패"))
        assertEquals(1, connProCount) // 단발 — 재시도 없음
    }

    @Test
    fun `makeOrderNo failure does not reach connPro (no charge)`() = runBlocking {
        var connProCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
                req.path == "/game/pension720/game.jsp" ->
                    MockResponse().setBody("ok").addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
                req.path == "/makeAutoNo.do" ->
                    enc("""{"resultCode":"100","selClsNo":"1","selLotNo":"574067","round":"325"}""")
                req.path == "/makeOrderNo.do" -> enc("""{"resultCode":"302","resultMsg":"판매 마감"}""")
                req.path == "/connPro.do" -> { connProCount++; enc("""{"resultCode":"100"}""") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val ex = runCatching { service().purchase(1) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException && ex.message!!.contains("주문 생성"))
        assertEquals(0, connProCount) // 결제 단계 미도달
    }

    @Test
    fun `second game failure keeps first ticket as partial success`() = runBlocking {
        var connProCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
                req.path == "/game/pension720/game.jsp" ->
                    MockResponse().setBody("ok").addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
                req.path == "/makeAutoNo.do" ->
                    enc("""{"resultCode":"100","selClsNo":"1","selLotNo":"574067","round":"325"}""")
                req.path == "/makeOrderNo.do" ->
                    enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
                req.path == "/connPro.do" -> {
                    connProCount++
                    if (connProCount == 1) enc(prchsInfo("574067", 1))
                    else enc("""{"resultCode":"E110","resultMsg":"구매 한도 초과"}""")   // 한도 소진(실측 시나리오)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = service().purchase(5)
        assertEquals(1, result.tickets.size)                     // 성공분 보존
        assertEquals(1000, result.amount)
        assertEquals(4, result.partialFailure?.failedGames)     // 실패 게임 + 미시도 잔여
        assertTrue(result.partialFailure?.cause is DhlotteryException)
        assertEquals(2, connProCount)                            // 실패 후 잔여 게임 미진행(추가 결제 없음)
    }

    private fun cfg(vararg slots: Slot720, fallback: FallbackPolicy = FallbackPolicy.REASSIGN_ALL) =
        NumberConfig720(
            (slots.toList() + List(5) { Slot720.Unset }).take(5),
            fallback, NumberConfig720.CURRENT_SCHEMA, 1L,
        )

    /** 수동 지정번호 디스패처. checkVerifyNo가 [available]에 따라 비점유/점유(추천) 응답. */
    private fun manualDispatcher(available: Boolean, number: String, jo: Int, autoNumber: String = "999999", autoJo: Int = 4) =
        object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
                req.path == "/game/pension720/game.jsp" ->
                    MockResponse().setBody("""<input type="hidden" name="USER_ID" value="tester99"/>""")
                        .addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
                // 실측 형식: resultMsg=resultCode/verifyYn/recommendYN/회차/셋트/조/번호/타입 (N=가능, Y=점유)
                req.path == "/checkVerifyNo.do" ->
                    enc("""{"resultCode":"100","resultMsg":"100/Y/${if (available) "N" else "Y"}/325/S/$jo/$number/M"}""")
                req.path == "/makeAutoNo.do" ->  // 폴백 자동 배정용
                    enc("""{"resultCode":"100","selClsNo":"$autoJo","selLotNo":"$autoNumber","round":"325"}""")
                req.path == "/makeOrderNo.do" ->
                    enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
                // 수동 가능 → 지정번호 구매, 점유(폴백) → 자동 재배정 번호 구매. (q는 암호문이라 available로 결정)
                req.path == "/connPro.do" ->
                    enc(if (available) prchsInfo(number, jo) else prchsInfo(autoNumber, autoJo))
                else -> MockResponse().setResponseCode(404)
            }
        }

    @Test
    fun `config semiauto buys assigned number in chosen group`() = runBlocking {
        server.dispatcher = happyDispatcher(number = "123456", jo = 2)
        val r = service().purchase(cfg(Slot720.SemiAuto(2)))
        assertEquals(1, r.tickets.size)
        assertEquals(2, r.tickets[0].jo)
        assertEquals("123456", r.tickets[0].number)
    }

    @Test
    fun `config empty throws before network`() = runBlocking {
        val ex = runCatching { service().purchase(cfg()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException && ex.message!!.contains("구매할 게임이 없습니다"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `config manual available buys exact number`() = runBlocking {
        server.dispatcher = manualDispatcher(available = true, number = "111111", jo = 3)
        val r = service().purchase(cfg(Slot720.Manual(3, listOf(1, 1, 1, 1, 1, 1))))
        assertEquals(1, r.tickets.size)
        assertEquals(3, r.tickets[0].jo)
        assertEquals("111111", r.tickets[0].number)
    }

    @Test
    fun `config manual taken with GIVE_UP skips returns empty result (not error)`() = runBlocking {
        server.dispatcher = manualDispatcher(available = false, number = "111111", jo = 3)
        // 점유 + 구매 포기 = 정책상 정상 결과(오류 아님) → 빈 티켓/0원.
        val r = service().purchase(cfg(Slot720.Manual(3, listOf(1, 1, 1, 1, 1, 1)), fallback = FallbackPolicy.GIVE_UP))
        assertTrue(r.tickets.isEmpty())
        assertEquals(0, r.amount)
    }

    @Test
    fun `config manual taken with KEEP_GROUP reassigns auto number`() = runBlocking {
        server.dispatcher = manualDispatcher(available = false, number = "111111", jo = 3, autoNumber = "888888", autoJo = 3)
        val r = service().purchase(cfg(Slot720.Manual(3, listOf(1, 1, 1, 1, 1, 1)), fallback = FallbackPolicy.KEEP_GROUP_RANDOM))
        assertEquals(1, r.tickets.size)
        assertEquals("888888", r.tickets[0].number)   // 지정번호 대신 자동 재배정
    }

    @Test
    fun `html error response treated as unknown result (no crash)`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
                req.path == "/game/pension720/game.jsp" ->
                    MockResponse().setBody("ok").addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
                req.path == "/makeAutoNo.do" ->
                    enc("""{"resultCode":"100","selClsNo":"1","selLotNo":"574067","round":"325"}""")
                req.path == "/makeOrderNo.do" ->
                    enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
                req.path == "/connPro.do" -> MockResponse().setBody("<html><body>error ocurred!</body></html>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val ex = runCatching { service().purchase(1) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException && ex.message!!.contains("결과 불명"))
    }
}
