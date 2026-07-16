package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.Ticket720
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 연금복권720+ 온라인 구매 서비스 (el.dhlottery.co.kr + pension.js AES 서브시스템).
 *
 * 라이브 캡처로 확정한 4단계 계약(720-purchase-resume-runbook.md):
 *   0) el 세션 확립: TotalGame.jsp?LottoId=LP72 → game.jsp 로 el JSESSIONID(암호화 passphrase) 발급
 *   1) makeAutoNo.do  — 반자동(조 고정, 번호 자동배정). 무결제.
 *   2) makeOrderNo.do — 주문번호 예약. 무결제.
 *   3) connPro.do     — 실결제(₩1,000/게임). **비멱등 → 재시도 절대 금지.** 무응답/HTML=결과불명.
 *
 * 각 단계: 평문(jQuery serialize 동치, 값 URL-encode) → [Crypto720.encrypt] → [Crypto720.wireQ](이중
 * 인코딩) → `q=`로 POST → 응답 `{q}` 복호화 → JSON. passphrase(JSESSIONID)는 서버가 회전시킬 수 있어
 * **매 단계 쿠키에서 새로 읽는다**(브라우저 encrypt()가 매번 getCookie하는 것과 동일).
 *
 * 게이트: 이 서비스는 [Feature720.PURCHASE_ENABLED]를 **직접 검사하지 않는다** — 자동구매 무장은
 * 호출부([com.umicorp.autolotto720.scheduler.AutoPurchaseWorker])가 게이트한다. 따라서 감독 하의
 * 단발 실구매(폰 검증)로 이 코드를 실행할 수 있고, 스케줄 자동발화는 워커 게이트가 계속 막는다.
 */
class PurchaseService720(
    private val auth: AuthService,
    private val session: DhlotterySession,
    private val clock: Clock = Clock.system(Round720.KST),
) {
    private val elHost: String get() = session.elUrl.toHttpUrl().host

    /**
     * 반자동 [games]게임 구매(조 1..5 순환, 번호 자동배정). 게임 수(1~5)는 네트워크보다 먼저 검증.
     * 각 게임은 makeAutoNo→makeOrderNo→connPro 1사이클(connPro 성공이 다음 게임의 "구매 진행중" 락을 해제).
     */
    suspend fun purchase(games: Int): PurchaseResult720 = withContext(Dispatchers.IO) {
        if (games !in 1..5) throw DhlotteryException("게임 수는 1~5개여야 합니다. (현재: $games)")
        purchaseGames(List(games) { GameSpec(jo = (it % 5) + 1) })
    }

    /** 반자동 게임 스펙 — 조 고정, 번호는 서버 자동배정(수동 지정번호는 별도 검증 슬라이스). */
    data class GameSpec(val jo: Int) {
        init { require(jo in 1..5) { "조는 1~5여야 합니다: $jo" } }
    }

    private suspend fun purchaseGames(specs: List<GameSpec>): PurchaseResult720 {
        require(specs.size in 1..5) { "게임 수는 1~5개여야 합니다: ${specs.size}" }
        establishGameSession()
        if (elJsessionId() == null) throw DhlotteryException("게임 세션 확립 실패 (로그인 상태를 확인하세요)")

        val round = Round720.getUpcomingDrawRound(ZonedDateTime.now(clock))
        val tickets = specs.map { purchaseOneGame(round, it) }
        return PurchaseResult720(round, tickets, tickets.size * UNIT_PRICE)
    }

    /** el 게임 클라이언트 진입 → el JSESSIONID(암호화 passphrase) 발급. */
    private fun establishGameSession() {
        session.get(session.el(ApiConstants.TOTAL_GAME_720), follow = true).close()
        session.get(
            session.el(ApiConstants.GAME720),
            mapOf("Referer" to session.el(ApiConstants.TOTAL_GAME_720)),
            follow = true,
        ).close()
    }

    /** 단품(₩1,000) 1게임: 배정→예약(무결제)→결제(단발). connPro 결과불명/실패는 예외로 던진다(재시도 금지). */
    private fun purchaseOneGame(round: Int, spec: GameSpec): Ticket720 {
        // 1) makeAutoNo — 조 반자동 번호 배정 (무결제)
        val r1 = encStep(ApiConstants.MAKE_AUTO_NO_720, buildPlain(
            "ROUND" to round.toString(), "SEL_NO" to "", "BUY_CNT" to "",
            "AUTO_SEL_SET" to "S", "SEL_CLASS" to spec.jo.toString(), "BUY_TYPE" to "A", "ACCS_TYPE" to "01",
        ))
        requireSuccess(r1, "번호 배정")
        val jo = r1.optString("selClsNo").split(",").first().ifBlank { spec.jo.toString() }
        val number = r1.optString("selLotNo").split(",").first()
        require(number.matches(Regex("\\d{6}"))) { "배정 번호 형식 오류: $number" }

        // 2) makeOrderNo — 주문번호 예약 (무결제)
        val r2 = encStep(ApiConstants.MAKE_ORDER_NO_720, buildPlain(
            "ROUND" to round.toString(), "SEL_NO" to number, "BUY_CNT" to "1",
            "AUTO_SEL_SET" to "S", "SEL_CLASS" to jo, "BUY_TYPE" to "A", "ACCS_TYPE" to "01",
        ))
        requireSuccess(r2, "주문 생성")
        val orderNo = r2.optString("orderNo")
        val orderDate = r2.optString("orderDate")
        require(orderNo.isNotBlank() && orderDate.isNotBlank()) { "주문번호 누락" }

        // 3) connPro — 실결제 (단발, 재시도 금지). 결과불명(HTML/복호화 실패)은 encStep이 예외로 던진다.
        val r3 = encStep(ApiConstants.CONN_PRO_720, buildPlain(
            "ROUND" to round.toString(), "BUY_NO" to "$jo$number", "BUY_CNT" to "1",
            "BUY_SET_TYPE" to "S", "BUY_TYPE" to "A", "ACCS_TYPE" to "01",
            "orderNo" to orderNo, "orderDate" to orderDate, "auto_process" to "Y",
            "set_type" to "S", "classnum" to jo, "selnum" to number, "buytype" to "A",
            "num1" to number[0].toString(), "num2" to number[1].toString(), "num3" to number[2].toString(),
            "num4" to number[3].toString(), "num5" to number[4].toString(), "num6" to number[5].toString(),
            "verifyYN" to "N",
        ))
        val code = r3.optString("resultCode")
        if (code !in SUCCESS_CODES) {
            throw DhlotteryException("구매 실패(code=$code): ${r3.optString("resultMsg").ifBlank { "결과 불명 — 내역으로 대조하세요" }}")
        }
        // 성공: saleTicket("조+6자리", 콤마구분)에서 실제 발급 티켓 파싱. 누락 시 요청값으로 대체.
        val sold = r3.optString("saleTicket").split(",").firstOrNull { it.length == 7 }
        val soldJo = sold?.substring(0, 1)?.toIntOrNull() ?: jo.toInt()
        val soldNo = sold?.substring(1) ?: number
        return Ticket720(round = round, jo = soldJo, number = soldNo, purchaseDate = LocalDateTime.now(clock))
    }

    /**
     * el 암호화 단계 실행: 평문 → q(암호문, 이중 인코딩) POST → 응답 `{q}` 복호화 → JSON.
     * passphrase는 요청 직전 쿠키의 현재 JSESSIONID(회전 대응). 비-JSON 응답(HTML "error ocurred")·복호화
     * 실패는 결과불명으로 간주해 [DhlotteryException]을 던진다(connPro에서 이는 곧 재시도 금지 신호).
     */
    private fun encStep(path: String, plain: String): JSONObject {
        val jses = elJsessionId() ?: throw DhlotteryException("세션 만료 — 다시 로그인하세요")
        val q = Crypto720.wireQ(Crypto720.encrypt(plain, jses))
        val body = session.post(
            session.el(path),
            "q=$q",
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to session.el(ApiConstants.GAME720),
                "Origin" to session.elUrl,
            ),
        ).use { it.body?.string().orEmpty() }
        if (!body.trimStart().startsWith("{")) {
            throw DhlotteryException("서버 응답 오류 ($path) — 결과 불명, 재시도 금지")
        }
        val enc = JSONObject(body).optString("q")
        val json = if (enc.isNotBlank()) Crypto720.decrypt(enc, jses) else body
        return JSONObject(json)
    }

    private fun elJsessionId(): String? = session.cookies.cookieValue(elHost, "JSESSIONID")

    private fun requireSuccess(resp: JSONObject, step: String) {
        val code = resp.optString("resultCode")
        if (code != "100") {
            throw DhlotteryException("$step 실패(code=$code): ${resp.optString("resultMsg").ifBlank { "판매 마감 또는 일시 오류" }}")
        }
    }

    /** jQuery `.serialize()` 동치 — 값을 application/x-www-form-urlencoded로 인코딩(orderDate 공백·콜론 등). */
    private fun buildPlain(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private companion object {
        const val UNIT_PRICE = 1000
        val SUCCESS_CODES = setOf("100", "110", "120")  // 100=완료, 110=부분완료, 120=가능 티켓 없음
    }
}

/** 720 구매 결과. */
data class PurchaseResult720(val round: Int, val tickets: List<Ticket720>, val amount: Int)
