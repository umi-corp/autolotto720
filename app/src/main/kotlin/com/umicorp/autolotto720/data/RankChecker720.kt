package com.umicorp.autolotto720.data

/**
 * 연금복권720+ 등수 판정 (순수 함수). 단일 최고 등수만 반환한다.
 *
 * 규칙(스펙):
 *  1등 = 조 일치 AND 6자리 일치
 *  2등 = 6자리 일치, 조 불일치
 *  보너스 = 보너스 6자리 완전 일치 (조 무관) — 1등 번호와 다른 별도 번호
 *  3~7등 = 1등 번호의 끝 5/4/3/2/1자리 일치 (조 무관)
 *
 * 우선순위: 1등 > (2등 | 보너스, 상호배타 — 두 6자리는 서로 다름) > 3등 > … > 7등 > 미당첨.
 * 6자리 완전 일치(2등/보너스)는 어떤 끝자리 일치보다 상위이므로 접미사 루프보다 먼저 판정.
 */
object RankChecker720 {
    fun rankOf(myJo: Int, myNumber: String, win: WinningNumbers720): Rank720 {
        // fail-fast(R1 F8): padStart는 자르지 않으므로 7자리·비숫자가 조용히 접미사 비교에 참여하는 것을 차단.
        require(myJo in 1..5) { "조는 1~5여야 합니다: $myJo" }
        require(myNumber.length == 6 && myNumber.all { it in '0'..'9' }) { "번호는 6자리 숫자여야 합니다: $myNumber" }
        val my = myNumber
        val winNum = win.number       // WinningNumbers720 생성자가 이미 6자리 보장
        val bonus = win.bonusNumber

        if (my == winNum) return if (myJo == win.jo) Rank720.FIRST else Rank720.SECOND
        if (my == bonus) return Rank720.BONUS

        // 끝 k자리 일치 → 3등(k=5) … 7등(k=1)
        for (k in 5 downTo 1) {
            if (my.takeLast(k) == winNum.takeLast(k)) {
                return when (k) {
                    5 -> Rank720.THIRD
                    4 -> Rank720.FOURTH
                    3 -> Rank720.FIFTH
                    2 -> Rank720.SIXTH
                    else -> Rank720.SEVENTH
                }
            }
        }
        return Rank720.NONE
    }
}
