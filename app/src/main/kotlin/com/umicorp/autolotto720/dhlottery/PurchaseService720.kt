package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.Slot720
import com.umicorp.autolotto720.data.Ticket720
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Clock
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

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
     * 번호 탭 설정([NumberConfig720]) 기반 구매 — 슬롯별 조/번호를 그대로 산다.
     *  - [Slot720.FullAuto] → 조·번호 모두 자동(조는 슬롯 위치로 1..5 분산)
     *  - [Slot720.SemiAuto] → 조 고정, 번호 자동배정
     *  - [Slot720.Manual]   → 조+지정번호. 점유(매진) 시 [NumberConfig720.fallback] 정책 적용.
     */
    suspend fun purchase(config: NumberConfig720, round: Int): PurchaseResult720 = withContext(Dispatchers.IO) {
        if (config.setMode) return@withContext purchaseSet(round)   // 모든조 세트(SA) — 슬롯 매핑 앞에서 분기
        val specs = config.slots.mapIndexedNotNull { i, slot ->
            when (slot) {
                Slot720.Unset -> null
                Slot720.FullAuto -> GameSpec.Auto(jo = (i % 5) + 1)
                is Slot720.SemiAuto -> GameSpec.Auto(jo = slot.group)
                is Slot720.Manual -> GameSpec.Manual(jo = slot.group, digits = slot.digits)
            }
        }
        if (specs.isEmpty()) throw DhlotteryException("구매할 게임이 없습니다 (번호 탭에서 설정하세요)")
        if (specs.size > 5) throw DhlotteryException("온라인 구매 한도는 5게임입니다 (현재: ${specs.size})")
        purchaseGames(specs, config.fallback, round)
    }

    /** 반자동 [games]게임(조 1..5 순환, 번호 자동배정) 구매 — 감독 테스트/워커 브리지용 간이 진입점. */
    suspend fun purchase(games: Int, round: Int): PurchaseResult720 = withContext(Dispatchers.IO) {
        if (games !in 1..5) throw DhlotteryException("게임 수는 1~5개여야 합니다. (현재: $games)")
        purchaseGames(List(games) { GameSpec.Auto(jo = (it % 5) + 1) }, FallbackPolicy.REASSIGN_ALL, round)
    }

    /** 구매 게임 스펙 — 자동/반자동(조 고정, 번호 자동)과 수동(조+지정번호). */
    sealed interface GameSpec {
        val jo: Int
        data class Auto(override val jo: Int) : GameSpec {
            init { require(jo in 1..5) { "조는 1~5여야 합니다: $jo" } }
        }
        data class Manual(override val jo: Int, val digits: List<Int>) : GameSpec {
            init {
                require(jo in 1..5) { "조는 1~5여야 합니다: $jo" }
                require(digits.size == 6 && digits.all { it in 0..9 }) { "번호는 6자리(0~9)여야 합니다: $digits" }
            }
            val number: String get() = digits.joinToString("")
        }
    }

    // round는 호출부(PurchaseLock 안)가 확정해 주입 — 서비스 내부 재계산 금지(TOCTOU 방지, 모든 경로 동일 회차).
    private suspend fun purchaseGames(specs: List<GameSpec>, fallback: FallbackPolicy, round: Int): PurchaseResult720 {
        require(specs.size in 1..5) { "게임 수는 1~5개여야 합니다: ${specs.size}" }
        // 세션 확립(el JSESSIONID 발급) + USER_ID 추출을 먼저 — freeze는 그 다음이어야 game.jsp 쿠키가 저장된다.
        val userId = establishGameSession()
        if (elJsessionId() == null) throw DhlotteryException("게임 세션 확립 실패 (로그인 상태를 확인하세요)")

        // 이후 구매 동안 el JSESSIONID 고정 — 서버가 응답에서 회전시켜도 주문(makeOrderNo)과 결제(connPro)가
        // 같은 세션에 묶이게 한다(회전 시 주문 유실 방지). 브라우저 동작과 동일.
        session.cookies.freeze("JSESSIONID")
        try {
            // GIVE_UP 폴백으로 스킵된 게임은 null → 제외. connPro 성공이 다음 게임의 "구매 진행중" 락을 해제.
            // 전부 스킵돼 빈 결과가 나올 수 있음(지정번호 점유 + 구매 포기) — 이는 오류가 아니라 정책상 정상 결과.
            // 게임 도중 오류(한도 초과 등): 이미 결제된 게임이 있으면 버리지 않고 남은 게임을 중단, 부분 성공으로
            // 반환한다 — 호출자가 회차 가드를 기록해야 재실행 중복결제를 막는다. 한 게임도 못 샀으면 전체 실패(throw).
            val tickets = mutableListOf<Ticket720>()
            var failure: PartialFailure? = null
            for ((i, spec) in specs.withIndex()) {
                val ticket = try {
                    purchaseOneGame(round, spec, userId, fallback)
                } catch (e: Exception) {
                    if (e is CancellationException || tickets.isEmpty()) throw e
                    failure = PartialFailure(failedGames = specs.size - i, cause = e)
                    break
                }
                if (ticket != null) tickets += ticket
            }
            return PurchaseResult720(round, tickets, tickets.size * UNIT_PRICE, failure)
        } finally {
            session.cookies.unfreeze("JSESSIONID")
        }
    }

    /**
     * 모든조 세트(SA) 구매 — makeAutoNo(SA, 5조 공통 번호 1개) → makeOrderNo(SA, 5매 예약) → connPro 1회.
     * 티켓은 응답 prchsLtNoInfoLstCn 행 수(N)로 판정: N=5 완전, 1≤N<5 부분(전액 원장·PartialFailure),
     * N=0/형식이상 결과 불명 throw. connPro 이후 실패는 명시 거절만 확정, 그 외 "결과 불명" 마커.
     */
    private suspend fun purchaseSet(round: Int): PurchaseResult720 {   // round 주입, 폴백 개념 없음(자동번호 1개 배정)
        val userId = establishGameSession()
        if (elJsessionId() == null) throw DhlotteryException("게임 세션 확립 실패 (로그인 상태를 확인하세요)")
        session.cookies.freeze("JSESSIONID")
        try {
            val number = assignSetNumber(round)
            return buySet(round, number, userId)
        } finally {
            session.cookies.unfreeze("JSESSIONID")
        }
    }

    /** makeAutoNo를 SA로 1회 호출 → 5개 조 공통 6자리 번호. */
    private fun assignSetNumber(round: Int): String {
        val r = encStep(ApiConstants.MAKE_AUTO_NO_720, buildPlain(
            "ROUND" to round.toString(), "SEL_NO" to "", "BUY_CNT" to "",
            "AUTO_SEL_SET" to "SA", "SEL_CLASS" to "", "BUY_TYPE" to "A", "ACCS_TYPE" to "01",
        ))
        requireSuccess(r, "세트 번호 배정")
        val number = r.optString("selLotNo").split(",").first()
        require(number.matches(Regex("\\d{6}"))) { "세트 배정 번호 형식 오류: $number" }
        return number
    }

    /** makeOrderNo(SA·5매) → connPro(SA·5매·1회) → 실응답 행 수 기준 티켓. */
    private fun buySet(round: Int, number: String, userId: String): PurchaseResult720 {
        val r2 = encStep(ApiConstants.MAKE_ORDER_NO_720, buildPlain(
            "ROUND" to round.toString(), "SEL_NO" to number, "BUY_CNT" to "5",
            "AUTO_SEL_SET" to "SA", "SEL_CLASS" to "", "BUY_TYPE" to "A", "ACCS_TYPE" to "01",
        ))
        requireSuccess(r2, "세트 주문 생성")
        val orderNo = r2.optString("orderNo"); val orderDate = r2.optString("orderDate")
        require(orderNo.isNotBlank() && orderDate.isNotBlank()) { "세트 주문번호 누락" }

        val buyNo = (1..5).joinToString(",") { "$it$number" }
        val r3 = encStep(ApiConstants.CONN_PRO_720, buildPlain(
            "ROUND" to round.toString(), "FLAG" to "", "BUY_KIND" to "01",
            "BUY_NO" to buyNo, "BUY_CNT" to "5", "BUY_SET_TYPE" to "SA,SA,SA,SA,SA",
            "BUY_TYPE" to "A,A,A,A,A,", "ACCS_TYPE" to "01",
            "orderNo" to orderNo, "orderDate" to orderDate, "TRANSACTION_ID" to "", "WIN_DATE" to "",
            "USER_ID" to userId, "PAY_TYPE" to "",
            "resultErrorCode" to "", "resultErrorMsg" to "", "resultOrderNo" to "",
            "WORKING_FLAG" to "false", "NUM_CHANGE_TYPE" to "",
            "auto_process" to "Y", "set_type" to "SA", "classnum" to "", "selnum" to number, "buytype" to "A",
            "num1" to number[0].toString(), "num2" to number[1].toString(), "num3" to number[2].toString(),
            "num4" to number[3].toString(), "num5" to number[4].toString(), "num6" to number[5].toString(),
            "DSEC" to "0", "CLOSE_DATE" to "", "verifyYN" to "N", "curdeposit" to "0", "curpay" to "5000",
        ))
        val code = r3.optString("resultCode")
        // connPro 응답의 모든 비-SUCCESS 코드는 결과 불명(재시도 금지). 명시 무결제 거절 화이트리스트는
        // 실측(Task 8) 전까지 두지 않는다 — 근거 없는 "재시도 안전" 분류가 곧 이중결제 창이므로 최대 보수.
        if (code !in SUCCESS_CODES) throw DhlotteryException(rejectionMessage(code, r3))
        val tickets = parseSetTickets(r3, round, number)   // 형식 이상/0행이면 여기서 "결과 불명" throw
        val failure = if (tickets.size < 5)
            PartialFailure(failedGames = 5 - tickets.size, cause = DhlotteryException("세트 부분완료 — 결과 불명분은 내역으로 대조하세요"))
        else null
        return PurchaseResult720(round, tickets, tickets.size * UNIT_PRICE, failure)
    }

    /**
     * prchsLtNoInfoLstCn 다중 행(세미콜론/개행 구분) → 티켓. 각 행 "번호|주문|일련|회차|조".
     * 형식이상(요청번호[reqNumber] 불일치·조 1..5 범위밖·조 중복·6행+)은 **조용히 버리지 않고** "결과 불명"으로
     * throw한다 — 서버가 code=100이어도 응답 행이 이상하면 파싱 전체를 신뢰할 수 없으므로 통째 불명 처리(회차
     * 가드가 Unknown에도 커밋돼 이중결제를 막는다). "정상 행 N개(1≤N<5)"는 부분 성공, "형식 이상"은 불명 throw로 구분.
     */
    private fun parseSetTickets(r3: JSONObject, round: Int, reqNumber: String): List<Ticket720> {
        val raw = r3.optJSONObject("data")?.optString("prchsLtNoInfoLstCn").orEmpty()
        // 각 행 trim — CRLF(\r\n) 응답에서 split("\n")이 행 끝에 남기는 \r을 제거(마지막 필드 toIntOrNull 실패 → 정상 5행도 "결과 불명" 오판 방지).
        val rows = raw.split(";", "\n").map { it.trim() }.filter { it.isNotBlank() }
        if (rows.size > 5) throw DhlotteryException("세트 응답 이상(${rows.size}행) — 결과 불명, 내역으로 대조하세요")
        val seenJo = mutableSetOf<Int>()
        val tickets = rows.map { row ->
            val f = row.split("|")
            val no = f.getOrNull(0)
            val jo = f.getOrNull(4)?.toIntOrNull()
            if (no != reqNumber || jo == null || jo !in 1..5 || !seenJo.add(jo)) {
                throw DhlotteryException("세트 응답 형식 이상 — 결과 불명, 내역으로 대조하세요")
            }
            Ticket720(round = round, jo = jo, number = no, purchaseDate = LocalDateTime.now(clock))
        }
        if (tickets.isEmpty()) throw DhlotteryException("세트 구매 결과 불명 — 내역으로 대조하세요")
        return tickets
    }

    /** 모든 비-SUCCESS 코드에 "결과 불명" 마커를 심는다(서버 msg 유무와 무관) → classifyPurchaseFailure=Unknown. */
    private fun rejectionMessage(code: String, r3: JSONObject): String {
        val msg = r3.optString("resultMsg").ifBlank { r3.optString("resultMessage") }
        return "구매 실패(code=$code): 결과 불명 — ${msg.ifBlank { "내역으로 대조하세요" }}"
    }

    /** el 게임 클라이언트 진입 → el JSESSIONID(암호화 passphrase) 발급 + game.jsp의 USER_ID 반환.
     *  데스크톱 UA 필수 — game.jsp는 모바일 UA를 감지하면 모바일 구매 페이지로 보내(계약과 다른 응답). */
    private fun establishGameSession(): String {
        session.get(session.el(ApiConstants.TOTAL_GAME_720), mapOf("User-Agent" to DESKTOP_UA), follow = true).close()
        val gameHtml = session.get(
            session.el(ApiConstants.GAME720),
            mapOf("User-Agent" to DESKTOP_UA, "Referer" to session.el(ApiConstants.TOTAL_GAME_720)),
            follow = true,
        ).use { it.body?.string().orEmpty() }
        val userId = Regex("""name="USER_ID"\s+value="([^"]*)"""").find(gameHtml)?.groupValues?.get(1).orEmpty()
        dbg("게임 세션 확립: elJSESSIONID=${elJsessionId() != null} userIdFound=${userId.isNotBlank()}")
        return userId
    }

    /**
     * 1게임 구매. 자동/반자동은 makeAutoNo로 번호 배정, 수동은 checkVerifyNo로 지정번호 점유 확인.
     * 수동 지정번호가 점유(매진)면 [fallback]에 따라 스킵(null)·같은 조 자동·조까지 자동으로 대체한다.
     * makeOrderNo까지 무결제, connPro가 단발 실결제(재시도 금지). GIVE_UP 스킵 시 null 반환.
     */
    private fun purchaseOneGame(round: Int, spec: GameSpec, userId: String, fallback: FallbackPolicy): Ticket720? {
        return when (spec) {
            is GameSpec.Auto -> {
                val (jo, number) = assignAuto(round, spec.jo)
                buyNumber(round, jo, number, userId, manual = false)
            }
            is GameSpec.Manual -> {
                if (verifyManualAvailable(round, spec.jo, spec.number)) {
                    buyNumber(round, spec.jo.toString(), spec.number, userId, manual = true)
                } else when (fallback) {
                    FallbackPolicy.GIVE_UP -> null   // 점유 → 이 게임 스킵
                    FallbackPolicy.KEEP_GROUP_RANDOM -> {  // 조 유지, 번호 자동 재배정
                        val (jo, number) = assignAuto(round, spec.jo)
                        buyNumber(round, jo, number, userId, manual = false)
                    }
                    FallbackPolicy.REASSIGN_ALL -> {  // 조+번호 모두 자동 재배정
                        val (jo, number) = assignAuto(round, (spec.jo % 5) + 1)
                        buyNumber(round, jo, number, userId, manual = false)
                    }
                }
            }
        }
    }

    /** makeAutoNo로 [reqJo] 조의 번호를 서버 배정받는다(무결제) → (조, 6자리번호). */
    private fun assignAuto(round: Int, reqJo: Int): Pair<String, String> {
        val r = encStep(ApiConstants.MAKE_AUTO_NO_720, buildPlain(
            "ROUND" to round.toString(), "SEL_NO" to "", "BUY_CNT" to "",
            "AUTO_SEL_SET" to "S", "SEL_CLASS" to reqJo.toString(), "BUY_TYPE" to "A", "ACCS_TYPE" to "01",
        ))
        requireSuccess(r, "번호 배정")
        val jo = r.optString("selClsNo").split(",").first().ifBlank { reqJo.toString() }
        val number = r.optString("selLotNo").split(",").first()
        require(number.matches(Regex("\\d{6}"))) { "배정 번호 형식 오류: $number" }
        return jo to number
    }

    /**
     * 수동 지정번호([number] in 조 [jo])의 구매 가능(비점유) 여부를 checkVerifyNo로 확인한다(무결제).
     *
     * 라이브 응답(실측): resultMsg가 슬래시 구분 문자열
     *   `resultCode/verifyYn/recommendYN/회차/셋트/조들/번호들/타입`
     *   예) 가능 `100/Y/N/325/S/1/483010/M` · 점유 `100/Y/Y/325/S/1,1,1,1,1/917128,…/A`(서버 추천번호 동반)
     * verifyYn=="Y" 이고 recommendYN=="N"(점유 아님)이면 구매 가능. 슬래시 파싱을 우선하되,
     * 형식이 바뀌면 동일 이름의 JSON 필드로 폴백한다.
     */
    private fun verifyManualAvailable(round: Int, jo: Int, number: String): Boolean {
        val r = encStep(ApiConstants.CHECK_VERIFY_NO_720, buildPlain(
            "ROUND" to round.toString(), "SEL_NO" to number, "BUY_CNT" to "1",
            "AUTO_SEL_SET" to "S", "SEL_CLASS" to jo.toString(), "BUY_TYPE" to "M", "ACCS_TYPE" to "01",
        ))
        if (r.optString("resultCode") != "100") return false
        val tokens = r.optString("resultMsg").split("/")
        val verifyYn = tokens.getOrNull(1)?.trim().takeIf { !it.isNullOrEmpty() }
            ?: r.optString("verifyYn")
        val recommendYn = tokens.getOrNull(2)?.trim().takeIf { !it.isNullOrEmpty() }
            ?: r.optString("recommendYN")
        return verifyYn.equals("Y", ignoreCase = true) && !recommendYn.equals("Y", ignoreCase = true)  // 점유(추천) 아님
    }

    /**
     * makeOrderNo(예약, 무결제) → connPro(실결제, 단발) → 발급 티켓. [manual]=true면 수동(buytype=M) 계약.
     * connPro는 #frm 전체 필드를 브라우저 serialize 순서로 전송해야 서버가 예외 없이 처리한다.
     */
    private fun buyNumber(round: Int, jo: String, number: String, userId: String, manual: Boolean): Ticket720 {
        val buyType = if (manual) "M" else "A"
        val autoProcess = if (manual) "N" else "Y"
        val verifyYn = if (manual) "Y" else "N"

        val r2 = encStep(ApiConstants.MAKE_ORDER_NO_720, buildPlain(
            "ROUND" to round.toString(), "SEL_NO" to number, "BUY_CNT" to "1",
            "AUTO_SEL_SET" to "S", "SEL_CLASS" to jo, "BUY_TYPE" to buyType, "ACCS_TYPE" to "01",
        ))
        requireSuccess(r2, "주문 생성")
        val orderNo = r2.optString("orderNo")
        val orderDate = r2.optString("orderDate")
        require(orderNo.isNotBlank() && orderDate.isNotBlank()) { "주문번호 누락" }

        val r3 = encStep(ApiConstants.CONN_PRO_720, buildPlain(
            "ROUND" to round.toString(), "FLAG" to "", "BUY_KIND" to "01",
            "BUY_NO" to "$jo$number", "BUY_CNT" to "1", "BUY_SET_TYPE" to "S", "BUY_TYPE" to buyType, "ACCS_TYPE" to "01",
            "orderNo" to orderNo, "orderDate" to orderDate, "TRANSACTION_ID" to "", "WIN_DATE" to "",
            "USER_ID" to userId, "PAY_TYPE" to "",
            "resultErrorCode" to "", "resultErrorMsg" to "", "resultOrderNo" to "",
            "WORKING_FLAG" to "false", "NUM_CHANGE_TYPE" to "",
            "auto_process" to autoProcess, "set_type" to "S", "classnum" to jo, "selnum" to number, "buytype" to buyType,
            "num1" to number[0].toString(), "num2" to number[1].toString(), "num3" to number[2].toString(),
            "num4" to number[3].toString(), "num5" to number[4].toString(), "num6" to number[5].toString(),
            "DSEC" to "0", "CLOSE_DATE" to "", "verifyYN" to verifyYn, "curdeposit" to "0", "curpay" to "1000",
        ))
        val code = r3.optString("resultCode")
        if (code !in SUCCESS_CODES) throw DhlotteryException(rejectionMessage(code, r3))
        // 성공 응답(실측): data.prchsLtNoInfoLstCn = "번호|주문번호|일련번호|회차|조". 형식 이상 시 요청값 폴백.
        val info = r3.optJSONObject("data")?.optString("prchsLtNoInfoLstCn").orEmpty().split("|")
        val soldNo = info.getOrNull(0)?.takeIf { it.matches(Regex("\\d{6}")) } ?: number
        val soldJo = info.getOrNull(4)?.toIntOrNull()?.takeIf { it in 1..5 } ?: jo.toInt()
        return Ticket720(round = round, jo = soldJo, number = soldNo, purchaseDate = LocalDateTime.now(clock))
    }

    /**
     * el 암호화 단계 실행: 평문 → q(암호문, 이중 인코딩) POST → 응답 `{q}` 복호화 → JSON.
     * passphrase는 요청 직전 쿠키의 현재 JSESSIONID(회전 대응). 비-JSON 응답(HTML "error ocurred")·복호화
     * 실패는 결과불명으로 간주해 [DhlotteryException]을 던진다(connPro에서 이는 곧 재시도 금지 신호).
     */
    private fun encStep(path: String, plain: String): JSONObject {
        val jses = elJsessionId() ?: run {
            dbg("$path: el JSESSIONID 없음 (세션 미확립)")
            throw DhlotteryException("세션 만료 — 다시 로그인하세요")
        }
        val q = Crypto720.wireQ(Crypto720.encrypt(plain, jses))
        val (status, body) = session.post(
            session.el(path),
            "q=$q",
            mapOf(
                "User-Agent" to DESKTOP_UA,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to session.el(ApiConstants.GAME720),
                "Origin" to session.elUrl,
            ),
        ).use { it.code to it.body?.string().orEmpty() }
        val isJson = body.trimStart().startsWith("{")
        dbg("$path: http=$status json=$isJson jsesLen=${jses.length} bodyLen=${body.length}")
        if (!isJson) {
            dbg("$path: 비-JSON 응답 head=${body.trimStart().take(80).replace("\n", " ")}")
            throw DhlotteryException("서버 응답 오류 ($path) — 결과 불명, 재시도 금지")
        }
        val enc = JSONObject(body).optString("q")
        val json = if (enc.isNotBlank()) Crypto720.decrypt(enc, jses) else body
        val obj = JSONObject(json)
        dbg("$path: resultCode=${obj.optString("resultCode")} resultMsg=${obj.optString("resultMsg")}")
        return obj
    }

    /** [DEBUG 전용] 구매 단계 로깅 — 자격증명/계정ID/응답 본문은 남기지 않는다(단계·코드·크기만). */
    private fun dbg(msg: String) {
        if (com.umicorp.autolotto720.BuildConfig.DEBUG) android.util.Log.d("Purchase720", msg)
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
        val SUCCESS_CODES = setOf("100")   // 실측 확정된 완료만. 110/120은 Task 8 전까지 결과 불명으로 처리.
        // el 게임 서버는 데스크톱 UA 전제(모바일 UA → 모바일 페이지 리다이렉트). 라이브 캡처에 쓴 UA.
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

/** 720 구매 결과. [partialFailure]가 있으면 다게임 구매가 도중에 끊긴 부분 성공 — [tickets]는 실제 결제분만. */
data class PurchaseResult720(
    val round: Int,
    val tickets: List<Ticket720>,
    val amount: Int,
    val partialFailure: PartialFailure? = null,
)

/** 다게임 구매 중단 — [failedGames]=결제되지 않은 게임 수(실패 게임 + 미시도 잔여), [cause]=첫 실패 원인. */
data class PartialFailure(val failedGames: Int, val cause: Exception)
