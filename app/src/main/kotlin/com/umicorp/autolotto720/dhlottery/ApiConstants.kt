package com.umicorp.autolotto720.dhlottery

/**
 * 동행복권 API 상수. host(베이스 URL)는 [DhlotterySession]이 붙인다 —
 * 테스트에서 MockWebServer URL을 주입할 수 있도록 경로만 상수로 둔다.
 */
object ApiConstants {
    const val DEFAULT_BASE = "https://www.dhlottery.co.kr"
    const val DEFAULT_OLOTTO = "https://ol.dhlottery.co.kr"

    // www 경로
    const val LOGIN_PAGE = "/login"
    const val RSA_MODULUS = "/login/selectRsaModulus.do"
    const val LOGIN = "/login/securityLoginCheck.do"
    const val MAIN = "/main"
    const val BALANCE = "/mypage/selectUserMndp.do"
    const val MYPAGE_LEDGER = "/mypage/mylotteryledger" // 원장 페이지(.do 아님) — 내역 방문/Referer용
    const val PURCHASE_HISTORY = "/mypage/selectMyLotteryledger.do"

    // 720 경로 — 당첨번호 조회는 확정(720-api-contract.md §1), 나머지는 로그인 스파이크 대상 TODO placeholder.
    const val WINNING_NUMBER_720 = "/pt720/selectPstPt720WnList.do"

    // TODO(720 구매 계약 미확정 — §4): el.dhlottery.co.kr 게임 클라이언트 진입점. 구매는 pension.js AES
    // 암호화 서브시스템이라 평문 재현 불가 — 로그인 세션에서 브라우저 캡처 필요.
    const val GAME720 = "/game/pension720/game.jsp"

    // TODO(720 구매 계약 미확정 — §4): 자리표시자 경로. 실캡처 전까지 사용 금지.
    const val READY_SOCKET_720 = "/game/pension720/readySocket.json"
    const val EXEC_BUY_720 = "/game/pension720/execBuy.do"
    const val TICKET_DETAIL_720 = "/mypage/pension720TicketDetail.do"

    val DEFAULT_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive",
    )
}
