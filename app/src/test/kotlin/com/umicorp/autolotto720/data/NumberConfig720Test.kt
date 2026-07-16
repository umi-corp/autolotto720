package com.umicorp.autolotto720.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberConfig720Test {

    // === Slot720 생성 검증(§3 불변식) ===

    @Test fun semiAuto_and_manual_construct_when_valid() {
        assertEquals(3, Slot720.SemiAuto(3).group)
        val m = Slot720.Manual(2, listOf(1, 7, 5, 0, 2, 0))
        assertEquals(2, m.group)
        assertEquals(listOf(1, 7, 5, 0, 2, 0), m.digits)
    }

    @Test(expected = IllegalArgumentException::class) fun semiAuto_rejects_group_out_of_range() {
        Slot720.SemiAuto(6)
    }

    @Test(expected = IllegalArgumentException::class) fun manual_rejects_group_zero() {
        Slot720.Manual(0, listOf(0, 0, 0, 0, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class) fun manual_rejects_wrong_digit_count() {
        Slot720.Manual(1, listOf(1, 2, 3, 4, 5))
    }

    @Test(expected = IllegalArgumentException::class) fun manual_rejects_digit_out_of_range() {
        Slot720.Manual(1, listOf(1, 2, 3, 4, 5, 10))
    }

    @Test(expected = IllegalArgumentException::class) fun config_rejects_non_five_slots() {
        NumberConfig720(listOf(Slot720.Unset), FallbackPolicy.KEEP_GROUP_RANDOM, 1, 0)
    }

    // === JSON 왕복 ===

    @Test fun json_round_trip_preserves_all_slot_types() {
        val config = NumberConfig720(
            slots = listOf(
                Slot720.Unset,
                Slot720.FullAuto,
                Slot720.SemiAuto(4),
                Slot720.Manual(2, listOf(1, 7, 5, 0, 2, 0)),
                Slot720.Unset,
            ),
            fallback = FallbackPolicy.GIVE_UP,
            schemaVersion = NumberConfig720.CURRENT_SCHEMA,
            revision = 7L,
        )
        val restored = NumberConfig720.fromJson(config.toJson())
        assertEquals(config, restored)
    }

    @Test fun json_round_trip_preserves_reassign_all_fallback() {
        val config = NumberConfig720(
            slots = List(5) { Slot720.FullAuto },
            fallback = FallbackPolicy.REASSIGN_ALL,
            schemaVersion = NumberConfig720.CURRENT_SCHEMA,
            revision = 3L,
        )
        assertEquals(FallbackPolicy.REASSIGN_ALL, NumberConfig720.fromJson(config.toJson())!!.fallback)
    }

    @Test fun manual_digits_preserve_leading_zero_and_order() {
        val config = NumberConfig720(
            slots = List(5) { if (it == 0) Slot720.Manual(1, listOf(0, 0, 0, 7, 2, 7)) else Slot720.Unset },
            fallback = FallbackPolicy.KEEP_GROUP_RANDOM,
            schemaVersion = NumberConfig720.CURRENT_SCHEMA,
            revision = 1L,
        )
        val restored = NumberConfig720.fromJson(config.toJson())!!
        assertEquals(listOf(0, 0, 0, 7, 2, 7), (restored.slots[0] as Slot720.Manual).digits)
    }

    // === fromJson: 거절·sanitize(§3, §10) ===

    @Test fun fromJson_returns_null_on_blank_or_garbage() {
        assertNull(NumberConfig720.fromJson(null))
        assertNull(NumberConfig720.fromJson(""))
        assertNull(NumberConfig720.fromJson("not json"))
    }

    @Test fun fromJson_unknown_schema_falls_to_empty() {
        val json = JSONObject().apply { put("schemaVersion", 999); put("revision", 5) }.toString()
        val c = NumberConfig720.fromJson(json)!!
        assertEquals(NumberConfig720.empty(), c)         // 휴리스틱 파싱 금지 → 안전 기본값
    }

    @Test fun fromJson_sanitizes_corrupt_slot_to_unset() {
        // group=9(범위 밖) semiAuto, digits 5개 manual → 각각 Unset으로 안전화, 나머지는 보존.
        val json = """
            {"schemaVersion":1,"revision":2,"fallback":"KEEP_GROUP_RANDOM","slots":[
              {"type":"semiAuto","group":9},
              {"type":"manual","group":1,"digits":[1,2,3,4,5]},
              {"type":"fullAuto"},
              {"type":"bogus"},
              {"type":"manual","group":3,"digits":[9,9,9,9,9,9]}
            ]}
        """.trimIndent()
        val c = NumberConfig720.fromJson(json)!!
        assertEquals(Slot720.Unset, c.slots[0])
        assertEquals(Slot720.Unset, c.slots[1])
        assertEquals(Slot720.FullAuto, c.slots[2])
        assertEquals(Slot720.Unset, c.slots[3])
        assertEquals(Slot720.Manual(3, listOf(9, 9, 9, 9, 9, 9)), c.slots[4])
    }

    @Test fun fromJson_pads_and_truncates_to_five_slots() {
        val short = """{"schemaVersion":1,"revision":1,"fallback":"GIVE_UP","slots":[{"type":"fullAuto"}]}"""
        val c = NumberConfig720.fromJson(short)!!
        assertEquals(5, c.slots.size)
        assertEquals(Slot720.FullAuto, c.slots[0])
        assertTrue(c.slots.drop(1).all { it == Slot720.Unset })

        val long = """
            {"schemaVersion":1,"revision":1,"fallback":"KEEP_GROUP_RANDOM","slots":[
              {"type":"fullAuto"},{"type":"fullAuto"},{"type":"fullAuto"},
              {"type":"fullAuto"},{"type":"fullAuto"},{"type":"fullAuto"}
            ]}
        """.trimIndent()
        assertEquals(5, NumberConfig720.fromJson(long)!!.slots.size)
    }

    @Test fun fromJson_bad_fallback_defaults_to_reassign_all() {
        val json = """{"schemaVersion":1,"revision":1,"fallback":"WHAT","slots":[]}"""
        assertEquals(FallbackPolicy.REASSIGN_ALL, NumberConfig720.fromJson(json)!!.fallback)
    }

    @Test fun empty_is_all_unset_no_games() {
        val e = NumberConfig720.empty()
        assertEquals(0, e.gameCount)
        assertEquals(0L, e.revision)
        assertEquals(FallbackPolicy.REASSIGN_ALL, e.fallback)   // 기본 폴백 = 조+번호 모두 자동 배정
        assertTrue(e.slots.all { it == Slot720.Unset })
    }
}
