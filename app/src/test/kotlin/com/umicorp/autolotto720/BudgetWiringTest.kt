package com.umicorp.autolotto720

import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.SpendEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetWiringTest {
    @Test fun `attemptAmount is 5000 for set and gameCount x 1000 for slots`() {
        assertEquals(5000, attemptAmount(NumberConfig720.empty().copy(setMode = true)))
        assertEquals(0, attemptAmount(NumberConfig720.empty()))
    }
    @Test fun `parsePending round-trips round epochDay amount`() {
        val json = """{"round":325,"epochDay":100,"amount":5000}"""
        assertEquals(SpendEntry(325, 100, 5000), parsePending(json))
    }
    @Test fun `parsePending returns null for blank or corrupt`() {
        assertNull(parsePending(null)); assertNull(parsePending("")); assertNull(parsePending("{}"))
    }
    @Test fun `parsePending rejects negative amount or epochDay (money guard fail-closed)`() {
        // 손상 PENDING의 음수 amount/epochDay가 BudgetGuard.check를 완화하지 않도록 null(=거절) 반환.
        assertNull(parsePending("""{"round":325,"epochDay":100,"amount":-5000}"""))
        assertNull(parsePending("""{"round":325,"epochDay":-1,"amount":5000}"""))
    }
}
