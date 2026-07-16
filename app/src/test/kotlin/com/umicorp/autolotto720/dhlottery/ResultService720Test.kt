package com.umicorp.autolotto720.dhlottery

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ResultService720Test {
    private fun fixture(name: String) =
        {}.javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    @Test fun parses_group_number_bonus_from_fixture() = runTest {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("<html>ok</html>")) // main visit (cookie)
            enqueue(MockResponse().setBody(fixture("win720_result.json")))
            start()
        }
        val session = DhlotterySession(baseUrl = server.url("/").toString().trimEnd('/'))
        val result = ResultService720(session).getWinningNumbers(319)   // filter data.result[] by psltEpsd
        assertNotNull(result)
        assertEquals(319, result!!.round)
        assertEquals(3, result.jo)                 // wnBndNo
        assertEquals("201327", result.number)      // wnRnkVl
        assertEquals("632035", result.bonusNumber) // bnsRnkVl
        assertEquals("2026-06-11", result.date)    // psltRflYmd 20260611 → yyyy-MM-dd
        server.shutdown()
    }

    @Test fun preserves_leading_zero() = runTest {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("<html>ok</html>"))
            enqueue(MockResponse().setBody(fixture("win720_result.json")))
            start()
        }
        val session = DhlotterySession(baseUrl = server.url("/").toString().trimEnd('/'))
        val result = ResultService720(session).getWinningNumbers(314) // wnRnkVl "060727"
        assertEquals("060727", result!!.number)
        server.shutdown()
    }

    @Test fun non_200_returns_null() = runTest {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("<html/>"))
            enqueue(MockResponse().setResponseCode(500))
            start()
        }
        val session = DhlotterySession(baseUrl = server.url("/").toString().trimEnd('/'))
        assertEquals(null, ResultService720(session).getWinningNumbers(1))
        server.shutdown()
    }

    // FIX 4: 앞쪽 null/비객체 원소가 있어도 유효한 타깃 회차를 찾아 파싱한다(optJSONObject skip).
    @Test fun leading_null_element_still_resolves_target() = runTest {
        val body = """{"data":{"result":[null,{"psltEpsd":319,"wnBndNo":"3","wnRnkVl":"201327","bnsRnkVl":"632035","psltRflYmd":"20260611"}]}}"""
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("<html>ok</html>"))
            enqueue(MockResponse().setBody(body))
            start()
        }
        val session = DhlotterySession(baseUrl = server.url("/").toString().trimEnd('/'))
        val result = ResultService720(session).getWinningNumbers(319)
        assertNotNull(result)
        assertEquals(319, result!!.round)
        assertEquals(3, result.jo)
        assertEquals("201327", result.number)
        server.shutdown()
    }
}
