package com.umicorp.autolotto720.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umicorp.autolotto720.AppContainer
import com.umicorp.autolotto720.data.Ticket720
import com.umicorp.autolotto720.data.WinningNumbers720
import com.umicorp.autolotto720.dhlottery.Round720
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

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

    /** 당겨서 새로고침: 당첨번호 + 잔액. */
    fun refreshAll() {
        fetchWinningNumbers()
        viewModelScope.launch { container.refreshBalance() }
    }
}

/**
 * 구매조건 설정(원 NumberViewModel 대체, Task12): 자동 구매 on/off + 매수(1~5, v1 자동배정 전용).
 * 720 온라인 구매는 [com.umicorp.autolotto720.dhlottery.Feature720.PURCHASE_ENABLED] 게이트 —
 * 화면이 배너로 "준비 중"을 알리고 on/off 스위치를 잠근다. 매수 설정은 게이트와 무관하게 저장해
 * 둔다(게이트가 열리면 그대로 쓰인다).
 */
class PurchaseSetupViewModel(private val container: AppContainer) : ViewModel() {
    val autoEnabled = container.autoEnabled
    val autoGames = container.autoGames

    fun setAutoEnabled(v: Boolean) { viewModelScope.launch { container.setAutoEnabled(v) } }

    fun setAutoGames(n: Int) { viewModelScope.launch { container.setAutoGames(n.coerceIn(1, 5)) } }
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

    fun setAutoEnabled(v: Boolean) { viewModelScope.launch { container.setAutoEnabled(v) } }

    /** 요일 변경. 구매 불가 시간이면 false 반환(원본 검증 스낵바). */
    fun setPurchaseDay(day: Int): Boolean {
        if (!isValidPurchaseTime(day, autoPurchaseHour.value)) return false
        viewModelScope.launch { container.setAutoPurchaseDay(day) }
        return true
    }

    /** 시간 변경. 구매 불가 시간이면 false 반환. */
    fun setPurchaseTime(hour: Int, minute: Int): Boolean {
        if (!isValidPurchaseTime(autoPurchaseDay.value, hour)) return false
        viewModelScope.launch { container.setAutoPurchaseTime(hour, minute) }
        return true
    }

    fun setLanguage(lang: String) { viewModelScope.launch { container.setLanguage(lang) } }
    fun setBalanceAlertEnabled(v: Boolean) { viewModelScope.launch { container.setBalanceAlertEnabled(v) } }
    fun setBalanceAlertThreshold(v: Int) { viewModelScope.launch { container.setBalanceAlertThreshold(v) } }
    fun resetAll() { viewModelScope.launch { container.resetAll() } }

    companion object {
        /**
         * 구매 가능 시간 (원본 `_isValidPurchaseTime`). day: 1=월 .. 7=일.
         * 토(6): 06:00~19:59, 그 외(평일/일): 06:00~23:59. (토 20:00~일 05:59 판매정지)
         * ponytail: 이 하드 검증은 사이트 전반 운영시간 규칙(645 유산) — 720 목요일 마감(17:00) 자체는
         * 하드 블록이 아니라 SettingsScreen의 인라인 경고(Task14)로만 안내한다.
         */
        fun isValidPurchaseTime(day: Int, hour: Int): Boolean =
            if (day == 6) hour in 6..19 else hour >= 6
    }
}
