package com.umicorp.autolotto720.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 720 "Neon Vault" 디자인 토큰 (Material 3 Expressive). 벽지 dynamic color 대신 고정 스킴.
 *
 * autolotto(부모)와의 구분을 위해 브랜드 정체성을 앱 아이콘(딥네이비 배경 + 네온 그린 슬롯 "720")에
 * 맞춰 재정의: 캔버스는 웜 크림 대신 쿨 아이스, primary는 에메랄드 그린, 네이비를 구조색으로,
 * 골드는 당첨 강조로만 유지. (autolotto = 크림 #F6F1E7 + 틸 #0F8B8D 와 한눈에 구분됨.)
 * 변수명은 하위 호출부 호환을 위해 유지(값·역할만 변경).
 */

// 캔버스 / 표면 (웜 크림 → 쿨 아이스)
val LgCream = Color(0xFFEEF2F7)          // 배경: 쿨 아이스(네이비 언더톤)
val LgSurface = Color(0xFFFFFFFF)        // 프로스트 카드
val LgSurfaceLow = Color(0xFFF6F9FD)     // 낮은 표면
val LgSurfaceVariant = Color(0xFFDEE5F0) // 중립 표면

// 브랜드 (아이콘 = 네온 그린 + 딥네이비)
val LgTeal = Color(0xFF0E9E57)           // primary: 에메랄드 그린(아이콘 화살표)
val LgTealInk = Color(0xFF0A5E37)        // deep emerald accent(primary 파생)
val LgNeon = Color(0xFF26D967)           // 네온 그린 글로우(그라디언트 액센트, 텍스트 배경 금지)
val LgNavy = Color(0xFF1E2D50)           // 아이콘 카드 네이비(구조/보조색)
val LgNavyDeep = Color(0xFF0E1330)       // 아이콘 코너 딥네이비(스플래시/콜드스타트)
val LgGold = Color(0xFFF5B301)           // 당첨 강조(tertiary)
val LgGreen = Color(0xFF22C55E)          // 밝은 그린(보조 tertiary 컨테이너용)
val LgAmber = Color(0xFFE9930B)          // warning

// 잉크 / 텍스트
val LgInk = Color(0xFF16233F)            // onSurface / 헤딩·본문 딥네이비
val LgMuted = Color(0xFF5C6B85)          // 보조 텍스트(쿨톤)
val LgOutline = Color(0xFFC7D0E0)        // 쿨 톤 외곽선
val LgError = Color(0xFFBA1A1A)

// CTA 딥네이비 그라디언트 (execBuy 등 핵심 버튼) — 아이콘 네이비와 동일 계열
val LgCtaNavyStart = Color(0xFF16233F)
val LgCtaNavyEnd = Color(0xFF24406B)

/** 딥네이비 CTA 그라디언트 브러시 (좌상→우하). onPrimary=흰색. */
fun ctaGradient(): Brush = Brush.linearGradient(listOf(LgCtaNavyStart, LgCtaNavyEnd))

/** 홈 카운트다운 히어로용 그라디언트 (navy→emerald→neon) — 아이콘 톤. autolotto의 오렌지 제거. */
fun heroGradient(): Brush = Brush.linearGradient(
    0.0f to LgNavy,
    0.5f to LgTeal,
    1.0f to LgNeon,
)

val LightColors = lightColorScheme(
    primary = LgTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEACD),
    onPrimaryContainer = LgTealInk,
    secondary = LgNavy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7DEF0),
    onSecondaryContainer = Color(0xFF12203F),
    tertiary = LgGold,
    onTertiary = Color(0xFF3A2E00),
    tertiaryContainer = Color(0xFFFDECC0),
    onTertiaryContainer = Color(0xFF3A2E00),
    error = LgError,
    onError = Color.White,
    background = LgCream,
    onBackground = LgInk,
    surface = LgCream,
    onSurface = LgInk,
    surfaceVariant = LgSurfaceVariant,
    onSurfaceVariant = LgMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LgSurfaceLow,
    surfaceContainer = Color(0xFFE7ECF5),
    surfaceContainerHigh = Color(0xFFE0E7F2),
    surfaceContainerHighest = Color(0xFFD8E1EF),
    outline = LgOutline,
    outlineVariant = Color(0xFFDBE2EE),
)

// 다크: Neon Vault 팔레트를 아이콘 네이비 기반으로 어둡게 변주.
val DarkColors = darkColorScheme(
    primary = Color(0xFF5FE39A),
    onPrimary = Color(0xFF00391D),
    primaryContainer = LgTealInk,
    onPrimaryContainer = Color(0xFFBFEACD),
    secondary = Color(0xFFAFC0EC),
    onSecondary = Color(0xFF12203F),
    tertiary = LgGold,
    onTertiary = Color(0xFF3A2E00),
    background = LgNavyDeep,
    onBackground = Color(0xFFE6EAF3),
    surface = LgNavyDeep,
    onSurface = Color(0xFFE6EAF3),
    surfaceVariant = Color(0xFF2A3350),
    onSurfaceVariant = Color(0xFFB9C2D8),
    outline = Color(0xFF5D6683),
)
