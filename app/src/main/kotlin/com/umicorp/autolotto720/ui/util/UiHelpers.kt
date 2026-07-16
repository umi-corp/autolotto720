package com.umicorp.autolotto720.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.umicorp.autolotto720.R
import com.umicorp.autolotto720.data.Rank720
import java.util.Locale

/**
 * UI 헬퍼 (원본 `lib/utils/ui_helpers.dart` 포트, Task13에서 720 등수로 전환).
 *
 * - 숫자 포맷은 일반 함수(컴포지션 불필요).
 * - 로컬라이즈가 필요한 헬퍼는 `@Composable`(stringResource 사용) — 백그라운드(Worker)에선
 *   쓰지 않는다. 알림 문구는 하드코딩 한국어(Notifications.kt)다.
 */

/** 천 단위 콤마 포맷. 당첨금은 Long(Int.MAX 초과 가능) — 원본 `formatNumber`의 음수 처리까지 `%,d`가 대신한다. */
fun formatNumber(n: Long): String = String.format(Locale.US, "%,d", n)

/** Int 잔액/소액용 오버로드 (잔액 `balance`는 Int). */
fun formatNumber(n: Int): String = formatNumber(n.toLong())

/** 조 → 로컬라이즈 라벨 ("N조" 등). 홈 당첨번호·내역 티켓 조+번호 표시가 공유. */
@Composable
fun localizedJoLabel(jo: Int): String = stringResource(R.string.joLabel, jo)

/** [Rank720] → 로컬라이즈 등수 라벨 (1~7등 + 보너스 + 미당첨/추첨대기). */
@Composable
fun localizedRank(rank: Rank720): String = when (rank) {
    Rank720.FIRST -> stringResource(R.string.rank1st)
    Rank720.SECOND -> stringResource(R.string.rank2nd)
    Rank720.THIRD -> stringResource(R.string.rank3rd)
    Rank720.FOURTH -> stringResource(R.string.rank4th)
    Rank720.FIFTH -> stringResource(R.string.rank5th)
    Rank720.SIXTH -> stringResource(R.string.rank6th)
    Rank720.SEVENTH -> stringResource(R.string.rank7th)
    Rank720.BONUS -> stringResource(R.string.rankBonus)
    Rank720.NONE -> stringResource(R.string.rankNoWin720)
    Rank720.PENDING -> stringResource(R.string.rankPendingDraw)
}

/**
 * [Rank720] → 당첨금 표기. 1·2등/보너스는 연금식 고정 문구, 3~7등은 [prize](일시금)를 ₩ 포맷,
 * 미당첨/추첨대기는 빈 문자열(호출부가 표시 여부를 결정).
 */
@Composable
fun localizedPrize(rank: Rank720, prize: Long): String = when (rank) {
    Rank720.FIRST -> stringResource(R.string.prizeAnnuity1st)
    Rank720.SECOND, Rank720.BONUS -> stringResource(R.string.prizeAnnuity2nd)
    Rank720.THIRD, Rank720.FOURTH, Rank720.FIFTH, Rank720.SIXTH, Rank720.SEVENTH ->
        stringResource(R.string.prizeLabel, formatNumber(prize))
    Rank720.NONE, Rank720.PENDING -> ""
}

/** 로컬라이즈된 요일명 리스트 (월~일) — 원본 `localizedDayNames`. */
@Composable
fun localizedDayNames(): List<String> = listOf(
    stringResource(R.string.dayMon),
    stringResource(R.string.dayTue),
    stringResource(R.string.dayWed),
    stringResource(R.string.dayThu),
    stringResource(R.string.dayFri),
    stringResource(R.string.daySat),
    stringResource(R.string.daySun),
)

/**
 * 자동 구매 스케줄 텍스트 (원본 `formatPurchaseScheduleL10n`).
 * day는 1=월 .. 7=일. weeklySchedule 템플릿("매주 {day}요일 {time}")에 짧은 요일명 + HH:mm을 넣는다.
 */
@Composable
fun formatPurchaseSchedule(day: Int, hour: Int, minute: Int): String {
    val time = String.format(Locale.US, "%02d:%02d", hour, minute)
    return stringResource(R.string.weeklySchedule, localizedDayNames()[day - 1], time)
}
