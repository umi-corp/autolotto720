package com.umicorp.autolotto720

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.umicorp.autolotto720.data.BudgetGuard
import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.SecureStore
import com.umicorp.autolotto720.data.Slot720
import com.umicorp.autolotto720.data.SpendEntry
import com.umicorp.autolotto720.dhlottery.AuthService
import com.umicorp.autolotto720.dhlottery.DhlotteryException
import com.umicorp.autolotto720.dhlottery.DhlotterySession
import com.umicorp.autolotto720.dhlottery.Feature720
import com.umicorp.autolotto720.dhlottery.HistoryService720
import com.umicorp.autolotto720.dhlottery.PurchaseResult720
import com.umicorp.autolotto720.dhlottery.PurchaseService720
import com.umicorp.autolotto720.dhlottery.ResultService720
import com.umicorp.autolotto720.dhlottery.Round720
import com.umicorp.autolotto720.scheduler.AlarmScheduler
import com.umicorp.autolotto720.scheduler.BalanceAlert
import com.umicorp.autolotto720.scheduler.PurchaseLock
import com.umicorp.autolotto720.update.AppUpdater
import com.umicorp.autolotto720.update.UpdateInfo
import java.io.File
import com.umicorp.autolotto720.ui.vm.HistoryViewModel
import com.umicorp.autolotto720.ui.vm.HomeViewModel
import com.umicorp.autolotto720.ui.vm.PurchaseSetupViewModel
import com.umicorp.autolotto720.ui.vm.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * 앱 스코프 컴포지션 루트 (원본 Riverpod `ProviderScope` + 전역 프로바이더 대응).
 *
 * 두 가지를 한곳에 모은다:
 *  1) **공유 서비스** — 로그인 세션을 앱 전역에서 공유하기 위한 단일 [DhlotterySession]/[AuthService]/
 *     [SecureStore]/[AlarmScheduler]. (백그라운드 Worker는 자체 세션을 쓴다 — 이미 구현됨.)
 *  2) **공유 반응형 상태** — 화면 간(홈↔설정 등) 즉시 동기화되어야 하는 값들의 단일 출처(StateFlow).
 *     원본 전역 프로바이더(isLoggedIn/balance/autoEnabled/...)와 1:1.
 *
 * 중앙 설계 사실(이중 상태): 설정 변경은 **StateFlow(UI 반응) + SecureStore(영속) 양쪽**에 써야 한다.
 * 백그라운드 알람 콜백은 위젯/플로우 접근 없이 SecureStore를 raw 키로 직접 읽으므로, 백그라운드가
 * 필요로 하는 값은 반드시 SecureStore에도 영속해야 한다. 그 양방향 쓰기를 이 클래스의 setter들이 일원화한다.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // === 공유 서비스 ===
    val store = SecureStore(appContext)
    val session = DhlotterySession()
    val auth = AuthService(session)
    val scheduler = AlarmScheduler(appContext)

    // === 720 서비스 ===
    val resultService720 = ResultService720()
    val historyService720 = HistoryService720(session, resultService720)

    // === 공유 반응형 상태 (원본 전역 프로바이더와 1:1) ===
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _balance = MutableStateFlow(0)
    val balance: StateFlow<Int> = _balance.asStateFlow()

    private val _autoEnabled = MutableStateFlow(false)
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    private val _autoGames = MutableStateFlow(0)
    val autoGames: StateFlow<Int> = _autoGames.asStateFlow()

    // "번호" 탭 설정(5슬롯 sealed 상태·폴백정책). 화면 하이드레이션의 단일 출처.
    private val _numberConfig = MutableStateFlow(NumberConfig720.empty())
    val numberConfig: StateFlow<NumberConfig720> = _numberConfig.asStateFlow()

    private val _autoPurchaseDay = MutableStateFlow(5)     // 기본 금요일(판매개시 다음날, 지정번호 선점 최적) — SecureStore 기본값과 동일
    val autoPurchaseDay: StateFlow<Int> = _autoPurchaseDay.asStateFlow()

    private val _autoPurchaseHour = MutableStateFlow(9)
    val autoPurchaseHour: StateFlow<Int> = _autoPurchaseHour.asStateFlow()

    private val _autoPurchaseMinute = MutableStateFlow(0)
    val autoPurchaseMinute: StateFlow<Int> = _autoPurchaseMinute.asStateFlow()

    private val _balanceAlertEnabled = MutableStateFlow(false)
    val balanceAlertEnabled: StateFlow<Boolean> = _balanceAlertEnabled.asStateFlow()

    private val _balanceAlertThreshold = MutableStateFlow(5000)
    val balanceAlertThreshold: StateFlow<Int> = _balanceAlertThreshold.asStateFlow()

    private val _language = MutableStateFlow("system")     // "system"/"ko"/"en"/"ja"
    val language: StateFlow<String> = _language.asStateFlow()

    private val _loggedInUserId = MutableStateFlow<String?>(null)
    val loggedInUserId: StateFlow<String?> = _loggedInUserId.asStateFlow()

    /** 마지막 구매 회차(멱등 가드) — 즉시 구매 CTA 모드(첫/추가) 분기의 단일 출처. */
    private val _lastPurchasedRound = MutableStateFlow(0)
    val lastPurchasedRound: StateFlow<Int> = _lastPurchasedRound.asStateFlow()

    // === 인앱 업데이트 (사이드로드 배포) ===
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateProgress = MutableStateFlow<Float?>(null)  // null=대기, 0..1=다운로드 중
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    // === 스플래시 하이드레이션 (원본 splash_screen `_initialize` 1:1) ===

    /** SecureStore → 플로우. 네트워크 없음. */
    suspend fun loadSettings() = withContext(Dispatchers.IO) {
        _autoEnabled.value = store.getAutoEnabled()
        _autoGames.value = store.getAutoGames()
        _numberConfig.value = loadNumberConfig()
        _autoPurchaseDay.value = store.getAutoPurchaseDay()
        _autoPurchaseHour.value = store.getAutoPurchaseHour()
        _autoPurchaseMinute.value = store.getAutoPurchaseMinute()
        _balanceAlertEnabled.value = store.getBalanceAlertEnabled()
        _balanceAlertThreshold.value = store.getBalanceAlertThreshold()
        _language.value = store.getLanguage()
        val currentUser = store.getCredentials().userId
        _loggedInUserId.value = currentUser
        // G1 마이그레이션: 소유 계정 키 신설 이전 기록은 owner=null → 현재 계정으로 1회 스탬프(레거시=기기스코프 복원, 중복결제 방지).
        val recordedRound = store.getLastPurchasedRound()
        if (shouldBackfillOwner(recordedRound, store.getLastPurchaseOwner(), currentUser)) {
            store.setLastPurchase(recordedRound, currentUser!!)
        }
        _lastPurchasedRound.value = accountScopedRound(recordedRound, store.getLastPurchaseOwner(), currentUser)
        // 앱 실행마다 알람 재무장(자동구매 활성 시). 업데이트·강제종료·OEM 정리로 소실된 알람 복구 — 멱등.
        scheduler.rescheduleAll()
    }

    /** GitHub 릴리스에서 새 버전 확인 → updateInfo 세팅(없으면 null). AppShell 진입 시 호출. */
    suspend fun checkForUpdate() {
        _updateInfo.value = AppUpdater.check(BuildConfig.VERSION_NAME)
    }

    fun dismissUpdate() { _updateInfo.value = null }

    /** 감지된 업데이트 APK 다운로드 → 성공 시 File. 진행률은 [updateProgress]. */
    suspend fun downloadUpdate(context: Context): File? {
        val info = _updateInfo.value ?: return null
        _updateProgress.value = 0f
        val file = AppUpdater.download(context, info.downloadUrl) { p -> _updateProgress.value = p }
        _updateProgress.value = null
        return file
    }

    /** 저장된 자격증명으로 자동 로그인 + 잔액 + 잔액부족 체크. 실패는 조용히 무시(원본). */
    suspend fun autoLogin() {
        if (!store.hasCredentials()) return
        val cred = store.getCredentials()
        val id = cred.userId ?: return
        val pw = cred.password ?: return
        try {
            auth.login(id, pw)
            _isLoggedIn.value = true
            _loggedInUserId.value = id
            val b = auth.getBalance()
            _balance.value = b
            BalanceAlert.checkAndNotify(appContext, b, _balanceAlertEnabled.value, _balanceAlertThreshold.value)
        } catch (_: Exception) {
            // 원본 debugPrint('자동 로그인 실패') 후 무시.
        }
    }

    // === 계정 ===

    /** 수동 로그인(설정 화면). 실패 시 throw(INVALID_CREDENTIALS 등) — 호출자가 매핑. 잔액알림은 안 함(원본과 동일). */
    suspend fun login(id: String, pw: String) {
        auth.login(id, pw)
        // 자격증명 커밋은 구매 임계구역과 직렬화(PurchaseLock) — 워커/즉시구매의 자격증명 읽기와 경합 방지.
        PurchaseLock.mutex.withLock { store.saveCredentials(id, pw) }
        _isLoggedIn.value = true
        _loggedInUserId.value = id
        // 회차 가드는 계정 스코프 — 기록은 건드리지 않고 판정 시점에 소유 계정으로 대조한다([accountScopedRound]).
        // 다른 계정 로그인 시 이 계정 기준 0(=미구매), 같은 계정 재로그인 시 기록 보존.
        _lastPurchasedRound.value = accountScopedRound(store.getLastPurchasedRound(), store.getLastPurchaseOwner(), id)
        _balance.value = auth.getBalance()
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        auth.logout()
        store.deleteCredentials()
        // 자격증명 없는 자동구매는 매주 조용히 무동작(+다른 계정 재로그인 시 이전 설정으로 재개 위험)
        // → 로그아웃과 함께 해제·알람 취소. 비로그인 상태에선 스위치가 잠겨 사용자가 끌 수도 없다.
        if (_autoEnabled.value) setAutoEnabled(false)
        _isLoggedIn.value = false
        _balance.value = 0
        _loggedInUserId.value = null
    }

    /**
     * [DEBUG 전용] 감독 하 단발 반자동 1매 실구매 — el AES 계약(PurchaseService720) 실기기 검증용.
     * 스케줄 자동구매(AutoPurchaseWorker의 [com.umicorp.autolotto720.dhlottery.Feature720] 게이트)와는 독립.
     * connPro는 비멱등이라 실패해도 재시도하지 않고 결과 문자열만 돌려준다(호출부가 사용자에게 표시).
     */
    suspend fun debugPurchaseOne(): String = withContext(Dispatchers.IO) {
        if (!_isLoggedIn.value) return@withContext "로그인 후 시도하세요"
        try {
            // 번호 탭에 설정된 게임이 있으면 그대로(수동/반자동/전부자동), 없으면 반자동 1매로 계약만 검증.
            val svc = PurchaseService720(auth, session)
            val config = _numberConfig.value
            val round = Round720.getUpcomingDrawRound(ZonedDateTime.now(Round720.KST))   // 회차 주입 — 모든 경로 동일 회차(TOCTOU 제거)
            val result = if (config.gameCount > 0) svc.purchase(config, round) else svc.purchase(games = 1, round = round)
            refreshBalance()
            if (result.tickets.isEmpty()) "구매한 게임 없음 (지정번호 점유 + 구매 포기 정책)"
            else "구매 성공: " + result.tickets.joinToString(", ") { "${it.jo}조 ${it.number}" } +
                " (${result.round}회, ₩${result.amount})" +
                (result.partialFailure?.let { " · 미결제 ${it.failedGames}게임: ${it.cause.message}" } ?: "")
        } catch (e: Exception) {
            "구매 실패: ${e.message}"
        }
    }

    /**
     * [DEBUG 전용] 자동구매 회차 멱등 가드([AutoPurchaseWorker.isRoundAlreadyPurchased]) 리셋.
     * 같은 회차를 예약 워커로 다시 구매하는 테스트용 — 0(=미구매)으로 되돌린다. 프로덕션 경로엔 노출 안 됨.
     */
    fun debugResetPurchasedRound(): String {
        store.setLastPurchasedRound(0)
        _lastPurchasedRound.value = 0   // 즉시 구매 CTA(첫/추가 분기)도 같이 되돌린다.
        return "회차기록 리셋됨 — 다음 예약 워커 실행 시 이번 회차를 실제 구매합니다"
    }

    /** 잔액 재조회 + 잔액부족 체크(원본 home/_refreshBalance). [from]=즉시구매 전용 세션 등 다른 로그인 세션 지정용. */
    suspend fun refreshBalance(from: AuthService = auth) {
        val b = from.getBalance()
        _balance.value = b
        BalanceAlert.checkAndNotify(appContext, b, _balanceAlertEnabled.value, _balanceAlertThreshold.value)
    }

    /** 워커가 백그라운드에서 회차를 갱신했을 수 있어 즉시 구매 CTA 탭 때 재읽기(계정 스코프). */
    suspend fun refreshLastPurchasedRound() = withContext(Dispatchers.IO) {
        _lastPurchasedRound.value =
            accountScopedRound(store.getLastPurchasedRound(), store.getLastPurchaseOwner(), store.getCredentials().userId)
    }

    // === 즉시 구매 (번호 탭 CTA — 645 docs/DESIGN-instant-purchase.md의 720 포트) ===

    /** 구매 요청 후 결과를 확인 못 한 실패(네트워크·타임아웃) — 재시도 유도 금지 신호. */
    class PurchaseResultUnknownException(cause: Throwable) : Exception(cause)

    /** 확정 다이얼로그가 표시한 회차와 실제 회차가 달라 구매 없이 중단한 경우. */
    class RoundChangedException : Exception()

    /** Mutex 획득 시점에 판매시간이 종료되어 구매 없이 중단한 경우(락 대기 중 경계 통과). */
    class SaleClosedException : Exception()

    /** 결제는 성공했으나 로컬 회차 가드 영속화(commit)에 실패한 경우 — 결과를 실어 성공(경고)로 표시한다. */
    class PurchaseRecordFailedException(val result: PurchaseResult720) : Exception()

    /** 지출을 원장에 더하고 7일 초과 정리 후 저장. commit 성공 여부 반환(성공·부분·결과불명 공통). */
    private fun recordSpend(round: Int, today: Long, amount: Int): Boolean {
        val next = BudgetGuard.record(BudgetGuard.parseLedger(store.getSpendLedger()), SpendEntry(round, today, amount), today)
        return store.setSpendLedger(BudgetGuard.toJson(next))
    }

    /**
     * 즉시 구매. [expectedRound]는 확정 다이얼로그가 표시한 회차 — Mutex 안에서 현재 회차와 대조해
     * 다르면 [RoundChangedException](구매 요청 없음, 표시≠결제 방지). [extra]=false(첫 구매:
     * 저장된 [config] 슬롯 그대로 — 조/번호 지정 포함)는 회차 가드 재판정 후 이미 구매된 회차면
     * null(= "방금 구매됨"), [extra]=true(추가: 완전자동 [autoGames]게임)는 가드로 막지 않는다
     * (서버 주간한도가 방어선). 세션 만료 대비 매번 재로그인(워커 패턴). 성공 응답 관측 즉시 성공 확정 —
     * 회차+계정 기록 실패가 성공 결과를 가리면 재시도를 오유도하므로 runCatching. 잔액 갱신은 락 밖(실패 무시).
     */
    suspend fun instantPurchase(
        extra: Boolean,
        expectedRound: Int,
        autoGames: Int,
        config: NumberConfig720?,
    ): PurchaseResult720? {
        // 방어적 kill switch — 워커와 대칭(F2). 계약이 깨져 게이트를 내리면 실결제 경로를 CTA 게이트와 이중으로 막는다.
        check(Feature720.PURCHASE_ENABLED) { "instant purchase is gated off" }
        // 즉시 구매 전용 세션(F5) — 공유 auth/session은 수동 login()/logout()이 PurchaseLock 밖에서 변이하므로
        // 구매 중 세션이 덮일 수 있다. 워커와 동일하게 자체 세션으로 격리해 경합을 원천 차단한다.
        val purchaseSession = DhlotterySession()
        val purchaseAuth = AuthService(purchaseSession)
        val result = PurchaseLock.mutex.withLock {
            // 락 대기 중 판매 마감·회차 경계를 넘었을 수 있어 게이트 전부를 락 안에서 재평가.
            val now = ZonedDateTime.now(Round720.KST)
            val saleOpen = SettingsViewModel.isValidPurchaseTime(now.dayOfWeek.value, now.hour, now.minute)
            val round = Round720.getUpcomingDrawRound(now)
            // 자격증명·로컬 검증은 네트워크 이전 — purchase try 밖(아래)에서 평가해 "결과 불명" 오분류를 막는다(F9).
            val cred = store.getCredentials()
            val id = requireNotNull(cred.userId) { "로그인이 필요합니다." }
            val pw = requireNotNull(cred.password) { "로그인이 필요합니다." }
            // 회차 가드는 계정 스코프(F4) — 다른 계정 기록이면 이 계정 기준 미구매(0). A→B→A 단일 슬롯 한계는 수용(주석).
            val recorded = accountScopedRound(store.getLastPurchasedRound(), store.getLastPurchaseOwner(), id)
            _lastPurchasedRound.value = recorded
            when (purchaseGate(extra, recorded, round, expectedRound, saleOpen)) {
                PurchaseGate.SALE_CLOSED -> throw SaleClosedException()
                PurchaseGate.ROUND_CHANGED -> throw RoundChangedException()
                PurchaseGate.ALREADY_PURCHASED -> return@withLock null  // 워커 선점 — 이미 구매됨
                PurchaseGate.PROCEED -> Unit
            }

            // ① 미결 PENDING이 이번 회차에 남아 있으면 직전 결제 결과 불명 — extra 포함 모든 결제 금지(내역 확인 유도, F2).
            // 순서 고정(PENDING 잔존 → 예산): 예산 예외보다 "결과 불명" 안내를 먼저 표면화해 사용자 안내를 정확히 한다.
            val pending = parsePending(store.getPendingPurchase())
            if (pending?.round == round) {
                _lastPurchasedRound.value = round
                throw PurchaseResultUnknownException(DhlotteryException("직전 구매 결과가 확인되지 않았습니다. 내역을 확인해주세요."))
            }

            // ② 예산 가드 — 결제 진입 전, 미결 PENDING까지 더해 일/회차 한도를 검사(초과 시 미결제·미기록·미커밋).
            val attempt = if (extra) autoGames * 1000 else attemptAmount(requireNotNull(config) { "구매할 게임 설정이 없습니다." })
            val today = java.time.LocalDate.now(Round720.KST).toEpochDay()
            val ledger = BudgetGuard.parseLedger(store.getSpendLedger())
            if (!BudgetGuard.check(ledger, today, round, attempt, store.getDailyBudget(), store.getWeeklyBudget(), pending)) {
                throw BudgetExceededException(store.getDailyBudget(), store.getWeeklyBudget())
            }

            purchaseAuth.login(id, pw)                              // 세션 만료 대비 매번 재로그인(워커 패턴)

            val svc = PurchaseService720(purchaseAuth, purchaseSession)
            val cfg = if (extra) null else requireNotNull(config) { "구매할 게임 설정이 없습니다." }

            // connPro 진입 전 선기록(round·epochDay·amount) — 결과 불명·프로세스 사망 시 이 회차 재결제 차단.
            // commit 실패면 결제에 들어가지 않는다(fail-closed). 원장은 확정 후에만 기록되고, 그 전엔 이 PENDING이 예산에 반영된다.
            if (!store.setPendingPurchase(pendingJson(round, today, attempt))) {
                throw DhlotteryException("결제 준비에 실패했습니다. 다시 시도해주세요.")
            }

            val r = try {
                // 추가 구매는 조·번호 모두 자동(완전자동) N게임 — 저장된 수동 번호 중복 구매 방지. round 주입(TOCTOU 제거).
                if (extra) svc.purchase(games = autoGames, round = round) else svc.purchase(cfg!!, round = round)
            } catch (e: Exception) {
                if (e is CancellationException) throw e             // 취소는 재래핑 금지(구조적 동시성, F1)
                // 720 서비스는 결과 불명도 DhlotteryException으로 던진다 — 오분류는 중복 결제 위험(F14로 분리).
                when (val f = classifyPurchaseFailure(e)) {
                    is PurchaseFailure.Rejected -> {
                        runCatching { store.clearPendingPurchase() }   // 무결제 확정 — PENDING 해제, 예산 미반영
                        throw f.cause                                  // 서버 확정 거절 — 메시지 그대로(재시도 안전)
                    }
                    is PurchaseFailure.Unknown -> {
                        // 결과 불명 — 회차 가드 커밋 + 시도 전액 원장 기록. 둘 다 성공해야 PENDING 해제(아니면 백업 유지).
                        withContext(NonCancellable) {
                            val g = runCatching { store.setLastPurchase(round, id) }.getOrDefault(false)
                            val l = recordSpend(round, today, attempt)      // 전액
                            if (g && l) runCatching { store.clearPendingPurchase() }
                        }
                        throw PurchaseResultUnknownException(f.cause)   // 결과 불명 — 재시도 유도 금지
                    }
                }
            }
            // 성공 확정 — 결제는 됨. 필수 보상(가드·원장 커밋·실패 시 예약중단)은 취소 불가 구간에서 — CE가 끊지 못하게 한다(G3, F3).
            withContext(NonCancellable) {
                _lastPurchasedRound.value = r.round                      // 인메모리 가드(이 세션 CTA 보호)
                val committed = runCatching { store.setLastPurchase(round, id) }.getOrDefault(false)   // round 주입값과 동일
                val ledgerOk = recordSpend(round, today, if (r.partialFailure != null) attempt else r.amount)  // 부분은 시도 전액
                if (committed && ledgerOk) {
                    runCatching { store.clearPendingPurchase() }   // 가드+원장 반영 완료 → PENDING 해제 안전
                } else {
                    // 디스크 가드/원장 미반영 → 워커 재구매 가능. 예약 자동구매 중단 + PENDING을 백업으로 유지.
                    runCatching { setAutoEnabled(false) }
                    throw PurchaseRecordFailedException(r)               // 결제 성공은 유지(재결제 문구 금지)
                }
            }
            r
        } ?: return null
        // 잔액 갱신은 락 밖 — 전용 세션(방금 로그인)으로 조회해 공유 세션 만료와 무관하게 정확. 실패해도 성공 표시 유지.
        runCatching { refreshBalance(purchaseAuth) }.onFailure { if (it is CancellationException) throw it }
        return result
    }

    // === 자동구매 설정 (write-through: 플로우 + SecureStore + 알람) ===

    suspend fun setAutoEnabled(v: Boolean) = withContext(Dispatchers.IO) {
        _autoEnabled.value = v
        store.setAutoEnabled(v)
        if (v) {
            scheduler.scheduleAutoPurchase()
            scheduler.scheduleCheckResult()
        } else {
            scheduler.cancelAll()
        }
    }

    suspend fun setAutoGames(n: Int) = withContext(Dispatchers.IO) {
        _autoGames.value = n
        store.setAutoGames(n)
    }

    // === "번호" 탭 설정 (§10 마이그레이션·§3 sanitize) ===

    /**
     * 저장 JSON → 설정. 없으면 빈 설정(초기설정은 게임 없음 — 구 매수 마이그레이션 폐기, 사용자 피드백).
     * 일회성 정리: rev=1이고 수동/반자동 슬롯이 없는 저장본 = 구버전 "매수→전부자동" 마이그레이션 산출물 →
     * 빈 설정으로 교체 영속화. 직접 저장본은 rev≥2라 보존된다(수동 저장은 항상 마이그레이션 이후 발생).
     * 손상/미지 스키마는 [NumberConfig720.fromJson]이 안전 기본값으로 떨어뜨린다.
     */
    private fun loadNumberConfig(): NumberConfig720 {
        val saved = NumberConfig720.fromJson(store.getNumberConfig()) ?: return NumberConfig720.empty()
        if (saved.revision == 1L && saved.slots.none { it is Slot720.SemiAuto || it is Slot720.Manual }) {
            val reset = NumberConfig720.empty().copy(revision = saved.revision + 1)  // 단조 증가 유지
            store.setNumberConfig(reset.toJson())
            return reset
        }
        return saved
    }

    /**
     * committed 5슬롯+폴백정책 영속화. revision은 단조 증가(원복해도 신규). 게임 수(autoGames)도 함께 반영.
     * **저장이 구매를 무장하지 않는다** — autoEnabled/게이트/동의는 건드리지 않는다(설계 §9, 안전조건).
     */
    suspend fun saveNumberConfig(slots: List<Slot720>, fallback: FallbackPolicy) = withContext(Dispatchers.IO) {
        val next = NumberConfig720(
            slots = slots,
            fallback = fallback,
            schemaVersion = NumberConfig720.CURRENT_SCHEMA,
            revision = _numberConfig.value.revision + 1,
        )
        store.setNumberConfig(next.toJson())
        _numberConfig.value = next
        // 게임 수는 설정된(non-Unset) 슬롯 수와 동기화(설정 화면 표기·구 워커 매수 키 정합). 구매 활성화 아님.
        // ponytail: 임시 브리지 — 게이트 오픈 후 워커가 numberConfig를 단일 출처로 삼으면 이 autoGames 미러링은 제거 예정.
        _autoGames.value = next.gameCount
        store.setAutoGames(next.gameCount)
    }

    suspend fun setAutoPurchaseDay(day: Int) = withContext(Dispatchers.IO) {
        _autoPurchaseDay.value = day
        store.setAutoPurchaseDay(day)            // 스토어 먼저 — 스케줄러가 스토어를 다시 읽는다
        if (_autoEnabled.value) scheduler.scheduleAutoPurchase()
    }

    suspend fun setAutoPurchaseTime(hour: Int, minute: Int) = withContext(Dispatchers.IO) {
        _autoPurchaseHour.value = hour
        _autoPurchaseMinute.value = minute
        store.setAutoPurchaseHour(hour)
        store.setAutoPurchaseMinute(minute)
        if (_autoEnabled.value) scheduler.scheduleAutoPurchase()
    }

    suspend fun setLanguage(lang: String) = withContext(Dispatchers.IO) {
        _language.value = lang
        store.setLanguage(lang)
    }

    suspend fun setBalanceAlertEnabled(v: Boolean) = withContext(Dispatchers.IO) {
        _balanceAlertEnabled.value = v
        store.setBalanceAlertEnabled(v)
    }

    suspend fun setBalanceAlertThreshold(v: Int) = withContext(Dispatchers.IO) {
        _balanceAlertThreshold.value = v
        store.setBalanceAlertThreshold(v)
    }

    // === 데이터 초기화 (원본 설정 화면 `_showResetDialog`) ===
    suspend fun resetAll() = withContext(Dispatchers.IO) {
        store.clearAll()
        auth.logout()
        scheduler.cancelAll()  // 삭제된 설정을 참조하는 알람 제거 (원본 대비 의도된 보강)
        _isLoggedIn.value = false
        _balance.value = 0
        _autoEnabled.value = false
        _autoGames.value = 0
        _numberConfig.value = NumberConfig720.empty()
        _autoPurchaseDay.value = 5
        _autoPurchaseHour.value = 9
        _autoPurchaseMinute.value = 0
        _balanceAlertEnabled.value = false
        _balanceAlertThreshold.value = 5000
        _loggedInUserId.value = null
        _lastPurchasedRound.value = 0   // clearAll이 회차·소유 계정 기록도 지운다 — 플로우를 stale로 두지 않는다.
    }

    /** 화면별 ViewModel 팩토리(컴포지션 루트가 컨테이너 주입). */
    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(this@AppContainer) }
        initializer { PurchaseSetupViewModel(this@AppContainer) }
        initializer { HistoryViewModel(this@AppContainer) }
        initializer { SettingsViewModel(this@AppContainer) }
    }
}

/**
 * Application 서브클래스 — 프로세스 1개당 [AppContainer] 1개(applicationContext 기반).
 * 매니페스트 `android:name=".AutoLotto720Application"`로 등록.
 */
class AutoLotto720Application : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Compose/Activity에서 앱 스코프 컨테이너 접근. */
val Context.appContainer: AppContainer
    get() = (applicationContext as AutoLotto720Application).container

/**
 * 실패 2분류(순수) — 720 `PurchaseService720`는 645와 달리 **결과 불명**(비-JSON/복호화 실패 응답,
 * 미지 resultCode)도 `DhlotteryException`으로 던진다. 그 메시지에 심긴 "결과 불명" 표식을 보고
 * 서버 확정 거절(재시도 안전)과 결과 불명(재시도 유도 금지)을 가른다 — 비멱등 connPro 뒤일 수 있어
 * 오분류는 곧 중복 결제 위험. 서비스가 문구를 바꿔도 이 표식은 유지해야 한다
 * (`PurchaseService720Test`의 "html error response treated as unknown result"가 서비스 쪽을 고정).
 */
fun isUnknownResultMessage(message: String?): Boolean = message?.contains("결과 불명") == true

/** 실패 2분류 결과(순수) — [Rejected]=서버 확정 거절(재시도 안전), [Unknown]=결과 불명(재시도 유도 금지). */
sealed interface PurchaseFailure {
    val cause: Throwable
    data class Rejected(override val cause: Throwable) : PurchaseFailure
    data class Unknown(override val cause: Throwable) : PurchaseFailure
}

/**
 * 구매 실패를 재시도 안전(Rejected)/금지(Unknown)로 가르는 순수 함수(F14).
 * 720 `PurchaseService720`는 645와 달리 결과 불명도 `DhlotteryException`으로 던지므로,
 * DhlotteryException이면서 "결과 불명" 표식이 **없을 때만** 확정 거절(Rejected)로 본다.
 * 그 외(표식 있는 DhlotteryException, 비-DhlotteryException 전부)는 비멱등 connPro 뒤일 수 있어 Unknown.
 */
fun classifyPurchaseFailure(e: Throwable): PurchaseFailure = when {
    e is DhlotteryException && !isUnknownResultMessage(e.message) -> PurchaseFailure.Rejected(e)
    else -> PurchaseFailure.Unknown(e)
}

/**
 * 계정 스코프 회차 가드(순수, F4) — 기록 소유자가 현재 계정과 다르면 0(=이 계정 미구매)으로 본다.
 * 로그인 시 기록을 리셋하지 않고 판정 시점에 대조 → 계정 A→B→A에서 A 자기 가드가 지워지던 홀을 막는다.
 * owner=null은 **레거시 기록**(소유 계정 키 신설 이전) — 기기 스코프였던 원 동작대로 현재 계정 것으로 신뢰한다.
 * 엄격 비교로 0을 주면 업데이트 직후 이미 구매한 회차를 다시 사 중복결제(G1). loadSettings 백필이 이후 스탬프한다.
 * 단일 슬롯 한계: A→B(구매)→A는 B가 슬롯을 덮어 여전히 못 막는다("autolotto 정책 그대로" 수용).
 */
fun accountScopedRound(recordedRound: Int, recordedOwner: String?, currentUserId: String?): Int =
    if (currentUserId != null && (recordedOwner == null || recordedOwner == currentUserId)) recordedRound else 0

/** G1 백필 판정(순수) — 레거시(owner 없는) 회차 기록이 있고 로그인돼 있으면 현재 계정으로 1회 스탬프한다. */
fun shouldBackfillOwner(recordedRound: Int, recordedOwner: String?, currentUserId: String?): Boolean =
    recordedRound > 0 && recordedOwner == null && currentUserId != null

/** Mutex 내 구매 게이트(순수) — 판매 종료·회차 변경은 모드 공통 중단, 회차 가드는 첫 구매에만. */
enum class PurchaseGate { PROCEED, ALREADY_PURCHASED, ROUND_CHANGED, SALE_CLOSED }

fun purchaseGate(extra: Boolean, recordedRound: Int, currentRound: Int, expectedRound: Int, saleOpen: Boolean): PurchaseGate = when {
    !saleOpen -> PurchaseGate.SALE_CLOSED
    currentRound != expectedRound -> PurchaseGate.ROUND_CHANGED
    !extra && recordedRound >= currentRound -> PurchaseGate.ALREADY_PURCHASED
    else -> PurchaseGate.PROCEED
}

/** 설정 기준 시도 금액(원) — 세트 5,000, 슬롯 모드 게임수×1,000. */
fun attemptAmount(config: NumberConfig720): Int =
    if (config.setMode) 5000 else config.gameCount * 1000

/** connPro 선기록 JSON {round, epochDay, amount} — check(pending=…)와 동일 필드. */
private fun pendingJson(round: Int, epochDay: Long, amount: Int): String =
    org.json.JSONObject().put("round", round).put("epochDay", epochDay).put("amount", amount).toString()

/** PENDING JSON → SpendEntry(없거나 손상·round 필드 없음이면 null). */
fun parsePending(json: String?): SpendEntry? {
    if (json.isNullOrBlank()) return null
    val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
    if (!o.has("round")) return null
    return SpendEntry(o.optInt("round"), o.optLong("epochDay"), o.optInt("amount"))
}

/** 예산 한도 초과로 결제에 진입하지 못한 경우 — 재시도 유도 금지. daily/weekly 값만 담고 표시는 UI가 리소스로 매핑. */
class BudgetExceededException(val daily: Int, val weekly: Int) : Exception("budget exceeded")
