package com.umicorp.autolotto720.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.umicorp.autolotto720.accountScopedRound
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.SecureStore
import com.umicorp.autolotto720.dhlottery.AuthService
import com.umicorp.autolotto720.dhlottery.DhlotterySession
import com.umicorp.autolotto720.dhlottery.Feature720
import com.umicorp.autolotto720.dhlottery.PurchaseService720
import com.umicorp.autolotto720.dhlottery.Round720
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * 구매 직렬화 락 — 예약 워커와 즉시 구매(AppContainer)가 같은 앱 프로세스에서 공유.
 * "자격증명 읽기~구매 실행~회차 기록"이 임계구역: 기록 전에 풀면 상대가 이전 회차 값을 읽는다.
 * 잔액 조회·알림은 락 밖.
 */
object PurchaseLock {
    val mutex = Mutex()
}

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
            if (e is CancellationException) throw e   // 코루틴 취소는 삼키지 않는다 — 오분류/오보 방지(R3).
            val msg = e.message ?: ""
            // [purchase] 실패는 서버가 요청을 이미 처리했을 수 있어 재전송 금지(중복 결제 방지) → 즉시 알림.
            // 그 이전 단계(자격증명·로그인 등)는 일시 오류일 수 있어 재시도하고, 마지막 시도에만 알린다.
            val ambiguous = isAmbiguousFailure(msg)
            if (!ambiguous && runAttemptCount < MAX_ATTEMPTS - 1) {
                result = Result.retry()
            } else {
                val body = when {
                    msg.contains("[login]") -> "로그인에 실패했습니다. 아이디/비밀번호를 확인해주세요."
                    // [commit] 실패는 결제 성공 후 회차 기록(setLastPurchasedRound)만 실패 → 구매 완료로 안내(비재시도).
                    msg.contains("[commit]") -> "구매는 완료되었으나 기록 저장에 실패했습니다. 앱에서 구매 내역을 확인해주세요."
                    // [notify] 실패는 구매가 이미 커밋(setLastPurchasedRound)된 뒤 알림 표시만 실패한 것 → 구매 완료로 안내.
                    msg.contains("[notify]") -> "구매는 완료되었으나 알림 표시에 실패했습니다."
                    // API 응답 메시지 그대로 전달 (주간구매금액 초과 등)
                    msg.contains("구매 실패:") -> msg.replace("[purchase] ", "")
                    else -> "자동 구매에 실패했습니다. 앱을 열어 상태를 확인해주세요."
                }
                // 알림 표시 실패가 아래 알람 재등록을 막지 않도록 방어 — show가 throw해도 체인은 이어진다(R2 N2).
                runCatching { Notifications.show(ctx, "⚠️ AutoLotto720 오류", body, 99) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }

        // 2) 다음 주 알람 재등록 (one-shot 체인) — 자동구매 활성 시에만. 멱등이라 재시도 회차에 또 호출돼도 안전.
        try {
            if (store.getAutoEnabled()) AlarmScheduler(ctx).scheduleAutoPurchase()
        } catch (e: Exception) {
            if (e is CancellationException) throw e   // 코루틴 취소는 삼키지 않는다(R2 N4).
            runCatching { Notifications.show(ctx, "⚠️ AutoLotto720 오류", "알람 재등록에 실패했습니다.", 98) }
                .onFailure { if (it is CancellationException) throw it }
        }

        return result
    }

    /** 원본 `_executeAutoPurchase` 포트. 실패 시 `[step] 메시지`로 감싸 던진다(doWork의 매핑이 사용). */
    private suspend fun executeAutoPurchase() {
        var step = "init"
        try {
            val session = DhlotterySession()
            val auth = AuthService(session)

            // 자격증명 읽기~회차 기록 = 즉시 구매(AppContainer.instantPurchase)·수동 로그인(계정 전환 커밋)과
            // 공유하는 임계구역(PurchaseLock). Worker 재실행·중복 알람·번호 탭 즉시 구매·로그인 경합에도
            // 같은 회차를 두 번 사거나 기록이 다른 계정으로 어긋나지 않는다. 잔액 조회·알림은 락 밖(아래).
            step = "read_credentials"
            val result = PurchaseLock.mutex.withLock {
                val cred = store.getCredentials()
                val userId = cred.userId
                val password = cred.password
                val autoEnabled = store.getAutoEnabled()
                // 번호 탭 설정(조/번호)을 단일 출처로 — 슬롯별 자동/반자동/수동을 그대로 산다(매수만이 아님).
                val config = NumberConfig720.fromJson(store.getNumberConfig()) ?: NumberConfig720.empty()

                if (!autoEnabled || userId == null || password == null) return
                if (config.gameCount == 0) return

                step = "feature_gate"
                // ponytail: 구매 계약(AES, pension.js) 미확보 — 게이트 켜지기 전까지 조용히 무동작(재시도 없음).
                // Feature720.PURCHASE_ENABLED=true로 뒤집히면 아래 가드부터 정상 작동한다.
                if (!Feature720.PURCHASE_ENABLED) return

                val now = ZonedDateTime.now(Round720.KST)

                // 판매마감 가드(R2 N2 — 좁은 창만): 다가오는 회차의 추첨일이 오늘이고 17:00 이후면 스킵.
                // 19:05 추첨 후엔 다가오는 회차가 다음 주로 롤오버되므로(추첨일≠오늘) 이 가드를 통과해 정상 구매된다.
                step = "sales_close_guard"
                if (isSalesClosed(now)) {
                    // Doze 지연 등으로 목 17:00 이후 실행돼 스킵 — 침묵 종료 대신 통지한다(R2 N7). 다음 주 알람은 계속 재등록.
                    Notifications.show(
                        ctx,
                        "⚠️ AutoLotto720 자동구매 건너뜀",
                        "판매 마감 시간(목 17:00) 이후 실행되어 이번 회차 자동구매를 건너뛰었습니다. 구매 시각 설정을 확인해주세요.",
                        3,
                        tab = Notifications.TAB_SETTINGS,
                    )
                    return
                }

                // 회차 멱등 가드: Worker 재실행(프로세스 킬 후 WorkManager 재스케줄)·중복 알람에도 같은 회차 재구매 방지.
                // 계정 스코프(F4) — 다른 계정 기록이면 이 계정 기준 미구매로 보고 통과(즉시 구매와 동일 판정).
                step = "round_guard"
                val scopedRound = accountScopedRound(store.getLastPurchasedRound(), store.getLastPurchaseOwner(), userId)
                if (isRoundAlreadyPurchased(now, scopedRound)) return

                step = "login"
                auth.login(userId, password)

                step = "purchase"
                val purchaseService = PurchaseService720(auth, session)
                val r = try {
                    purchaseService.purchase(config)
                } catch (purchaseError: Exception) {
                    if (purchaseError is CancellationException) throw purchaseError   // 취소는 [purchase]로 오분류하지 않는다(R3).
                    throw Exception(purchaseError.message ?: "$purchaseError")
                }

                // 성공 즉시 회차+계정 기록(commit) — 이후 재실행은 round_guard가 차단.
                // ponytail: 서버 처리~기록 사이 찰나에 킬되는 창은 남는다(중복결제 double-charge ceiling,
                // "autolotto 정책 그대로" 결정으로 수용) — unique work 직렬화 + [purchase] 비재시도가 1차 방어.
                // 결제는 성공했으므로 기록 저장 실패는 [purchase]가 아닌 [commit]으로 분류 → "구매 실패" 오보 방지(R2 N6, 비재시도).
                step = "commit"
                // 커밋 반환 확인(G2) — false면 디스크 가드 미기록 → 재실행 시 round_guard가 못 막아 중복결제.
                // 즉시구매와 동일 정책: [commit]로 분류(비재시도·성공 오보 금지) + 예약 자동구매 중단(재구매 차단).
                if (!store.setLastPurchase(r.round, userId)) {   // (회차, 계정) 원자 커밋 — 부분 기록 방지(F3).
                    store.setAutoEnabled(false)                  // doWork 알람 재등록 게이트도 내려 재실행 차단
                    throw Exception("회차 기록 저장에 실패했습니다.")   // step=commit → [commit] 래핑 → 비재시도
                }
                r
            }

            // 구매 후 잔액 체크 (락 밖, 실패 무시 — 원본 catch (_) {}). 취소는 삼키지 않는다(R3).
            runCatching {
                val postBalance = auth.getBalance()
                BalanceAlert.checkAndNotify(ctx, postBalance)
            }.onFailure { if (it is CancellationException) throw it }

            step = "notify"
            if (result.tickets.isEmpty()) {
                // 지정번호 점유 + '구매 포기' 정책 → 산 게임 없음. 오류가 아니라 정책상 정상 결과이므로 안내만.
                Notifications.show(
                    ctx,
                    "ℹ️ AutoLotto720 이번 회차 미구매",
                    "제 ${result.round}회 · 지정한 번호가 이미 판매되어 '구매 포기' 정책에 따라 구매하지 않았습니다.",
                    1,
                    tab = Notifications.TAB_HISTORY,
                )
            } else {
                val ticketLines = result.tickets.joinToString("\n") { "${it.jo}조 ${it.number}" }
                Notifications.show(
                    ctx,
                    "🎰 AutoLotto720 자동 구매 완료!",
                    "제 ${result.round}회 · ${result.tickets.size}게임\n$ticketLines",
                    1,
                    tab = Notifications.TAB_HISTORY,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e   // 취소는 [step] 래핑/오분류하지 않고 그대로 전파(R3).
            throw Exception("[$step] ${e.message ?: e}")
        }
    }

    companion object {
        /** 총 시도 횟수(최초 1 + 재시도 2). 간격은 SchedulerReceivers의 backoff(15분 선형). */
        private const val MAX_ATTEMPTS = 3

        /**
         * 판매마감 가드: [Round720.isSalesClosed] 단일 판정에 위임(입력 정규화 포함) — VM 입력검증과
         * 로직을 공유해 드리프트를 막는다. 테스트 전용 노출(internal) — Worker 본체는 Context/SecureStore
         * 의존이라 JVM 단위테스트로 직접 인스턴스화할 수 없어(645 레이어도 동일 제약) 위임 판정만 검증한다.
         */
        internal fun isSalesClosed(now: ZonedDateTime): Boolean = Round720.isSalesClosed(now)

        /** 회차 멱등 가드: 이미 다가오는 회차를 구매했으면 true(스킵). 입력을 KST로 정규화. 테스트 전용 노출. */
        internal fun isRoundAlreadyPurchased(now: ZonedDateTime, lastPurchasedRound: Int): Boolean {
            val kstNow = now.withZoneSameInstant(Round720.KST)
            return lastPurchasedRound >= Round720.getUpcomingDrawRound(kstNow)
        }

        /**
         * [purchase]/[commit]/[notify] 실패는 결제가 이미 처리된 뒤라 재시도 금지(중복 결제 방지). 테스트 전용 노출.
         * [commit]은 결제 성공 후 회차 기록 실패라 재시도하면 round_guard가 못 막아 재구매(double-charge) 위험 → 비재시도.
         */
        internal fun isAmbiguousFailure(message: String): Boolean =
            message.startsWith("[purchase]") || message.startsWith("[commit]") || message.startsWith("[notify]")
    }
}
