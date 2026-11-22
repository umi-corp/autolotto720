package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.LedgerRow
import com.umicorp.autolotto720.data.RowStatus
import com.umicorp.autolotto720.data.Window
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** 픽스처는 실측 응답 형태를 따른 합성 값이다(실제 주문번호·번호 아님). */
class LedgerService720Test {

    private lateinit var server: MockWebServer
    private lateinit var session: DhlotterySession
    private val pages = mutableListOf<String?>()
    private var winningCalls = 0
    private val window = Window(LocalDate.parse("2026-08-22"), LocalDate.parse("2026-11-22"))

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        session = DhlotterySession(baseUrl = baseUrl, olottoUrl = baseUrl)
    }

    @After fun tearDown() = server.shutdown()

    private val service get() = LedgerService720(session, ResultService720(session))

    /** [result] null이면 당첨번호 엔드포인트가 404(재판정 불가)다. */
    private fun serve(result: String? = null, page: (Int) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                ApiConstants.PURCHASE_HISTORY -> {
                    val n = request.requestUrl!!.queryParameter("pageNum")
                    pages += n
                    page(n!!.toInt())
                }
                ApiConstants.WINNING_NUMBER_720 -> {
                    winningCalls++
                    if (result == null) MockResponse().setResponseCode(404)
                    else MockResponse().setBody("""{"data":{"result":[$result]}}""")
                }
                else -> MockResponse().setBody("ok")
            }
        }
    }

    private fun ok(total: Int, rows: List<String>) =
        MockResponse().setBody("""{"data":{"total":$total,"list":[${rows.joinToString(",")}]}}""")

    private fun pension(order: String, gmInfo: String, result: String = "낙첨", wnRnk: String? = null, amt: String? = "0", round: Int = 330) =
        """{"ntslOrdrNo":"$order","ltGdsCd":"LP72","ltGdsNm":"연금복권720+","ltEpsd":$round,"eltOrdrDt":"2026-10-01",
           "prchsQty":1,"gmInfo":"$gmInfo","ltWnResult":"$result","wnRnk":${wnRnk?.let { "\"$it\"" } ?: "null"},
           "ltWnAmt":${amt?.let { "\"$it\"" } ?: "null"}}"""

    // 330회 당첨번호: 1등 3조 654321, 보너스 111111
    private val win330 = """{"psltEpsd":330,"wnBndNo":"3","wnRnkVl":"654321","bnsRnkVl":"111111","psltRflYmd":"20261008"}"""

    @Test fun fetch_flattensTicketsWithOrderKey() = runBlocking {
        serve { ok(5, (1..5).map { pension("O1", "$it:123456") }) }
        val rows = service.fetch(window)
        assertEquals(5, rows.map { it.k }.toSet().size)
        assertTrue(rows.all { it.q == 1 && it.k.startsWith("O1|") })
    }

    @Test fun fetch_commaGmInfo_splits() = runBlocking {
        serve { ok(1, listOf(pension("O1", "1:123456,2:123456"))) }
        assertEquals(setOf("O1|1:123456", "O1|2:123456"), service.fetch(window).map { it.k }.toSet())
    }

    @Test fun fetch_skipsOtherProducts() = runBlocking {
        val lotto = """{"ntslOrdrNo":"L1","ltGdsCd":"LO40","ltGdsNm":"로또6/45","ltEpsd":1250,"eltOrdrDt":"2026-10-01","prchsQty":5,"ltWnResult":"낙첨"}"""
        serve { ok(2, listOf(lotto, pension("O1", "1:123456"))) }
        assertEquals(listOf("O1|1:123456"), service.fetch(window).map { it.k })
    }

    @Test fun fetch_resolvesWithWinningNumbers() = runBlocking {
        serve(win330) { ok(1, listOf(pension("O1", "1:000001", result = "당첨", wnRnk = "7", amt = "1000"))) }
        val r = service.fetch(window).single()
        assertEquals("SEVENTH", r.rk)
        assertEquals(1000L, r.amt)
        assertEquals(RowStatus.WIN, r.st)
        assertFalse(r.u)
    }

    @Test fun fetch_pendingResolvesWhenNumbersPublished() = runBlocking {
        serve(win330) { ok(1, listOf(pension("O1", "1:999999", result = "미추첨", amt = null))) }
        val r = service.fetch(window).single()
        assertEquals(RowStatus.LOSE, r.st)
        assertEquals("NONE", r.rk)
    }

    @Test fun fetch_pendingWithoutNumbers_staysPending() = runBlocking {
        serve { ok(1, listOf(pension("O1", "1:999999", result = "미추첨", amt = null))) }
        val r = service.fetch(window).single()
        assertEquals(RowStatus.PENDING, r.st)
        assertNull(r.rk)
        assertEquals(0L, r.amt)
        assertFalse(r.u)
    }

    @Test fun fetch_noWinningNumbers_marksUnresolvedAndZeroAnnuity() = runBlocking {
        serve { ok(1, listOf(pension("O1", "1:654321", result = "당첨", wnRnk = "2", amt = "7000000"))) }
        val r = service.fetch(window).single()
        assertTrue(r.u)
        assertEquals("SECOND", r.rk)
        assertEquals(0L, r.amt)
        assertEquals(RowStatus.WIN, r.st)
    }

    @Test fun fetch_noWinningNumbers_lowRankUsesLumpPrize() = runBlocking {
        serve { ok(1, listOf(pension("O1", "1:000001", result = "당첨", wnRnk = "7", amt = "1000"))) }
        val r = service.fetch(window).single()
        assertTrue(r.u)
        assertEquals(1000L, r.amt)
    }

    @Test fun resolveUnresolved_clearsFlag() = runBlocking {
        serve(win330) { ok(0, emptyList()) }
        val row = LedgerRow("O1|3:654321", LocalDate.parse("2026-10-01"), 330, 1, RowStatus.WIN, 0, rk = "SECOND", u = true)
        val other = LedgerRow("O2|1:000000", LocalDate.parse("2026-10-01"), 331, 1, RowStatus.LOSE, 0, rk = "NONE", u = true)
        val out = resolveUnresolved(listOf(row, other), ResultService720(session)).associateBy { it.k }
        val first = out.getValue("O1|3:654321")
        assertFalse(first.u)
        assertEquals("FIRST", first.rk)
        assertEquals(0L, first.amt)
        assertTrue(out.getValue("O2|1:000000").u) // 331회 당첨번호 없음 → 그대로
    }

    // 당첨번호 목록은 전 회차를 한 번에 준다. 프리필은 과거로 내려가므로 실행(서비스 인스턴스)당 한 번만 받는다.
    @Test fun fetch_winningNumbersFetchedOncePerRun() = runBlocking {
        val win329 = """{"psltEpsd":329,"wnBndNo":"1","wnRnkVl":"111112","bnsRnkVl":"222222","psltRflYmd":"20261001"}"""
        serve("$win330,$win329") { ok(1, listOf(pension("O${pages.size}", "1:000001", result = "낙첨", round = 330 - pages.size + 1))) }
        val svc = service
        svc.fetch(window)
        svc.fetch(Window(LocalDate.parse("2026-05-21"), LocalDate.parse("2026-08-21")))
        assertEquals(1, winningCalls)
    }

    @Test fun fetch_pagesUntilTotal() = runBlocking {
        serve { n -> ok(140, (if (n == 1) 0 until 100 else 100 until 140).map { pension("O$it", "1:123456") }) }
        assertEquals(140, service.fetch(window).size)
        assertEquals(listOf("1", "2"), pages)
    }

    @Test fun fetch_htmlBody_throws() {
        serve { MockResponse().setBody("<html>login</html>") }
        assertThrows(IOException::class.java) { runBlocking { service.fetch(window) } }
    }
}
