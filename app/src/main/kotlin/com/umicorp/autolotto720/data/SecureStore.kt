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
    const val MANUAL_NUMBERS = "manual_numbers"
    const val AUTO_PURCHASE_DAY = "auto_purchase_day"
    const val AUTO_PURCHASE_HOUR = "auto_purchase_hour"
    const val AUTO_PURCHASE_MINUTE = "auto_purchase_minute"
    const val LANGUAGE = "app_language"
    const val BALANCE_ALERT_ENABLED = "balance_alert_enabled"
    const val BALANCE_ALERT_THRESHOLD = "balance_alert_threshold"
    const val BALANCE_ALERT_LAST_DATE = "balance_alert_last_date"

    /** 네이티브 전용(Flutter에 없던 키) — 자동구매 중복 결제 방지용 마지막 구매 회차. ALL(이관 목록) 미포함. */
    const val LAST_PURCHASED_ROUND = "last_purchased_round"

    /** 마이그레이션·일괄 처리용 전체 키 목록. */
    val ALL = listOf(
        USER_ID, PASSWORD, AUTO_ENABLED, AUTO_GAMES, MANUAL_NUMBERS,
        AUTO_PURCHASE_DAY, AUTO_PURCHASE_HOUR, AUTO_PURCHASE_MINUTE, LANGUAGE,
        BALANCE_ALERT_ENABLED, BALANCE_ALERT_THRESHOLD, BALANCE_ALERT_LAST_DATE,
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
 * - 기본값/파싱 규칙도 원본 getter와 동일(미설정 시 day=4(목, 720 추첨일), hour=9, minute=0, threshold=5000,
 *   manual_numbers="[]", language="system" 등).
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

    /** 수동 번호 저장 (JSON 5슬롯 문자열). */
    fun setManualNumbers(json: String) = putString(SecureKeys.MANUAL_NUMBERS, json)

    /** 미설정 시 "[]" (원본 기본값). */
    fun getManualNumbers(): String = prefs.getString(SecureKeys.MANUAL_NUMBERS, null) ?: "[]"

    // === 구매 시간 설정 ===

    /** 구매 요일 (1=월 ~ 7=일). */
    fun setAutoPurchaseDay(day: Int) = putString(SecureKeys.AUTO_PURCHASE_DAY, day.toString())

    fun getAutoPurchaseDay(): Int = prefs.getString(SecureKeys.AUTO_PURCHASE_DAY, null)?.toIntOrNull() ?: 4

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
