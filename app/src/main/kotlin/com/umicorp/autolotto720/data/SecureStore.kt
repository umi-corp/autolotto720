package com.umicorp.autolotto720.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 보안저장 키 문자열 — Flutter `SecureStorageService`와 1:1.
 * 오타 방지용 단일 출처. 백그라운드(AlarmManager 리시버)가 raw 문자열로 읽는 키도 전부 여기서 나온다.
 */
object SecureKeys {
    const val USER_ID = "dhlottery_user_id"
    const val PASSWORD = "dhlottery_password"
    const val AUTO_ENABLED = "auto_purchase_enabled"
    const val AUTO_GAMES = "auto_games"
    const val AUTO_PURCHASE_DAY = "auto_purchase_day"
    const val AUTO_PURCHASE_HOUR = "auto_purchase_hour"
    const val AUTO_PURCHASE_MINUTE = "auto_purchase_minute"
    const val LANGUAGE = "app_language"
    const val BALANCE_ALERT_ENABLED = "balance_alert_enabled"
    const val BALANCE_ALERT_THRESHOLD = "balance_alert_threshold"
    const val BALANCE_ALERT_LAST_DATE = "balance_alert_last_date"

    /** "번호" 탭 설정(NumberConfig720 JSON) — 5슬롯 sealed 상태·폴백정책·schemaVersion·revision. */
    const val NUMBER_CONFIG = "number_config"

    /** 네이티브 전용(Flutter에 없던 키) — 자동구매 중복 결제 방지용 마지막 구매 회차. ALL(이관 목록) 미포함. */
    const val LAST_PURCHASED_ROUND = "last_purchased_round"

    /** [LAST_PURCHASED_ROUND]를 기록한 계정 ID — 계정 전환 시 남의 회차 가드로 막히지 않게 스코프를 준다. ALL 미포함. */
    const val LAST_PURCHASE_OWNER = "last_purchase_owner"

    const val DAILY_BUDGET = "daily_budget"
    const val WEEKLY_BUDGET = "weekly_budget"
    /** 지출 원장(JSON): 시도별 {round, epochDay, amount}. 7일 초과 항목은 기록 시 정리. */
    const val SPEND_LEDGER = "spend_ledger"
    /** connPro 진입 직전 선기록 — {round, epochDay, amount}. 미결 잔존 시 해당 회차 재결제 차단.
     *  ponytail: 계정 스코프 없음 — 멀티계정에서 A의 미결 PENDING이 B의 같은 회차 구매를 과차단할 수 있으나
     *  무결제(안전측)라 수용. 빈발 시 userId를 넣어 계정 일치 시에만 차단으로 승급. */
    const val PENDING_PURCHASE = "pending_purchase"

    /** 마이그레이션·일괄 처리용 전체 키 목록. */
    val ALL = listOf(
        USER_ID, PASSWORD, AUTO_ENABLED, AUTO_GAMES, NUMBER_CONFIG,
        AUTO_PURCHASE_DAY, AUTO_PURCHASE_HOUR, AUTO_PURCHASE_MINUTE, LANGUAGE,
        BALANCE_ALERT_ENABLED, BALANCE_ALERT_THRESHOLD, BALANCE_ALERT_LAST_DATE,
        DAILY_BUDGET, WEEKLY_BUDGET, SPEND_LEDGER, PENDING_PURCHASE,
    )
}

/**
 * 보안저장소 (자격증명·자동구매설정·수동번호·알림설정 영속화) — Flutter `SecureStorageService` 포트.
 *
 * 영속 진실의 출처. EncryptedSharedPreferences(Android Keystore 암호화)를 쓴다:
 * MasterKey=AES256_GCM, 키=AES256_SIV, 값=AES256_GCM.
 *
 * Flutter와 1:1 충실도 규칙:
 * - 모든 값을 **문자열**로 저장(불리언="true"/"false", 정수=10진 문자열). 원본 flutter_secure_storage가
 *   문자열만 저장했고, 백그라운드가 raw 키로 읽어 같은 방식으로 파싱하던 패턴을 그대로 유지하기 위함.
 * - 기본값/파싱 규칙도 원본 getter와 동일(미설정 시 day=5(금, 판매개시 다음날·지정번호 선점 최적), hour=9, minute=0, threshold=5000,
 *   language="system" 등).
 *
 * 백그라운드 리시버도 같은 `Context`로 인스턴스를 만들어 직접 읽는다(원본의 백그라운드 isolate 직접읽기 패턴 유지).
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION") // security-crypto는 deprecated지만 표준·동작 (DESIGN §6). 제거 시 Tink/Keystore.
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = createPrefs(PREFS_FILE)

    @Suppress("DEPRECATION")
    private fun createPrefs(fileName: String): SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // === 계정 ===

    fun saveCredentials(userId: String, password: String) {
        // money-path: commit()으로 즉시 디스크 영속화 (원본 await write 의미 유지, 로그인 직후 강제종료에도 자격증명 보존).
        prefs.edit()
            .putString(SecureKeys.USER_ID, userId)
            .putString(SecureKeys.PASSWORD, password)
            .commit()
    }

    fun getCredentials(): Credentials = Credentials(
        userId = prefs.getString(SecureKeys.USER_ID, null),
        password = prefs.getString(SecureKeys.PASSWORD, null),
    )

    fun hasCredentials(): Boolean {
        val c = getCredentials()
        return c.userId != null && c.password != null
    }

    fun deleteCredentials() {
        prefs.edit()
            .remove(SecureKeys.USER_ID)
            .remove(SecureKeys.PASSWORD)
            .commit()
    }

    // === 자동 구매 설정 ===

    fun setAutoEnabled(enabled: Boolean) = putString(SecureKeys.AUTO_ENABLED, enabled.toString())

    fun getAutoEnabled(): Boolean = prefs.getString(SecureKeys.AUTO_ENABLED, null) == "true"

    fun setAutoGames(games: Int) = putString(SecureKeys.AUTO_GAMES, games.toString())

    /** int.tryParse(val ?? '') ?? 0 와 동일. */
    fun getAutoGames(): Int = prefs.getString(SecureKeys.AUTO_GAMES, null)?.toIntOrNull() ?: 0

    // === "번호" 탭 설정 (NumberConfig720 JSON) ===

    /** committed 5슬롯+폴백정책+revision JSON 저장. 저장은 구매를 무장하지 않는다(게이트·동의 별개).
     *  money-path: commit() 반환으로 영속 성공 확인 — 호출부가 실패 시 StateFlow/saved를 낙관 갱신하지 않게 한다. */
    fun setNumberConfig(json: String): Boolean = prefs.edit().putString(SecureKeys.NUMBER_CONFIG, json).commit()

    /** 저장된 JSON(없으면 null → 마이그레이션/기본값 폴백). 파싱·sanitize는 [NumberConfig720.fromJson]. */
    fun getNumberConfig(): String? = prefs.getString(SecureKeys.NUMBER_CONFIG, null)

    // === 구매 시간 설정 ===

    /** 구매 요일 (1=월 ~ 7=일). */
    fun setAutoPurchaseDay(day: Int) = putString(SecureKeys.AUTO_PURCHASE_DAY, day.toString())

    fun getAutoPurchaseDay(): Int = prefs.getString(SecureKeys.AUTO_PURCHASE_DAY, null)?.toIntOrNull() ?: 5

    fun setAutoPurchaseHour(hour: Int) = putString(SecureKeys.AUTO_PURCHASE_HOUR, hour.toString())

    fun getAutoPurchaseHour(): Int = prefs.getString(SecureKeys.AUTO_PURCHASE_HOUR, null)?.toIntOrNull() ?: 9

    fun setAutoPurchaseMinute(minute: Int) = putString(SecureKeys.AUTO_PURCHASE_MINUTE, minute.toString())

    fun getAutoPurchaseMinute(): Int = prefs.getString(SecureKeys.AUTO_PURCHASE_MINUTE, null)?.toIntOrNull() ?: 0

    // === 언어 설정 ===

    fun setLanguage(lang: String) = putString(SecureKeys.LANGUAGE, lang)

    fun getLanguage(): String = prefs.getString(SecureKeys.LANGUAGE, null) ?: "system"

    // === 잔액 부족 알림 ===

    fun setBalanceAlertEnabled(enabled: Boolean) = putString(SecureKeys.BALANCE_ALERT_ENABLED, enabled.toString())

    fun getBalanceAlertEnabled(): Boolean = prefs.getString(SecureKeys.BALANCE_ALERT_ENABLED, null) == "true"

    fun setBalanceAlertThreshold(threshold: Int) = putString(SecureKeys.BALANCE_ALERT_THRESHOLD, threshold.toString())

    fun getBalanceAlertThreshold(): Int =
        prefs.getString(SecureKeys.BALANCE_ALERT_THRESHOLD, null)?.toIntOrNull() ?: 5000

    fun setBalanceAlertLastDate(date: String) = putString(SecureKeys.BALANCE_ALERT_LAST_DATE, date)

    fun getBalanceAlertLastDate(): String? = prefs.getString(SecureKeys.BALANCE_ALERT_LAST_DATE, null)

    // === 자동구매 멱등 가드 ===

    /** 구매 성공 직후 기록(commit) — Worker 재실행 시 같은 회차 중복 결제 방지. */
    fun setLastPurchasedRound(round: Int) = putString(SecureKeys.LAST_PURCHASED_ROUND, round.toString())

    fun getLastPurchasedRound(): Int = prefs.getString(SecureKeys.LAST_PURCHASED_ROUND, null)?.toIntOrNull() ?: 0

    /** [LAST_PURCHASED_ROUND]를 기록한 계정 — 회차 가드를 계정 스코프(AppContainer.accountScopedRound)로 판정. */
    fun getLastPurchaseOwner(): String? = prefs.getString(SecureKeys.LAST_PURCHASE_OWNER, null)

    /**
     * (회차, 소유 계정)을 **한 Editor·한 commit()으로 원자 저장** — 부분 기록(회차만/소유만) 방지.
     * commit() 반환값을 그대로 돌려주어(비동기 apply 아님) 호출부가 영속 성공을 확인할 수 있게 한다.
     */
    fun setLastPurchase(round: Int, userId: String): Boolean =
        prefs.edit()
            .putString(SecureKeys.LAST_PURCHASED_ROUND, round.toString())
            .putString(SecureKeys.LAST_PURCHASE_OWNER, userId)
            .commit()

    // === 예산 한도 (사용자 설정, 원 단위) ===

    /** money-path: commit() 반환으로 영속 성공 확인 — false면 호출부가 StateFlow를 낙관 갱신하지 않는다. */
    fun setDailyBudget(won: Int): Boolean = prefs.edit().putString(SecureKeys.DAILY_BUDGET, won.toString()).commit()
    fun getDailyBudget(): Int = prefs.getString(SecureKeys.DAILY_BUDGET, null)?.toIntOrNull() ?: 5000

    /** money-path: commit() 반환으로 영속 성공 확인 — false면 호출부가 StateFlow를 낙관 갱신하지 않는다. */
    fun setWeeklyBudget(won: Int): Boolean = prefs.edit().putString(SecureKeys.WEEKLY_BUDGET, won.toString()).commit()
    fun getWeeklyBudget(): Int = prefs.getString(SecureKeys.WEEKLY_BUDGET, null)?.toIntOrNull() ?: 5000

    // === 지출 원장 / PENDING ===

    fun getSpendLedger(): String? = prefs.getString(SecureKeys.SPEND_LEDGER, null)
    fun setSpendLedger(json: String): Boolean = prefs.edit().putString(SecureKeys.SPEND_LEDGER, json).commit()

    fun getPendingPurchase(): String? = prefs.getString(SecureKeys.PENDING_PURCHASE, null)
    /** money-path: commit() 반환으로 영속 성공 확인 — false면 호출부가 결제에 진입하지 않는다. */
    fun setPendingPurchase(json: String): Boolean = prefs.edit().putString(SecureKeys.PENDING_PURCHASE, json).commit()
    fun clearPendingPurchase() { prefs.edit().remove(SecureKeys.PENDING_PURCHASE).commit() }

    // === 전체 초기화 ===

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    /** Flutter의 record `({String? userId, String? password})` 대응. */
    data class Credentials(val userId: String?, val password: String?)

    private companion object {
        const val PREFS_FILE = "autolotto720_secure_prefs"
    }
}
