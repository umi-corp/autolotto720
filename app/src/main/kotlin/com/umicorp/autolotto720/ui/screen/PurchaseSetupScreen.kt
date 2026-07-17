@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.umicorp.autolotto720.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umicorp.autolotto720.PurchaseFailure
import com.umicorp.autolotto720.R
import com.umicorp.autolotto720.classifyPurchaseFailure
import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.NumberConfig720
import com.umicorp.autolotto720.data.Slot720
import com.umicorp.autolotto720.dhlottery.Feature720
import com.umicorp.autolotto720.ui.appViewModel
import com.umicorp.autolotto720.ui.theme.LgAmber
import com.umicorp.autolotto720.ui.theme.LgTeal
import com.umicorp.autolotto720.ui.theme.MotionSpecs
import com.umicorp.autolotto720.ui.util.formatNumber
import com.umicorp.autolotto720.ui.util.localizedJoLabel
import com.umicorp.autolotto720.ui.vm.PurchaseSetupViewModel
import com.umicorp.autolotto720.ui.vm.PurchaseSetupViewModel.InstantState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 조 선택 센티넬 — 칩 [자동][1][2][3][4][5]의 상태값. 1..5는 고정 조.
private const val GROUP_NONE = -1   // 아무 조도 안 고른 draft(=Unset 편집 시작)
private const val GROUP_AUTO = 0    // [자동] = FullAuto (번호도 자동 강제)

// committed 확정본이 프로세스 사망(회전·강제종료)에도 살아남게 rememberSaveable로 승격(확정≠저장, 영속 store와 별개).
// 기존 NumberConfig720 JSON 직렬화를 재사용 — 슬롯 5개만 실어 나른다(fallback/revision은 별도 saveable).
private val SlotListSaver: Saver<SnapshotStateList<Slot720>, String> = Saver(
    save = { NumberConfig720(it.toList(), FallbackPolicy.REASSIGN_ALL, NumberConfig720.CURRENT_SCHEMA, 0L).toJson() },
    restore = { json ->
        val slots = NumberConfig720.fromJson(json)?.slots ?: List(5) { Slot720.Unset }
        mutableStateListOf(*slots.toTypedArray())
    },
)

// draft 자리값(미입력=null)을 -1로 인코딩해 Bundle에 싣는다.
private val DigitsSaver: Saver<SnapshotStateList<Int?>, ArrayList<Int>> = Saver(
    save = { ArrayList(it.map { d -> d ?: -1 }) },
    restore = { saved -> mutableStateListOf(*saved.map { if (it < 0) null else it }.toTypedArray()) },
)

/**
 * "번호" 탭 — 수동 게임 설정 (설계 §4~§5). 원 자동배정 자리표시를 대체한다.
 *
 * 5슬롯 sealed 상태머신(§3 [Slot720]) + draft/committed 분리(§4). 슬롯탭·전부자동(주사위 텀블)·
 * 조 선택기·자동/수동 토글·자리별 슬롯릴·게임요약·폴백정책·저장 색전환을 부모(로또) 번호 탭에서 차용하되
 * 입력 단위를 연금 규격(조 1~5 + 6자리)으로 교체했다.
 *
 * **저장 ≠ 구매**: 설정 저장은 예약 자동구매를 무장하지 않는다(§9) — 자동구매 on/off 토글은 설정 탭에만 둔다.
 * 저장 버튼 아래 **즉시 구매 CTA**만이 사용자가 이 화면에서 실결제를 개시하는 유일한 경로이며, 확인
 * 다이얼로그(원탭 결제 금지)·판매시간/회차 게이트·구매 Mutex를 거친다(645 docs/DESIGN-instant-purchase.md 포트).
 */
@Composable
fun PurchaseSetupScreen(modifier: Modifier = Modifier) {
    val vm: PurchaseSetupViewModel = appViewModel()
    val config by vm.config.collectAsState()
    val isLoggedIn by vm.isLoggedIn.collectAsState()
    val lastRound by vm.lastPurchasedRound.collectAsState()
    val instantState by vm.instantState.collectAsState()

    // committed: 저장/구매 대상 5슬롯(§3 타입). draft: 현재 슬롯의 미확정 편집 상태.
    // rememberSaveable — 확정본·draft가 프로세스 사망에도 살아남는다(확정≠저장, 영속 store와 별개).
    val committed = rememberSaveable(saver = SlotListSaver) {
        mutableStateListOf<Slot720>(Slot720.Unset, Slot720.Unset, Slot720.Unset, Slot720.Unset, Slot720.Unset)
    }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var currentSlot by rememberSaveable { mutableStateOf(0) }
    var groupSel by rememberSaveable { mutableStateOf(GROUP_NONE) }
    var numberManual by rememberSaveable { mutableStateOf(false) }
    val digits = rememberSaveable(saver = DigitsSaver) {
        mutableStateListOf<Int?>(null, null, null, null, null, null)  // nullable = 미입력(≠0)
    }
    var activeDigit by rememberSaveable { mutableStateOf(0) }
    var fallback by rememberSaveable { mutableStateOf(FallbackPolicy.REASSIGN_ALL) }  // enum=Serializable → autoSaver
    var saved by rememberSaveable { mutableStateOf(true) }
    var setMode by rememberSaveable { mutableStateOf(false) }  // 모든조 세트(슬롯 편집 없이 5조 동일번호 자동배정)

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val view = LocalView.current  // 확정 시 TalkBack 전체 리드백(§5-4 F14)

    var rollingAllAuto by remember { mutableStateOf(false) }
    var showAllAutoDialog by remember { mutableStateOf(false) }
    var showApplyAllDialog by remember { mutableStateOf(false) }

    fun setDigits(vals: List<Int?>) { for (i in 0..5) digits[i] = vals.getOrNull(i) }

    // 슬롯 진입: draft ← committed 동기화(§4). 슬롯 전환 시 미확정 draft는 이 재동기화로 자동 폐기된다.
    fun syncDraftToSlot(slot: Int) {
        when (val s = committed[slot]) {
            Slot720.Unset -> { groupSel = GROUP_NONE; numberManual = false; setDigits(List(6) { null }) }
            Slot720.FullAuto -> { groupSel = GROUP_AUTO; numberManual = false; setDigits(List(6) { null }) }
            is Slot720.SemiAuto -> { groupSel = s.group; numberManual = false; setDigits(List(6) { null }) }
            is Slot720.Manual -> { groupSel = s.group; numberManual = true; setDigits(s.digits) }
        }
        activeDigit = digits.indexOfFirst { it == null }.let { if (it < 0) 0 else it }
    }

    // 저장된 config를 1회 하이드레이트(§4). revision>0이면 실제 저장본 → 잠근다(빈 all-Unset과 구분).
    // ponytail: config 이중 방출(empty→실값) 경합은 initialized 가드로 무해화 — 실값 도착 전 편집하면 그 편집이
    //   초기 하이드레이트에 덮일 여지가 있으나, 스플래시가 loadSettings 완료 후 화면을 띄워 실질 무위험.
    // ponytail: 로그아웃 시 initialized가 리셋 안 됨 — 다계정 전환 시 이전 committed가 남을 수 있으나, 720은
    //   계정별 설정 분리가 없어(단일 저장) 저위험. 필요 시 로그인 상태를 key에 물려 재하이드레이트.
    LaunchedEffect(config) {
        if (!initialized) {
            for (i in 0..4) committed[i] = config.slots.getOrElse(i) { Slot720.Unset }
            fallback = config.fallback
            setMode = config.setMode
            currentSlot = (0..4).firstOrNull { committed[it] == Slot720.Unset } ?: 0
            syncDraftToSlot(currentSlot)
            if (config.revision > 0L || config.slots.any { it != Slot720.Unset }) initialized = true
        }
    }

    // 즉시 구매: 저장된 게임 0개면 설정·저장 안내(이미 이 화면이므로 이동 없이 스낵바).
    // 스낵바는 scope로 분리 — dismiss가 상태를 바꿔 이 이펙트가 재시작(=취소)돼도 표시가 살아남는다.
    LaunchedEffect(instantState) {
        if (instantState is PurchaseSetupViewModel.InstantState.NeedsSetup) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.instantNeedsSetup)) }
            vm.dismissInstant()
        }
    }
    InstantPurchaseDialogs(instantState, vm)

    // draft → committed 매핑(§4). null이면 확정 불가(수동 6자리 미완성 등).
    fun draftToSlot(): Slot720? = when {
        groupSel == GROUP_AUTO -> Slot720.FullAuto
        groupSel in 1..5 && !numberManual -> Slot720.SemiAuto(groupSel)
        groupSel in 1..5 && numberManual && digits.all { it != null } -> Slot720.Manual(groupSel, digits.map { it!! })
        else -> null
    }

    val confirmPop = remember { Animatable(1f) }

    fun confirmSlot() {
        val slot = draftToSlot() ?: return
        // 확정 시점 동일 조+동일 6자리 수동 중복 차단(§6 F13).
        if (slot is Slot720.Manual && committed.withIndex().any { (i, s) ->
                i != currentSlot && s is Slot720.Manual && s.group == slot.group && s.digits == slot.digits
            }
        ) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.duplicateManualError)) }
            return
        }
        committed[currentSlot] = slot
        saved = false
        // 다음 "미설정" 슬롯으로 이동(§4) — 이미 채워진 슬롯은 건너뛴다.
        currentSlot = ((currentSlot + 1)..4).firstOrNull { committed[it] == Slot720.Unset } ?: currentSlot
        // 확정 직전 전체 리드백(§5-4 F14) — 수동 확정본만 자릿값을 읽어준다.
        if (slot is Slot720.Manual) {
            view.announceForAccessibility(
                context.getString(R.string.cdConfirmReadback, slot.group, slot.digits.joinToString(" ")),
            )
        }
        syncDraftToSlot(currentSlot)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { confirmPop.snapTo(0.92f); confirmPop.animateTo(1f, MotionSpecs.bouncy()) }
    }

    // 파괴적 조작(전부자동·전 조 적용) 공통: 이전 committed 스냅샷 + undo 스낵바로 원복(§5).
    fun applyDestructive(newSlots: List<Slot720>, msg: String) {
        val prev = committed.toList()
        for (i in 0..4) committed[i] = newSlots[i]
        saved = false
        syncDraftToSlot(currentSlot)
        scope.launch {
            val res = snackbar.showSnackbar(
                msg,
                actionLabel = context.getString(R.string.snackbarUndoAction),
                duration = SnackbarDuration.Short,
            )
            if (res == SnackbarResult.ActionPerformed) {
                for (i in 0..4) committed[i] = prev[i]
                saved = false
                syncDraftToSlot(currentSlot)
            }
        }
    }

    // 전부자동 가드: 수동/반자동 슬롯이 있으면 확인(FullAuto/Unset은 덮어써도 무손실 — 같은 완전자동/미설정).
    val needsAllAutoGuard = committed.any { it is Slot720.SemiAuto || it is Slot720.Manual }
    // 전 조 적용 가드: 사용자가 지정한 FullAuto도 덮어쓰므로 "비-Unset이 하나라도" 있으면 확인(더 엄격).
    val needsApplyAllGuard = committed.any { it != Slot720.Unset }
    val allFullAuto = committed.all { it == Slot720.FullAuto }

    fun triggerAllAuto() { if (needsAllAutoGuard) showAllAutoDialog = true else rollingAllAuto = true }

    // 전부자동: 주사위 텀블(~0.95s) 후 5슬롯 FullAuto 적용 + undo. 연출 중 입력 잠금.
    LaunchedEffect(rollingAllAuto) {
        if (rollingAllAuto) {
            delay(950)
            applyDestructive(List(5) { Slot720.FullAuto }, context.getString(R.string.snackbarAllAutoApplied))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            rollingAllAuto = false
        }
    }

    val manualComplete = numberManual && groupSel in 1..5 && digits.all { it != null }
    fun doApplyToAll() {
        val ds = digits.map { it ?: return }
        applyDestructive(List(5) { Slot720.Manual(it + 1, ds) }, context.getString(R.string.snackbarAppliedToAll))
    }
    fun triggerApplyToAll() { if (needsApplyAllGuard) showApplyAllDialog = true else doApplyToAll() }

    val locked = rollingAllAuto  // 연출 중 전체 입력·저장 잠금(§5-2 F11).

    Box(modifier) {
        Scaffold(
            modifier = Modifier.fillMaxSize().creamPageBackground(),
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
            snackbarHost = { SnackbarHost(snackbar) },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                TonalCard(accent = LgTeal, contentPadding = PaddingValues(16.dp)) {
                    Text(
                        stringResource(R.string.numberSetupInstruction),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))

                // 모드 세그먼트 — [슬롯별] / [모든조 세트]. 세트 토글은 즉시 저장(revision 증가) —
                // 세트는 조 편집이 없어 토글 자체가 확정이다. saved 색은 committed+세트가 영속됨을 반영.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !setMode,
                        enabled = !locked,
                        onClick = {
                            if (setMode) { setMode = false; vm.saveConfig(committed.toList(), fallback, false); saved = true }
                        },
                        label = { Text(stringResource(R.string.slotModeLabel), fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = setMode,
                        enabled = !locked,
                        onClick = {
                            if (!setMode) { setMode = true; vm.saveConfig(committed.toList(), fallback, true); saved = true }
                        },
                        label = { Text(stringResource(R.string.setModeLabel), fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))

                if (!setMode) {
                SlotTabs(
                    committed = committed,
                    currentSlot = currentSlot,
                    onSelect = { if (!locked && it != currentSlot) { currentSlot = it; syncDraftToSlot(it) } },
                )
                Spacer(Modifier.height(4.dp))

                // 전부 자동
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { triggerAllAuto() }, enabled = !allFullAuto && !locked) {
                        Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.buttonAllAuto), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))

                // 조 선택기 — [자동][1][2][3][4][5]. [자동] 선택 시 번호 자동 강제(§3 불변식).
                Text(
                    stringResource(R.string.groupSelectorLabel),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                GroupSelector(
                    groupSel = groupSel,
                    enabled = !locked,
                    onSelect = { sel ->
                        groupSel = sel
                        // 조 자동 → 번호 수동 불가(불변식). 고정 조 선택은 항상 수동부터 시작 —
                        // 자동은 유저가 원할 때 토글(사용자 피드백, 조를 바꿀 때마다 수동 리셋).
                        numberManual = sel != GROUP_AUTO
                    },
                )
                Spacer(Modifier.height(16.dp))

                // 번호 영역 — 조=고정일 때만 자동/수동 토글(§5-4).
                when {
                    groupSel == GROUP_NONE -> HintCard(stringResource(R.string.pickGroupPrompt))
                    groupSel == GROUP_AUTO -> InfoCard(
                        title = stringResource(R.string.fullAutoCardTitle),
                        desc = stringResource(R.string.fullAutoCardDesc),
                    )
                    else -> {
                        NumberModeToggle(numberManual = numberManual, enabled = !locked, onChange = { numberManual = it })
                        Spacer(Modifier.height(16.dp))
                        if (!numberManual) {
                            InfoCard(
                                title = stringResource(R.string.semiAutoCardTitle),
                                desc = stringResource(R.string.semiAutoCardDesc),
                            )
                        } else {
                            DigitInput(
                                group = groupSel,
                                digits = digits,
                                activeDigit = activeDigit,
                                enabled = !locked,
                                onSelectCell = { activeDigit = it },
                                onKey = { n ->
                                    digits[activeDigit] = n
                                    activeDigit = ((activeDigit + 1)..5).firstOrNull { digits[it] == null } ?: activeDigit
                                },
                                onBackspace = {
                                    // 현재 칸이 비어 있으면 앞 칸으로 이동하며 지운다 — 연속 삭제(사용자 피드백).
                                    if (digits[activeDigit] != null) {
                                        digits[activeDigit] = null
                                    } else if (activeDigit > 0) {
                                        activeDigit -= 1
                                        digits[activeDigit] = null
                                    }
                                },
                                applyEnabled = manualComplete && !locked,
                                onApplyAll = { triggerApplyToAll() },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))

                // 확정("이 게임 적용") / 초기화 — 확정은 저장과 별개(§4).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { committed[currentSlot] = Slot720.Unset; saved = false; syncDraftToSlot(currentSlot) },
                        enabled = !locked,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = CircleShape,
                    ) { Text(stringResource(R.string.buttonReset), fontWeight = FontWeight.SemiBold) }
                    CtaButton(
                        onClick = { confirmSlot() },
                        enabled = draftToSlot() != null && !locked,
                        modifier = Modifier.weight(2f).graphicsLayer {
                            scaleX = confirmPop.value; scaleY = confirmPop.value
                        },
                    ) {
                        Text(
                            stringResource(R.string.buttonConfirmGame, ('A' + currentSlot).toString()),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                GameSummary(
                    committed = committed,
                    currentSlot = currentSlot,
                    onRemove = { i ->
                        committed[i] = Slot720.Unset
                        saved = false
                        if (i == currentSlot) syncDraftToSlot(currentSlot)
                    },
                )
                Spacer(Modifier.height(16.dp))

                // 폴백 정책(§6) — 점유 실패 시 조 유지 재배정(기본) / 포기.
                FallbackSelector(
                    fallback = fallback,
                    enabled = !locked,
                    onSelect = { if (it != fallback) { fallback = it; saved = false } },
                )
                Spacer(Modifier.height(16.dp))
                } else {
                    // 모든조 세트 — 슬롯 편집 없이 5개 조 동일번호 자동배정 1매씩(§ 세트 모드).
                    InfoCard(
                        title = stringResource(R.string.setModeLabel),
                        desc = stringResource(R.string.setModeInfo),
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // 저장 — committed + 폴백 영속. 저장/미저장 색 전환. 저장이 구매를 무장하지 않음(§9).
                // 저장 완료 색은 645와 동일한 그린 고정 — 720 테마 tertiary(LgGold)는 당첨 강조용이라 쓰지 않는다.
                val dark = isSystemInDarkTheme()
                val saveContainer by animateColorAsState(
                    if (saved) (if (dark) Color(0xFF9BD97A) else Color(0xFF6FBF3B)) else MaterialTheme.colorScheme.primary,
                    label = "saveContainer",
                )
                val saveContent by animateColorAsState(
                    if (saved) (if (dark) Color(0xFF10300A) else Color.White) else MaterialTheme.colorScheme.onPrimary,
                    label = "saveContent",
                )
                val gameCount = if (setMode) 5 else committed.count { it != Slot720.Unset }
                Button(
                    onClick = {
                        vm.saveConfig(committed.toList(), fallback, setMode)
                        saved = true
                        scope.launch {
                            snackbar.showSnackbar(
                                if (gameCount == 0) context.getString(R.string.snackbarSaveEmpty)
                                else context.getString(R.string.snackbarSaveSuccess, gameCount),
                            )
                        }
                    },
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = saveContainer, contentColor = saveContent),
                ) {
                    Icon(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.Save, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            saved -> stringResource(R.string.buttonSaveDone)
                            gameCount == 0 -> stringResource(R.string.buttonSaveEmpty)
                            else -> stringResource(R.string.buttonSaveGames, gameCount)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                // 즉시 구매 CTA는 실결제 경로 — 구매 게이트가 내려가면(계약 파기 kill switch) 아예 노출하지 않는다(F2).
                if (Feature720.PURCHASE_ENABLED) {
                    Spacer(Modifier.height(12.dp))
                    // 즉시 구매 — 저장된 조·번호로 지금 결제(설정 → 저장 → 구매 흐름). 미저장 변경은 저장 유도.
                    InstantPurchaseCta(
                        isLoggedIn = isLoggedIn,
                        purchasedThisRound = lastRound >= vm.currentRound,
                        saleOpen = vm.isSaleOpenNow(),
                        onTap = {
                            // 저장 상태 요구는 첫 구매만 — 추가 구매(자동 N게임)는 저장 슬롯을 쓰지 않는다.
                            if (!saved && lastRound < vm.currentRound) {
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.instantNeedsSetup)) }
                            } else {
                                vm.onInstantTap()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        DiceRollOverlay(visible = rollingAllAuto)
    }

    if (showAllAutoDialog) {
        OverwriteDialog(
            onConfirm = { showAllAutoDialog = false; rollingAllAuto = true },
            onDismiss = { showAllAutoDialog = false },
        )
    }
    if (showApplyAllDialog) {
        OverwriteDialog(
            onConfirm = { showApplyAllDialog = false; doApplyToAll() },
            onDismiss = { showApplyAllDialog = false },
        )
    }
}

/** 전부 자동 주사위 텀블 오버레이 — 스크림 위에서 주사위 타일이 회전·바운스하며 착지(~0.95s). */
@Composable
private fun DiceRollOverlay(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                // 연출 중 아래 컨트롤로 터치가 통과하지 않도록 스크림이 입력을 소비.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            val rotation = remember { Animatable(-540f) }
            val scale = remember { Animatable(0.3f) }
            LaunchedEffect(Unit) {
                launch { scale.animateTo(1f, MotionSpecs.bouncy()) }
                rotation.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.graphicsLayer {
                        rotationZ = rotation.value; scaleX = scale.value; scaleY = scale.value
                    },
                ) { GlossyIconTile(icon = Icons.Rounded.Casino, tint = LgTeal, size = 88.dp) }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.rollingAllAuto),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** 덮어쓰기 확인 다이얼로그(전부자동·전 조 적용 공통 가드). */
@Composable
private fun OverwriteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialogOverwriteTitle), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.dialogOverwriteMessage)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.buttonOverwrite), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.buttonCancel)) } },
    )
}

/** A~E 게임 슬롯 칩 — 설정 완료(non-Unset) 체크, 선택 슬롯 틴트. */
@Composable
private fun SlotTabs(committed: List<Slot720>, currentSlot: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 0 until 5) {
            val isCurrent = i == currentSlot
            val configured = committed[i] != Slot720.Unset
            val bg by animateColorAsState(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLowest,
                animationSpec = MotionSpecs.gentle(),
                label = "slotBg",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .border(
                        1.dp,
                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .clickable { onSelect(i) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    configured,
                    enter = scaleIn(MotionSpecs.bouncy()) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Check,
                            null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                }
                Text(
                    ('A' + i).toString(),
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/** 조 선택 칩 [자동][1][2][3][4][5]. 선택 시 primaryContainer 틴트. */
@Composable
private fun GroupSelector(groupSel: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        GroupChip(
            label = stringResource(R.string.groupAuto),
            selected = groupSel == GROUP_AUTO,
            enabled = enabled,
            weight = 1.4f,
            onClick = { onSelect(GROUP_AUTO) },
        )
        for (g in 1..5) {
            GroupChip(
                label = g.toString(),
                selected = groupSel == g,
                enabled = enabled,
                weight = 1f,
                onClick = { onSelect(g) },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GroupChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    weight: Float,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = MotionSpecs.gentle(),
        label = "groupChipBg",
    )
    Box(
        modifier = Modifier
            .weight(weight)
            .height(48.dp)
            .clip(CircleShape)
            .background(bg)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** 자동/수동 세그먼트 토글(조 고정 시에만 노출) — 인디케이터 bouncy 슬라이드. */
@Composable
private fun NumberModeToggle(numberManual: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, scheme.outlineVariant, CircleShape),
    ) {
        val half = maxWidth / 2
        // autolotto와 동일 배열: [수동][자동] — 수동=왼쪽(0), 자동=오른쪽(half).
        val indicatorX by animateDpAsState(
            targetValue = if (numberManual) 0.dp else half,
            animationSpec = MotionSpecs.bouncy(),
            label = "modeIndicator",
        )
        Box(
            Modifier
                .offset(x = indicatorX)
                .width(half)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(lerp(LgTeal, Color.White, 0.80f), lerp(LgTeal, Color.White, 0.60f)),
                    ),
                )
                .border(1.dp, LgTeal.copy(alpha = 0.35f), CircleShape),
        )
        Row(Modifier.fillMaxSize()) {
            ModeLabel(stringResource(R.string.numberModeManual), active = numberManual, enabled = enabled, onClick = { onChange(true) }, modifier = Modifier.weight(1f))
            ModeLabel(stringResource(R.string.numberModeAuto), active = !numberManual, enabled = enabled, onClick = { onChange(false) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeLabel(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MotionSpecs.gentle(),
        label = "modeLabel",
    )
    Box(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

/** 안내 카드(조 미선택 프롬프트 등) — 중립 톤. */
@Composable
private fun HintCard(text: String) {
    SectionCard(contentPadding = PaddingValues(24.dp)) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** 자동/반자동 안내 카드(아이콘 + 제목 + 설명). */
@Composable
private fun InfoCard(title: String, desc: String) {
    SectionCard(contentPadding = PaddingValues(28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            GlossyIconTile(icon = Icons.Rounded.Casino, tint = LgTeal, size = 52.dp)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                desc,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 자리별 슬롯릴 6칸 + 0~9 키패드 + 확정 전 큰 프리뷰 + "전 조에 동일번호 적용"(§5-4). */
@Composable
private fun DigitInput(
    group: Int,
    digits: List<Int?>,
    activeDigit: Int,
    enabled: Boolean,
    onSelectCell: (Int) -> Unit,
    onKey: (Int) -> Unit,
    onBackspace: () -> Unit,
    applyEnabled: Boolean,
    onApplyAll: () -> Unit,
) {
    SectionCard(contentPadding = PaddingValues(16.dp)) {
        Text(
            stringResource(R.string.numberInputSectionTitle),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))

        // 확정 전 큰 프리뷰: "3조 1 7 5 0 2 0" (미입력은 "-"). 색상 외에도 텍스트로 상태 병행.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.clip(CircleShape).background(LgTeal).padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.joLabel, group),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                digits.joinToString(" ") { it?.toString() ?: "-" },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(16.dp))

        // 6칸 슬롯릴 — 탭으로 활성칸 선택. 자리별 contentDescription + 자릿값 힌트(10만~일 자리, §5-4 F14).
        val placeNames = stringArrayResource(R.array.digitPlaceNames)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0..5) {
                val v = digits[i]
                val active = i == activeDigit
                val place = placeNames.getOrElse(i) { (i + 1).toString() }
                val cd = if (v == null) stringResource(R.string.cdDigitEmpty, place)
                else stringResource(R.string.cdDigitFilled, place, v)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLowest,
                        )
                        .border(
                            if (active) 2.dp else 1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(enabled = enabled) { onSelectCell(i) }
                        .clearAndSetSemantics { contentDescription = cd },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        v?.toString() ?: "-",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (v == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 0~9 키패드 (5×2) + 지우기.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowStart in listOf(0, 5)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in rowStart until rowStart + 5) {
                        Key(
                            label = n.toString(),
                            cd = stringResource(R.string.cdKeypadDigit, n),
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = { onKey(n) },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onBackspace,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.Backspace, contentDescription = stringResource(R.string.cdBackspace), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // 전 조에 동일번호 적용(§5-4 퀵액션) — 6자리 완성 시에만.
        TextButton(onClick = onApplyAll, enabled = applyEnabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.applyToAllGroups), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Key(label: String, cd: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 게임 요약 A~E — 미설정/선택 중/완전자동/반자동/조+번호. 슬롯별 삭제. */
@Composable
private fun GameSummary(committed: List<Slot720>, currentSlot: Int, onRemove: (Int) -> Unit) {
    SectionCard {
        SectionHeader(stringResource(R.string.gameSummaryTitle))
        Spacer(Modifier.height(8.dp))
        for (i in 0 until 5) {
            val s = committed[i]
            val configured = s != Slot720.Unset
            val selecting = s == Slot720.Unset && i == currentSlot
            val label = when (s) {
                Slot720.Unset -> if (selecting) stringResource(R.string.gameSummarySelecting)
                else stringResource(R.string.gameSummaryNotSet)
                Slot720.FullAuto -> stringResource(R.string.gameSummaryFullAuto)
                is Slot720.SemiAuto -> stringResource(R.string.gameSummarySemiAuto, s.group)
                is Slot720.Manual -> stringResource(R.string.gameSummaryManual, s.group, s.digits.joinToString(""))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (configured) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ('A' + i).toString(),
                        color = if (configured) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (selecting) {
                    // "지금 설정 중" 라이브 펄스 닷 — autolotto 게임 설정 섹션과 동일 연출.
                    val pulse by rememberInfiniteTransition(label = "nowPulse").animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                        label = "nowPulseAlpha",
                    )
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(LgTeal.copy(alpha = pulse)),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    color = when {
                        selecting -> LgTeal
                        configured -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (configured || selecting) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = if (s is Slot720.Manual) FontFamily.Monospace else FontFamily.Default,
                )
                if (configured) {
                    IconButton(onClick = { onRemove(i) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cdRemoveGame),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 폴백 정책 선택기(§6) — 조+번호 모두 자동 배정(기본) / 조 유지·번호 자동 배정 / 포기. */
@Composable
private fun FallbackSelector(fallback: FallbackPolicy, enabled: Boolean, onSelect: (FallbackPolicy) -> Unit) {
    SectionCard {
        SectionHeader(stringResource(R.string.fallbackPolicyLabel))
        Spacer(Modifier.height(8.dp))
        FallbackRow(
            text = stringResource(R.string.fallbackReassignAll),
            selected = fallback == FallbackPolicy.REASSIGN_ALL,
            enabled = enabled,
            onClick = { onSelect(FallbackPolicy.REASSIGN_ALL) },
        )
        Spacer(Modifier.height(8.dp))
        FallbackRow(
            text = stringResource(R.string.fallbackKeepGroup),
            selected = fallback == FallbackPolicy.KEEP_GROUP_RANDOM,
            enabled = enabled,
            onClick = { onSelect(FallbackPolicy.KEEP_GROUP_RANDOM) },
        )
        Spacer(Modifier.height(8.dp))
        FallbackRow(
            text = stringResource(R.string.fallbackGiveUp),
            selected = fallback == FallbackPolicy.GIVE_UP,
            enabled = enabled,
            onClick = { onSelect(FallbackPolicy.GIVE_UP) },
        )
    }
}

@Composable
private fun FallbackRow(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLowest,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.ConfirmationNumber,
            null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 즉시 구매 프라이머리 CTA. 비활성 사유(로그인 > 판매시간)를 라벨로 표시, 구매완료 회차엔 "추가 구매". */
@Composable
private fun InstantPurchaseCta(
    isLoggedIn: Boolean,
    purchasedThisRound: Boolean,
    saleOpen: Boolean,
    onTap: () -> Unit,
) {
    val disabledLabel = when {
        !isLoggedIn -> stringResource(R.string.hintLoginRequired)
        !saleOpen -> stringResource(R.string.instantNotSaleTime)
        else -> null
    }
    CtaButton(onClick = onTap, enabled = disabledLabel == null) {
        Text(
            disabledLabel ?: stringResource(
                if (purchasedThisRound) R.string.buttonExtraPurchase else R.string.buttonInstantPurchase,
            ),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 즉시 구매 다이얼로그 — InstantState별 확인/게임수선택/진행/결과/에러. */
@Composable
private fun InstantPurchaseDialogs(state: InstantState, vm: PurchaseSetupViewModel) {
    when (state) {
        is InstantState.ConfirmingFirst -> AlertDialog(
            onDismissRequest = { vm.dismissInstant() },
            title = { Text(stringResource(R.string.instantConfirmTitle)) },
            text = {
                if (state.config.setMode) {
                    Text(stringResource(R.string.setModeConfirm))
                } else {
                    Text(
                        stringResource(
                            R.string.instantConfirmBody,
                            state.round, state.games, formatNumber(state.games * 1000),
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmFirst() }) { Text(stringResource(R.string.buttonConfirm)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissInstant() }) { Text(stringResource(R.string.buttonCancel)) }
            },
        )
        is InstantState.PickingExtra -> {
            var games by rememberSaveable { mutableIntStateOf(1) }   // 회전 시 선택 게임수 유지(F10)
            AlertDialog(
                onDismissRequest = { vm.dismissInstant() },
                title = { Text(stringResource(R.string.extraPickTitle)) },
                text = {
                    Column {
                        Text(stringResource(R.string.extraPickBody), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..5).forEach { n ->
                                FilterChip(selected = games == n, onClick = { games = n }, label = { Text("$n") })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.instantConfirmBody,
                                state.round, games, formatNumber(games * 1000),
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmExtra(games) }) { Text(stringResource(R.string.buttonConfirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissInstant() }) { Text(stringResource(R.string.buttonCancel)) }
                },
            )
        }
        is InstantState.InProgress -> AlertDialog(
            onDismissRequest = {},                                  // 진행 중 닫기 금지
            title = { Text(stringResource(R.string.instantConfirmTitle)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.instantInProgress))
                }
            },
            confirmButton = {},
        )
        is InstantState.Success -> AlertDialog(
            onDismissRequest = { vm.dismissInstant() },
            title = { Text(stringResource(R.string.instantSuccessTitle)) },
            text = {
                Column {
                    Text(stringResource(R.string.instantSuccessBody, state.result.round, state.result.tickets.size))
                    Spacer(Modifier.height(12.dp))
                    // 지정번호 점유 + '구매 포기' 정책이면 산 게임이 0 — 오류가 아니라 정책상 정상 결과.
                    if (state.result.tickets.isEmpty()) {
                        Text(stringResource(R.string.instantNoTickets), style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.result.tickets.forEach { t ->
                            JoNumberDisplay(
                                joLabel = localizedJoLabel(t.jo),
                                number = t.number,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    // 다게임 구매가 도중에 끊긴 부분 성공 — 미결제 게임 수를 경고(실패의 재시도 안전성으로 문구 분기).
                    state.result.partialFailure?.let { pf ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                if (classifyPurchaseFailure(pf.cause) is PurchaseFailure.Unknown) R.string.instantPartialUnknown
                                else R.string.instantPartialRejected,
                                pf.failedGames,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // 결제는 성공했으나 로컬 회차 가드 저장 실패 — 예약 자동구매를 중단했음을 경고(F3).
                    if (!state.guardSaved) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.instantGuardSaveFailed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissInstant() }) { Text(stringResource(R.string.buttonConfirm)) }
            },
        )
        is InstantState.AlreadyPurchased -> InstantNoticeDialog(
            title = stringResource(R.string.instantConfirmTitle),
            text = stringResource(R.string.instantAlreadyPurchased),
            onDismiss = { vm.dismissInstant() },
        )
        is InstantState.SaleClosed -> InstantNoticeDialog(
            title = stringResource(R.string.instantConfirmTitle),
            text = stringResource(R.string.instantNotSaleTime),
            onDismiss = { vm.dismissInstant() },
        )
        is InstantState.RoundChanged -> InstantNoticeDialog(
            title = stringResource(R.string.instantConfirmTitle),
            text = stringResource(R.string.instantRoundChanged),
            onDismiss = { vm.dismissInstant() },
        )
        is InstantState.Error -> InstantNoticeDialog(
            title = stringResource(R.string.instantErrorTitle),
            text = if (state.unknown) stringResource(R.string.instantUnknownResult)
            else state.message ?: stringResource(R.string.instantErrorFallback),
            onDismiss = { vm.dismissInstant() },
        )
        InstantState.Idle, InstantState.NeedsSetup -> Unit
    }
}

/** 공지성 다이얼로그 공통 스켈레톤(제목·본문·확인 버튼) — 상태별 문구만 다르다. */
@Composable
private fun InstantNoticeDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.buttonConfirm)) }
        },
    )
}
