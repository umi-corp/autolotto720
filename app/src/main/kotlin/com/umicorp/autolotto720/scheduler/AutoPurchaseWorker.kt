package com.umicorp.autolotto720.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.umicorp.autolotto720.data.SecureStore
import com.umicorp.autolotto720.dhlottery.AuthService
import com.umicorp.autolotto720.dhlottery.DhlotterySession
import com.umicorp.autolotto720.dhlottery.Feature720
import com.umicorp.autolotto720.dhlottery.PurchaseService720
import com.umicorp.autolotto720.dhlottery.Round720
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 자동구매 백그라운드 작업 — 연금복권720+ 포트 (원본 `_onAutoPurchaseAlarm`/645 AutoPurchaseWorker 기반).
 *
 * SecureStore에서 자격증명·설정을 직접 읽고(원본 백그라운드 isolate 직접읽기 패턴 유지),
 * 로그인 → 구매 → 성공/실패 알림 → 끝에서 다음 주 알람 자가재등록.
 *
 * 720 온라인 구매는 클라이언트 사이드 AES 계약이 미확보(720-api-contract.md §4)라
 * [Feature720.PURCHASE_ENABLED]=false인 동안은 로그인/구매를 전혀 시도하지 않고 조용히 무동작한다
 * (호출 시 [PurchaseService720.purchase]가 항상 throw하므로 게이트를 그 앞에서 끊는다).
 *
 * 네트워크는 Worker(코루틴, ~10분 한도)에서 수행 — onReceive 10초 제한 회피.
 */
class AutoPurchaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val ctx = applicationContext
    private val store = SecureStore(ctx)

    override suspend fun doWork(): Result {
        var result: Result = Result.success()

        // 1) 실제 자동구매 (원본 _onAutoPurchaseAlarm의 try/catch + 메시지 매핑)
        try {
            executeAutoPurchase()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // [purchase] 실패는 서버가 요청을 이미 처리했을 수 있어 재전송 금지(중복 결제 방지) → 즉시 알림.
            // 그 이전 단계(자격증명·로그인 등)는 일시 오류일 수 있어 재시도하고, 마지막 시도에만 알린다.
            val ambiguous = isAmbiguousFailure(msg)
            if (!ambiguous && runAttemptCount < MAX_ATTEMPTS - 1) {
                result = Result.retry()
            } else {
                val body = when {
                    msg.contains("[login]") -> "로그인에 실패했습니다. 아이디/비밀번호를 확인해주세요."
                    // API 응답 메시지 그대로 전달 (주간구매금액 초과 등)
                    msg.contains("구매 실패:") -> msg.replace("[purchase] ", "")
                    else -> "자동 구매에 실패했습니다. 앱을 열어 상태를 확인해주세요."
                }
                Notifications.show(ctx, "⚠️ AutoLotto720 오류", body, 99)
            }
        }

        // 2) 다음 주 알람 재등록 (one-shot 체인) — 자동구매 활성 시에만. 멱등이라 재시도 회차에 또 호출돼도 안전.
        try {
            if (store.getAutoEnabled()) AlarmScheduler(ctx).scheduleAutoPurchase()
        } catch (e: Exception) {
            Notifications.show(ctx, "⚠️ AutoLotto720 오류", "알람 재등록에 실패했습니다.", 98)
        }

        return result
    }

    /** 원본 `_executeAutoPurchase` 포트. 실패 시 `[step] 메시지`로 감싸 던진다(doWork의 매핑이 사용). */
    private suspend fun executeAutoPurchase() {
        var step = "init"
        try {
            step = "read_credentials"
            val cred = store.getCredentials()
            val userId = cred.userId
            val password = cred.password
            val autoEnabled = store.getAutoEnabled()
            val games = store.getAutoGames()

            if (!autoEnabled || userId == null || password == null) return
            if (games == 0) return

            step = "feature_gate"
            // ponytail: 구매 계약(AES, pension.js) 미확보 — 게이트 켜지기 전까지 조용히 무동작(재시도 없음).
            // Feature720.PURCHASE_ENABLED=true로 뒤집히면 아래 가드부터 정상 작동한다.
            if (!Feature720.PURCHASE_ENABLED) return

            val now = ZonedDateTime.now(Round720.KST)

            // 판매마감 가드(R2 N2 — 좁은 창만): 다가오는 회차의 추첨일이 오늘이고 17:00 이후면 스킵.
            // 19:05 추첨 후엔 다가오는 회차가 다음 주로 롤오버되므로(추첨일≠오늘) 이 가드를 통과해 정상 구매된다.
            step = "sales_close_guard"
            if (isSalesClosed(now)) return

            // 회차 멱등 가드: Worker 재실행(프로세스 킬 후 WorkManager 재스케줄)·중복 알람에도 같은 회차 재구매 방지.
            step = "round_guard"
            if (isRoundAlreadyPurchased(now, store.getLastPurchasedRound())) return

            step = "login"
            val session = DhlotterySession()
            val auth = AuthService(session)
            auth.login(userId, password)

            step = "purchase"
            val purchaseService = PurchaseService720(auth, session)
            try {
                val result = purchaseService.purchase(games = games)

                // 성공 즉시 회차 기록(commit) — 이후 재실행은 round_guard가 차단.
                // ponytail: 서버 처리~기록 사이 찰나에 킬되는 창은 남는다(중복결제 double-charge ceiling,
                // "autolotto 정책 그대로" 결정으로 수용) — unique work 직렬화 + [purchase] 비재시도가 1차 방어.
                store.setLastPurchasedRound(result.round)

                // 구매 후 잔액 체크 (실패 무시 — 원본 catch (_) {})
                runCatching {
                    val postBalance = auth.getBalance()
                    BalanceAlert.checkAndNotify(ctx, postBalance)
                }

                step = "notify"
                val ticketLines = result.tickets.joinToString("\n") { "${it.jo}조 ${it.number}" }

                Notifications.show(
                    ctx,
                    "🎰 AutoLotto720 자동 구매 완료!",
                    "제 ${result.round}회 · ${result.tickets.size}게임\n$ticketLines",
                    1,
                    tab = Notifications.TAB_HISTORY,
                )
            } catch (purchaseError: Exception) {
                throw Exception(purchaseError.message ?: "$purchaseError")
            }
        } catch (e: Exception) {
            throw Exception("[$step] ${e.message ?: e}")
        }
    }

    companion object {
        /** 총 시도 횟수(최초 1 + 재시도 2). 간격은 SchedulerReceivers의 backoff(15분 선형). */
        private const val MAX_ATTEMPTS = 3
        private val SALES_CLOSE: LocalTime = LocalTime.of(17, 0)

        /**
         * 판매마감 가드: 다가오는 회차의 추첨일이 오늘이고 17:00 이후면 true(스킵).
         * 테스트 전용 노출(internal) — Worker 본체는 Context/SecureStore 의존이라 JVM 단위테스트로
         * 직접 인스턴스화할 수 없어(645 레이어도 동일 제약), 위임하는 순수 판정만 검증한다.
         */
        internal fun isSalesClosed(now: ZonedDateTime): Boolean {
            val upcomingRound = Round720.getUpcomingDrawRound(now)
            return Round720.getDrawDate(upcomingRound) == now.toLocalDate() && now.toLocalTime() >= SALES_CLOSE
        }

        /** 회차 멱등 가드: 이미 다가오는 회차를 구매했으면 true(스킵). 테스트 전용 노출. */
        internal fun isRoundAlreadyPurchased(now: ZonedDateTime, lastPurchasedRound: Int): Boolean =
            lastPurchasedRound >= Round720.getUpcomingDrawRound(now)

        /** [purchase]/[notify] 실패는 서버가 이미 처리했을 수 있어 재시도 금지(중복 결제 방지). 테스트 전용 노출. */
        internal fun isAmbiguousFailure(message: String): Boolean =
            message.startsWith("[purchase]") || message.startsWith("[notify]")
    }
}
