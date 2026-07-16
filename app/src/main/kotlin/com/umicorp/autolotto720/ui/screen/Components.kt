package com.umicorp.autolotto720.ui.screen

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/**
 * 조+6자리 번호 표시 (홈 당첨번호 · 내역 티켓 공유, Task11/13). 원 [n]볼 그리드(LottoBall, 645
 * 1~45)를 대체한다 — 720은 "조(1~5) + 6자리 문자열" 표기라 볼 그리드 개념이 없다.
 * [joLabel]은 호출부가 이미 로컬라이즈해 넘긴다(`ui.util.localizedJoLabel`/`rankBonus` 등).
 */
@Composable
fun JoNumberDisplay(
    joLabel: String,
    number: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
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
        Text(
            number,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
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

/** 은은한 크림 페이지 배경(부드러운 피치 blob 느낌). 스크린 루트에 붙일 수 있는 헬퍼. */
@Composable
fun Modifier.creamPageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return this.background(
        Brush.verticalGradient(
            listOf(
                lerp(scheme.background, Color(0xFFF7D9B8), 0.10f),
                scheme.background,
            ),
        ),
    )
}
