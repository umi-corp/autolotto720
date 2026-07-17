package com.umicorp.autolotto720.data

import java.time.LocalDateTime

/** 연금복권720+ 당첨 등수. NONE=미당첨, PENDING=추첨 전. */
enum class Rank720 { FIRST, SECOND, THIRD, FOURTH, FIFTH, SIXTH, SEVENTH, BONUS, NONE, PENDING }

/**
 * 내역 볼 강조용 — 이 등수가 맞힌 "뒤 k자리" 개수. [RankChecker720]의 접미사 규칙과 1:1.
 * 1·2등·보너스=6(전체 일치), 3~7등=5·4·3·2·1자리, 미당첨/추첨전=0.
 * 720은 끝자리 1개만 맞아도 7등이라, NONE(낙첨)은 실제 맞은 자리 0 → 6자리 전부 흐림에 쓰인다.
 */
val Rank720.matchedDigits: Int
    get() = when (this) {
        Rank720.FIRST, Rank720.SECOND, Rank720.BONUS -> 6
        Rank720.THIRD -> 5
        Rank720.FOURTH -> 4
        Rank720.FIFTH -> 3
        Rank720.SIXTH -> 2
        Rank720.SEVENTH -> 1
        Rank720.NONE, Rank720.PENDING -> 0
    }

/**
 * 구매한 연금복권720+ 티켓 1장.
 * number/bonus는 6자리 0패딩 문자열 — leading zero 보존을 위해 Int가 아니라 String.
 */
data class Ticket720(
    val round: Int,
    val jo: Int,                 // 조 1..5
    val number: String,          // 6자리 0패딩
    val rank: Rank720? = null,
    val prize: Long = 0,         // 3~7등 일시금(원). 연금식(1·2·보너스)은 0, 표기는 UI에서.
    val checked: Boolean = false,
    val purchaseDate: LocalDateTime,
) {
    init { require720(round, jo, number) }
}

/** 회차별 당첨번호. date는 "yyyy-MM-dd" 포맷 문자열. number/bonus는 서로 다른 6자리(계약). */
data class WinningNumbers720(
    val round: Int,
    val jo: Int,                 // 1등 조
    val number: String,          // 1등 6자리 0패딩
    val bonusNumber: String,     // 보너스 6자리 0패딩
    val date: String,
) {
    init {
        require720(round, jo, number)
        require(bonusNumber.length == 6 && bonusNumber.all { it in '0'..'9' }) { "보너스 번호는 6자리 숫자여야 합니다: $bonusNumber" }
        require(number != bonusNumber) { "1등 번호와 보너스 번호는 서로 달라야 합니다: $number" } // R2 계약 코드화
    }
}

/** 720 티켓/당첨 공통 불변조건 — 파서 버그(빈값·7자리·비숫자·조 범위)를 생성 지점에서 차단(R1 F8). */
private fun require720(round: Int, jo: Int, number: String) {
    require(round >= 1) { "회차는 1 이상이어야 합니다: $round" }
    require(jo in 1..5) { "조는 1~5여야 합니다: $jo" }
    // '0'..'9'만 허용(isDigit는 유니코드 숫자까지 통과 — 000000~999999 계약보다 넓음, R2 codex#11)
    require(number.length == 6 && number.all { it in '0'..'9' }) { "번호는 6자리 숫자여야 합니다: $number" }
}
