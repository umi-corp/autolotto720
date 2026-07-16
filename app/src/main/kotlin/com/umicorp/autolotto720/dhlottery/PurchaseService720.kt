package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.Ticket720
import java.time.Clock

/**
 * 연금복권720+ 온라인 구매 서비스.
 *
 * 645(ol.dhlottery.co.kr 평문 POST)와 달리 720 온라인 구매는 el.dhlottery.co.kr/game/pension720/game.jsp
 * + pension.js의 클라이언트 사이드 AES 암호화 서브시스템이다. 브라우저 캡처로 계약을 확보하기 전까지는
 * [Feature720.PURCHASE_ENABLED]로 게이트한다(720-api-contract.md §4) — 게임 수 검증만 통과하면
 * 항상 "준비 중" [DhlotteryException]을 던지고 네트워크/로그인에는 닿지 않는다.
 *
 * [auth]/[session]은 645 AuthService/DhlotterySession을 재사용한다(계정 계열 공유, 계약 §4).
 * [clock]은 실 구매 플로우(회차 계산 등)에서 쓸 KST 고정 시계 — 테스트에서 고정 Clock 주입용.
 */
class PurchaseService720(
    private val auth: AuthService,
    private val session: DhlotterySession,
    private val clock: Clock = Clock.system(Round720.KST),
) {

    /**
     * 720 구매 실행. 게임 수 검증(1~5)이 기능 게이트보다 먼저, 기능 게이트가 네트워크/로그인보다
     * 먼저 실행된다 — 둘 다 통과해야 아래 미구현 플로우에 닿는다(현재는 항상 도달 불가).
     */
    suspend fun purchase(games: Int): PurchaseResult720 {
        if (games !in 1..5) throw DhlotteryException("게임 수는 1~5개여야 합니다. (현재: $games)")
        if (!Feature720.PURCHASE_ENABLED) throw DhlotteryException("구매 기능은 준비 중입니다 (720 구매 계약 미확보)")

        // TODO(720 구매 계약 미확정 — contract §4): 위 가드가 항상 먼저 던지므로 아래는 현재 도달 불가.
        // AES(pension.js) 프로토콜을 브라우저에서 캡처하기 전까지 execBuy 상당 POST를 임의로 만들지 않는다.
        // 실제 플로우(캡처 후 채울 것):
        //   1) el.dhlottery.co.kr/game/pension720/game.jsp 방문 (session 재사용, 로그인은 auth로 확인)
        //   2) buildParam720(games)로 평문 파라미터 구성
        //   3) pension.js AES 프로토콜로 암호화 → execBuy 상당 엔드포인트 POST
        //   4) 응답 파싱 → round(Round720 기준)/tickets(Ticket720)/amount 추출 → PurchaseResult720
        error("PURCHASE_ENABLED=false: 720 구매 미구현 (AES 계약 미확보, contract §4)")
    }

    /** AES 구매 계약(pension.js) 캡처 후 구현 — contract §4. */
    private fun buildParam720(games: Int): String =
        TODO("AES 구매 계약(pension.js) 캡처 후 구현 — contract §4")
}

/** 720 구매 결과. */
data class PurchaseResult720(val round: Int, val tickets: List<Ticket720>, val amount: Int)
