package com.umicorp.autolotto720.dhlottery

import org.junit.Assert.assertEquals
import org.junit.Test

class Crypto720Test {

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /**
     * 결정적 벡터: CryptoJS/실서버와의 바이트 일치 증명.
     * salt/iv를 고정하면 PBKDF2 키·AES 출력·q 인코딩 전 과정이 결정적 → node 레퍼런스가 서버로
     * 검증한 값과 정확히 일치해야 한다. 이 assertion이 통과하면 Kotlin 이식이 서버와 맞는다는 증거.
     */
    @Test
    fun `deterministic vector matches CryptoJS server output`() {
        val jsessionId = "ABCDEF0123456789ABCDEF0123456789extra" // substring(0,32) = pass phrase
        val salt = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff".hexToBytes()
        val iv = "0f0e0d0c0b0a09080706050403020100".hexToBytes()
        val plaintext = "ROUND=325&SEL_NO=&BUY_CNT=&AUTO_SEL_SET=SA&SEL_CLASS=&BUY_TYPE=A&ACCS_TYPE=01"
        val expectedQ = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff" +
            "0f0e0d0c0b0a0908070605040302010025EriR7aQE+tNvUamCrgZ49kspfiORHm6jSNMIPONPOsi" +
            "kZq6EzWj5CzKINSf79222l5s+fGP9zUfcTpTtT/XmY931oVvYObWYNT0W52zts="

        assertEquals(expectedQ, Crypto720.encryptWith(plaintext, jsessionId, salt, iv))
    }

    @Test
    fun `encrypt decrypt round-trips including korean and empty`() {
        val jses = "ABCDEF0123456789ABCDEF0123456789extra"
        for (plain in listOf(
            "ROUND=325&SEL_NO=&BUY_CNT=&AUTO_SEL_SET=SA",
            "연금복권720+ 한글 테스트 문자열",
            "",
        )) {
            assertEquals(plain, Crypto720.decrypt(Crypto720.encrypt(plain, jses), jses))
        }
    }

    @Test
    fun `short jsessionId under 32 chars round-trips`() {
        val jses = "short_session" // 13 chars → 전체를 pass phrase로 사용
        val plain = "ROUND=325&BUY_TYPE=A"
        assertEquals(plain, Crypto720.decrypt(Crypto720.encrypt(plain, jses), jses))
    }
}
