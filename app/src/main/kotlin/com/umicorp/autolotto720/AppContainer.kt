package com.umicorp.autolotto720

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.SecureStore
import com.umicorp.autolotto720.data.Slot720
import com.umicorp.autolotto720.dhlottery.AuthService
import com.umicorp.autolotto720.dhlottery.DhlotterySession
import com.umicorp.autolotto720.dhlottery.HistoryService720
import com.umicorp.autolotto720.dhlottery.PurchaseService720
import com.umicorp.autolotto720.dhlottery.ResultService720
import com.umicorp.autolotto720.scheduler.AlarmScheduler
import com.umicorp.autolotto720.scheduler.BalanceAlert
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    private val _autoPurchaseDay = MutableStateFlow(4)     // 기본 목요일(720 추첨일) — SecureStore 기본값과 동일
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
        _loggedInUserId.value = store.getCredentials().userId
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
        store.saveCredentials(id, pw)
        _isLoggedIn.value = true
        _loggedInUserId.value = id
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
            val result = if (config.gameCount > 0) svc.purchase(config) else svc.purchase(games = 1)
            refreshBalance()
            if (result.tickets.isEmpty()) "구매 응답에 티켓 정보 없음 (내역 확인 필요)"
            else "구매 성공: " + result.tickets.joinToString(", ") { "${it.jo}조 ${it.number}" } +
                " (${result.round}회, ₩${result.amount})"
        } catch (e: Exception) {
            "구매 실패: ${e.message}"
        }
    }

    /** 잔액 재조회 + 잔액부족 체크(원본 home/_refreshBalance). */
    suspend fun refreshBalance() {
        val b = auth.getBalance()
        _balance.value = b
        BalanceAlert.checkAndNotify(appContext, b, _balanceAlertEnabled.value, _balanceAlertThreshold.value)
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
        _autoPurchaseDay.value = 4
        _autoPurchaseHour.value = 9
        _autoPurchaseMinute.value = 0
        _balanceAlertEnabled.value = false
        _balanceAlertThreshold.value = 5000
        _loggedInUserId.value = null
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
