package com.hyper.podsrx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyper.podsrx.BuildConfig
import com.hyper.podsrx.device.*
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
internal fun MiuixSettingsScreen(
    externalSettings: Boolean,
    initialPage: String? = null,
    onConnect: () -> Unit,
    onOverlay: () -> Unit,
    onSystemBluetooth: () -> Unit,
    onFinish: () -> Unit
) {
    val displayName by rememberDeviceValue { it.displayName }
    MiuixRuntimeEffects()
    var page by rememberSaveable { mutableStateOf(initialPage?.takeIf { it in setOf("spatial", "more") } ?: "device") }
    val pages = rememberSaveableStateHolder()
    val back = {
        when (page) {
            "device" -> onFinish()
            "diagnostics", "licenses" -> page = "more"
            else -> page = "device"
        }
    }
    BackHandler(enabled = page != "device") { back() }
    val dark = isSystemInDarkTheme()
    val colors = remember(dark) { if (dark) darkColorScheme(
        primary = Color(0xff80acff), background = Color(0xff141416), surface = Color(0xff252528), surfaceContainer = Color(0xff252528)
    ) else lightColorScheme(
        primary = Accent, background = Color(0xfff2f2f6), surface = Color.White, surfaceContainer = Color.White, onSurfaceSecondary = Color(0xff77777e)
    ) }
    MiuixTheme(colors = colors) {
        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            pages.SaveableStateProvider(page) {
                val scroll = rememberScrollState()
                val threshold = with(LocalDensity.current) { 78.dp.toPx() }
                val collapsed by remember(threshold) { derivedStateOf { scroll.value > threshold } }
                val title = when (page) {
                    "spatial" -> "空间音频"
                    "more" -> "更多设置"
                    "advanced" -> "耳机高级设置"
                    "connection" -> "自动连接帮助"
                    "diagnostics" -> "连接诊断"
                    "licenses" -> "开源许可"
                    else -> displayName
                }
                Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                    DeviceToolbar(title, page != "device" || collapsed, back,
                        if (page == "device") ({ page = "more" }) else null)
                    Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (page) {
                            "device" -> MiuixDevicePage(onConnect, onSystemBluetooth) { page = it }
                            "spatial" -> LiveDeviceContent { SpatialPanel(it) }
                            "advanced" -> LiveDeviceContent { AdvancedSettings(it) }
                            "more" -> MoreSettings(onOverlay, onSystemBluetooth) { page = it }
                            "connection" -> LiveDeviceContent { ConnectionHelp(it, onConnect, onSystemBluetooth) }
                            "diagnostics" -> LiveDeviceContent { Diagnostics(it) }
                            "licenses" -> Licenses()
                        }
                        DeviceMessage()
                        Text("HyperPods-X · ${BuildConfig.VERSION_NAME}${if (externalSettings) " · 蓝牙设备设置" else ""}",
                            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceToolbar(title: String, showTitle: Boolean, onBack: () -> Unit, onMore: (() -> Unit)?) {
    val titleAlpha by animateFloatAsState(if (showTitle) 1f else 0f, label = "collapsedTitle")
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        NavigationButton("返回", back = true, onClick = onBack)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(title, fontSize = 21.sp, color = MiuixTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(titleAlpha).clearAndSetSemantics { if (showTitle) { contentDescription = title; heading() } })
        }
        if (onMore != null) NavigationButton("更多设置", back = false, onClick = onMore) else Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun MiuixRuntimeEffects() {
    val keepOn by rememberDeviceValue { it.spatial.calibrationMode }
    val view = LocalView.current
    DisposableEffect(view, keepOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = previous }
    }
}

@Composable
private fun LiveDeviceContent(content: @Composable (PodsState) -> Unit) {
    val state by DeviceRepository.state.collectAsState()
    content(state)
}

@Composable
private fun DeviceMessage() {
    val message by rememberDeviceValue { it.message }
    if (message.isNotBlank()) Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun MiuixDevicePage(onConnect: () -> Unit, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit) {
    val state by DeviceRepository.state.collectAsState()
    Text(state.displayName, fontSize = 34.sp, color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp).semantics { heading() })
    Hero(state)
    if (!state.running) Action("连接耳机", Modifier.fillMaxWidth()) { navigate("connection"); onConnect() }
    BatteryCard(state)
    ListeningControls(state)
    SettingsCard {
        RowItem("重命名", "在系统蓝牙中修改此设备的名称", value = state.displayName, action = onSystemBluetooth)
        RowItem("自动连接帮助", "配对、重连与后台运行") { navigate("connection") }
    }
    DeviceFeatures(state) { navigate("spatial") }
    Section("系统蓝牙") {
        RowItem("音频与设备权限", "AAC、音量同步、通话 / 媒体音频及通讯录权限，由系统管理", action = onSystemBluetooth)
    }
    SettingsCard {
        RowItem("耳机高级设置", "声音、按压手势与均衡器") { navigate("advanced") }
        RowItem("更多设置", "开盖弹窗、连接诊断与设备信息") { navigate("more") }
    }
}

@Composable
private fun Hero(state: PodsState) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(state.headset.pairArtwork), "${state.headset.name} 双耳透明产品图", contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = 230.dp, height = 218.dp).padding(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (state.connected) ChargingGreen else MiuixTheme.colorScheme.onSurfaceSecondary))
            Text(if (state.connected) "已连接" else state.status, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }
}

@Composable
private fun BatteryCard(state: PodsState) {
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(4 to "左", 2 to "右", 8 to "充电盒").forEachIndexed { index, (id, label) ->
                if (index > 0) Box(Modifier.width(.5.dp).height(56.dp).background(MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .25f)))
                val battery = batteryPresentation(state.batteries[id])
                Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$label，${battery.label}，${battery.note}" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, fontSize = 17.sp)
                    Text(battery.label, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    BatteryGlyph(battery)
                    Text(battery.note.ifBlank { " " }, fontSize = 10.sp,
                        color = if (battery.charging) ChargingGreen else MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }
    }
}

@Composable
private fun DeviceFeatures(state: PodsState, onSpatial: () -> Unit) {
    Section("功能设置") {
        SpatialModeSelector(state, showTitle = true)
        RowItem("头部追踪与校准", if (state.spatial.calibrated) "已保存校准，可回中或重新校准" else "首次使用需要完成三轴校准", action = onSpatial)
        ToggleRow("自动入耳检测", "摘下一只暂停，戴回继续；单耳使用时同样适用", state.earAutomation) { DeviceRepository.setEarAutomation(it) }
        if (state.earAutomation) Text(earDetectionDescription(state), fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp))
        listOf(0x28, 0x26).forEach { id ->
            Controls.all.find { it.id == id && state.controls[it.id]?.firstOrNull() != null }?.let { ControlRow(it) }
        }
    }
}

@Composable
private fun AdvancedSettings(state: PodsState) {
    val available = Controls.all.filter { it.id !in setOf(0x0d, 0x0a, 0x28, 0x26, 0x2e) && state.controls[it.id]?.firstOrNull() != null }
    for ((group, specs) in available.groupBy { it.group }) Section(group) { specs.forEach { ControlRow(it) } }
    EqualizerSection(state)
    CallControlSection(state)
    if (state.controls[0x0a]?.firstOrNull() != null) Section("耳机传感器设置") {
        ControlRow(Controls.all.first { it.id == 0x0a })
        Text("耳机侧报告的配置；Android 自动暂停 / 继续由首页的“自动入耳检测”控制。", fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
    }
    val missing = listOf(0x01 to "麦克风选边", 0x16 to "左右耳长按动作", 0x1a to "长按切换模式组合").filter { it.first !in state.controls }
    if (state.connected && missing.isNotEmpty()) Section("当前设备尚未提供") {
        Text(missing.joinToString("、") { it.second }, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(18.dp))
    }
    if (available.isEmpty()) Section("等待设备") {
        Text("连接后读取耳机支持的设置。尚未收到的设置不会生成可写开关。", modifier = Modifier.padding(18.dp), fontSize = 14.sp)
    }
}

@Composable
private fun PreferenceToggle(title: String, subtitle: String, read: (PodsState) -> Boolean, write: (Boolean) -> Unit) {
    val checked by rememberDeviceValue(read)
    ToggleRow(title, subtitle, checked, onChange = write)
}

@Composable
private fun NativePreviewRow() {
    val native by rememberDeviceValue { it.nativePopup }
    RowItem("预览超级岛", enabled = native) { PodsService.previewIsland() }
}

@Composable
private fun DeviceInformation() {
    val info by rememberDeviceValue { Triple(it.displayName, it.model, it.firmware) }
    InfoRow("名称", info.first)
    InfoRow("型号", info.second.ifBlank { "等待读取" })
    InfoRow("设备报告版本", info.third.ifBlank { "等待读取" })
}

@Composable
private fun MoreSettings(onOverlay: () -> Unit, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit) {
    val logExport = LocalLogExport.current
    val hostRestart by HostRestartCoordinator.state.collectAsState()
    val style by AppearancePreferences.style.collectAsState()
    val changeStyle = LocalChangeUiStyle.current
    Section("外观") {
        ToggleRow(style.label, "切换界面风格", style == UiStyle.IOS, onChange = changeStyle)
    }
    Section("弹窗与提醒") {
        PreferenceToggle("开盖弹窗", "仅响应已验证的耳机开盖广播", { it.popupEnabled }, DeviceRepository::setPopup)
        PreferenceToggle("HyperOS 原生弹窗", "优先使用系统弹窗与超级岛", { it.nativePopup }, DeviceRepository::setNativePopup)
        PreferenceToggle("连接电量卡片", "重新连接时显示真实电量", { it.connectionPopup }, DeviceRepository::setConnectionPopup)
        PreferenceToggle("电量提醒", "低电量、充满电和放回充电盒时发送通知", { it.batteryAlerts }, DeviceRepository::setBatteryAlerts)
        RowItem("启用系统适配", "在 LSPosed 中授权系统设置、小米蓝牙、设备互联和手机管家") { FrameworkBridge.requestNativeScope() }
        RowItem(if (hostRestart.running) "正在重启作用域…" else "重启作用域", hostRestart.message,
            enabled = !hostRestart.running, action = HostRestartCoordinator::restart)
        RowItem("允许悬浮显示", "原生通道不可用时使用兼容卡片", action = onOverlay)
        RowItem("预览电量卡片") { PodsService.previewPopup() }
        NativePreviewRow()
    }
    Section("连接与系统") {
        RowItem("系统蓝牙设置", "管理配对、音频与设备权限", action = onSystemBluetooth)
        RowItem("连接诊断", "控制通道、广播与连接记录") { navigate("diagnostics") }
        RowItem(if (logExport.busy) "正在导出日志…" else "导出日志", logExport.status,
            enabled = !logExport.busy, action = logExport.export)
    }
    Section("设备信息") {
        DeviceInformation()
        RowItem("开源许可", "GPL-3.0-or-later 与第三方声明") { navigate("licenses") }
    }
}

@Composable
private fun ConnectionHelp(state: PodsState, onConnect: () -> Unit, onSystemBluetooth: () -> Unit) {
    Section("连接耳机") {
        RowItem("1. 在系统中配对", "打开充电盒，在系统蓝牙中连接 AirPods", action = onSystemBluetooth)
        RowItem(if (state.running) "2. 重新连接控制通道" else "2. 连接 HyperPods-X", "授予附近设备与位置权限后读取电量和耳机设置") {
            if (state.running) PodsService.reconnect() else onConnect()
        }
        InfoRow("当前状态", state.status)
    }
    // Read candidates again after a permission result, even when no service has started yet.
    val candidates = DeviceRepository.candidates()
    if (state.address.isEmpty() && candidates.size > 1) Section("选择耳机") {
        candidates.forEach { device ->
            val name = try { device.name } catch (_: SecurityException) { null }
            RowItem(name ?: "AirPods", "使用这副已配对的耳机") { DeviceRepository.bind(device) }
        }
    }
    Section("自动连接帮助") {
        Text("系统蓝牙负责音频连接；HyperPods-X 负责读取电量和耳机控制。请允许应用后台运行，并在 LSPosed 中启用系统设置和小米蓝牙作用域。", fontSize = 15.sp, modifier = Modifier.padding(18.dp))
    }
}

@Composable
private fun SpatialPanel(state: PodsState) {
    val s = state.spatial
    LaunchedEffect(Unit) { PodsService.spatialRefresh() }
    DisposableEffect(Unit) { onDispose { PodsService.spatialCancelCalibration() } }
    val tracking = s.trackingRequested || s.nativeActive
    val ready = state.connected && s.route && s.bridge && !s.stopping && !s.modeChanging
    Section("空间音频") {
        SpatialModeSelector(state)
    }
    Section("校准") {
        if (s.calibrationMode) {
            val direction = listOf("向右转头约 30°", "抬头约 25°", "向右侧倾约 25°").getOrElse(s.calibrationStep) { "回正" }
            Text(when {
                s.calibrationPreparing -> "正在连接耳机，请保持双耳佩戴。"
                s.collecting -> "请缓慢${direction}，保持片刻，再回正并完成本步。"
                else -> "第 ${s.calibrationStep + 1} / 3 步：先正视手机，开始后${direction}。"
            }, fontSize = 15.sp, modifier = Modifier.padding(18.dp))
            RowItem(if (s.collecting) "完成本步" else "开始第 ${s.calibrationStep + 1} 步", "手机与身体保持不动", enabled = !s.calibrationPreparing) { PodsService.spatialCalibrate() }
            RowItem("取消校准", "保留上次校准结果") { PodsService.spatialCancelCalibration() }
        } else {
            RowItem("三轴校准", if (tracking) "请先选择关闭或固定模式" else if (s.calibrated) "已保存校准结果 · 可重新校准" else "按提示完成三个头部动作",
                enabled = ready && !tracking) { PodsService.spatialBeginCalibration() }
            RowItem("正视手机并回中", "手机位置改变后，重新确定前方", enabled = s.nativeActive && !s.trackingStarting) { PodsService.spatialRecenter() }
        }
    }
    if (s.notice.isNotBlank()) Text(s.notice, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SpatialModeSelector(state: PodsState, showTitle: Boolean = false) {
    val mode = state.spatial.spatialPresentation(state.connected)
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) Text("空间音频", fontSize = 18.sp)
        SpatialModeSlider(mode) { PodsService.spatialMode(it) }
        Text(mode.description, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        if (showTitle && state.spatial.notice.isNotBlank()) Text(state.spatial.notice, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
    }
}

private data class ControlRowState(val address: String, val value: Int?, val pending: Boolean, val connected: Boolean, val description: String)

@Composable
private fun ControlRow(spec: ControlSpec) {
    val row by rememberDeviceValue { ControlRowState(it.address, it.controls[spec.id]?.firstOrNull(), spec.id in it.pending, it.connected, if (spec.id == 0x28) it.conversation.description else "") }
    val value = row.value ?: return
    val pending = row.pending
    val enabled = row.connected && !pending && spec.writable
    if (spec.range != null) {
        val submitted = if (pending) PodsService.pendingControlValue(spec.id) else null
        // Keep the submitted target while awaiting the device. When pending ends,
        // initialize from the actual report, including a timeout with no value change.
        var draft by remember(row.address, spec.id, value, pending, submitted) {
            mutableFloatStateOf((if (pending) submitted ?: value else value).toFloat())
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${spec.title} · ${draft.toInt()}", fontSize = 18.sp)
            if (spec.id == 0x1f) Text("最低提示音量为15", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Slider(draft, { draft = it }, enabled = enabled, valueRange = spec.range.first.toFloat()..spec.range.last.toFloat(),
                onValueChangeFinished = {
                    val target = draft.toInt().coerceIn(spec.range)
                    if (enabled && target != value) {
                        PodsService.setControl(spec.id, target)
                    }
                })
            if (!spec.writable) Text("写入尚未验证，当前仅显示耳机报告值", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            else Text(if (pending) "${submitted?.let { "等待确认 $it" } ?: "等待耳机确认"} · 耳机当前回读：$value" else "耳机当前回读：$value",
                fontSize = 13.sp, color = if (pending) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    } else if (spec.toggle) {
        val description = when {
            pending -> "等待耳机确认…"
            spec.id == 0x0a -> "耳机固件的传感器配置，不代表 Android 媒体自动化"
            spec.id == 0x28 -> row.description
            spec.id == 0x26 -> "根据环境调整聆听音量"
            else -> ""
        }
        ToggleRow(if (spec.id == 0x0a) "耳机侧入耳检测" else spec.title, description, value == 1, enabled) { PodsService.setControl(spec.id, if (it) 1 else 2) }
    } else Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(spec.title, fontSize = 18.sp)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            spec.choices.forEach { choice -> Pill(choice.label, value == choice.value, enabled = enabled) { PodsService.setControl(spec.id, choice.value) } }
        }
        if (pending) Text("等待耳机确认…", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun EqualizerSection(state: PodsState) {
    val confirmed = state.equalizer?.takeIf { it.size >= 4 } ?: return
    val busy = PodsService.EQ_COMMAND in state.pending
    var draft by remember(confirmed, busy) { mutableStateOf(confirmed) }
    Section("耳机均衡器") {
        ToggleRow("自定 EQ", if (busy) "等待耳机确认…" else "设置保存在耳机中", confirmed[0] == 2, state.connected && !busy) {
            PodsService.setEqualizer(listOf(if (it) 2 else 1) + confirmed.drop(1))
        }
        if (confirmed[0] == 2) Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("低频", "中频", "高频").forEachIndexed { index, label ->
                Text("$label · ${draft[index + 1]}", fontSize = 16.sp)
                Slider(draft[index + 1].toFloat(), { v -> draft = draft.toMutableList().also { it[index + 1] = v.toInt() } },
                    enabled = state.connected && !busy, valueRange = 0f..100f, onValueChangeFinished = { PodsService.setEqualizer(draft) })
            }
        }
    }
}

@Composable
private fun CallControlSection(state: PodsState) {
    val value = state.controls[0x24]?.getOrNull(1) ?: return
    if (value !in 2..3) return
    Section("通话按压") {
        ToggleRow("单次按压结束通话", if (0x24 in state.pending) "等待耳机确认…" else if (value == 2) "双次按压静音或取消静音" else "单次按压静音，双次按压结束通话",
            value == 2, state.connected && 0x24 !in state.pending) { PodsService.setCallControl(it) }
        Text("耳机侧配置；Android 通话行为仍需单独验证。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

@Composable
private fun Licenses() {
    for ((title, file) in listOf("第三方声明" to "NOTICES.md", "图片来源与权利说明" to "ARTWORK-SOURCES.md", "GPL-3.0-or-later" to "GPL-3.0.txt", "Apache-2.0" to "Apache-2.0.txt")) {
        val content = remember(file) { DeviceRepository.app.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
        Section(title) { Text(content, fontSize = 13.sp, modifier = Modifier.padding(18.dp)) }
    }
}

@Composable
private fun Diagnostics(state: PodsState) {
    Section("当前环境") {
        InfoRow("模块框架", state.framework)
        InfoRow("控制通道", if (state.connected) "已连接" else "未连接")
        InfoRow("原生弹窗", if (state.nativePopup) state.nativePopupStatus else "已关闭")
        InfoRow("状态中心", state.hubSummary)
        state.hostCapabilities.forEach { (process, results) -> InfoRow(process, capabilitySummary(results)) }
        InfoRow("绑定身份", if (state.identityReady) "已确认" else "等待密钥")
        InfoRow("广播 / 已验证", "${state.broadcasts} / ${state.trustedBroadcasts}")
        InfoRow("邻近广播 / 身份匹配", "${state.proximityCandidates} / ${state.identityMatches}")
        InfoRow("盒盖", when (state.lidOpen) { true -> "已打开"; false -> "已关闭"; null -> "等待广播" })
        InfoRow("设备报告的设置项", state.controls.size.toString())
        InfoRow("佩戴状态", wearingDescription(state))
        InfoRow("自动入耳检测", if (state.earAutomation) state.earDetectionStatus else "已关闭")
    }
    Section("连接记录") {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { state.log.takeLast(15).forEach { Text(it, fontSize = 12.sp) } }
    }
    Action("停止耳机服务", Modifier.fillMaxWidth()) { DeviceRepository.stop() }
}
