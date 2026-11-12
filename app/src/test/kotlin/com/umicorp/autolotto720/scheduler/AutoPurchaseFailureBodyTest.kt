package com.umicorp.autolotto720.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 자동구매 실패 알림이 연결 실패를 자격증명 탓으로 통보하지 않아야 한다.
 *
 * 사용자 리포트의 "로그인이 안 되는 것처럼 보인다"의 정체 — 동행복권 무응답(타임아웃)인데
 * 알림은 "아이디/비밀번호를 확인해주세요"였고, 사용자는 멀쩡한 계정을 의심하며 손을 놓았다.
 */
class AutoPurchaseFailureBodyTest {

    private fun bodyFor(e: Exception) =
        AutoPurchaseWorker.failureBody(e.message.orEmpty(), e.isConnectivityFailure())

    @Test
    fun `timeout during login is not reported as a credential problem`() {
        val body = bodyFor(Exception("[login] timeout", SocketTimeoutException("timeout")))

        assertFalse(body, body.contains("비밀번호"))
        assertTrue(body, body.contains("연결하지 못했습니다"))
    }

    @Test
    fun `real credential rejection still says to check the credentials`() {
        // cause 없음 = 네트워크 실패 아님
        assertEquals(
            "로그인에 실패했습니다. 아이디/비밀번호를 확인해주세요.",
            bodyFor(Exception("[login] INVALID_CREDENTIALS")),
        )
    }

    @Test
    fun `commit failure after payment is never masked as a connection problem`() {
        // [commit]은 결제가 이미 성공한 뒤라, 연결 실패로 뭉뚱그리면 구매된 사실을 숨긴다.
        val body = bodyFor(Exception("[commit] 회차 기록 저장에 실패했습니다.", IOException("disk")))

        assertFalse(body, body.contains("연결하지 못했습니다"))
        assertEquals("구매는 완료되었으나 기록 저장에 실패했습니다. 앱에서 구매 내역을 확인해주세요.", body)
    }

    @Test
    fun `purchase-stage timeout keeps the check-the-app guidance`() {
        // 구매 접수 여부가 불명이라 "연결 실패"로 바꾸면 안 된다.
        val body = bodyFor(Exception("[purchase] read timed out", SocketTimeoutException("timeout")))

        assertFalse(body, body.contains("연결하지 못했습니다"))
        assertEquals("자동 구매에 실패했습니다. 앱을 열어 상태를 확인해주세요.", body)
    }

    @Test
    fun `server rejection message is passed through`() {
        assertEquals(
            "구매 실패: 주간 구매금액을 초과했습니다",
            bodyFor(Exception("[purchase] 구매 실패: 주간 구매금액을 초과했습니다")),
        )
    }

    @Test
    fun `connectivity failure is detected through the cause chain`() {
        assertTrue(Exception("wrapped", Exception("inner", IOException("boom"))).isConnectivityFailure())
        assertTrue(IOException("direct").isConnectivityFailure())
        assertFalse(Exception("plain").isConnectivityFailure())
    }

    @Test
    fun `cause cycle does not hang the detector`() {
        // 방어 코드 확인 — 순환 cause에도 유한 시간에 판정한다.
        val a = Exception("a")
        val b = Exception("b", a)
        a.initCause(b)
        assertFalse(a.isConnectivityFailure())
    }
}
