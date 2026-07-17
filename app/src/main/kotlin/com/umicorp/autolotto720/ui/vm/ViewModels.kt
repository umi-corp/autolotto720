package com.umicorp.autolotto720.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umicorp.autolotto720.AppContainer
import com.umicorp.autolotto720.BudgetExceededException
import com.umicorp.autolotto720.R
import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.Slot720
import com.umicorp.autolotto720.data.Ticket720
import com.umicorp.autolotto720.data.WinningNumbers720
import com.umicorp.autolotto720.dhlottery.PurchaseResult720
import com.umicorp.autolotto720.dhlottery.Round720
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * 화면별 ViewModel (원본 Riverpod 화면 상태 포트, Task11~14에서 연금복권720+로 전환).
 *
 * 공유 반응형 상태(로그인·잔액·자동구매설정·언어·잔액알림)는 [AppContainer]가 단일 출처로 들고
 * 있고(원본 전역 프로바이더와 1:1), ViewModel은 그 StateFlow를 그대로 재노출 + 화면-로컬 상태와
 * 액션만 갖는다. suspend 서비스 호출은 전부 viewModelScope에서 수행한다.
 *
 * UI는 단위테스트 불가 → 검증은 컴파일 + assembleDebug. 실제 화면 배선은 Slice 5b.
 */

/** 홈: 카운트다운(목요일 19:05 추첨) + 지난 회차 720 당첨번호 + 잔액/자동구매 상태(공유). */
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val isLoggedIn = container.isLoggedIn
    val balance = container.balance
    val autoEnabled = container.autoEnabled
    val autoPurchaseDay = container.autoPurchaseDay
    val autoPurchaseHour = container.autoPurchaseHour
    val autoPurchaseMinute = container.autoPurchaseMinute
    val balanceAlertEnabled = container.balanceAlertEnabled
    val balanceAlertThreshold = container.balanceAlertThreshold

    /** 지금 판매 중(=다가오는 추첨)인 회차. 카운트다운 헤더용. */
    val currentRound: Int get() = Round720.getUpcomingDrawRound()

    private val _winning = MutableStateFlow<WinningNumbers720?>(null)
    val winning: StateFlow<WinningNumbers720?> = _winning.asStateFlow()
    private val _loadingNumbers = MutableStateFlow(false)
    val loadingNumbers: StateFlow<Boolean> = _loadingNumbers.asStateFlow()

    init { fetchWinningNumbers() }

    /**
     * 최신 완료 회차(현재-추첨) 당첨번호 조회. 추첨(목 19:05) 직후엔 아직 결과가 게시되지 않아
     * null이 올 수 있어 그 경우 직전 회차로 폴백 — 홈 카드가 비지 않게 한다(R2 codex#12).
     * 둘 다 실패하면 기존 값을 유지(원본 debugPrint 후 무시와 동일).
     */
    fun fetchWinningNumbers() {
        viewModelScope.launch {
            _loadingNumbers.value = true
            val latest = Round720.getLatestCompletedRound()
            val result = runCatching { container.resultService720.getWinningNumbers(latest) }.getOrNull()
                ?: runCatching { container.resultService720.getWinningNumbers(latest - 1) }.getOrNull()
            result?.let { _winning.value = it }
            _loadingNumbers.value = false
        }
    }

    /** 당겨서 새로고침: 당첨번호 + 잔액 + 구매 회차 가드(워커가 백그라운드에서 갱신했을 수 있음 — 설계 명시, F8). */
    fun refreshAll() {
        fetchWinningNumbers()
        viewModelScope.launch { container.refreshBalance() }
        viewModelScope.launch { container.refreshLastPurchasedRound() }
    }
}

/**
 * "번호" 탭(수동 게임 설정, 설계 §3~§10): 5슬롯 sealed 상태 + 폴백정책 설정을 저장한다.
 * **설정 저장은 예약 자동구매를 무장하지 않는다**(저장 ≠ 구매 — 설계 §9).
 *
 * 즉시 구매([instantState] 상태머신)는 이와 별개로, 사용자가 CTA를 눌러 확인 다이얼로그까지 통과했을
 * 때만 실결제를 개시한다(645 docs/DESIGN-instant-purchase.md 포트 — 자동 재시도 없음, 판매시간·회차
 * 게이트는 탭·확정 두 시점에서 재검증, 최종 판정은 [AppContainer.instantPurchase]의 구매 Mutex 안).
 */
class PurchaseSetupViewModel(private val container: AppContainer) : ViewModel() {
    val autoEnabled = container.autoEnabled
    val config = container.numberConfig

    fun setAutoEnabled(v: Boolean) { viewModelScope.launch { container.setAutoEnabled(v) } }

    /** committed 5슬롯+폴백정책+세트모드 저장(revision 단조 증가). 구매는 활성화하지 않는다. */
    fun saveConfig(slots: List<Slot720>, fallback: FallbackPolicy, setMode: Boolean) {
        viewModelScope.launch { container.saveNumberConfig(slots, fallback, setMode) }
    }

    // === 즉시 구매 (저장 버튼 아래 CTA — 645 docs/DESIGN-instant-purchase.md의 720 포트) ===

    val isLoggedIn = container.isLoggedIn
    val lastPurchasedRound = container.lastPurchasedRound

    /** 지금 판매 중(=다가오는 추첨)인 회차. CTA 모드(첫/추가) 분기용. */
    val currentRound: Int get() = Round720.getUpcomingDrawRound()

    /**
     * 즉시 구매 다이얼로그 상태머신.
     * Idle → (탭) ConfirmingFirst | PickingExtra | NeedsSetup | SaleClosed
     *      → (확정) InProgress → Success | AlreadyPurchased | SaleClosed | RoundChanged | Error
     *      → (닫기) Idle
     * ConfirmingFirst는 탭 시점 저장 설정 스냅샷을 담아 확인창 표시 내용 = 실제 구매 내용을 보장.
     */
    sealed interface InstantState {
        data object Idle : InstantState
        data object NeedsSetup : InstantState         // 저장된 게임 0개 — 설정·저장 안내(스낵바)
        data class ConfirmingFirst(val round: Int, val config: NumberConfig720) : InstantState {
            val games: Int get() = config.gameCount
        }
        data class PickingExtra(val round: Int) : InstantState
        data object InProgress : InstantState
        data object AlreadyPurchased : InstantState   // 첫 구매 확정 직전 워커 선점
        data object SaleClosed : InstantState         // 탭·확정 시점 판매시간 재검증 실패
        data object RoundChanged : InstantState       // 확정 회차 ≠ 실제 회차 — 구매 없이 취소
        /** [guardSaved]=false: 결제 성공했으나 로컬 회차 가드 저장 실패 — 예약 자동구매 중단됨(경고 병기). */
        data class Success(val result: PurchaseResult720, val guardSaved: Boolean = true) : InstantState
        data class Error(val message: String?, val unknown: Boolean) : InstantState
    }

    private val _instantState = MutableStateFlow<InstantState>(InstantState.Idle)
    val instantState: StateFlow<InstantState> = _instantState.asStateFlow()

    /**
     * 지금(KST)이 판매시간인지 — CTA 표시용. 확정 시점에도 재검증한다.
     * 720 규칙: 목 17:00~23:59 구매 금지(현 회차는 목 17:00 마감, 다음 회차는 금 00:00 개시) —
     * [SettingsViewModel.isValidPurchaseTime]→[Round720.isScheduleBlocked] 단일 판정 재사용(645 토/일 규칙 아님).
     */
    fun isSaleOpenNow(): Boolean {
        val now = ZonedDateTime.now(Round720.KST)
        return SettingsViewModel.isValidPurchaseTime(now.dayOfWeek.value, now.hour, now.minute)
    }

    /** 탭 판정 코루틴 핸들 — 이중 탭으로 판정 코루틴이 둘 뜨는 것 자체를 차단. */
    private var tapJob: Job? = null

    /** CTA 탭: 게이트 재검증 후 모드 분기(첫 구매/추가/설정 유도). 저장된 설정 기준. */
    fun onInstantTap() {
        if (_instantState.value != InstantState.Idle || tapJob?.isActive == true) return
        tapJob = viewModelScope.launch {
            if (!isSaleOpenNow()) {                                 // 표시가 stale했던 경우 — 사유 표시
                advanceFromIdle(InstantState.SaleClosed)
                return@launch
            }
            runCatching { container.refreshLastPurchasedRound() }
                .onFailure { if (it is CancellationException) throw it }
            val round = Round720.getUpcomingDrawRound()
            if (container.lastPurchasedRound.value >= round) {
                advanceFromIdle(InstantState.PickingExtra(round))
                return@launch
            }
            val saved = container.numberConfig.value
            advanceFromIdle(
                if (saved.gameCount == 0) InstantState.NeedsSetup
                else InstantState.ConfirmingFirst(round, saved),
            )
        }
    }

    /**
     * Idle일 때만 상태 전이. 빠른 이중 탭으로 코루틴이 둘 뜨면 늦게 복귀한 쪽이 진행 중
     * 상태(InProgress 등)를 다이얼로그 단계로 되돌려 재확정(=이중 결제) 여지를 만들 수 있어,
     * 상태 기록 직전에 선점 여부를 재확인한다(viewModelScope=Main 단일 스레드라 원자적).
     */
    private fun advanceFromIdle(next: InstantState) {
        if (_instantState.value == InstantState.Idle) _instantState.value = next
    }

    /** 첫 구매 확정 — 탭 시점 저장 설정 스냅샷 그대로 실행. 최종 회차·가드 재판정은 컨테이너 Mutex 안. */
    fun confirmFirst() {
        val s = _instantState.value as? InstantState.ConfirmingFirst ?: return
        launchPurchase {
            container.instantPurchase(extra = false, expectedRound = s.round, autoGames = 0, config = s.config)
        }
    }

    /** 추가 구매 확정 — 완전자동(조·번호 자동) [games]게임. 가드로 막지 않음(서버 한도 위임), 회차만 대조. */
    fun confirmExtra(games: Int) {
        val s = _instantState.value as? InstantState.PickingExtra ?: return
        launchPurchase {
            container.instantPurchase(extra = true, expectedRound = s.round, autoGames = games, config = null)
        }
    }

    fun dismissInstant() {
        if (_instantState.value == InstantState.InProgress) return  // 진행 중 닫기 금지
        _instantState.value = InstantState.Idle
    }

    private fun launchPurchase(block: suspend () -> PurchaseResult720?) {
        if (_instantState.value == InstantState.InProgress) return  // 중복 확정 no-op
        if (!isSaleOpenNow()) {                                     // 확정 시점 판매시간 재검증
            _instantState.value = InstantState.SaleClosed
            return
        }
        _instantState.value = InstantState.InProgress
        viewModelScope.launch {
            _instantState.value = try {
                val r = block()
                if (r == null) InstantState.AlreadyPurchased else InstantState.Success(r)
            } catch (e: AppContainer.PurchaseRecordFailedException) {
                InstantState.Success(e.result, guardSaved = false)   // 결제 성공·로컬 가드 저장 실패(예약 자동구매 중단)
            } catch (e: AppContainer.SaleClosedException) {
                InstantState.SaleClosed                              // 락 대기 중 판매 종료
            } catch (e: AppContainer.RoundChangedException) {
                InstantState.RoundChanged                            // 구매 요청 없이 취소됨
            } catch (e: AppContainer.PurchaseResultUnknownException) {
                InstantState.Error(message = null, unknown = true)
            } catch (e: BudgetExceededException) {
                // 예산 초과는 값(daily/weekly)만 담겨 오므로 여기서 3로케일 리소스로 매핑(재시도 유도 아님).
                InstantState.Error(
                    container.appContext.getString(R.string.budgetExceeded, e.daily, e.weekly),
                    unknown = false,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e              // 취소는 Error로 오분류하지 않고 전파(F1)
                InstantState.Error(message = e.message, unknown = false)
            }
        }
    }
}

/**
 * 기록(Task13): 로그인 상태면 dhlottery에서 720 구매내역(티켓 단위)을 라이브 조회(로컬 DB 없음).
 * [com.umicorp.autolotto720.dhlottery.Feature720.PURCHASE_ENABLED]=false인 동안은
 * [AppContainer.historyService720]가 항상 빈 목록을 반환 — 화면은 빈 상태로 "준비 중"을 알린다.
 *
 * 조회는 3개월 창 단위(동행복권 1회 조회 한도) — 첫 로드는 최근 3개월, "더 보기"가 이전 3개월
 * 창을 이어 붙여 최대 4창(서버 보관 한도 1년)까지 내려간다. 새로고침은 첫 창부터 리셋.
 */
class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    val isLoggedIn = container.isLoggedIn  // 빈 화면 문구 분기용(원본 isLoggedInProvider watch)
    private val _tickets = MutableStateFlow<List<Ticket720>>(emptyList())
    val tickets: StateFlow<List<Ticket720>> = _tickets.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()
    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 다음 "더 보기" 창의 끝 날짜 다음 날(= 마지막으로 로드한 창의 시작일). */
    private var oldestStart: LocalDate = LocalDate.now()
    private var windowsLoaded = 0

    init { loadHistory() }

    /** 첫 3개월 창 로드(새로고침 겸용 — 더 보기로 쌓인 이전 창은 버리고 리셋). */
    fun loadHistory() {
        if (!container.isLoggedIn.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val end = LocalDate.now()
                val start = end.minusMonths(WINDOW_MONTHS)
                _tickets.value = container.historyService720.fetchPurchases(start, end)
                    .sortedByDescending { it.round }
                oldestStart = start
                windowsLoaded = 1
                _canLoadMore.value = true
            } catch (e: Exception) {
                _error.value = e.message  // 5b가 historyLoadError 템플릿으로 로컬라이즈
                _canLoadMore.value = false
            } finally {
                _loading.value = false
            }
        }
    }

    /** 이전 3개월 창 추가 로드. 실패는 조용히 무시 — 버튼이 남아 재시도 가능. */
    fun loadMore() {
        if (_loading.value || _loadingMore.value || !_canLoadMore.value) return
        viewModelScope.launch {
            _loadingMore.value = true
            try {
                val end = oldestStart.minusDays(1)
                val start = end.minusMonths(WINDOW_MONTHS)
                val more = container.historyService720.fetchPurchases(start, end)
                _tickets.value = (_tickets.value + more).sortedByDescending { it.round }
                oldestStart = start
                windowsLoaded++
                if (windowsLoaded >= MAX_WINDOWS) _canLoadMore.value = false
            } catch (_: Exception) {
            } finally {
                _loadingMore.value = false
            }
        }
    }

    private companion object {
        const val WINDOW_MONTHS = 3L  // 동행복권 1회 조회 한도
        const val MAX_WINDOWS = 4     // 4×3개월 = 서버 보관 한도 1년
    }
}

/** 설정: 로그인/로그아웃·잔액·자동구매 설정·언어·잔액알림·초기화. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val isLoggedIn = container.isLoggedIn
    val balance = container.balance
    val autoEnabled = container.autoEnabled
    val autoGames = container.autoGames
    val autoPurchaseDay = container.autoPurchaseDay
    val autoPurchaseHour = container.autoPurchaseHour
    val autoPurchaseMinute = container.autoPurchaseMinute
    val balanceAlertEnabled = container.balanceAlertEnabled
    val balanceAlertThreshold = container.balanceAlertThreshold
    val dailyBudget = container.dailyBudget
    val weeklyBudget = container.weeklyBudget
    val language = container.language
    val loggedInUserId = container.loggedInUserId

    /** 로그인 진행/결과 (스낵바·다이얼로그용). 원본 INVALID_CREDENTIALS 분기 유지. */
    sealed interface LoginState {
        data object Idle : LoginState
        data object InProgress : LoginState
        data object Success : LoginState
        data object InvalidCredentials : LoginState
        data object Error : LoginState
    }

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    fun login(id: String, pw: String) {
        val u = id.trim()
        val p = pw.trim()
        if (u.isEmpty() || p.isEmpty()) return
        viewModelScope.launch {
            _loginState.value = LoginState.InProgress
            _loginState.value = try {
                container.login(u, p)
                LoginState.Success
            } catch (e: Exception) {
                if ((e.message ?: "").contains("INVALID_CREDENTIALS")) LoginState.InvalidCredentials
                else LoginState.Error
            }
        }
    }

    /** 스낵바 표시 후 상태 소비. */
    fun consumeLoginState() { _loginState.value = LoginState.Idle }

    fun logout() { viewModelScope.launch { container.logout() } }
    fun refreshBalance() { viewModelScope.launch { container.refreshBalance() } }

    /** [DEBUG 전용] 감독 하 단발 1매 실구매 → 결과 문자열 콜백(스낵바 표시용). */
    fun debugTestPurchase(onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(container.debugPurchaseOne()) }
    }

    /** [DEBUG 전용] 자동구매 회차 멱등 가드 리셋 → 예약 워커 재구매 테스트용. */
    fun debugResetRound(onResult: (String) -> Unit) {
        onResult(container.debugResetPurchasedRound())
    }

    fun setAutoEnabled(v: Boolean) { viewModelScope.launch { container.setAutoEnabled(v) } }

    /** 요일 변경. 저장된 시:분과 조합한 후보가 구매 불가 시간이면 false 반환(원본 검증 스낵바). */
    fun setPurchaseDay(day: Int): Boolean {
        if (!isValidPurchaseTime(day, autoPurchaseHour.value, autoPurchaseMinute.value)) return false
        viewModelScope.launch { container.setAutoPurchaseDay(day) }
        return true
    }

    /** 시간 변경. 저장된 요일과 조합한 후보가 구매 불가 시간이면 false 반환. */
    fun setPurchaseTime(hour: Int, minute: Int): Boolean {
        if (!isValidPurchaseTime(autoPurchaseDay.value, hour, minute)) return false
        viewModelScope.launch { container.setAutoPurchaseTime(hour, minute) }
        return true
    }

    fun setLanguage(lang: String) { viewModelScope.launch { container.setLanguage(lang) } }
    fun setBalanceAlertEnabled(v: Boolean) { viewModelScope.launch { container.setBalanceAlertEnabled(v) } }
    fun setBalanceAlertThreshold(v: Int) { viewModelScope.launch { container.setBalanceAlertThreshold(v) } }
    fun setDailyBudget(v: Int) { viewModelScope.launch { container.setDailyBudget(v) } }
    fun setWeeklyBudget(v: Int) { viewModelScope.launch { container.setWeeklyBudget(v) } }
    fun resetAll() { viewModelScope.launch { container.resetAll() } }

    companion object {
        /**
         * 구매 가능 시간 (연금복권720+ 규칙). "지금"이 아니라 저장하려는 후보 스케줄(요일,시,분)을 검증한다(R2 N1):
         * 목 17:00~금 00:00(목 17:00~23:59)은 설정 불가 — 현 회차 판매가 목 17:00 마감이라 목요일 저녁을
         * 스케줄 대상으로 두면 그 주 무구매가 되므로 저장을 막는다. 금 00:00부터 다음 회차 판매라 허용.
         * 스케줄 규칙([Round720.isScheduleBlocked])은 워커 런타임 판매마감 가드([Round720.isSalesClosed])와 독립.
         */
        fun isValidPurchaseTime(day: Int, hour: Int, minute: Int): Boolean =
            !Round720.isScheduleBlocked(day, hour, minute)
    }
}
