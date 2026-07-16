@file:OptIn(ExperimentalMaterial3Api::class)

package com.umicorp.autolotto720.ui.screen

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.umicorp.autolotto720.R
import com.umicorp.autolotto720.dhlottery.Feature720
import com.umicorp.autolotto720.ui.appViewModel
import com.umicorp.autolotto720.ui.theme.LgAmber
import com.umicorp.autolotto720.ui.theme.LgTeal
import com.umicorp.autolotto720.ui.vm.PurchaseSetupViewModel

/**
 * 구매조건 설정 (원 `NumberScreen`의 1~45 그리드 대체, Task12) — 연금복권720+ v1: 자동배정 전용.
 *
 * 720 온라인 구매는 클라이언트 사이드 AES 계약 미확보로 [Feature720.PURCHASE_ENABLED]=false —
 * 이 화면은 배너로 "준비 중"을 명시하고 on/off 스위치를 잠근다(조용한 무동작 대신 명시적 게이트).
 * 매수(1~5) 설정은 게이트와 무관하게 저장해둔다 — 게이트가 열리면 [AutoPurchaseWorker]가 그대로 읽는다.
 * 조/번호 직접 지정(수동)은 추후 지원 — 자리만 안내 카드로 예약해둔다.
 */
@Composable
fun PurchaseSetupScreen(modifier: Modifier = Modifier) {
    val vm: PurchaseSetupViewModel = appViewModel()
    val autoEnabled by vm.autoEnabled.collectAsState()
    val autoGamesStored by vm.autoGames.collectAsState()
    val games = if (autoGamesStored <= 0) 1 else autoGamesStored.coerceIn(1, 5)

    Scaffold(
        modifier = modifier.fillMaxSize().creamPageBackground(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.numberSetupTitle),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            TonalCard(accent = LgAmber, contentPadding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = LgAmber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            stringResource(R.string.purchaseComingSoonTitle),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.purchaseComingSoonDesc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // 자동 구매 on/off — 게이트가 닫힌 동안은 잠금(조용한 무동작 대신 명시적 비활성화).
            SectionCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { GlossyIconTile(icon = Icons.Rounded.ConfirmationNumber, tint = LgTeal) },
                    headlineContent = { Text(stringResource(R.string.settingEnableAutoPurchase), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.purchaseComingSoonDesc)) },
                    trailingContent = {
                        Switch(
                            checked = autoEnabled,
                            onCheckedChange = if (Feature720.PURCHASE_ENABLED) {
                                { v -> vm.setAutoEnabled(v) }
                            } else {
                                null
                            },
                            enabled = Feature720.PURCHASE_ENABLED,
                        )
                    },
                )
            }
            Spacer(Modifier.height(20.dp))

            // 매수(1~5) — 게이트와 무관하게 저장(게이트가 열리면 AutoPurchaseWorker가 그대로 읽는다).
            SectionCard {
                Text(
                    stringResource(R.string.purchaseGamesCountLabel),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.purchaseGamesCountHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = { if (games > 1) vm.setAutoGames(games - 1) },
                        enabled = games > 1,
                    ) { Icon(Icons.Rounded.Remove, contentDescription = null) }
                    Text(
                        games.toString(),
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    FilledTonalIconButton(
                        onClick = { if (games < 5) vm.setAutoGames(games + 1) },
                        enabled = games < 5,
                    ) { Icon(Icons.Rounded.Add, contentDescription = null) }
                }
            }
            Spacer(Modifier.height(20.dp))

            // 조/번호 직접 지정 — v1 미지원 안내 자리.
            TonalCard(accent = LgTeal, contentPadding = PaddingValues(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    GlossyIconTile(icon = Icons.Rounded.ConfirmationNumber, tint = LgTeal, size = 48.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.purchaseManualComingSoon),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
