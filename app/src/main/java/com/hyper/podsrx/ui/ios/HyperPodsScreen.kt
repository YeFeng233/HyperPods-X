// Adapted from NEORUAA/HyperPods-X PR #1 (ae9fa5e), GPL-3.0-or-later.
package com.hyper.podsrx.ui.ios

import com.hyper.podsrx.ui.BatteryPresentation
import com.hyper.podsrx.ui.batteryPresentation
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import com.hyper.podsrx.device.*
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosPageHorizontalPadding
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.*
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavDisplay
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavHeaderMotion
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.flow.collect

private val LocalSettingsBodyPadding = staticCompositionLocalOf<PaddingValues> { PaddingValues() }

@Composable
internal fun IosSettingsScreen(
    externalSettings: Boolean,
    initialPage: String? = null,
    onConnect: () -> Unit,
    onOverlay: () -> Unit,
    onSystemBluetooth: () -> Unit,
    onFinish: () -> Unit,
) {
    val state by DeviceRepository.state.collectAsState()
    var confirmedControls by remember(state.address, state.running) { mutableStateOf<Map<Int, List<Int>>>(emptyMap()) }
    var confirmedEqualizer by remember(state.address, state.running) { mutableStateOf<List<Int>?>(null) }
    SideEffect {
        if (state.running) {
            if (state.controls.isNotEmpty()) confirmedControls = confirmedControls + state.controls
            state.equalizer?.let { confirmedEqualizer = it }
        }
    }
    val retainingReadback = state.running && state.pending.isNotEmpty()
    val visibleControls = if (retainingReadback) confirmedControls + state.controls else state.controls
    val cachedEqualizer = retainingReadback && state.equalizer == null && confirmedEqualizer != null
    val displayState = if (retainingReadback) state.copy(
        controls = visibleControls,
        equalizer = state.equalizer ?: confirmedEqualizer,
        pending = state.pending + (visibleControls.keys - state.controls.keys) +
            if (cachedEqualizer) setOf(PodsService.EQ_COMMAND) else emptySet(),
    ) else state
    val latestDisplayState = rememberUpdatedState(displayState)
    val context = LocalContext.current
    val volumeController = remember(context) { MediaVolumeController(context) }
    val volume by volumeController.state.collectAsState()
    DisposableEffect(volumeController) { onDispose { volumeController.close() } }
    val view = LocalView.current
    DisposableEffect(state.spatial.calibrationMode) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state.spatial.calibrationMode
        onDispose { view.keepScreenOn = previous }
    }
    val firstPage = when (initialPage) {
        "spatial", "audio", "controls", "accessibility", "module", "connection", "battery" -> initialPage
        "more" -> "module"
        "advanced" -> "audio"
        else -> null
    }
    val backStack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) {
        mutableStateListOf(settingsEntryKey("device")).apply { firstPage?.let { add(settingsEntryKey(it)) } }
    }
    val headerMotion = remember { GlasmioNavHeaderMotion(backStack.last()) }
    val topHeader = remember { GlasmioHeaderScrollState(collapseProgress = { 1f }, scrollEdgeProgress = { 0f }) }
    val headerScrollStates = remember { mutableStateMapOf<String, GlasmioHeaderScrollState>() }
    SideEffect {
        val frame = headerMotion.frame.value
        headerScrollStates.keys.retainAll(backStack.toSet() + setOf(frame.fromEntryKey, frame.toEntryKey))
    }
    var nameValue by rememberSaveable(state.address, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.displayName, selection = TextRange(state.displayName.length)))
    }
    var nameError by rememberSaveable(state.address) { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        var previousLog = DeviceRepository.state.value.log
        DeviceRepository.state.collect { current ->
            if (current.log != previousLog) {
                val overlap = (minOf(previousLog.size, current.log.size) downTo 0).first {
                    previousLog.takeLast(it) == current.log.take(it)
                }
                current.log.drop(overlap).mapNotNull(::actionFailureMessage).lastOrNull()?.let { actionError = it }
            }
            previousLog = current.log
        }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigate: (String) -> Unit = { destination ->
        if (backStack.lastOrNull()?.settingsRouteName() != destination) {
            if (destination == "name") {
                nameValue = TextFieldValue(state.displayName, selection = TextRange(state.displayName.length))
                nameError = null
            }
            backStack.add(settingsEntryKey(destination))
        }
    }
    val saveName: (Boolean) -> Boolean = { showError ->
        val cleaned = nameValue.text.trim()
        val error = when {
            cleaned.isEmpty() -> "请输入名称"
            cleaned.length > 100 -> "名称最多可包含 100 个字符"
            cleaned == state.displayName -> null
            DeviceRepository.rename(cleaned) -> null
            else -> "无法保存耳机名称，请重试。"
        }
        if (showError) nameError = error
        error == null
    }
    val leavePage: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        } else onFinish()
    }
    val back: () -> Unit = {
        if (backStack.lastOrNull()?.settingsRouteName() == "name" && nameError == null) saveName(false)
        leavePage()
    }
    val finishName: () -> Unit = {
        if (saveName(true)) leavePage()
    }
    BackHandler(enabled = backStack.size == 1) { back() }
    ListeningIntentHost(state) {
        GlasmioAppTheme {
            val background = GlasmioTheme.colors.groupedBackground
            GlasmioBackdropHost(
                modifier = Modifier.fillMaxSize(),
                sampledContent = { Box(Modifier.fillMaxSize().background(background)) },
                overlayContent = {
                    val renderedPage = rememberLayerBackdrop()
                    CompositionLocalProvider(LocalBlurBackdrop provides renderedPage) {
                        GlasmioTextSelectionHost(
                            labels = GlasmioTextSelectionLabels(cut = "剪切", copy = "复制", paste = "粘贴", selectAll = "全选",
                                autofill = "自动填充", more = "更多", expanded = "已展开", collapsed = "已折叠"),
                        ) {
                            Box(Modifier.fillMaxSize().glassBackdropSource(renderedPage).trackBackdropPosition(renderedPage)) {
                                GlasmioNavigationScaffold(
                                    motion = headerMotion,
                                    header = { entryKey ->
                                        val key = entryKey as String
                                        val page = key.settingsRouteName()
                                        GlasmioHeaderSpec(
                                            title = settingsTitle(page, state),
                                            leading = listOf(
                                                GlasmioHeaderGroup("navigation", listOf(
                                                    GlasmioHeaderAction("navigation.back", "chevron.backward", "返回", back) {
                                                        SfIcon(SfSymbol.ChevronBack, null, mirrored = true)
                                                    },
                                                )),
                                            ),
                                            scrollState = headerScrollStates[key] ?: topHeader,
                                        )
                                    },
                                ) { headerPadding ->
                                    CompositionLocalProvider(LocalSettingsBodyPadding provides headerPadding) {
                                        GlasmioNavDisplay(
                                            motion = headerMotion,
                                            backStack = backStack,
                                            modifier = Modifier.fillMaxSize(),
                                            onBack = back,
                                            entryProvider = { entryKey ->
                                                NavEntry(key = entryKey, contentKey = entryKey) {
                                                    val entryDisplayState = latestDisplayState.value
                                                    val page = entryKey.settingsRouteName()
                                                    val listState = rememberLazyListState()
                                                    val collapseDistance = with(LocalDensity.current) { 56.dp.toPx() }
                                                    val scrollState = remember(listState, collapseDistance) {
                                                        GlasmioHeaderScrollState(
                                                            collapseProgress = { 1f },
                                                            scrollEdgeProgress = {
                                                                if (listState.firstVisibleItemIndex > 0) 1f
                                                                else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f)
                                                            },
                                                        )
                                                    }
                                                    SideEffect { headerScrollStates[entryKey] = scrollState }
                                                    DisposableEffect(entryKey) {
                                                        if (page == "spatial") PodsService.spatialRefresh()
                                                        onDispose { if (page == "spatial") PodsService.spatialCancelCalibration() }
                                                    }
                                                    val hostPadding = LocalSettingsBodyPadding.current
                                                    val bodyPadding = PaddingValues(
                                                        start = IosPageHorizontalPadding,
                                                        top = hostPadding.calculateTopPadding(),
                                                        end = IosPageHorizontalPadding,
                                                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                                                    )
                                                    Box(Modifier.fillMaxSize()) {
                                                        GlasmioPageBody(
                                                            contentPadding = bodyPadding,
                                                            topBarBlurProgress = scrollState.scrollEdgeProgress(),
                                                        ) { padding ->
                                                            LazyColumn(
                                                                state = listState,
                                                                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                                                                contentPadding = padding,
                                                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                                            ) {
                                                                if (page == "device") {
                                                                    item("products") { DeviceProducts(entryDisplayState) }
                                                                    item("name") { DeviceName(entryDisplayState) { navigate("name") } }
                                                                    item("listening") {
                                                                        Column { GlasmioSectionTitle("聆听模式"); ListeningControls(entryDisplayState) }
                                                                    }
                                                                    item("volume") {
                                                                        Column {
                                                                            GlasmioSectionTitle("音量")
                                                                            GlasmioVolumeSlider(volume.fraction, volumeController::setFraction,
                                                                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "音量" },
                                                                                enabled = volume.maximum > 0,
                                                                                onValueChangeFinished = volumeController::refresh)
                                                                        }
                                                                    }
                                                                    item("features") {
                                                                        SettingsCard {
                                                                            FeatureLink("音频与路径", "speaker.wave.3.fill", Color(0xffff2d55), false) { navigate("audio") }
                                                                            FeatureLink("控制与手势", "hand.pinch.fill", Color(0xff8e8e93)) { navigate("controls") }
                                                                            FeatureLink("无障碍", "accessibility", Color(0xff00a7ed)) { navigate("accessibility") }
                                                                        }
                                                                    }
                                                                    item("battery") {
                                                                        SettingsCard { FeatureLink("电池", "battery.100percent", Color(0xff34c759), false) { navigate("battery") } }
                                                                    }
                                                                    if (!entryDisplayState.running) item("connection") {
                                                                        SettingsCard {
                                                                            RowItem("连接 AirPods") { navigate("connection"); onConnect() }
                                                                        }
                                                                    }
                                                                    item("information") {
                                                                        Section("关于") {
                                                                            InfoRow("型号名称", entryDisplayState.headset.name)
                                                                            InfoRow("型号号码", entryDisplayState.model.ifBlank { "—" })
                                                                            InfoRow("版本", entryDisplayState.firmware.ifBlank { "—" })
                                                                        }
                                                                    }
                                                                    item("module") {
                                                                        SettingsCard {
                                                                            RowItem("更多设置") { navigate("module") }
                                                                            RowItem("关于 HyperPods-X") { navigate("about") }
                                                                        }
                                                                    }
                                                                } else if (page == "name") {
                                                                    item("nameEditor") {
                                                                        DeviceNameEditor(nameValue, {
                                                                            nameValue = it
                                                                            nameError = null
                                                                        }, nameError, finishName)
                                                                    }
                                                                } else IosSettingsPageContent(page, entryDisplayState, onConnect, onOverlay, onSystemBluetooth, navigate)
                                                                if (page in setOf("module", "connection", "diagnostics") && entryDisplayState.message.isNotBlank()) {
                                                                    item("message") { SettingsFootnote(entryDisplayState.message) }
                                                                }
                                                            }
                                                        }
                                                        GlasmioPageTitle(entryKey)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            GlasmioAlertDialog(
                                visible = actionError != null,
                                onDismissRequest = { actionError = null },
                                title = "未能完成操作",
                                message = actionError,
                                buttons = listOf(GlasmioAlertButtonSpec("好", { actionError = null })),
                            )
                        }
                    }
                },
            )
        }
    }
}

private fun settingsEntryKey(route: String) = "$route#${java.util.UUID.randomUUID()}"
private fun String.settingsRouteName() = substringBefore('#')

private fun actionFailureMessage(message: String): String? = when {
    message.startsWith("聆听模式未确认目标值") ||
        message.startsWith("自适应降噪幅度未确认目标值") -> null
    message.endsWith("未确认目标值，已保留设备回读结果") ->
        "${message.substringBefore("未确认目标值")}未能更新，请重试。"
    message.startsWith("设置发送失败：") -> "无法更新耳机设置，请重试。"
    else -> when (message) {
        "请先连接耳机", "耳机控制尚未连接" -> "耳机已断开连接，请重新连接后重试。"
        "请在自适应模式下调整降噪幅度" -> "请先切换到自适应模式。"
        "请等待自适应模式与降噪幅度读取完成" -> "正在读取耳机设置，请稍后重试。"
        "请等待降噪幅度确认后再切换模式" -> "正在更新自适应音频，请稍后切换模式。"
        "请等待降噪指令发送完成后再切换模式" -> "正在发送自适应音频设置，请稍后切换模式。"
        "当前设置或取值尚未开放写入" -> "此设置暂时无法更改。"
        "无法调节媒体音量" -> message
        "请先连接并佩戴双耳" -> message
        "请先取得姿态流并完成三轴校准" -> "请先完成三轴校准。"
        "系统追踪启动失败" -> "无法开启头部追踪，请重试。"
        else -> null
    }
}

private fun settingsTitle(page: String, state: PodsState): String = when {
    page.startsWith("control/") -> Controls.all.firstOrNull { it.id == page.substringAfter('/').toIntOrNull() }?.title ?: "设置"
    page == "call/mute" -> "静音与取消静音"
    page == "call/end" -> "结束通话"
    page == "audio" -> "音频与路径"
    page == "controls" -> "控制与手势"
    page == "accessibility" -> "无障碍"
    page == "spatial" -> "空间音频"
    page == "equalizer" -> "均衡器"
    page == "battery" -> "电池"
    page == "module" -> "更多设置"
    page == "connection" -> "连接设置"
    page == "diagnostics" -> "连接诊断"
    page == "licenses" -> "开源许可"
    page == "about" -> "关于"
    else -> state.displayName
}

@Composable
private fun FeatureLink(title: String, symbol: String, color: Color, separator: Boolean = true, onClick: () -> Unit) {
    GlasmioListRow(title = title, leading = {
        Box(Modifier.size(29.dp).clip(GlasmioTheme.shapes.extraSmall).background(color), contentAlignment = Alignment.Center) {
            SfIcon(symbol, null, size = 23.dp, tint = Color.White)
        }
    }, onClick = onClick, showTopSeparator = separator)
}

@Composable
private fun DeviceProducts(state: PodsState) {
    val left = batteryPresentation(state.batteries[4])
    val right = batteryPresentation(state.batteries[2])
    val case = batteryPresentation(state.batteries[8])
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(state.headset.pairArtwork), "AirPods", contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 104.dp, height = 110.dp))
            Spacer(Modifier.height(8.dp))
            if (left == right) BatteryCircle(left, "AirPods")
            else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BatteryCircle(left, "左", compact = true)
                BatteryCircle(right, "右", compact = true)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(state.headset.caseArtwork), "充电盒", contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 132.dp, height = 110.dp))
            Spacer(Modifier.height(8.dp))
            BatteryCircle(case, "充电盒")
        }
    }
}

@Composable
private fun BatteryCircle(battery: BatteryPresentation, label: String, compact: Boolean = false) {
    val colors = GlasmioTheme.colors
    val track = colors.tertiaryContent.copy(alpha = .16f)
    Column(Modifier.clearAndSetSemantics {
        contentDescription = "$label，${battery.label}${if (battery.note.isNotBlank()) "，${battery.note}" else ""}"
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(if (compact) 30.dp else 34.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, -90f, 360f, false, style = stroke)
                battery.level?.let { drawArc(ChargingGreen, -90f, it * 3.6f, false, style = stroke) }
            }
            if (battery.charging) SfIcon("bolt.fill", null, size = 17.dp, tint = ChargingGreen)
        }
        Text(if (compact) "$label ${battery.label}" else battery.label,
            style = if (compact) GlasmioTheme.textStyles.footnote else GlasmioTheme.textStyles.body,
            color = colors.secondaryContent, maxLines = 1)
    }
}

@Composable
private fun DeviceName(state: PodsState, onClick: () -> Unit) {
    SettingsCard {
        GlasmioListRow(title = "名称", detail = state.displayName, onClick = onClick,
            enabled = state.address.isNotBlank() && DeviceRepository.permissionsGranted())
    }
}

@Composable
private fun DeviceNameEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: String?,
    onDone: () -> Unit,
) {
    val colors = GlasmioTheme.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth()) {
        SettingsCard {
            GlasmioTextField(value, onValueChange,
                modifier = Modifier.focusRequester(focus).semantics { contentDescription = "名称" },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                trailing = {
                    if (value.text.isNotEmpty()) Box(
                        Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = "清除文本") {
                            onValueChange(TextFieldValue())
                            focus.requestFocus()
                        }, contentAlignment = Alignment.Center,
                    ) {
                        SfIcon("xmark.circle.fill", "清除文本", size = 18.dp, tint = colors.tertiaryContent)
                    }
                })
        }
        error?.let { SettingsFootnote(it) }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}
