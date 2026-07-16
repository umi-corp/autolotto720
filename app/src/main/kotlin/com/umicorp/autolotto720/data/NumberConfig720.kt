package com.umicorp.autolotto720.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 연금복권720+ "번호" 탭 슬롯 모델 (설계 §3) — 배타적 sealed 4상태.
 *
 * 모드는 필드에서 파생된다(별도 mode 필드 없음 → 필드↔모드 모순 불가). 핵심 불변식:
 * **"조 자동 + 번호 수동" 조합은 타입상 표현 불가** — 조를 자동에 맡기면 번호도 자동([FullAuto]),
 * 번호를 수동으로 찍으려면 조를 반드시 고정([Manual])해야 한다.
 *
 * 생성 시 require로 검증 — 파서 버그(범위 밖 조·자릿수 오류)가 committed에 새지 않도록 생성 지점 차단.
 */
sealed interface Slot720 {
    /** 미설정 — 구매하지 않는 슬롯. non-Unset 슬롯 수 = 게임 수. */
    data object Unset : Slot720

    /** 완전 자동 — 조·번호 모두 구매 직전 서버 배정(연출은 non-authoritative). */
    data object FullAuto : Slot720

    /** 반자동 — 조만 고정, 번호는 서버 배정. */
    data class SemiAuto(val group: Int) : Slot720 {
        init { require(group in 1..5) { "조는 1~5여야 합니다: $group" } }
    }

    /** 수동 — 조 고정 + 6자리 확정(각 자리 0~9, 순서 중요, 자리별 중복 허용). */
    data class Manual(val group: Int, val digits: List<Int>) : Slot720 {
        init {
            require(group in 1..5) { "조는 1~5여야 합니다: $group" }
            require(digits.size == 6) { "번호는 6자리여야 합니다: ${digits.size}자리" }
            require(digits.all { it in 0..9 }) { "각 자리는 0~9여야 합니다: $digits" }
        }
    }
}

/** 점유(매진) 실패 폴백 정책 (설계 §6). 조 유지 재배정(기본) / 포기(스킵). */
enum class FallbackPolicy { KEEP_GROUP_RANDOM, GIVE_UP }

/**
 * "번호" 탭 저장 설정 (설계 §3, §10). [slots]는 항상 5개(A~E).
 *
 * [revision]은 단조 증가(설정을 원복해도 신규 리비전) — 향후 워커 동의 결속(§13-A)이 이 값으로 기존
 * 동의를 무효화한다. [schemaVersion]은 마이그레이션 분기용. JSON 직렬화는 org.json(신규 의존성 없음).
 *
 * **저장 ≠ 구매 승인**: 이 설정을 저장해도 [com.umicorp.autolotto720.dhlottery.Feature720.PURCHASE_ENABLED]
 * 게이트와 동의는 건드리지 않는다.
 */
data class NumberConfig720(
    val slots: List<Slot720>,
    val fallback: FallbackPolicy,
    val schemaVersion: Int,
    val revision: Long,
) {
    init { require(slots.size == 5) { "슬롯은 5개여야 합니다: ${slots.size}개" } }

    /** 설정된(=구매 대상) 게임 수. Unset은 객체이므로 "non-null"이 아닌 "non-Unset"으로 센다. */
    val gameCount: Int get() = slots.count { it != Slot720.Unset }

    /** canonical JSON(필드 고정 순서) — 저장·해시 공용. */
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("revision", revision)
        put("fallback", fallback.name)
        put("slots", JSONArray().apply { slots.forEach { put(slotToJson(it)) } })
    }.toString()

    companion object {
        /** 현재 스키마 버전. 미지(미래) 버전은 휴리스틱 파싱 금지 → 안전 기본값으로 떨어뜨린다. */
        const val CURRENT_SCHEMA = 1

        /** 전부 Unset·기본 폴백·revision 0(구매 안 함). */
        fun empty(): NumberConfig720 =
            NumberConfig720(List(5) { Slot720.Unset }, FallbackPolicy.KEEP_GROUP_RANDOM, CURRENT_SCHEMA, 0L)

        /**
         * 저장 JSON → 설정. 손상·미지 스키마·범위 밖 값은 **거절/안전화**(설계 §3, §10):
         *  - 파싱 자체 실패 → null(호출부가 마이그레이션/기본값으로 폴백).
         *  - schemaVersion ≠ CURRENT → 휴리스틱 금지, [empty] 반환.
         *  - 개별 슬롯 손상(조∉1..5·자릿수≠6·digit∉0..9·미지 type) → 그 슬롯만 Unset으로.
         *  - 슬롯 수 5 초과는 잘라내고, 부족하면 Unset으로 채운다.
         */
        fun fromJson(json: String?): NumberConfig720? {
            if (json.isNullOrBlank()) return null
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val schema = obj.optInt("schemaVersion", -1)
            if (schema != CURRENT_SCHEMA) return empty()   // 미지 버전: 휴리스틱 파싱 금지
            val revision = obj.optLong("revision", 0L).coerceAtLeast(0L)
            val fallback = runCatching { FallbackPolicy.valueOf(obj.optString("fallback")) }
                .getOrDefault(FallbackPolicy.KEEP_GROUP_RANDOM)
            val arr = obj.optJSONArray("slots")
            val slots = MutableList<Slot720>(5) { Slot720.Unset }
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), 5)) {
                    slots[i] = arr.optJSONObject(i)?.let { slotFromJson(it) } ?: Slot720.Unset
                }
            }
            return NumberConfig720(slots, fallback, CURRENT_SCHEMA, revision)
        }

        /**
         * 구 설정 마이그레이션(설계 §10): number config 없고 기존 매수 N>0이면
         * **앞 N슬롯 FullAuto, 나머지 Unset**, 폴백=기본. 구매 동의는 미승계(현재 동의 개념 없음).
         */
        fun migrateFromAutoGames(autoGames: Int): NumberConfig720 {
            val n = autoGames.coerceIn(0, 5)
            val slots = List(5) { if (it < n) Slot720.FullAuto else Slot720.Unset }
            // revision 1: 마이그레이션도 "신규 저장"이라 0보다 커야 이후 단조 증가가 성립.
            return NumberConfig720(slots, FallbackPolicy.KEEP_GROUP_RANDOM, CURRENT_SCHEMA, 1L)
        }

        private fun slotToJson(slot: Slot720): JSONObject = JSONObject().apply {
            when (slot) {
                Slot720.Unset -> put("type", "unset")
                Slot720.FullAuto -> put("type", "fullAuto")
                is Slot720.SemiAuto -> { put("type", "semiAuto"); put("group", slot.group) }
                is Slot720.Manual -> {
                    put("type", "manual")
                    put("group", slot.group)
                    put("digits", JSONArray(slot.digits))
                }
            }
        }

        /** 슬롯 1개 파싱 — 손상 값은 null 반환(호출부가 Unset으로 안전화). */
        private fun slotFromJson(obj: JSONObject): Slot720? = when (obj.optString("type")) {
            "unset" -> Slot720.Unset
            "fullAuto" -> Slot720.FullAuto
            "semiAuto" -> runCatching { Slot720.SemiAuto(obj.getInt("group")) }.getOrNull()
            "manual" -> {
                val arr = obj.optJSONArray("digits")
                val digits = if (arr == null) null else (0 until arr.length()).map { arr.optInt(it, -1) }
                if (digits == null) null
                else runCatching { Slot720.Manual(obj.getInt("group"), digits) }.getOrNull()
            }
            else -> null
        }
    }
}
