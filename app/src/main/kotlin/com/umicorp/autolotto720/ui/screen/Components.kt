package com.umicorp.autolotto720.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umicorp.autolotto720.ui.theme.MotionSpecs
import com.umicorp.autolotto720.ui.theme.ctaGradient
import com.umicorp.autolotto720.ui.theme.heroGradient
import kotlinx.coroutines.delay

// 동행복권 연금복권720+ 공식 볼 팔레트 — 흰 볼 + 자리별 색 테두리(사이트 CSS --d-wf-1n~6n 실측값).
private val PensionDigitRings = listOf(
    Color(0xFFDE4C0E), // 십만
    Color(0xFFF08200), // 만
    Color(0xFFF3C00F), // 천
    Color(0xFF2A9BDB), // 백
    Color(0xFFA87AD7), // 십
    Color(0xFFADB0BA), // 일
)

/**
 * 조+6자리 번호 표시 (홈 당첨번호 · 내역 티켓 공유, Task11/13). 6자리는 동행복권 연금복권720+
 * 공식 컨셉의 원형 볼(흰 배경 + 자리별 색 테두리)로 렌더링한다(사용자 피드백 — 밋밋한 숫자 대체).
 * [joLabel]은 호출부가 이미 로컬라이즈해 넘긴다(`ui.util.localizedJoLabel`/`rankBonus` 등).
 */
@Composable
fun JoNumberDisplay(
    joLabel: String,
    number: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    matchedSuffix: Int = -1,   // -1=추첨 전(균일 렌더). 0..6=추첨완료 시 맞은 "뒤 k자리"(Rank720.matchedDigits).
    ballPopKey: Any? = null,   // null=정적(내역). 비null(예: 회차)=홈 등장 시 볼별 스태거 팝인(645 동일).
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(accent)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(joLabel, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            number.forEachIndexed { i, ch ->
                // 추첨완료(matchedSuffix>=0)면 맞은 뒤 k자리는 강조, 앞자리는 흐림. 추첨 전(-1)은 균일.
                val matched = matchedSuffix >= 0 && i >= number.length - matchedSuffix
                BallPop(index = i, key = ballPopKey) {
                    PensionBall(
                        digit = ch,
                        ring = PensionDigitRings.getOrElse(i) { PensionDigitRings.last() },
                        dimmed = matchedSuffix >= 0 && !matched,
                        matched = matched,
                    )
                }
            }
        }
    }
}

/** 볼 스태거 팝인 — [key]!=null이면 index 순서로 bouncy 스케일 등장(홈 당첨번호, 645 PopIn 동일). null=정적(내역). */
@Composable
private fun BallPop(index: Int, key: Any?, content: @Composable () -> Unit) {
    if (key == null) {
        content()
        return
    }
    val scale = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(MotionSpecs.staggerDelay(index).toLong())
        scale.animateTo(1f, MotionSpecs.bouncy())
    }
    Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) { content() }
}

/**
 * 연금복권 공식 스타일 볼 1개 — 흰 원 + 색 테두리 + 볼드 숫자.
 * [matched]=추첨에서 맞은 자리 → 링 색으로 채워 강조(흰 숫자). [dimmed]=안 맞은 자리 → alpha로 흐림.
 */
@Composable
private fun PensionBall(digit: Char, ring: Color, dimmed: Boolean = false, matched: Boolean = false) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (matched) ring else Color.White)
            .border(2.5.dp, ring, CircleShape)
            .alpha(if (dimmed) 0.32f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            digit.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            // 맞은(채워진) 볼은 흰 숫자로 대비, 그 외 테마 무관 고정 네이비(공식 사이트 검정 숫자 톤).
            color = if (matched) Color.White else Color(0xFF1E2D50),
        )
    }
}

/** 라운드 카드(넉넉한 여백) — 화면 공용. 프로스트 표면(surfaceContainerLowest). */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 홈 카운트다운용 유기적 "리퀴드" 히어로 (blue→teal→orange 그라디언트).
 * [title]은 상단 라벨(예: "제 1232회 추첨까지"), [content]에 큰 숫자 슬롯을 넣는다. 텍스트는 흰색 기준.
 */
@Composable
fun GradientBlobHero(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 좌우 비대칭 라운드 = 유기적 blob 느낌.
    val blob = RoundedCornerShape(topStart = 64.dp, topEnd = 44.dp, bottomStart = 44.dp, bottomEnd = 64.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, blob, clip = false)
            .clip(blob)
            .background(heroGradient())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

/**
 * 컬러 드렌치 카드 (예치금=틸, 자동구매=민트). [accent] 그라디언트로 꽉 채운다. 텍스트/아이콘 흰색 기준.
 * [onClick] 지정 시 클릭 가능(예: 예치금 카드 → 상세).
 */
@Composable
fun ColorDrenchedCard(
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(accent, Color.White, 0.12f), lerp(accent, Color.Black, 0.18f))))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
    ) {
        Column(content = content)
    }
}

/** 톤얼 카드 — [accent]를 옅게 깐 부드러운 컨테이너(색 드렌치 대신 은은한 강조). 텍스트는 onSurface. */
@Composable
fun TonalCard(
    accent: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(lerp(accent, MaterialTheme.colorScheme.surface, 0.82f))
            .border(1.dp, accent.copy(alpha = 0.22f), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** 광택 라운드 스퀘어 아이콘 타일 (설정 행 등). [tint] 그라디언트 위에 흰 아이콘. */
@Composable
fun GlossyIconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(size.value * 0.32f)
    Box(
        modifier = modifier
            .size(size)
            .shadow(2.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.28f), tint))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

/** StatusPill 톤: 확인 대기 / 낙첨 / 당첨. */
enum class PillTone { Pending, Lose, Win, Neutral }

/** 상태 토널 필. [tone]에 따라 색을 고른다. 텍스트는 스킴 파생. */
@Composable
fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        PillTone.Win -> scheme.secondaryContainer to scheme.onSecondaryContainer
        PillTone.Lose -> scheme.surfaceVariant to scheme.onSurfaceVariant
        PillTone.Pending -> scheme.primaryContainer to scheme.onPrimaryContainer
        PillTone.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 볼드 섹션 헤더 (이모지 헤더 대체). 화면이 넘기는 이모지도 그대로 표시. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.ExtraBold,
    )
}

/** 딥네이비 CTA 그라디언트 버튼 (핵심 액션: 번호 설정/구매). 라벨/아이콘 흰색. */
@Composable
fun CtaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val shape = CircleShape
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (enabled) 6.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(if (enabled) ctaGradient() else Brush.linearGradient(listOf(Color(0xFFB9B4A8), Color(0xFFB9B4A8))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 은은한 쿨 페이지 배경(상단 에메랄드 워시). 스크린 루트에 붙일 수 있는 헬퍼. */
@Composable
fun Modifier.creamPageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return this.background(
        Brush.verticalGradient(
            listOf(
                lerp(scheme.background, Color(0xFF0E9E57), 0.06f),
                scheme.background,
            ),
        ),
    )
}
