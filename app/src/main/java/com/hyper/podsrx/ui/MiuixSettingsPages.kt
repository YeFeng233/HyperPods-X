package com.hyper.podsrx.ui

import android.annotation.SuppressLint
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
import com.hyper.podsrx.R
import com.hyper.podsrx.device.*
import com.hyper.podsrx.i18n.AppLanguage
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.i18n.currentAppLanguage
import com.hyper.podsrx.i18n.rememberStrings
import com.hyper.podsrx.i18n.setAppLanguage
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.miuixSettingsPageContent(
    page: String, state: PodsState, onConnect: () -> Unit,
    onSystemBluetooth: () -> Unit, navigate: (String) -> Unit, strings: Strings,
    floatingBottomBar: Boolean, glassBottomBar: Boolean,
) {
    when {
        page.startsWith("control/") -> controlChoices(state, page.substringAfter('/').toIntOrNull())
        page.startsWith("call/") -> callChoices(state, page.substringAfter('/'))
        page == "audio" -> audioSettings(state, onSystemBluetooth, navigate)
        page == "controls" -> gestureSettings(state, navigate)
        page == "accessibility" -> accessibilitySettings(state, navigate)
        page == "spatial" -> spatialSettings(state, strings)
        page == "equalizer" -> equalizerSettings(state)
        page == "module" -> moduleSettings(state, navigate, floatingBottomBar, glassBottomBar)
        page == "connection" -> connectionPage(state, strings, onConnect, onSystemBluetooth)
        page == "diagnostics" -> diagnosticsSettings(state, strings)
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
            "earDetection" -> miuixGroupedItems(key, listOf(SettingsToggle.EAR_AUTOMATION)) { toggle ->
                ToggleRow(toggle.title, "", toggle.checked(state), onChange = DeviceRepository::setEarAutomation)
            }
            "spatial" -> miuixGroupedItems(key, listOf(key)) {
                RowItem("空间音频", value = state.spatial.mode?.label(rememberStrings()) ?: "—") { navigate(key) }
            }
            "systemAudio" -> miuixGroupedItems(key, listOf(key)) {
                RowItem("音频与设备权限", action = onSystemBluetooth)
            }
            else -> SettingsCatalog.audioControlGroups.firstOrNull { it.key == key }?.let { group ->
                controlGroup(state, group, navigate)
            }
        }
    }
}

private fun LazyListScope.gestureSettings(state: PodsState, navigate: (String) -> Unit) {
    if (SettingsCatalog.gesturePresenceIds.none { it in state.controls }) {
        missingControls(state)
        return
    }
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
            else -> SettingsCatalog.gestureControlGroups.firstOrNull { it.key == key }?.let { controlGroup(state, it, navigate) }
        }
    }
}

private fun LazyListScope.accessibilitySettings(state: PodsState, navigate: (String) -> Unit) {
    if (SettingsCatalog.accessibilityPresenceIds.none { it in state.controls }) {
        missingControls(state)
        return
    }
    SettingsCatalog.accessibilityControlGroups.forEach { group ->
        when (group.key) {
            SettingsCatalog.pressSpeed.key, SettingsCatalog.pressDuration.key -> {
                if (group.controlIds.single() in state.controls)
                    controlChoices(state, group.controlIds.single(), group.title, group.choiceLabels, group.key)
            }
            SettingsCatalog.adaptiveAudio.key -> if (0x2e in state.controls) {
                miuixHeader(group.key + ".title", requireNotNull(group.title))
                miuixItem(group.key) { SettingsCard { Box(Modifier.padding(24.dp)) { AdaptiveStrengthControl(state) } } }
            }
            else -> controlGroup(state, group, navigate)
        }
    }
}

private fun LazyListScope.missingControls(state: PodsState) {
    item("missingControls") {
        val strings = rememberStrings()
        Text(strings(if (state.connected) R.string.settings_not_reported else R.string.earbuds_not_connected),
            modifier = Modifier.padding(horizontal = 28.dp),
            color = MiuixTheme.colorScheme.onSurfaceSecondary, style = MiuixTheme.textStyles.footnote1)
    }
}

private fun LazyListScope.controlGroup(state: PodsState, group: SettingsControlGroup, navigate: (String) -> Unit) {
    val specs = SettingsCatalog.availableControls(state, group)
    miuixGroupedItems(group.key, specs, title = group.title, itemKey = { it.id }) { ControlSetting(it, state, navigate) }
}

@Composable
private fun ControlSetting(spec: ControlSpec, state: PodsState, navigate: (String) -> Unit) {
    val strings = rememberStrings()
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
                    Text(strings(spec.titleRes), style = MiuixTheme.textStyles.headline1)
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
        spec.toggle -> ToggleRow(SettingsCatalog.toggleTitle(spec, strings), "", current == 1, enabled) {
            PodsService.setControl(spec.id, if (it) 1 else 2)
        }
        else -> RowItem(strings(spec.titleRes), enabled = enabled,
            value = spec.choices.firstOrNull { it.value == current }?.let { strings(it.labelRes) } ?: "—") {
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
        val strings = rememberStrings()
        ChoiceRow(labels[choice.value] ?: strings(choice.labelRes), current == choice.value, enabled) {
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

private fun LazyListScope.spatialSettings(state: PodsState, strings: Strings) {
    val spatial = state.spatial
    val presentation = spatial.spatialPresentation(state.connected, strings)
    val tracking = spatial.trackingRequested || spatial.nativeActive
    val ready = state.connected && spatial.route && spatial.bridge && !spatial.stopping && !spatial.modeChanging
    miuixGroupedItems("spatialModes", SpatialMode.entries, title = "空间音频") { option ->
        ChoiceRow(option.label(strings), presentation.selected == option, presentation.canSelect(option)) {
            PodsService.spatialMode(option)
        }
    }
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
    if (spatial.notice.isNotBlank()) miuixFootnote("spatialNotice", spatial.notice.resolve(strings))
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

private fun LazyListScope.moduleSettings(
    state: PodsState, navigate: (String) -> Unit, floatingBottomBar: Boolean, glassBottomBar: Boolean,
) {
    SettingsCatalog.tabbedModuleSections.forEach { section ->
        val items = if (section.key == "appearance") listOf("style", "floatingBottomBar", "glassBottomBar", "language")
            else section.items
        val visible = items.filter {
            when (it) {
                "islandPreview" -> state.nativePopup
                "glassBottomBar" -> floatingBottomBar
                else -> true
            }
        }
        miuixGroupedItems(section.key, visible, title = section.title) { key ->
            val strings = rememberStrings()
            val toggle = SettingsCatalog.popupToggles.firstOrNull { it.key == key }
            if (toggle != null) ToggleRow(toggle.title, "", toggle.checked(state)) { checked ->
                when (toggle) {
                    SettingsToggle.OPEN_CASE_POPUP -> DeviceRepository.setPopup(checked)
                    SettingsToggle.NATIVE_POPUP -> DeviceRepository.setNativePopup(checked)
                    SettingsToggle.CONNECTION_POPUP -> DeviceRepository.setConnectionPopup(checked)
                    SettingsToggle.BATTERY_ALERTS -> DeviceRepository.setBatteryAlerts(checked)
                    SettingsToggle.EAR_AUTOMATION -> DeviceRepository.setEarAutomation(checked)
                    SettingsToggle.AUTO_CONNECT -> DeviceRepository.setAutoConnect(checked)
                }
            } else when (key) {
                "audio", "controls", "accessibility" -> RowItem(SettingsCatalog.title(key, state, strings)) { navigate(key) }
                "style" -> {
                    val choose = LocalChangeUiStyle.current
                    GlassDropdownPreference(title = SettingsCatalog.moduleLabel(key), backdrop = LocalPageBackdrop.current,
                        items = UiStyle.entries.map { it.label }, selectedIndex = UiStyle.MIUIX.ordinal,
                        onSelectedIndexChange = { choose(UiStyle.entries[it]) })
                }
                "floatingBottomBar" -> {
                    ToggleRow(title = strings(R.string.floating_bottom_bar), subtitle = "", checked = floatingBottomBar,
                        onChange = AppearancePreferences::setFloatingBottomBar)
                }
                "glassBottomBar" -> {
                    ToggleRow(title = strings(R.string.glass_bottom_bar), subtitle = "", checked = glassBottomBar,
                        onChange = AppearancePreferences::setGlassBottomBar)
                }
                "language" -> {
                    val context = LocalContext.current
                    val language = currentAppLanguage()
                    GlassDropdownPreference(title = strings(R.string.app_language), backdrop = LocalPageBackdrop.current,
                        items = AppLanguage.entries.map { strings(it.labelRes) }, selectedIndex = language.ordinal,
                        onSelectedIndexChange = { setAppLanguage(context, AppLanguage.entries[it]) })
                }
                "islandPreview" -> RowItem(SettingsCatalog.moduleLabel(key)) { PodsService.previewIsland() }
                "exportLogs" -> {
                    val logExport = LocalLogExport.current
                    RowItem(if (logExport.busy) "正在导出日志…" else SettingsCatalog.moduleLabel(key), logExport.status,
                        enabled = !logExport.busy, action = logExport.export)
                }
                "diagnostics" -> RowItem(SettingsCatalog.moduleLabel(key)) { navigate(key) }
                "about" -> RowItem(strings(R.string.about)) { navigate(key) }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun LazyListScope.connectionPage(
    state: PodsState, strings: Strings, onConnect: () -> Unit, onSystemBluetooth: () -> Unit,
) {
    miuixGroupedItems("connection", listOf("status", "autoConnect", "bluetooth", "connect")) { key ->
        when (key) {
            "status" -> InfoRow(strings(R.string.status),
                strings(if (state.connected) R.string.connected else R.string.earbuds_not_connected))
            "autoConnect" -> ToggleRow(SettingsToggle.AUTO_CONNECT.title, "", state.autoConnect,
                onChange = DeviceRepository::setAutoConnect)
            "bluetooth" -> RowItem(strings(R.string.system_bluetooth), action = onSystemBluetooth)
            "connect" -> RowItem(strings(if (state.running) R.string.reconnect else R.string.connect_airpods),
                action = if (state.running) PodsService::reconnect else onConnect)
        }
    }
    val candidates = if (state.address.isBlank()) DeviceRepository.candidates() else emptyList()
    if (candidates.size > 1) miuixGroupedItems("connection.candidates", candidates,
        title = strings(R.string.choose_airpods_to_manage), itemKey = { it.address }) { device ->
        RowItem(device.name ?: "AirPods") { DeviceRepository.bind(device) }
    }
    if (state.messageCode == R.string.pair_airpods_first)
        miuixFootnote("connection.pairing", state.messageText.resolve(strings))
    if (state.running) miuixGroupedItems("connection.stop", listOf(Unit)) {
        BasicComponent(onClick = DeviceRepository::stop) {
            Text(strings(R.string.stop_earbud_service), color = MiuixStatusColors.lowBattery,
                style = MiuixTheme.textStyles.headline1)
        }
    }
    miuixGroupedItems("connection.information", listOf("name", "model", "number", "firmware"),
        title = strings(R.string.device_information)) { key ->
        when (key) {
            "name" -> InfoRow(strings(R.string.name), state.displayName)
            "model" -> InfoRow(strings(R.string.model_name), state.headset.name)
            "number" -> InfoRow(strings(R.string.model_number), state.model.ifBlank { "—" })
            "firmware" -> InfoRow(strings(R.string.firmware_version), state.firmware.ifBlank { "—" })
        }
    }
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

private fun LazyListScope.diagnosticsSettings(state: PodsState, strings: Strings) {
    val environment = buildList {
        add("模块框架" to state.framework.resolve(strings))
        add("控制通道" to if (state.connected) "已连接" else "未连接")
        add("原生弹窗" to if (state.nativePopup) state.nativePopupStatus.resolve(strings) else "已关闭")
        add("状态中心" to state.hubSummary(strings))
        state.hostCapabilities.forEach { (process, results) -> add(process to capabilitySummary(results, strings)) }
        add("绑定身份" to if (state.identityReady) "已确认" else "等待密钥")
        add("广播 / 已验证" to "${state.broadcasts} / ${state.trustedBroadcasts}")
        add("邻近广播 / 身份匹配" to "${state.proximityCandidates} / ${state.identityMatches}")
        add("盒盖" to when (state.lidOpen) { true -> "已打开"; false -> "已关闭"; null -> "等待广播" })
        add("设备报告的设置项" to state.controls.size.toString())
        add("佩戴状态" to wearingDescription(state, strings))
        add("自动入耳检测" to if (state.earAutomation) state.earDetectionStatus.resolve(strings) else "已关闭")
    }
    miuixGroupedItems("environment", environment, title = "当前环境", itemKey = { it.first }) { (title, value) -> InfoRow(title, value) }
    miuixGroupedItems("connectionLog", state.log.takeLast(15).withIndex().toList(), title = "连接记录", itemKey = { it.index }) { entry ->
        Text(entry.value, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    }
}
