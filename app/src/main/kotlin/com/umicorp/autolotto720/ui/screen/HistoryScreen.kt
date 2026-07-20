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
import com.umicorp.autolotto720.data.WinningNumbers720
import com.umicorp.autolotto720.data.matchedDigits
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
    val winningNumbers by vm.winningNumbers.collectAsState()

    // 회차별 그룹핑(autolotto 645 참조): 평탄화된 티켓을 회차로 묶어 카드 1개 안에 조+번호 행들로.
    // groupBy는 LinkedHashMap이라 dhlottery 최신순(첫 등장 회차 먼저)을 그대로 보존한다.
    val rounds = remember(tickets) { tickets.groupBy { it.round }.map { it.key to it.value } }

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
                            itemsIndexed(rounds) { i, g ->
                                RoundCard(round = g.first, tickets = g.second, index = i, winning = winningNumbers[g.first])
                            }
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

/**
 * 회차 1건 카드(autolotto 645 HistoryCard 대응): 헤더(회차+날짜+집계 상태) · 티켓별 조+번호 행
 * (추첨완료 시 행별 등수 필) · (당첨 시) 최고 등수 당첨금/연금 푸터.
 *
 * 720은 한 회차 티켓들이 동시 추첨되므로 미추첨/추첨완료가 균일 — 집계 상태를 헤더에 표시하고,
 * 조별 등수는 645의 게임 A~E 필처럼 행 우측에 붙인다.
 */
@Composable
private fun RoundCard(round: Int, tickets: List<Ticket720>, index: Int, winning: WinningNumbers720? = null) {
    fun pendingOf(t: Ticket720) = !t.checked || t.rank == null || t.rank == Rank720.PENDING
    val roundPending = tickets.any { pendingOf(it) }
    val winners = tickets.filter { !pendingOf(it) && it.rank != Rank720.NONE }
    val bestWinner = winners.minByOrNull { it.rank!!.ordinal }  // ordinal 오름차순 = 상위 등수
    val winner = bestWinner != null
    val noWin = !roundPending && !winner
    val date = tickets.first().purchaseDate
    val dateStr = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

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
        // 헤더: 제 {n}회 + 날짜 + 집계 StatusPill (+ 추첨완료 시 회차 당첨번호 미니볼 — 645 미니볼 포트)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.roundLabel, round),
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
                    roundPending -> StatusPill(stringResource(R.string.rankPendingDraw), PillTone.Pending)
                    winner -> StatusPill(
                        stringResource(R.string.rankWithEmoji, localizedRank(bestWinner!!.rank!!)),
                        PillTone.Win,
                    )
                    else -> StatusPill(stringResource(R.string.rankNoWin720), PillTone.Lose)
                }
            }
            // 회차 당첨번호(1등 조+번호 / 보너스) — 홈 WinningNumbers와 같은 2줄 구성을 미니(22dp)로.
            // 아래 티켓 행(30dp)의 강조 자리와 색으로 대조 가능(645 사용자 피드백). 조회 실패면 생략.
            // roundPending 게이트: 일부 티켓만 확인된 회차에서 '확인 대기' 필과 당첨번호가 동시에
            // 뜨는 모순 방지(crosscheck R1 F5) — 전 티켓 확인 후에만 표시.
            if (!roundPending) winning?.let { w ->
                Spacer(Modifier.height(10.dp))
                JoNumberDisplay(joLabel = localizedJoLabel(w.jo), number = w.number, ballSize = 22.dp)
                Spacer(Modifier.height(6.dp))
                JoNumberDisplay(
                    joLabel = stringResource(R.string.rankBonus),
                    number = w.bonusNumber,
                    accent = LgGold,
                    ballSize = 22.dp,
                )
            }
        }

        // 본문: 조 + 6자리 번호 (+ 추첨완료 시 맞은 뒤자리 강조·행별 등수 필). 티켓은 조 뱃지로 식별되므로
        // 행 letter 라벨(A~)은 두지 않는다 — 세트(동일번호 5매)·5매 초과분에서 설정 슬롯과 무관해 혼란만 준다.
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tickets.forEach { t ->
                val drawn = !pendingOf(t)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 추첨완료면 맞은 뒤 k자리 강조(나머지 흐림); 미추첨이면 균일(-1).
                    JoNumberDisplay(
                        joLabel = localizedJoLabel(t.jo),
                        number = t.number,
                        matchedSuffix = if (drawn) t.rank!!.matchedDigits else -1,
                    )
                    Spacer(Modifier.weight(1f))
                    // 미추첨이면 헤더의 '확인 대기'가 대표하므로 행별 필 생략(클러터 방지). ponytail: 좁은 기기
                    // 당첨행에서 볼+필이 겹치면 볼 크기 축소 필요할 수 있음 — 당첨은 드물어 이월.
                    if (drawn) {
                        val r = t.rank!!
                        if (r == Rank720.NONE) StatusPill(stringResource(R.string.rankNoWin720), PillTone.Lose)
                        else StatusPill(stringResource(R.string.rankWithEmoji, localizedRank(r)), PillTone.Win)
                    }
                }
            }
        }

        // 푸터: 최고 등수 당첨금(3~7등) / 연금 문구(1·2등·보너스) — 골드 밴드
        if (winner) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(lerp(LgGold, MaterialTheme.colorScheme.surfaceContainerLowest, 0.70f))
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    localizedPrize(bestWinner!!.rank!!, bestWinner.prize),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
