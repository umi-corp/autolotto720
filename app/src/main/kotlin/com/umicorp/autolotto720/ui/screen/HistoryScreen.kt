@file:OptIn(ExperimentalMaterial3Api::class)

package com.umicorp.autolotto720.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.umicorp.autolotto720.R
import com.umicorp.autolotto720.data.Rank720
import com.umicorp.autolotto720.data.Ticket720
import com.umicorp.autolotto720.ui.appViewModel
import com.umicorp.autolotto720.ui.theme.LgGold
import com.umicorp.autolotto720.ui.theme.LgTeal
import com.umicorp.autolotto720.ui.theme.MotionSpecs
import com.umicorp.autolotto720.ui.util.localizedJoLabel
import com.umicorp.autolotto720.ui.util.localizedPrize
import com.umicorp.autolotto720.ui.util.localizedRank
import com.umicorp.autolotto720.ui.vm.HistoryViewModel
import kotlinx.coroutines.delay

/**
 * 구매/당첨 기록 (Task13 — 연금복권720+ 티켓 단위 전환) — Lucky Gloss 리디자인.
 *
 * 로그인 상태면 dhlottery에서 최근 구매내역(티켓, 조+6자리) 라이브 조회. [Feature720.PURCHASE_ENABLED]
 * 가 꺼져 있는 동안은 [HistoryViewModel]이 항상 빈 목록을 받으므로 이 화면은 사실상 항상 빈 상태 —
 * 그 경우 "구매 기능 준비 중" 안내를 덧붙인다. 티켓 카드는 회차별 프로스트 카드(틴트 헤더 + StatusPill)
 * + 조+번호 표기. 시그니처 모션: 카드 스태거 등장(fade + rise, MotionSpecs.staggerDelay).
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val vm: HistoryViewModel = appViewModel()
    val tickets by vm.tickets.collectAsState()
    val loading by vm.loading.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val canLoadMore by vm.canLoadMore.collectAsState()
    val error by vm.error.collectAsState()
    val isLoggedIn by vm.isLoggedIn.collectAsState()

    Scaffold(
        modifier = modifier.creamPageBackground(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.historyTitle), fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = { vm.loadHistory() }, enabled = !loading) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cdRefresh),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { inner ->
        // 로딩→빈화면→목록 전환을 부드럽게(표준 Crossfade — 1.4.0엔 Expressive 컴포넌트 미수록).
        val phase = when {
            loading && tickets.isEmpty() -> 0
            tickets.isEmpty() -> 1
            else -> 2
        }
        Box(Modifier.fillMaxSize().padding(inner)) {
            Crossfade(targetState = phase, label = "historyState") { p ->
                when (p) {
                    0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    1 -> EmptyState(
                        isLoggedIn = isLoggedIn,
                        error = error,
                        // 최근 3개월이 비어도 이전 창에 내역이 있을 수 있음 → 빈 화면에서도 더 보기
                        canLoadMore = isLoggedIn && canLoadMore,
                        loadingMore = loadingMore,
                        onLoadMore = vm::loadMore,
                    )

                    else -> PullToRefreshBox(
                        isRefreshing = loading,
                        onRefresh = { vm.loadHistory() },
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(tickets) { i, item -> TicketCard(item, index = i) }
                            if (canLoadMore) {
                                item { LoadMoreButton(loading = loadingMore, onClick = vm::loadMore) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    isLoggedIn: Boolean,
    error: String?,
    canLoadMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlossyIconTile(Icons.Rounded.History, tint = LgTeal, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            if (isLoggedIn) stringResource(R.string.historyNoRecords)
            else stringResource(R.string.historyLoginToLoad),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        // 720 구매는 게이트 중(Feature720.PURCHASE_ENABLED=false) — 로그인 여부와 무관하게 항상 안내.
        Text(
            stringResource(R.string.historyComingSoonNote),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.historyLoadError, error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (canLoadMore) {
            Spacer(Modifier.height(8.dp))
            LoadMoreButton(loading = loadingMore, onClick = onLoadMore)
        }
    }
}

/** 목록 하단/빈 화면 공용 "더 보기" — 로드 중엔 같은 자리에서 스피너로 교체. */
@Composable
private fun LoadMoreButton(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        } else {
            TextButton(onClick = onClick) {
                Text(
                    stringResource(R.string.historyLoadMore),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

/** 티켓 1건 카드: 헤더(회차+날짜+상태) · 조+번호 · (당첨 시) 당첨금/연금 푸터. */
@Composable
private fun TicketCard(item: Ticket720, index: Int) {
    val rank = item.rank
    val pending = !item.checked || rank == null || rank == Rank720.PENDING
    val noWin = rank == Rank720.NONE
    val winner = !pending && !noWin
    val dateStr = "%04d-%02d-%02d".format(item.purchaseDate.year, item.purchaseDate.monthValue, item.purchaseDate.dayOfMonth)

    // 시그니처 모션: 스태거 등장 (fade + rise). ponytail: 스크롤로 재진입 시 재생됨 — 짧고 은은해 허용.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MotionSpecs.staggerDelay(index).toLong())
        shown = true
    }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = MotionSpecs.emphasized(),
        label = "cardAppear",
    )

    val shape = MaterialTheme.shapes.large
    // 헤더 틴트: 당첨=골드(진하게), 대기=골드(옅게), 낙첨=틸 민트(뮤트).
    val headerAccent = if (noWin) LgTeal else LgGold
    val headerBg = lerp(headerAccent, MaterialTheme.colorScheme.surfaceContainerLowest, if (winner) 0.70f else 0.86f)

    SectionCard(
        modifier = Modifier
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 24.dp.toPx()
            }
            .then(if (winner) Modifier.border(2.dp, LgGold, shape) else Modifier),
        contentPadding = PaddingValues(0.dp),
    ) {
        // 헤더: 제 {n}회 + 날짜 + StatusPill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.roundLabel, item.round),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    dateStr,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                pending -> StatusPill(stringResource(R.string.rankPendingDraw), PillTone.Pending)
                winner -> StatusPill(
                    stringResource(R.string.rankWithEmoji, localizedRank(rank!!)),
                    PillTone.Win,
                )
                else -> StatusPill(stringResource(R.string.rankNoWin720), PillTone.Lose)
            }
        }

        // 본문: 조 + 6자리 번호
        Column(Modifier.padding(16.dp)) {
            JoNumberDisplay(joLabel = localizedJoLabel(item.jo), number = item.number)
        }

        // 푸터: 당첨금(3~7등) / 연금 문구(1·2등·보너스) — 골드 밴드
        if (winner) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(lerp(LgGold, MaterialTheme.colorScheme.surfaceContainerLowest, 0.70f))
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    localizedPrize(rank!!, item.prize),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
