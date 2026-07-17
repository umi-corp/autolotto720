package com.umicorp.autolotto720.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberConfig720SetModeTest {

    @Test fun `default config is not setMode and stays schema 1`() {
        val c = NumberConfig720.empty()
        assertFalse(c.setMode)
        assertTrue(NumberConfig720.fromJson(c.toJson())!!.let { !it.setMode })
        assertTrue(c.toJson().contains("\"schemaVersion\":1"))
    }

    @Test fun `setMode config round-trips as schema 2 with gameCount 5`() {
        val c = NumberConfig720.empty().copy(setMode = true)
        assertEquals(5, c.gameCount)
        assertTrue(c.toJson().contains("\"schemaVersion\":2"))
        val back = NumberConfig720.fromJson(c.toJson())!!
        assertTrue(back.setMode)
        assertEquals(5, back.gameCount)
    }

    @Test fun `v1 json without setMode loads as setMode false`() {
        val v1 = """{"schemaVersion":1,"revision":3,"fallback":"REASSIGN_ALL","slots":[{"type":"fullAuto"},{"type":"unset"},{"type":"unset"},{"type":"unset"},{"type":"unset"}]}"""
        val c = NumberConfig720.fromJson(v1)!!
        assertFalse(c.setMode)
        assertEquals(1, c.gameCount)
    }

    @Test fun `schema 1 with stray setMode field stays false`() {
        // 조건부 v2 계약 방어: v1 데이터에 setMode 필드가 섞여 있어도 세트로 전환되지 않는다.
        val v1WithStray = """{"schemaVersion":1,"revision":1,"fallback":"REASSIGN_ALL","setMode":true,"slots":[{"type":"unset"},{"type":"unset"},{"type":"unset"},{"type":"unset"},{"type":"unset"}]}"""
        assertFalse(NumberConfig720.fromJson(v1WithStray)!!.setMode)
    }

    @Test fun `setMode preserves slots so toggling off restores them`() {
        val withSlot = NumberConfig720(
            listOf(Slot720.Manual(2, listOf(1,2,3,4,5,6))) + List(4){Slot720.Unset},
            FallbackPolicy.REASSIGN_ALL, 1, 1L,
        )
        val on = withSlot.copy(setMode = true)          // 세트 켜도 슬롯 직렬화 유지
        val restored = NumberConfig720.fromJson(on.toJson())!!.copy(setMode = false)
        assertEquals(withSlot.slots, restored.slots)
    }

    @Test fun `future schema is fail-closed to empty`() {
        val future = """{"schemaVersion":99,"revision":1,"fallback":"REASSIGN_ALL","slots":[]}"""
        val c = NumberConfig720.fromJson(future)!!
        assertEquals(NumberConfig720.empty(), c)
    }
}
