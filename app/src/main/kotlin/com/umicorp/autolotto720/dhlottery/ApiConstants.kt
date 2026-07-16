package com.umicorp.autolotto720.dhlottery

/**
 * 동행복권 API 상수. host(베이스 URL)는 [DhlotterySession]이 붙인다 —
 * 테스트에서 MockWebServer URL을 주입할 수 있도록 경로만 상수로 둔다.
 */
object ApiConstants {
    const val DEFAULT_BASE = "https://www.dhlottery.co.kr"
    const val DEFAULT_OLOTTO = "https://ol.dhlottery.co.kr"
    // 720 온라인 구매 게임 클라이언트 도메인. 645(ol)와 다른 서브도메인 — pension.js AES 서브시스템.
    const val DEFAULT_EL = "https://el.dhlottery.co.kr"

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

    // 720 온라인 구매 (el 도메인) — 라이브 캡처로 계약 확정(720-purchase-resume-runbook.md).
    // 진입: TotalGame.jsp?LottoId=LP72 → game.jsp 로 el JSESSIONID(암호화 passphrase) 발급.
    const val TOTAL_GAME_720 = "/game/TotalGame.jsp?LottoId=LP72"
    const val GAME720 = "/game/pension720/game.jsp"
    // 4단계 구매 플로우(모두 q=암호문 POST). makeOrderNo까지 무결제 예약, connPro가 실결제.
    const val MAKE_AUTO_NO_720 = "/makeAutoNo.do"
    const val MAKE_ORDER_NO_720 = "/makeOrderNo.do"
    const val CONN_PRO_720 = "/connPro.do"

    val DEFAULT_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive",
    )
}
