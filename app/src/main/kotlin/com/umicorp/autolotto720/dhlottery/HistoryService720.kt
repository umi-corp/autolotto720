package com.umicorp.autolotto720.dhlottery

import com.umicorp.autolotto720.data.Ticket720
import java.time.LocalDate

/**
 * 연금복권720+ 구매 내역 조회 서비스.
 *
 * 720 원장 상세 엔드포인트와 상품명(`ltGdsNm`=연금복권720+) 문자열이 아직 캡처되지 않았다 —
 * 테스트 계정에 720 구매 실적이 0건이라 확인이 불가했다(720-api-contract.md §5). 계약을 캡처하기
 * 전까지는 [Feature720.PURCHASE_ENABLED]로 게이트한다 — 게이트가 켜지지 않은 동안은 네트워크를
 * 전혀 타지 않고 빈 목록을 반환한다.
 *
 * [resultService]는 실제 플로우에서 미추첨(PENDING) 여부/당첨 등수 판정에 쓸 720 결과 조회 서비스.
 */
class HistoryService720(
    private val session: DhlotterySession,
    private val resultService: ResultService720,
) {

    /** 최근 구매 내역(최대 [count]건) — 결과확인 워커용. */
    suspend fun fetchRecentPurchases(count: Int = 10): List<Ticket720> =
        fetchPurchases(LocalDate.now().minusDays(90), LocalDate.now(), count)

    /** [from]~[to](포함) 기간의 구매 내역. */
    suspend fun fetchPurchases(from: LocalDate, to: LocalDate, count: Int = Int.MAX_VALUE): List<Ticket720> {
        if (!Feature720.PURCHASE_ENABLED) return emptyList()

        // TODO(720 구매 계약 미확정 — contract §5): 위 가드가 항상 먼저 반환하므로 아래는 현재 도달 불가.
        // 실제 플로우(계약 캡처 후 채울 것, 645 HistoryService와 동형):
        //   1) 마이페이지 원장 방문(Referer용) → selectMyLotteryledger.do(목록, [from]~[to])
        //   2) 목록에서 ltGdsNm == "연금복권720+" 항목만 필터
        //   3) 항목마다 720 상세 엔드포인트 호출 → 게임별 조/번호를 Ticket720으로 평탄화
        //   4) resultService로 해당 회차 추첨 여부 확인 → 미추첨이면 rank=Rank720.PENDING
        //   5) take(count)로 상한 적용
        return emptyList()
    }
}
