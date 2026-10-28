// Adapted from NEORUAA/HyperPods-X PR #1 (ae9fa5e), GPL-3.0-or-later.
package com.hyper.podsrx.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.ui.AppearancePreferences
import com.hyper.podsrx.ui.LocalChangeUiStyle
import com.hyper.podsrx.ui.UiStyle
import com.hyper.podsrx.BuildConfig
import com.hyper.podsrx.device.*
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioGroupedTableFooter
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle
import io.github.neoruaa.glasmio.ui.GlasmioSlider

internal fun LazyListScope.IosSettingsPageContent(
    page: String,
    state: PodsState,
    onConnect: () -> Unit,
    onOverlay: () -> Unit,
    onSystemBluetooth: () -> Unit,
    navigate: (String) -> Unit,
) {
    when {
        page.startsWith("control/") -> item(page) { ControlChoices(state, page.substringAfter('/').toIntOrNull()) }
        page.startsWith("call/") -> item(page) { CallControlChoices(state, page.substringAfter('/')) }
        page == "audio" -> AudioSettings(state, onSystemBluetooth, navigate)
        page == "controls" -> GestureSettings(state, navigate)
        page == "accessibility" -> AccessibilitySettings(state, navigate)
        page == "spatial" -> SpatialSettings(state)
        page == "equalizer" -> item(page) { EqualizerSettings(state) }
        page == "battery" -> item(page) { BatterySettings(state) }
        page == "module" -> ModuleSettings(state, onOverlay, onSystemBluetooth, navigate)
        page == "connection" -> ConnectionSettings(state, onConnect, onSystemBluetooth)
        page == "diagnostics" -> DiagnosticsSettings(state)
        page == "licenses" -> LicenseSettings()
        page == "about" -> AboutSettings(state, navigate)
    }
}

@Composable
internal fun SettingsFootnote(text: String) { GlasmioGroupedTableFooter(text) }

private fun LazyListScope.AudioSettings(state: PodsState, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit) {
    if (0x26 in state.controls) item("personalizedVolume") {
        Column {
            AvailableControls(state, listOf(0x26), navigate)
            SettingsFootnote("根据所处环境调整整体音量。")
        }
    }
    if (0x28 in state.controls) item("conversationAwareness") {
        Column {
            AvailableControls(state, listOf(0x28), navigate)
            SettingsFootnote("开始与他人讲话时降低媒体音量并减少背景噪声。")
        }
    }
    state.equalizer?.takeIf { it.size >= 4 }?.let { equalizer ->
        item("equalizer") {
            SettingsCard { RowItem("均衡器", value = if (equalizer[0] == 2) "自定" else "关闭") { navigate("equalizer") } }
        }
    }
    item("earDetection") {
        Column {
            SettingsCard { ToggleRow("自动入耳检测", "", state.earAutomation) { DeviceRepository.setEarAutomation(it) } }
            SettingsFootnote("戴上 AirPods 时自动播放音频，摘下时自动暂停。")
        }
    }
    item("spatial") {
        Section("空间音频") { RowItem("空间音频", value = state.spatial.mode?.label() ?: "—") { navigate("spatial") } }
    }
    ControlGroup("microphone", state, listOf(0x01), navigate)
    ControlGroup("sleepPause", state, listOf(0x35), navigate)
    item("systemAudio") {
        Column {
            SettingsCard { RowItem("音频与设备权限", action = onSystemBluetooth) }
            SettingsFootnote("在系统蓝牙中管理 AAC、音量同步和通话音频。")
        }
    }
}

private fun LazyListScope.GestureSettings(state: PodsState, navigate: (String) -> Unit) {
    if (state.controls[0x24]?.getOrNull(1)?.let { it in 2..3 } == true) item("callControls") { CallControlSettings(state, navigate) }
    ControlGroup("swipeControls", state, listOf(0x25, 0x23), navigate, "滑动控制")
    if (listOf(0x25, 0x23, 0x24).none { it in state.controls }) item("connection") { ConnectionNeeded(state, navigate) }
}

private fun LazyListScope.AccessibilitySettings(state: PodsState, navigate: (String) -> Unit) {
    if (0x17 in state.controls) item("pressSpeed") {
        Column {
            ControlChoices(state, 0x17, "按下速度", mapOf(1 to "较慢"))
            SettingsFootnote("调整按下 AirPods 两次或三次所需的速度。")
        }
    }
    if (0x18 in state.controls) item("pressDuration") {
        Column {
            ControlChoices(state, 0x18, "按住时长")
            SettingsFootnote("调整按住 AirPods 所需的时长。")
        }
    }
    if (0x1b in state.controls || 0x34 in state.controls) item("noiseControls") {
        Column {
            AvailableControls(state, listOf(0x1b, 0x34), navigate, "噪声控制")
            if (0x1b in state.controls) SettingsFootnote("允许仅一只 AirPod 在耳中时，进入降噪模式。")
        }
    }
    ControlGroup("chimeVolume", state, listOf(0x1f), navigate, "音效音量")
    if (0x2e in state.controls) item("adaptiveAudio") {
        Column { GlasmioSectionTitle("自适应音频"); AdaptiveStrengthControl(state) }
    }
    if (0x0a in state.controls) item("sensors") {
        Column {
            AvailableControls(state, listOf(0x0a), navigate, "耳机传感器")
            SettingsFootnote("此选项控制耳机的传感器配置，自动播放与暂停由“音频与路径”中的“自动入耳检测”管理。")
        }
    }
    if (listOf(0x17, 0x18, 0x1b, 0x34, 0x1f, 0x2e, 0x0a).none { it in state.controls }) {
        item("connection") { ConnectionNeeded(state, navigate) }
    }
}

private fun LazyListScope.ControlGroup(key: String, state: PodsState, ids: List<Int>, navigate: (String) -> Unit, title: String? = null) {
    if (ids.any { state.controls[it]?.isNotEmpty() == true }) item(key) { AvailableControls(state, ids, navigate, title) }
}

@Composable
private fun AvailableControls(state: PodsState, ids: List<Int>, navigate: (String) -> Unit, title: String? = null) {
    val specs = ids.mapNotNull { id -> Controls.all.firstOrNull { it.id == id && state.controls[id]?.firstOrNull() != null } }
    if (specs.isEmpty()) return
    val content: @Composable ColumnScope.() -> Unit = { specs.forEach { ControlSetting(it, state, navigate) } }
    if (title == null) SettingsCard(content) else Section(title, content)
}

@Composable
private fun ControlSetting(spec: ControlSpec, state: PodsState, navigate: (String) -> Unit) {
    val current = state.controls[spec.id]?.firstOrNull() ?: return
    val pending = spec.id in state.pending
    val enabled = state.connected && !pending && spec.writable
    if (spec.range != null) {
        val submitted = if (pending) PodsService.pendingControlValue(spec.id) else null
        var draft by remember(state.address, spec.id, current, pending, submitted) {
            mutableFloatStateOf((if (pending) submitted ?: current else current).toFloat())
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(spec.title, style = GlasmioTheme.textStyles.body)
                Text(draft.toInt().toString(), style = GlasmioTheme.textStyles.body, color = GlasmioTheme.colors.secondaryContent)
            }
            GlasmioSlider(draft, { draft = it }, enabled = enabled,
                valueRange = spec.range.first.toFloat()..spec.range.last.toFloat(),
                onValueChangeFinished = {
                    val target = draft.toInt().coerceIn(spec.range)
                    if (enabled && target != current) PodsService.setControl(spec.id, target)
                })
        }
    } else if (spec.toggle) {
        val title = when (spec.id) {
            0x0a -> "耳机侧入耳检测"
            0x1b -> "一只 AirPod 入耳时使用降噪"
            else -> spec.title
        }
        ToggleRow(title, "", current == 1, enabled) {
            PodsService.setControl(spec.id, if (it) 1 else 2)
        }
    } else RowItem(spec.title, enabled = enabled, value = spec.choices.firstOrNull { it.value == current }?.label ?: "—") {
        navigate("control/${spec.id}")
    }
}

@Composable
private fun ControlChoices(state: PodsState, id: Int?, title: String? = null, labels: Map<Int, String> = emptyMap()) {
    val spec = Controls.all.firstOrNull { it.id == id } ?: return
    val current = state.controls[spec.id]?.firstOrNull() ?: return
    val enabled = state.connected && spec.id !in state.pending && spec.writable
    val content: @Composable ColumnScope.() -> Unit = {
        spec.choices.forEachIndexed { index, choice ->
            GlasmioListRow(title = labels[choice.value] ?: choice.label,
                onClick = { if (choice.value != current) PodsService.setControl(spec.id, choice.value) },
                enabled = enabled, showTopSeparator = index > 0, showDisclosure = false,
                trailing = { if (current == choice.value) SfIcon("checkmark", null, size = 17.dp, tint = GlasmioTheme.colors.accent) })
        }
    }
    if (title == null) SettingsCard(content) else Section(title, content)
}

private fun LazyListScope.SpatialSettings(state: PodsState) {
    val spatial = state.spatial
    val presentation = spatial.spatialPresentation(state.connected)
    val tracking = spatial.trackingRequested || spatial.nativeActive
    val ready = state.connected && spatial.route && spatial.bridge && !spatial.stopping && !spatial.modeChanging
    item("spatialModes") {
        Column {
            Section("空间音频") {
                SpatialMode.entries.forEachIndexed { index, option ->
                    val enabled = when (option) {
                        SpatialMode.OFF -> presentation.offEnabled
                        SpatialMode.FIXED -> presentation.fixedEnabled
                        SpatialMode.HEAD_TRACKING -> presentation.trackingEnabled
                    }
                    GlasmioListRow(option.label(), enabled = enabled, showDisclosure = false, showTopSeparator = index > 0,
                        onClick = { PodsService.spatialMode(option) },
                        trailing = { if (presentation.selected == option) SfIcon("checkmark", null, size = 17.dp, tint = GlasmioTheme.colors.accent) })
                }
            }
            SettingsFootnote("固定模式让声音环绕在你周围。头部追踪会在转头时保持声源的方向。")
        }
    }
    item("headTracking") {
        Column {
            Section("头部追踪") {
                if (spatial.calibrationMode) {
                    val direction = listOf("向右转头约 30°", "抬头约 25°", "向右侧倾约 25°").getOrElse(spatial.calibrationStep) { "回正" }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("第 ${spatial.calibrationStep + 1} 步，共 3 步", style = GlasmioTheme.textStyles.headline)
                        Text(when {
                            spatial.calibrationPreparing -> "请保持双耳佩戴，正在连接耳机。"
                            spatial.collecting -> "缓慢${direction}，保持片刻，再回到正前方。"
                            else -> "正视手机，开始后${direction}。"
                        }, style = GlasmioTheme.textStyles.body)
                    }
                    RowItem(if (spatial.collecting) "完成本步" else "开始", enabled = !spatial.calibrationPreparing) { PodsService.spatialCalibrate() }
                    RowItem("取消校准") { PodsService.spatialCancelCalibration() }
                } else {
                    RowItem(if (spatial.calibrated) "重新校准" else "三轴校准", enabled = ready && !tracking) { PodsService.spatialBeginCalibration() }
                    RowItem("正视手机并回中", enabled = spatial.nativeActive && !spatial.trackingStarting) { PodsService.spatialRecenter() }
                }
            }
            if (!spatial.calibrated) SettingsFootnote("首次使用头部追踪前，请先完成三轴校准。")
            if (spatial.notice.isNotBlank()) SettingsFootnote(spatial.notice)
        }
    }
}

@Composable
private fun EqualizerSettings(state: PodsState) {
    val confirmed = state.equalizer?.takeIf { it.size >= 4 } ?: return
    val busy = PodsService.EQ_COMMAND in state.pending
    var draft by remember(confirmed, busy) { mutableStateOf(confirmed) }
    Section("均衡器") {
        ToggleRow("自定均衡器", "", confirmed[0] == 2, state.connected && !busy) {
            PodsService.setEqualizer(listOf(if (it) 2 else 1) + confirmed.drop(1))
        }
        if (confirmed[0] == 2) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("低频", "中频", "高频").forEachIndexed { index, label ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, style = GlasmioTheme.textStyles.body)
                    GlasmioSlider(draft[index + 1].toFloat(), { value -> draft = draft.toMutableList().also { it[index + 1] = value.toInt() } },
                        enabled = state.connected && !busy, valueRange = 0f..100f,
                        onValueChangeFinished = { PodsService.setEqualizer(draft) })
                }
            }
        }
    }
}

@Composable
private fun CallControlSettings(state: PodsState, navigate: (String) -> Unit) {
    val value = state.controls[0x24]?.getOrNull(1) ?: return
    if (value !in 2..3) return
    Section("通话控制") {
        GlasmioListRow("接听通话", detail = "按一下", showTopSeparator = false)
        RowItem("静音与取消静音", value = if (value == 2) "按两下" else "按一下") { navigate("call/mute") }
        RowItem("结束通话", value = if (value == 2) "按一下" else "按两下") { navigate("call/end") }
    }
}

@Composable
private fun CallControlChoices(state: PodsState, action: String) {
    val value = state.controls[0x24]?.getOrNull(1)?.takeIf { it in 2..3 } ?: return
    val onePress = if (action == "end") value == 2 else value == 3
    SettingsCard {
        listOf("按一下", "按两下").forEachIndexed { index, title ->
            val selected = if (index == 0) onePress else !onePress
            GlasmioListRow(title, showTopSeparator = index > 0, showDisclosure = false,
                enabled = state.connected && 0x24 !in state.pending,
                onClick = {
                    val endWithOnePress = if (action == "end") index == 0 else index == 1
                    PodsService.setCallControl(endWithOnePress)
                }, trailing = { if (selected) SfIcon("checkmark", null, size = 17.dp, tint = GlasmioTheme.colors.accent) })
        }
    }
}

@Composable
private fun BatterySettings(state: PodsState) {
    Section("AirPods 5") {
        listOf(4 to "左 AirPod", 2 to "右 AirPod", 8 to "充电盒").forEachIndexed { index, (id, title) ->
            val battery = batteryPresentation(state.batteries[id])
            GlasmioListRow(title = title, subtitle = battery.note.takeIf { it == "充电中" || it == "上次更新" },
                detail = battery.label, showTopSeparator = index > 0,
                trailing = { BatteryGlyph(battery) })
        }
    }
}

private fun LazyListScope.ModuleSettings(state: PodsState, onOverlay: () -> Unit, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit) {
    item("appearance") {
        val style by AppearancePreferences.style.collectAsState()
        val changeStyle = LocalChangeUiStyle.current
        Section("外观") {
            ToggleRow(style.label, "切换界面风格", style == UiStyle.IOS, onChange = changeStyle)
        }
    }

    item("popups") {
        Section("弹窗与提醒") {
            ToggleRow("开盖弹窗", "", state.popupEnabled) { DeviceRepository.setPopup(it) }
            ToggleRow("系统原生弹窗", "", state.nativePopup) { DeviceRepository.setNativePopup(it) }
            ToggleRow("连接电量卡片", "", state.connectionPopup) { DeviceRepository.setConnectionPopup(it) }
        }
    }
    item("previews") {
        SettingsCard {
            RowItem("电量卡片预览") { PodsService.previewPopup() }
            if (state.nativePopup) RowItem("超级岛预览") { PodsService.previewIsland() }
        }
    }
    item("systemIntegration") {
        Section("系统集成") {
            RowItem("系统原生适配") { FrameworkBridge.requestNativeScope() }
            RowItem("系统蓝牙入口") { FrameworkBridge.requestSettingsScope() }
            RowItem("悬浮显示权限", action = onOverlay)
            RowItem("系统蓝牙设置", action = onSystemBluetooth)
        }
    }
    item("moduleInformation") {
        SettingsCard {
            RowItem("连接设置") { navigate("connection") }
            RowItem("连接诊断") { navigate("diagnostics") }
            RowItem("关于 HyperPods-X") { navigate("about") }
        }
    }
}

private fun LazyListScope.ConnectionSettings(state: PodsState, onConnect: () -> Unit, onSystemBluetooth: () -> Unit) {
    item("connectionStatus") {
        SettingsCard {
            InfoRow("AirPods", state.displayName)
            InfoRow("状态", if (state.connected) "已连接" else "未连接")
            RowItem("系统蓝牙", action = onSystemBluetooth)
            RowItem(if (state.running) "重新连接" else "连接耳机") { if (state.running) PodsService.reconnect() else onConnect() }
        }
    }
    val candidates = if (state.address.isEmpty()) DeviceRepository.candidates() else emptyList()
    if (candidates.size > 1) item("candidates") {
        Section("选择 AirPods") {
            candidates.forEach { device ->
                val name = try { device.name } catch (_: SecurityException) { null }
                RowItem(name ?: "AirPods") { DeviceRepository.bind(device) }
            }
        }
    }
    item("connectionHelp") { SettingsFootnote("先在系统蓝牙中连接 AirPods，再允许附近设备、位置和后台运行权限。") }
    if (state.running) item("stopService") {
        SettingsCard {
            GlasmioListRow("停止耳机服务", titleColor = GlasmioTheme.colors.destructive, showTopSeparator = false, showDisclosure = false,
                onClick = { DeviceRepository.stop() })
        }
    }
}

@Composable
private fun ConnectionNeeded(state: PodsState, navigate: (String) -> Unit) {
    SettingsCard { RowItem(if (state.connected) "连接设置" else "连接 AirPods") { navigate("connection") } }
}

private fun LazyListScope.AboutSettings(state: PodsState, navigate: (String) -> Unit) {
    item("information") {
        SettingsCard {
            InfoRow("名称", state.displayName)
            InfoRow("型号", state.model.ifBlank { "—" })
            InfoRow("固件版本", state.firmware.ifBlank { "—" })
            InfoRow("HyperPods-X", BuildConfig.VERSION_NAME)
        }
    }
    item("licenses") { SettingsCard { RowItem("开源许可") { navigate("licenses") } } }
}

private fun LazyListScope.LicenseSettings() {
    for ((title, file) in listOf("第三方声明" to "NOTICES.md", "图片来源" to "ARTWORK-SOURCES.md", "GPL-3.0-or-later" to "GPL-3.0.txt", "Apache-2.0" to "Apache-2.0.txt")) {
        item(file) {
            val content = remember(file) { DeviceRepository.app.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
            Section(title) { Text(content, style = GlasmioTheme.textStyles.footnote, modifier = Modifier.padding(16.dp)) }
        }
    }
}

private fun LazyListScope.DiagnosticsSettings(state: PodsState) {
    item("environment") {
        Section("当前环境") {
            InfoRow("模块框架", state.framework)
            InfoRow("控制通道", if (state.connected) "已连接" else "未连接")
            InfoRow("原生弹窗", if (state.nativePopup) state.nativePopupStatus else "已关闭")
            InfoRow("绑定身份", if (state.identityReady) "已确认" else "等待密钥")
            InfoRow("广播 / 已验证", "${state.broadcasts} / ${state.trustedBroadcasts}")
            InfoRow("邻近广播 / 身份匹配", "${state.proximityCandidates} / ${state.identityMatches}")
            InfoRow("盒盖", when (state.lidOpen) { true -> "已打开"; false -> "已关闭"; null -> "等待广播" })
            InfoRow("设备报告的设置项", state.controls.size.toString())
            InfoRow("佩戴状态", wearingDescription(state))
            InfoRow("自动入耳检测", if (state.earAutomation) state.earDetectionStatus else "已关闭")
        }
    }
    item("connectionLog") {
        Section("连接记录") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.log.takeLast(15).forEach { Text(it, style = GlasmioTheme.textStyles.caption1) }
            }
        }
    }
    item("diagnosticScan") { Action("2 分钟广播诊断", Modifier.fillMaxWidth()) { PodsService.diagnoseScan() } }
}
