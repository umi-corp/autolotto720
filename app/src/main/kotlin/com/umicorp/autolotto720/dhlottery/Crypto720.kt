package com.umicorp.autolotto720.dhlottery

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 연금복권720+ 온라인 구매용 AES 암호화. 동행복권 encrypt.js(CryptoJS) 스킴을 JCE로 이식.
 * 실서버로 검증 완료(node 레퍼런스가 만든 payload를 서버가 수락, 서버 응답을 복호화 성공) —
 * [Crypto720Test]의 결정적 테스트 벡터가 CryptoJS/서버와의 바이트 일치를 고정한다.
 *
 * 스킴:
 *   passPhrase = JSESSIONID.substring(0, 32) (32자 미만이면 전체) → UTF-8
 *   salt = 랜덤 32바이트, iv = 랜덤 16바이트 (호출마다)
 *   key  = PBKDF2WithHmacSHA256(passPhrase, salt, iterations=1000, keyLength=128) → 16바이트
 *   cipher = AES/CBC/PKCS5Padding(plaintext_utf8, key, iv)
 *   q = lowercaseHex(salt) + lowercaseHex(iv) + base64(cipher)   // 표준 base64, 줄바꿈 없음
 *
 * 프로덕션 encrypt.js는 q에 커스텀 urlEncode를 적용하지만, 이 앱은 OkHttp [okhttp3.FormBody]가
 * HTTP 계층에서 인코딩하므로 [encrypt]는 RAW q(hex+hex+base64, urlEncode 안 함)를 반환한다.
 * 호출자는 이 값을 그대로 FormBody의 "q" 필드에 넣는다.
 */
object Crypto720 {

    private const val ITERATIONS = 1000
    private const val KEY_BITS = 128
    private val secureRandom = SecureRandom()

    /** 랜덤 salt(32)/iv(16)로 암호화 → q(hex+hex+base64). */
    fun encrypt(plain: String, jsessionId: String): String {
        val salt = ByteArray(32).also(secureRandom::nextBytes)
        val iv = ByteArray(16).also(secureRandom::nextBytes)
        return encryptWith(plain, jsessionId, salt, iv)
    }

    /** q(hex+hex+base64) → 평문(UTF-8). */
    fun decrypt(encText: String, jsessionId: String): String {
        val salt = encText.substring(0, 64).hexToBytes()
        val iv = encText.substring(64, 96).hexToBytes()
        val cipherBytes = Base64.getDecoder().decode(encText.substring(96))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(jsessionId, salt), IvParameterSpec(iv))
        }
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    /** 고정 salt/iv 주입용 — 결정적 테스트 벡터 검증. */
    internal fun encryptWith(plain: String, jsessionId: String, salt: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(jsessionId, salt), IvParameterSpec(iv))
        }
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return salt.toHex() + iv.toHex() + Base64.getEncoder().encodeToString(cipherBytes)
    }

    private fun deriveKey(jsessionId: String, salt: ByteArray): SecretKeySpec {
        val passPhrase = if (jsessionId.length >= 32) jsessionId.substring(0, 32) else jsessionId
        val spec = PBEKeySpec(passPhrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
