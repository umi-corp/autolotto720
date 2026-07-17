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

/** 세트(모든조 동일번호) 경로 계약 검증. connPro 1회, SA 평문, 티켓은 실응답 행 수 기준. */
class PurchaseService720SetTest {

    private lateinit var server: MockWebServer
    private lateinit var session: DhlotterySession
    private lateinit var clock: Clock
    private val jses = "TESTSESSION0123456789ABCDEFGHIJKLMNOP"

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val base = server.url("/").toString().trimEnd('/')
        session = DhlotterySession(baseUrl = base, olottoUrl = base, elUrl = base)
        clock = Clock.fixed(Instant.parse("2026-07-16T14:00:00Z"), Round720.KST)
    }
    @After fun tearDown() = server.shutdown()

    private fun enc(json: String) = MockResponse().setBody(JSONObject().put("q", Crypto720.encrypt(json, jses)).toString())
    private fun service() = PurchaseService720(AuthService(session), session, clock)
    private fun setConfig() = NumberConfig720.empty().copy(setMode = true)
    private fun theRound() = Round720.getUpcomingDrawRound(java.time.ZonedDateTime.now(clock))

    // 세트 성공: 5개 조 행 (번호|주문|일련|회차|조) 파이프+세미콜론 다중행. 실측 확정 전 잠정 형식.
    private fun setInfo(number: String) = (1..5).joinToString(";") { "$number|2026|100$it|325|$it" }

    /** MockWebServer가 받은 q=(wireQ 이중 인코딩) 평문을 복호화 — connPro/makeAutoNo 평문 단언용.
     *  wireQ가 '+'→%252B, '/'→%2F, '='→%3D로 바꾸므로 base64 decode 전에 역변환해야 복호에 성공한다. */
    private fun plain(req: RecordedRequest): String {
        val enc = req.body.readUtf8().removePrefix("q=")
            .replace("%252B", "+").replace("%2F", "/").replace("%3D", "=")
        return Crypto720.decrypt(enc, jses)
    }

    @Test fun `set happy path buys 5 tickets in one connPro`() = runBlocking {
        var connProCount = 0
        var autoPlain = ""
        var connPlain = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
                req.path == "/game/pension720/game.jsp" ->
                    MockResponse().setBody("""<input name="USER_ID" value="tester99"/>""")
                        .addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
                req.path == "/makeAutoNo.do" -> {
                    autoPlain = plain(req)
                    enc("""{"resultCode":"100","selClsNo":"1,2,3,4,5","selLotNo":"574067","round":"325"}""")
                }
                req.path == "/makeOrderNo.do" ->
                    enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
                req.path == "/connPro.do" -> {
                    connProCount++
                    connPlain = plain(req)
                    enc("""{"resultCode":"100","data":{"prchsLtNoInfoLstCn":"${setInfo("574067")}"}}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = service().purchase(setConfig(), theRound())
        assertEquals(5, result.tickets.size)
        assertEquals(5000, result.amount)
        assertEquals(listOf(1,2,3,4,5), result.tickets.map { it.jo })
        assertEquals(1, connProCount)
        // makeAutoNo: SA 1회, 빈 조, BUY_TYPE=A
        assertTrue(autoPlain.contains("AUTO_SEL_SET=SA"))
        assertTrue(autoPlain.contains("SEL_CLASS=&") || autoPlain.endsWith("SEL_CLASS="))
        assertTrue(autoPlain.contains("&BUY_TYPE=A&") || autoPlain.endsWith("&BUY_TYPE=A"))   // 정확 단일값
        // connPro: 확증 SA 계약 전 필드 단언 — 하나라도 빠지면 프로토콜 회귀.
        assertTrue(connPlain.contains("BUY_CNT=5"))
        assertTrue(connPlain.contains("curpay=5000"))
        assertTrue(connPlain.contains("set_type=SA"))
        assertTrue(connPlain.contains("BUY_SET_TYPE=SA%2CSA%2CSA%2CSA%2CSA"))   // 콤마 URLEncode
        assertTrue(connPlain.contains("BUY_TYPE=A%2CA%2CA%2CA%2CA%2C"))          // 후행 콤마 포함
        assertTrue(connPlain.contains("BUY_NO=1574067%2C2574067%2C3574067%2C4574067%2C5574067"))
    }

    @Test fun `set partial (3 rows) keeps tickets and flags partial failure`() = runBlocking {
        server.dispatcher = setDispatcher(rows = 3, code = "100")   // 부분 판정은 코드가 아니라 행 수(3<5)
        val result = service().purchase(setConfig(), theRound())
        assertEquals(3, result.tickets.size)
        assertEquals(2, result.partialFailure?.failedGames)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(result.partialFailure?.cause?.message))
    }

    @Test fun `set code 110 is treated as unknown until Task 8`() = runBlocking {
        server.dispatcher = setDispatcher(rows = 5, code = "110")   // 110은 미확정 — 성공 코드 아님
        val ex = runCatching { service().purchase(setConfig(), theRound()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(ex?.message))
    }

    @Test fun `set success code with zero rows throws unknown`() = runBlocking {
        server.dispatcher = setDispatcher(rows = 0, code = "100")
        val ex = runCatching { service().purchase(setConfig(), theRound()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(ex?.message))
    }

    @Test fun `set code 120 throws unknown (no fabricated ticket)`() = runBlocking {
        server.dispatcher = setDispatcher(rows = 0, code = "120")
        val ex = runCatching { service().purchase(setConfig(), theRound()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(ex?.message))
    }

    @Test fun `unknown code with non-blank server message still carries unknown marker`() = runBlocking {
        // 자금 안전 회귀: 서버가 안내 문구를 채워 보낸 미지 코드도 "결과 불명"이어야 재결제가 막힌다.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when {
                req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
                req.path == "/game/pension720/game.jsp" ->
                    MockResponse().setBody("""<input name="USER_ID" value="t"/>""")
                        .addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
                req.path == "/makeAutoNo.do" ->
                    enc("""{"resultCode":"100","selClsNo":"1,2,3,4,5","selLotNo":"574067","round":"325"}""")
                req.path == "/makeOrderNo.do" ->
                    enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
                req.path == "/connPro.do" -> enc("""{"resultCode":"999","resultMsg":"일시적인 오류가 발생했습니다"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val ex = runCatching { service().purchase(setConfig(), theRound()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(ex?.message))   // 마커 필수
    }

    @Test fun `set six rows throws unknown (not silently dropped)`() = runBlocking {
        // 6행+는 응답 신뢰 불가 → 조용히 버리지 않고 결과 불명 throw.
        server.dispatcher = setRawInfoDispatcher((1..6).joinToString(";") { "574067|2026|100$it|325|$it" })
        val ex = runCatching { service().purchase(setConfig(), theRound()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(ex?.message))
    }

    @Test fun `set duplicate jo row throws unknown (not partial)`() = runBlocking {
        // 조 [1,2,3,4,4] — 중복 조는 형식 이상 → 부분 성공 아니라 결과 불명 throw.
        val info = listOf(1, 2, 3, 4, 4).joinToString(";") { "574067|2026|100$it|325|$it" }
        server.dispatcher = setRawInfoDispatcher(info)
        val ex = runCatching { service().purchase(setConfig(), theRound()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(ex?.message))
    }

    @Test fun `set mismatched request number row throws unknown`() = runBlocking {
        // 배정 번호는 574067인데 한 행이 다른 번호(999999) → 요청번호 불일치 → 결과 불명 throw.
        val info = (1..5).joinToString(";") { i ->
            val no = if (i == 3) "999999" else "574067"
            "$no|2026|100$i|325|$i"
        }
        server.dispatcher = setRawInfoDispatcher(info)
        val ex = runCatching { service().purchase(setConfig(), theRound()) }.exceptionOrNull()
        assertTrue(ex is DhlotteryException)
        assertTrue(com.umicorp.autolotto720.isUnknownResultMessage(ex?.message))
    }

    // connPro가 code=100 + 임의의 prchsLtNoInfoLstCn 원문을 반환하는 세트 디스패처(이상 행 주입용).
    private fun setRawInfoDispatcher(info: String) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse = when {
            req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
            req.path == "/game/pension720/game.jsp" ->
                MockResponse().setBody("""<input name="USER_ID" value="t"/>""")
                    .addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
            req.path == "/makeAutoNo.do" ->
                enc("""{"resultCode":"100","selClsNo":"1,2,3,4,5","selLotNo":"574067","round":"325"}""")
            req.path == "/makeOrderNo.do" ->
                enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
            req.path == "/connPro.do" ->
                enc("""{"resultCode":"100","data":{"prchsLtNoInfoLstCn":"$info"}}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    // rows개 행을 code와 함께 반환하는 세트 디스패처.
    private fun setDispatcher(rows: Int, code: String) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse = when {
            req.path?.startsWith("/game/TotalGame.jsp") == true -> MockResponse().setBody("ok")
            req.path == "/game/pension720/game.jsp" ->
                MockResponse().setBody("""<input name="USER_ID" value="t"/>""")
                    .addHeader("Set-Cookie", "JSESSIONID=$jses; Path=/")
            req.path == "/makeAutoNo.do" ->
                enc("""{"resultCode":"100","selClsNo":"1,2,3,4,5","selLotNo":"574067","round":"325"}""")
            req.path == "/makeOrderNo.do" ->
                enc("""{"resultCode":"100","orderNo":"1","orderDate":"2026-07-16 23:03:23"}""")
            req.path == "/connPro.do" -> {
                val info = (1..rows).joinToString(";") { "574067|2026|100$it|325|$it" }
                enc("""{"resultCode":"$code","data":{"prchsLtNoInfoLstCn":"$info"}}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }
}
