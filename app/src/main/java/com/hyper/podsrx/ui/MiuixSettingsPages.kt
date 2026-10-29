package com.hyper.podsrx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyper.podsrx.BuildConfig
import com.hyper.podsrx.device.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.miuixSettingsPageContent(
    page: String, state: PodsState, onConnect: () -> Unit, onOverlay: () -> Unit,
    onSystemBluetooth: () -> Unit, navigate: (String) -> Unit,
) {
    when {
        page.startsWith("control/") -> controlChoices(state, page.substringAfter('/').toIntOrNull())
        page.startsWith("call/") -> callChoices(state, page.substringAfter('/'))
        page == "audio" -> audioSettings(state, onSystemBluetooth, navigate)
        page == "controls" -> gestureSettings(state, navigate)
        page == "accessibility" -> accessibilitySettings(state, navigate)
        page == "spatial" -> spatialSettings(state)
        page == "equalizer" -> equalizerSettings(state)
        page == "battery" -> batterySettings(state)
        page == "module" -> moduleSettings(state, onOverlay, onSystemBluetooth, navigate)
        page == "connection" -> connectionSettings(state, onConnect, onSystemBluetooth)
        page == "diagnostics" -> diagnosticsSettings(state)
        page == "about" -> aboutSettings(state, navigate)
        page == "licenses" -> licenseSettings()
    }
}

private fun LazyListScope.audioSettings(state: PodsState, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit) {
    SettingsCatalog.audioSectionOrder.forEach { key ->
        when (key) {
            "equalizer" -> state.equalizer?.takeIf { it.size >= 4 }?.let { equalizer ->
                miuixGroupedItems(key, listOf(key)) {
                    RowItem("均衡器", value = if (equalizer[0] == 2) "自定" else "关闭") { navigate("equalizer") }
                }
            }
            "earDetection" -> {
                miuixGroupedItems(key, listOf(SettingsToggle.EAR_AUTOMATION)) { toggle ->
                    ToggleRow(toggle.title, "", toggle.checked(state), onChange = DeviceRepository::setEarAutomation)
                }
                miuixFootnote("earDetectionHelp", "戴上 AirPods 时自动播放音频，摘下时自动暂停。")
            }
            "spatial" -> miuixGroupedItems(key, listOf(key), title = "空间音频") {
                RowItem("空间音频", value = state.spatial.mode?.label() ?: "—") { navigate(key) }
            }
            "systemAudio" -> {
                miuixGroupedItems(key, listOf(key)) { RowItem("音频与设备权限", action = onSystemBluetooth) }
                miuixFootnote("systemAudioHelp", "在系统蓝牙中管理 AAC、音量同步和通话音频。")
            }
            else -> SettingsCatalog.audioControlGroups.firstOrNull { it.key == key }?.let { group ->
                controlGroup(state, group, navigate)
                if (group == SettingsCatalog.personalizedVolume && 0x26 in state.controls)
                    miuixFootnote("personalizedVolumeHelp", "根据所处环境调整整体音量。")
                if (group == SettingsCatalog.conversationAwareness && 0x28 in state.controls)
                    miuixFootnote("conversationAwarenessHelp", "开始与他人讲话时降低媒体音量并减少背景噪声。")
            }
        }
    }
}

private fun LazyListScope.gestureSettings(state: PodsState, navigate: (String) -> Unit) {
    SettingsCatalog.gestureSectionOrder.forEach { key ->
        when (key) {
            "callControls" -> state.controls[0x24]?.getOrNull(1)?.takeIf { it in 2..3 }?.let { value ->
                miuixGroupedItems(key, listOf("answer", "mute", "end"), title = "通话控制") { row ->
                    when (row) {
                        "answer" -> InfoRow("接听通话", "按一下")
                        "mute" -> RowItem("静音与取消静音", value = if (value == 2) "按两下" else "按一下") { navigate("call/mute") }
                        "end" -> RowItem("结束通话", value = if (value == 2) "按一下" else "按两下") { navigate("call/end") }
                    }
                }
            }
            "connection" -> if (SettingsCatalog.gesturePresenceIds.none { it in state.controls }) connectionNeeded(state, navigate)
            else -> SettingsCatalog.gestureControlGroups.firstOrNull { it.key == key }?.let { controlGroup(state, it, navigate) }
        }
    }
}

private fun LazyListScope.accessibilitySettings(state: PodsState, navigate: (String) -> Unit) {
    SettingsCatalog.accessibilitySectionOrder.forEach { key ->
        if (key == "connection") {
            if (SettingsCatalog.accessibilityPresenceIds.none { it in state.controls }) connectionNeeded(state, navigate)
            return@forEach
        }
        val group = SettingsCatalog.accessibilityControlGroups.firstOrNull { it.key == key } ?: return@forEach
        when (group.key) {
            SettingsCatalog.pressSpeed.key, SettingsCatalog.pressDuration.key -> {
                if (group.controlIds.single() in state.controls) {
                    controlChoices(state, group.controlIds.single(), group.title, group.choiceLabels, group.key)
                    miuixFootnote("${group.key}Help", if (group == SettingsCatalog.pressSpeed)
                        "调整按下 AirPods 两次或三次所需的速度。" else "调整按住 AirPods 所需的时长。")
                }
            }
            SettingsCatalog.adaptiveAudio.key -> if (0x2e in state.controls) {
                miuixHeader(group.key + ".title", requireNotNull(group.title))
                miuixItem(group.key) { SettingsCard { Box(Modifier.padding(24.dp)) { AdaptiveStrengthControl(state) } } }
            }
            else -> {
                controlGroup(state, group, navigate)
                if (group == SettingsCatalog.noiseControls && 0x1b in state.controls) {
                    miuixFootnote("noiseControlsHelp", "允许仅一只 AirPod 在耳中时，进入降噪模式。")
                }
                if (group == SettingsCatalog.sensors && 0x0a in state.controls) {
                    miuixFootnote("sensorsHelp", "此选项控制耳机的传感器配置，自动播放与暂停由“音频与路径”中的“自动入耳检测”管理。")
                }
            }
        }
    }
}

private fun LazyListScope.controlGroup(state: PodsState, group: SettingsControlGroup, navigate: (String) -> Unit) {
    val specs = SettingsCatalog.availableControls(state, group)
    miuixGroupedItems(group.key, specs, title = group.title, itemKey = { it.id }) { ControlSetting(it, state, navigate) }
}

@Composable
private fun ControlSetting(spec: ControlSpec, state: PodsState, navigate: (String) -> Unit) {
    val current = state.controls[spec.id]?.firstOrNull() ?: return
    val enabled = SettingsCatalog.controlEnabled(state, spec)
    when {
        spec.range != null -> {
            val pending = spec.id in state.pending
            val submitted = if (pending) PodsService.pendingControlValue(spec.id) else null
            var draft by remember(state.address, spec.id, current, pending, submitted) {
                mutableFloatStateOf((if (pending) submitted ?: current else current).toFloat())
            }
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(spec.title, style = MiuixTheme.textStyles.headline1)
                    Text(draft.toInt().toString(), fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantActions
                            else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                        textAlign = TextAlign.End)
                }
                Slider(draft, { draft = it }, enabled = enabled,
                    valueRange = spec.range.first.toFloat()..spec.range.last.toFloat(),
                    onValueChangeFinished = {
                        val target = draft.toInt().coerceIn(spec.range)
                        if (enabled && target != current) PodsService.setControl(spec.id, target)
                    })
            }
        }
        spec.toggle -> ToggleRow(SettingsCatalog.toggleTitle(spec), "", current == 1, enabled) {
            PodsService.setControl(spec.id, if (it) 1 else 2)
        }
        else -> RowItem(spec.title, enabled = enabled,
            value = spec.choices.firstOrNull { it.value == current }?.label ?: "—") {
            navigate(SettingsCatalog.controlRoute(spec.id))
        }
    }
}

private fun LazyListScope.controlChoices(
    state: PodsState, id: Int?, title: String? = null, labels: Map<Int, String> = emptyMap(), key: String = "controlChoices",
) {
    val spec = Controls.all.firstOrNull { it.id == id } ?: return
    val current = state.controls[spec.id]?.firstOrNull() ?: return
    val enabled = SettingsCatalog.controlEnabled(state, spec)
    miuixGroupedItems(key, spec.choices, title = title, itemKey = { it.value }) { choice ->
        ChoiceRow(labels[choice.value] ?: choice.label, current == choice.value, enabled) {
            if (choice.value != current) PodsService.setControl(spec.id, choice.value)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    BasicComponent(title = title, onClick = onClick, enabled = enabled, role = Role.RadioButton,
        modifier = Modifier.semantics { selected = checked },
        endActions = { RadioButton(checked, null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {}) })
}

private fun LazyListScope.spatialSettings(state: PodsState) {
    val spatial = state.spatial
    val presentation = spatial.spatialPresentation(state.connected)
    val tracking = spatial.trackingRequested || spatial.nativeActive
    val ready = state.connected && spatial.route && spatial.bridge && !spatial.stopping && !spatial.modeChanging
    miuixGroupedItems("spatialModes", SpatialMode.entries, title = "空间音频") { option ->
        ChoiceRow(option.label(), presentation.selected == option, presentation.canSelect(option)) {
            PodsService.spatialMode(option)
        }
    }
    miuixFootnote("spatialHelp", "固定模式让声音环绕在你周围。头部追踪会在转头时保持声源的方向。")
    val items = if (spatial.calibrationMode) listOf("instructions", "start", "cancel") else listOf("calibrate", "recenter")
    miuixGroupedItems("headTracking", items, title = "头部追踪") { key ->
        when (key) {
            "instructions" -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val direction = listOf("向右转头约 30°", "抬头约 25°", "向右侧倾约 25°").getOrElse(spatial.calibrationStep) { "回正" }
                Text("第 ${spatial.calibrationStep + 1} 步，共 3 步", style = MiuixTheme.textStyles.headline1)
                Text(when {
                    spatial.calibrationPreparing -> "请保持双耳佩戴，正在连接耳机。"
                    spatial.collecting -> "缓慢${direction}，保持片刻，再回到正前方。"
                    else -> "正视手机，开始后${direction}。"
                })
            }
            "start" -> RowItem(if (spatial.collecting) "完成本步" else "开始", enabled = !spatial.calibrationPreparing) { PodsService.spatialCalibrate() }
            "cancel" -> RowItem("取消校准") { PodsService.spatialCancelCalibration() }
            "calibrate" -> RowItem(if (spatial.calibrated) "重新校准" else "三轴校准", enabled = ready && !tracking) { PodsService.spatialBeginCalibration() }
            "recenter" -> RowItem("正视手机并回中", enabled = spatial.nativeActive && !spatial.trackingStarting) { PodsService.spatialRecenter() }
        }
    }
    if (!spatial.calibrated) miuixFootnote("calibrationHelp", "首次使用头部追踪前，请先完成三轴校准。")
    if (spatial.notice.isNotBlank()) miuixFootnote("spatialNotice", spatial.notice)
}

private fun LazyListScope.equalizerSettings(state: PodsState) {
    val confirmed = state.equalizer?.takeIf { it.size >= 4 } ?: return
    val busy = PodsService.EQ_COMMAND in state.pending
    val enabled = state.connected && !busy
    val items = if (confirmed[0] == 2) listOf("enabled", "bass", "mid", "treble") else listOf("enabled")
    miuixGroupedItems("equalizer", items, title = "均衡器") { key ->
        if (key == "enabled") ToggleRow("自定均衡器", "", confirmed[0] == 2, enabled) {
            PodsService.setEqualizer(listOf(if (it) 2 else 1) + confirmed.drop(1))
        } else {
            val index = listOf("bass", "mid", "treble").indexOf(key) + 1
            var draft by remember(confirmed, busy) { mutableFloatStateOf(confirmed[index].toFloat()) }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(listOf("低频", "中频", "高频")[index - 1], style = MiuixTheme.textStyles.headline1)
                Slider(draft, { draft = it }, enabled = enabled, valueRange = 0f..100f,
                    onValueChangeFinished = {
                        // Copy the latest confirmed bands so a single slider never rewrites its siblings.
                        PodsService.setEqualizer(confirmed.toMutableList().also { it[index] = draft.toInt() })
                    })
            }
        }
    }
}

private fun LazyListScope.callChoices(state: PodsState, action: String) {
    val value = state.controls[0x24]?.getOrNull(1)?.takeIf { it in 2..3 } ?: return
    val onePress = if (action == "end") value == 2 else value == 3
    miuixGroupedItems("callChoices", listOf(0, 1)) { index ->
        ChoiceRow(if (index == 0) "按一下" else "按两下", if (index == 0) onePress else !onePress,
            state.connected && 0x24 !in state.pending) {
            PodsService.setCallControl(if (action == "end") index == 0 else index == 1)
        }
    }
}

private fun LazyListScope.batterySettings(state: PodsState) {
    miuixGroupedItems("battery", listOf(4 to "左 AirPod", 2 to "右 AirPod", 8 to "充电盒"),
        title = state.headset.name, itemKey = { it.first }) { (id, title) ->
        val battery = batteryPresentation(state.batteries[id])
        BasicComponent(title = title, summary = battery.note.takeIf { it == "充电中" }, endActions = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(battery.label, fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                BatteryGlyph(battery)
            }
        })
    }
}

private fun LazyListScope.moduleSettings(state: PodsState, onOverlay: () -> Unit, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit) {
    SettingsCatalog.moduleSections.forEach { section ->
        val visible = section.items.filter { it != "islandPreview" || state.nativePopup }
        miuixGroupedItems(section.key, visible, title = section.title) { key ->
            val toggle = SettingsCatalog.popupToggles.firstOrNull { it.key == key }
            if (toggle != null) ToggleRow(toggle.title, "", toggle.checked(state)) { checked ->
                when (toggle) {
                    SettingsToggle.OPEN_CASE_POPUP -> DeviceRepository.setPopup(checked)
                    SettingsToggle.NATIVE_POPUP -> DeviceRepository.setNativePopup(checked)
                    SettingsToggle.CONNECTION_POPUP -> DeviceRepository.setConnectionPopup(checked)
                    SettingsToggle.BATTERY_ALERTS -> DeviceRepository.setBatteryAlerts(checked)
                    SettingsToggle.EAR_AUTOMATION -> DeviceRepository.setEarAutomation(checked)
                }
            } else when (key) {
                "style" -> {
                    val style by AppearancePreferences.style.collectAsStateWithLifecycle()
                    val changeStyle = LocalChangeUiStyle.current
                    WindowDropdownPreference(title = SettingsCatalog.moduleLabel(key),
                        items = UiStyle.entries.map { it.label }, selectedIndex = style.ordinal,
                        onSelectedIndexChange = { index -> if (index != style.ordinal) changeStyle(UiStyle.entries[index]) })
                }
                "batteryPreview" -> RowItem(SettingsCatalog.moduleLabel(key)) { PodsService.previewPopup() }
                "islandPreview" -> RowItem(SettingsCatalog.moduleLabel(key)) { PodsService.previewIsland() }
                "nativeScope" -> RowItem(SettingsCatalog.moduleLabel(key), "在 LSPosed 中授权系统设置、小米蓝牙、设备互联和手机管家") { FrameworkBridge.requestNativeScope() }
                "restartHosts" -> {
                    val restart by HostRestartCoordinator.state.collectAsStateWithLifecycle()
                    RowItem(if (restart.running) "正在重启作用域…" else SettingsCatalog.moduleLabel(key),
                        restart.message, enabled = !restart.running, action = HostRestartCoordinator::restart)
                }
                "overlayPermission" -> RowItem(SettingsCatalog.moduleLabel(key), action = onOverlay)
                "bluetoothSettings" -> RowItem(SettingsCatalog.moduleLabel(key), action = onSystemBluetooth)
                "exportLogs" -> {
                    val logExport = LocalLogExport.current
                    RowItem(if (logExport.busy) "正在导出日志…" else SettingsCatalog.moduleLabel(key), logExport.status,
                        enabled = !logExport.busy, action = logExport.export)
                }
                "connection", "diagnostics", "about" -> RowItem(SettingsCatalog.moduleLabel(key)) { navigate(key) }
            }
        }
    }
}

private fun LazyListScope.connectionSettings(state: PodsState, onConnect: () -> Unit, onSystemBluetooth: () -> Unit) {
    miuixGroupedItems("connectionStatus", listOf("name", "status", "bluetooth", "connect")) { key ->
        when (key) {
            "name" -> InfoRow("AirPods", state.displayName)
            "status" -> InfoRow("状态", if (state.connected) "已连接" else "未连接")
            "bluetooth" -> RowItem("系统蓝牙", action = onSystemBluetooth)
            "connect" -> RowItem(if (state.running) "重新连接" else "连接耳机") {
                if (state.running) PodsService.reconnect() else onConnect()
            }
        }
    }
    val candidates = if (state.address.isEmpty()) DeviceRepository.candidates() else emptyList()
    if (candidates.size > 1) miuixGroupedItems("candidates", candidates, title = "选择 AirPods", itemKey = { it.address }) { device ->
        val name = try { device.name } catch (_: SecurityException) { null }
        RowItem(name ?: "AirPods") { DeviceRepository.bind(device) }
    }
    miuixFootnote("connectionHelp", "先在系统蓝牙中连接 AirPods，再允许附近设备、位置和后台运行权限。")
    if (state.running) miuixGroupedItems("stopService", listOf("stop")) {
        BasicComponent(onClick = DeviceRepository::stop) {
            Text("停止耳机服务", color = MiuixStatusColors.lowBattery, style = MiuixTheme.textStyles.headline1)
        }
    }
}

private fun LazyListScope.connectionNeeded(state: PodsState, navigate: (String) -> Unit) {
    miuixGroupedItems("connectionNeeded", listOf("connection")) {
        RowItem(if (state.connected) "连接设置" else "连接 AirPods") { navigate("connection") }
    }
}

private fun LazyListScope.aboutSettings(state: PodsState, navigate: (String) -> Unit) {
    val information = listOf("名称" to state.displayName, "型号" to state.model.ifBlank { "—" },
        "固件版本" to state.firmware.ifBlank { "—" }, "HyperPods-X" to BuildConfig.VERSION_NAME)
    miuixGroupedItems("information", information, itemKey = { it.first }) { (title, value) -> InfoRow(title, value) }
    miuixGroupedItems("licenses", listOf("licenses")) { RowItem("开源许可") { navigate("licenses") } }
}

private fun LazyListScope.licenseSettings() {
    listOf("第三方声明" to "NOTICES.md", "图片来源" to "ARTWORK-SOURCES.md",
        "GPL-3.0-or-later" to "GPL-3.0.txt", "Apache-2.0" to "Apache-2.0.txt").forEach { (title, file) ->
        miuixHeader(file + ".title", title)
        miuixItem(file) {
            val content = remember(file) { DeviceRepository.app.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
            SettingsCard { Text(content, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(24.dp)) }
        }
    }
}

private fun LazyListScope.diagnosticsSettings(state: PodsState) {
    val environment = buildList {
        add("模块框架" to state.framework)
        add("控制通道" to if (state.connected) "已连接" else "未连接")
        add("原生弹窗" to if (state.nativePopup) state.nativePopupStatus else "已关闭")
        add("状态中心" to state.hubSummary)
        state.hostCapabilities.forEach { (process, results) -> add(process to capabilitySummary(results)) }
        add("绑定身份" to if (state.identityReady) "已确认" else "等待密钥")
        add("广播 / 已验证" to "${state.broadcasts} / ${state.trustedBroadcasts}")
        add("邻近广播 / 身份匹配" to "${state.proximityCandidates} / ${state.identityMatches}")
        add("盒盖" to when (state.lidOpen) { true -> "已打开"; false -> "已关闭"; null -> "等待广播" })
        add("设备报告的设置项" to state.controls.size.toString())
        add("佩戴状态" to wearingDescription(state))
        add("自动入耳检测" to if (state.earAutomation) state.earDetectionStatus else "已关闭")
    }
    miuixGroupedItems("environment", environment, title = "当前环境", itemKey = { it.first }) { (title, value) -> InfoRow(title, value) }
    miuixGroupedItems("connectionLog", state.log.takeLast(15).withIndex().toList(), title = "连接记录", itemKey = { it.index }) { entry ->
        Text(entry.value, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    }
}
