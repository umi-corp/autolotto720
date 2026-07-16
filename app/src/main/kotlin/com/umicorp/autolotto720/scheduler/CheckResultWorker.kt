package com.umicorp.autolotto720.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.umicorp.autolotto720.data.Rank720
import com.umicorp.autolotto720.data.RankChecker720
import com.umicorp.autolotto720.data.SecureStore
import com.umicorp.autolotto720.data.Ticket720
import com.umicorp.autolotto720.data.WinningNumbers720
import com.umicorp.autolotto720.dhlottery.AuthService
import com.umicorp.autolotto720.dhlottery.DhlotterySession
import com.umicorp.autolotto720.dhlottery.HistoryService720
import com.umicorp.autolotto720.dhlottery.ResultService720
import com.umicorp.autolotto720.dhlottery.Round720
import kotlin.coroutines.cancellation.CancellationException

/**
 * 당첨확인 백그라운드 작업 — 연금복권720+ 포트 (원본 `_onCheckResultAlarm`/645 CheckResultWorker 기반).
 *
 * 당첨번호 조회(로그인 불필요, 결과확인은 라이브 — Task6) → 로그인 → 구매이력 조회(현재는 구매 게이트로
 * 항상 빈 목록 — Task8) → 매칭 티켓별 당첨/낙첨 알림 → 끝에서 결과확인 알람(1002, 고정 목 21:00) 자가재등록.
 *
 * 회차는 [Round720.getLatestCompletedRound]다 — 이 워커는 목 21:00(추첨 19:05 이후)에 돌므로
 * "지금 판매 중" 회차가 아니라 "방금 추첨된" 회차를 확인해야 한다(645의 "현재 판매 회차"와의 핵심 차이).
 */
class CheckResultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val ctx = applicationContext
    private val store = SecureStore(ctx)

    override suspend fun doWork(): Result {
        val lastAttempt = runAttemptCount >= MAX_ATTEMPTS - 1
        // 결과 미게시·일시 네트워크 오류는 재시도(backoff 15분 선형)로 흡수하고,
        // 시도 소진 시에만 실패를 알린다 — 침묵 종료·낙첨 오보 방지.
        val done = try {
            executeCheckResult(lastAttempt)
        } catch (e: Exception) {
            if (e is CancellationException) throw e   // 코루틴 취소는 삼키지 않는다 — 오보/오분류 방지(R3).
            if (lastAttempt) Notifications.show(ctx, "⚠️ AutoLotto720 오류", "당첨 결과 확인에 실패했습니다.", 99)
            lastAttempt
        }

        // 다음 주 알람 재등록 — 자동구매 활성 시에만. 멱등이라 재시도 회차에 또 호출돼도 안전.
        try {
            if (store.getAutoEnabled()) AlarmScheduler(ctx).scheduleCheckResult()
        } catch (e: Exception) {
            Notifications.show(ctx, "⚠️ AutoLotto720 오류", "결과확인 알람 재등록 실패: ${e.message ?: e}", 98)
        }

        return if (done) Result.success() else Result.retry()
    }

    /** 원본 `_executeCheckResult` 기반. @return true=완결(성공/폴백/무동작), false=미확정(재시도 필요). */
    private suspend fun executeCheckResult(lastAttempt: Boolean): Boolean {
        if (!store.getAutoEnabled()) return true

        val round = Round720.getLatestCompletedRound()
        // 당첨번호 미게시(null): 마지막 시도가 아니면 재시도(false), 마지막 시도면 폴백 알림 후 종료(true).
        // (미게시가 영구 지속돼도 무한 재시도하지 않고 결과확인 실패를 통지한다 — 결과확인은 로그인 불필요.)
        val winning = ResultService720().getWinningNumbers(round)
        if (winning == null) {
            val notif = unpostedNotification(round, lastAttempt) ?: return false
            Notifications.show(ctx, notif.first, notif.second, 2, tab = Notifications.TAB_HISTORY)
            return true
        }

        // 로그인 → 구매이력 조회. 실패(로그인/네트워크 등)는 마지막 시도 전까지 재시도.
        val tickets: List<Ticket720> = try {
            val cred = store.getCredentials()
            val userId = cred.userId ?: throw Exception("no_credentials")
            val password = cred.password ?: throw Exception("no_credentials")

            val session = DhlotterySession()
            val auth = AuthService(session)
            auth.login(userId, password)

            HistoryService720(session, ResultService720(session)).fetchRecentPurchases()
        } catch (e: Exception) {
            if (e is CancellationException) throw e   // 코루틴 취소는 삼키지 않는다(R3).
            if (!lastAttempt) return false
            emptyList() // 마지막 시도 — 매칭 없음으로 취급해 당첨번호만 폴백 알림
        }

        val (title, body) = when (val outcome = resolveCheckOutcome(winning, tickets, round)) {
            // 방어적 분기 — winning은 위에서 이미 non-null을 확인했으므로 이 갈래는 도달하지 않는다.
            is CheckOutcome.Retry -> return false
            is CheckOutcome.NoMatch -> buildFallbackNotification(round, outcome.winning)
            is CheckOutcome.Matched -> buildMatchedNotification(round, outcome.winning, outcome.tickets)
        }
        Notifications.show(ctx, title, body, 2, tab = Notifications.TAB_HISTORY)
        return true
    }

    private companion object {
        /** 총 시도 횟수(최초 1 + 재시도 3) — 목 21:00 기준 15분 선형 backoff로 ~22:30까지 커버. */
        const val MAX_ATTEMPTS = 4
    }
}

/**
 * 결과확인 순수 판정 결과 — Worker의 I/O(로그인·조회·알림)와 분리해 테스트 가능하게 노출한다.
 * (Worker 본체는 android.content.Context/SecureStore 의존이라 이 프로젝트의 JVM 단위테스트로는
 * 직접 인스턴스화할 수 없다 — 645 레이어도 워커 단위테스트가 없는 동일한 제약.)
 */
internal sealed interface CheckOutcome {
    /** 당첨번호 미게시 → 재시도. */
    data object Retry : CheckOutcome

    /** 해당 회차 매칭 구매 없음 → 당첨번호만 알리고 완결(645 `no_matching_purchase` 폴백과 동일). */
    data class NoMatch(val winning: WinningNumbers720) : CheckOutcome

    /** 매칭 티켓 있음(PENDING은 로컬 재계산 완료) → 등수 포함 알림. */
    data class Matched(val winning: WinningNumbers720, val tickets: List<Ticket720>) : CheckOutcome
}

/**
 * [allTickets] 중 [round] 매칭분을 찾아 판정한다. 매칭 티켓의 등수가 PENDING이면(히스토리 상세가
 * 아직 추첨결과를 반영하지 못한 경우) "추첨 대기"로 통지하는 대신 [winning]으로 로컬 재계산한다(R2 N4).
 */
internal fun resolveCheckOutcome(
    winning: WinningNumbers720?,
    allTickets: List<Ticket720>,
    round: Int,
): CheckOutcome {
    if (winning == null) return CheckOutcome.Retry
    val matched = allTickets.filter { it.round == round }
    if (matched.isEmpty()) return CheckOutcome.NoMatch(winning)
    // 미확정(null)·미추첨(PENDING) 등수는 로컬 재계산 — 미설정을 낙첨(NONE)으로 오보하지 않는다(R2 N4).
    // rank과 함께 prize도 일시금 표에서 세팅 → 3~7등이 총액 합산에 정상 반영된다.
    val resolved = matched.map { t ->
        if (t.rank == null || t.rank == Rank720.PENDING) {
            val r = RankChecker720.rankOf(t.jo, t.number, winning)
            t.copy(rank = r, prize = Notifications.lumpSumPrizeOf(r))
        } else t
    }
    return CheckOutcome.Matched(winning, resolved)
}

/** 매칭 티켓별 조+번호·등수·당첨금 알림 문구(제목, 본문). */
internal fun buildMatchedNotification(round: Int, winning: WinningNumbers720, tickets: List<Ticket720>): Pair<String, String> {
    val winningLine = "당첨번호: ${winning.jo}조 ${winning.number} + 보너스 ${winning.bonusNumber}"
    val gameLines = tickets.joinToString("\n") { t ->
        val rank = t.rank ?: Rank720.NONE
        val label = Notifications.rank720Label(rank)
        val prizeText = Notifications.rank720PrizeText(rank)
        val suffix = if (prizeText.isEmpty()) "" else " ($prizeText)"
        "${t.jo}조 ${t.number} → $label$suffix"
    }
    val isWinner = tickets.any { (it.rank ?: Rank720.NONE) != Rank720.NONE }
    val title = if (isWinner) "🎉 제 ${round}회 당첨!!!" else "😔 제 ${round}회 낙첨..."
    var body = "$winningLine\n\n$gameLines"
    // 3~7등(일시금)만 합산 — 1·2등·보너스는 연금식이라 단일 총액이 아니라 위 라인의 문구로 표기.
    // 총액도 등수에서 파생(단일 출처) — 라인 문구와 어긋나지 않고, 미설정 prize 필드에 좌우되지 않는다.
    val lumpSum = tickets.sumOf { Notifications.lumpSumPrizeOf(it.rank ?: Rank720.NONE) }
    if (lumpSum > 0) body += "\n\n총 당첨금(일시금): ₩${Notifications.formatThousands(lumpSum)}"
    return title to body
}

/**
 * 당첨번호 미게시(null) 처리 결정 — 순수 함수로 분리해 "마지막 시도 종료"를 워커 I/O 없이 검증 가능하게 한다.
 * @return null=재시도, non-null=(제목, 본문) 폴백 알림 후 재시도 체인 종료.
 */
internal fun unpostedNotification(round: Int, lastAttempt: Boolean): Pair<String, String>? =
    if (lastAttempt) "🎱 제 ${round}회 결과 확인 실패" to "결과 확인 실패 (당첨번호 미게시)" else null

/** 매칭 구매 없음(또는 조회 실패 마지막 시도) 폴백 — 당첨번호만 알림(제목, 본문). */
internal fun buildFallbackNotification(round: Int, winning: WinningNumbers720): Pair<String, String> {
    val title = "🎱 제 ${round}회 당첨번호"
    val body = "${winning.jo}조 ${winning.number} + 보너스 ${winning.bonusNumber}"
    return title to body
}
