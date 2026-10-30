// Adapted from NEORUAA/HyperPods-X PR #1 (ae9fa5e), GPL-3.0-or-later.
package com.hyper.podsrx.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.BuildConfig
import com.hyper.podsrx.R
import com.hyper.podsrx.device.*
import com.hyper.podsrx.i18n.AppLanguage
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.i18n.currentAppLanguage
import com.hyper.podsrx.i18n.rememberStrings
import com.hyper.podsrx.i18n.setAppLanguage
import com.hyper.podsrx.ui.AppearancePreferences
import com.hyper.podsrx.ui.SettingsCatalog
import com.hyper.podsrx.ui.SettingsToggle
import com.hyper.podsrx.ui.LocalChangeUiStyle
import com.hyper.podsrx.ui.LocalLogExport
import com.hyper.podsrx.ui.UiStyle
import com.hyper.podsrx.ui.batteryPresentation
import com.hyper.podsrx.ui.capabilitySummary
import com.hyper.podsrx.ui.label
import com.hyper.podsrx.ui.spatialPresentation
import com.hyper.podsrx.ui.wearingDescription
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioGroupedTableFooter
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle
import io.github.neoruaa.glasmio.ui.GlasmioSlider

internal fun LazyListScope.IosSettingsPageContent(
    page: String,
    state: PodsState,
    onConnect: () -> Unit,
    onOverlay: () -> Unit,
    onSystemBluetooth: () -> Unit,
    navigate: (String) -> Unit,
    strings: Strings,
) {
    when {
        page.startsWith("control/") -> item(page) { ControlChoices(state, page.substringAfter('/').toIntOrNull()) }
        page.startsWith("call/") -> item(page) { CallControlChoices(state, page.substringAfter('/')) }
        page == "audio" -> AudioSettings(state, onSystemBluetooth, navigate, strings = strings)
        page == "controls" -> GestureSettings(state, navigate, strings = strings)
        page == "accessibility" -> AccessibilitySettings(state, navigate, strings = strings)
        page == "spatial" -> SpatialSettings(state, strings = strings)
        page == "equalizer" -> item(page) { EqualizerSettings(state) }
        page == "battery" -> item(page) { BatterySettings(state) }
        page == "module" -> ModuleSettings(state, onOverlay, onSystemBluetooth, navigate, strings = strings)
        page == "connection" -> ConnectionSettings(state, onConnect, onSystemBluetooth, strings = strings)
        page == "diagnostics" -> DiagnosticsSettings(state, strings = strings)
        page == "licenses" -> LicenseSettings(strings = strings)
        page == "about" -> AboutSettings(state, navigate, strings = strings)
        page == "language" -> item(page) { LanguageSettings() }
    }
}

@Composable
internal fun SettingsFootnote(text: String) { GlasmioGroupedTableFooter(text) }

private fun LazyListScope.AudioSettings(state: PodsState, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit, strings: Strings) {
    SettingsCatalog.audioSectionOrder.forEach { key ->
        when (key) {
            SettingsCatalog.personalizedVolume.key -> {
                val group = SettingsCatalog.personalizedVolume
                if (group.controlIds.single() in state.controls) item(group.key) {
                    Column {
                        AvailableControls(state, group.controlIds, navigate)
                        SettingsFootnote("根据所处环境调整整体音量。")
                    }
                }
            }
            SettingsCatalog.conversationAwareness.key -> {
                val group = SettingsCatalog.conversationAwareness
                if (group.controlIds.single() in state.controls) item(group.key) {
                    Column {
                        AvailableControls(state, group.controlIds, navigate)
                        SettingsFootnote("开始与他人讲话时降低媒体音量并减少背景噪声。")
                    }
                }
            }
            "equalizer" -> state.equalizer?.takeIf { it.size >= 4 }?.let { equalizer ->
                item(key) {
                    SettingsCard { RowItem("均衡器", value = if (equalizer[0] == 2) "自定" else "关闭") { navigate("equalizer") } }
                }
            }
            "earDetection" -> item(key) {
                Column {
                    SettingsCard { ToggleRow(SettingsToggle.EAR_AUTOMATION.title, "", SettingsToggle.EAR_AUTOMATION.checked(state)) { DeviceRepository.setEarAutomation(it) } }
                    SettingsFootnote("戴上 AirPods 时自动播放音频，摘下时自动暂停。")
                }
            }
            "spatial" -> item(key) {
                Section("空间音频") { RowItem("空间音频", value = state.spatial.mode?.label(strings) ?: "—") { navigate("spatial") } }
            }
            "systemAudio" -> item(key) {
                Column {
                    SettingsCard { RowItem("音频与设备权限", action = onSystemBluetooth) }
                    SettingsFootnote("在系统蓝牙中管理 AAC、音量同步和通话音频。")
                }
            }
            else -> SettingsCatalog.audioControlGroups.firstOrNull { it.key == key }?.let { group ->
                ControlGroup(group.key, state, group.controlIds, navigate, group.title)
            }
        }
    }
}

private fun LazyListScope.GestureSettings(state: PodsState, navigate: (String) -> Unit, strings: Strings) {
    SettingsCatalog.gestureSectionOrder.forEach { key ->
        when (key) {
            "callControls" -> if (state.controls[0x24]?.getOrNull(1)?.let { it in 2..3 } == true) {
                item(key) { CallControlSettings(state, navigate) }
            }
            "connection" -> if (SettingsCatalog.gesturePresenceIds.none { it in state.controls }) {
                item(key) { ConnectionNeeded(state, navigate) }
            }
            else -> SettingsCatalog.gestureControlGroups.firstOrNull { it.key == key }?.let { group ->
                ControlGroup(group.key, state, group.controlIds, navigate, group.title)
            }
        }
    }
}

private fun LazyListScope.AccessibilitySettings(state: PodsState, navigate: (String) -> Unit, strings: Strings) {
    SettingsCatalog.accessibilitySectionOrder.forEach { key ->
        val group = SettingsCatalog.accessibilityControlGroups.firstOrNull { it.key == key }
        when (key) {
            SettingsCatalog.pressSpeed.key -> if (group != null && group.controlIds.single() in state.controls) item(group.key) {
                Column {
                    ControlChoices(state, group.controlIds.single(), group.title, group.choiceLabels)
                    SettingsFootnote("调整按下 AirPods 两次或三次所需的速度。")
                }
            }
            SettingsCatalog.pressDuration.key -> if (group != null && group.controlIds.single() in state.controls) item(group.key) {
                Column {
                    ControlChoices(state, group.controlIds.single(), group.title, group.choiceLabels)
                    SettingsFootnote("调整按住 AirPods 所需的时长。")
                }
            }
            SettingsCatalog.noiseControls.key -> if (group != null && group.controlIds.any { it in state.controls }) item(group.key) {
                Column {
                    AvailableControls(state, group.controlIds, navigate, group.title)
                    if (0x1b in state.controls) SettingsFootnote("允许仅一只 AirPod 在耳中时，进入降噪模式。")
                }
            }
            SettingsCatalog.adaptiveAudio.key -> if (group != null && group.controlIds.single() in state.controls) item(group.key) {
                Column { GlasmioSectionTitle(requireNotNull(group.title)); AdaptiveStrengthControl(state) }
            }
            SettingsCatalog.sensors.key -> if (group != null && group.controlIds.single() in state.controls) item(group.key) {
                Column {
                    AvailableControls(state, group.controlIds, navigate, group.title)
                    SettingsFootnote("此选项控制耳机的传感器配置，自动播放与暂停由“音频与路径”中的“自动入耳检测”管理。")
                }
            }
            "connection" -> if (SettingsCatalog.accessibilityPresenceIds.none { it in state.controls }) {
                item(key) { ConnectionNeeded(state, navigate) }
            }
            else -> group?.let { ControlGroup(it.key, state, it.controlIds, navigate, it.title) }
        }
    }
}

private fun LazyListScope.ControlGroup(key: String, state: PodsState, ids: List<Int>, navigate: (String) -> Unit, title: String? = null) {
    if (ids.any { state.controls[it]?.isNotEmpty() == true }) item(key) { AvailableControls(state, ids, navigate, title) }
}

@Composable
private fun AvailableControls(state: PodsState, ids: List<Int>, navigate: (String) -> Unit, title: String? = null) {
    val specs = SettingsCatalog.availableControls(state, ids)
    if (specs.isEmpty()) return
    val content: @Composable ColumnScope.() -> Unit = { specs.forEach { ControlSetting(it, state, navigate) } }
    if (title == null) SettingsCard(content) else Section(title, content)
}

@Composable
private fun ControlSetting(spec: ControlSpec, state: PodsState, navigate: (String) -> Unit) {
    val strings = rememberStrings()
    val current = state.controls[spec.id]?.firstOrNull() ?: return
    val pending = spec.id in state.pending
    val enabled = SettingsCatalog.controlEnabled(state, spec)
    if (spec.range != null) {
        val submitted = if (pending) PodsService.pendingControlValue(spec.id) else null
        var draft by remember(state.address, spec.id, current, pending, submitted) {
            mutableFloatStateOf((if (pending) submitted ?: current else current).toFloat())
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(strings(spec.titleRes), style = GlasmioTheme.textStyles.body)
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
        val title = SettingsCatalog.toggleTitle(spec, strings)
        ToggleRow(title, "", current == 1, enabled) {
            PodsService.setControl(spec.id, if (it) 1 else 2)
        }
    } else RowItem(strings(spec.titleRes), enabled = enabled, value = spec.choices.firstOrNull { it.value == current }?.let { strings(it.labelRes) } ?: "—") {
        navigate(SettingsCatalog.controlRoute(spec.id))
    }
}

@Composable
private fun ControlChoices(state: PodsState, id: Int?, title: String? = null, labels: Map<Int, String> = emptyMap()) {
    val strings = rememberStrings()
    val spec = Controls.all.firstOrNull { it.id == id } ?: return
    val current = state.controls[spec.id]?.firstOrNull() ?: return
    val enabled = SettingsCatalog.controlEnabled(state, spec)
    val content: @Composable ColumnScope.() -> Unit = {
        spec.choices.forEachIndexed { index, choice ->
            GlasmioListRow(title = labels[choice.value] ?: strings(choice.labelRes),
                onClick = { if (choice.value != current) PodsService.setControl(spec.id, choice.value) },
                enabled = enabled, showTopSeparator = index > 0, showDisclosure = false,
                trailing = { if (current == choice.value) SfIcon("checkmark", null, size = 17.dp, tint = GlasmioTheme.colors.accent) })
        }
    }
    if (title == null) SettingsCard(content) else Section(title, content)
}

private fun LazyListScope.SpatialSettings(state: PodsState, strings: Strings) {
    val spatial = state.spatial
    val presentation = spatial.spatialPresentation(state.connected, strings = strings)
    val tracking = spatial.trackingRequested || spatial.nativeActive
    val ready = state.connected && spatial.route && spatial.bridge && !spatial.stopping && !spatial.modeChanging
    item("spatialModes") {
        Column {
            Section(strings(R.string.spatial_audio)) {
                SpatialMode.entries.forEachIndexed { index, option ->
                    val enabled = when (option) {
                        SpatialMode.OFF -> presentation.offEnabled
                        SpatialMode.FIXED -> presentation.fixedEnabled
                        SpatialMode.HEAD_TRACKING -> presentation.trackingEnabled
                    }
                    GlasmioListRow(option.label(strings), enabled = enabled, showDisclosure = false, showTopSeparator = index > 0,
                        onClick = { PodsService.spatialMode(option) },
                        trailing = { if (presentation.selected == option) SfIcon("checkmark", null, size = 17.dp, tint = GlasmioTheme.colors.accent) })
                }
            }
            SettingsFootnote(strings(R.string.spatial_modes_footnote))
        }
    }
    item("headTracking") {
        Column {
            Section(strings(R.string.spatial_head_tracking)) {
                if (spatial.calibrationMode) {
                    val direction = listOf(strings(R.string.calibration_turn_right), strings(R.string.calibration_look_up), strings(R.string.calibration_tilt_right)).getOrElse(spatial.calibrationStep) { strings(R.string.calibration_face_forward) }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings(R.string.step_of_three, spatial.calibrationStep + 1), style = GlasmioTheme.textStyles.headline)
                        Text(when {
                            spatial.calibrationPreparing -> strings(R.string.calibration_connecting_ios)
                            spatial.collecting -> strings(R.string.calibration_collect_ios, direction)
                            else -> strings(R.string.calibration_prepare_ios, direction)
                        }, style = GlasmioTheme.textStyles.body)
                    }
                    RowItem(if (spatial.collecting) strings(R.string.finish_step) else strings(R.string.start), enabled = !spatial.calibrationPreparing) { PodsService.spatialCalibrate() }
                    RowItem(strings(R.string.cancel_calibration)) { PodsService.spatialCancelCalibration() }
                } else {
                    RowItem(if (spatial.calibrated) strings(R.string.recalibrate) else strings(R.string.three_axis_calibration), enabled = ready && !tracking) { PodsService.spatialBeginCalibration() }
                    RowItem(strings(R.string.recenter_facing_phone), enabled = spatial.nativeActive && !spatial.trackingStarting) { PodsService.spatialRecenter() }
                }
            }
            if (!spatial.calibrated) SettingsFootnote(strings(R.string.calibration_first_tracking_footnote))
            if (spatial.notice.resolve(strings).isNotBlank()) SettingsFootnote(spatial.notice.resolve(strings))
        }
    }
}

@Composable
private fun EqualizerSettings(state: PodsState) {
    val strings = rememberStrings()
    val confirmed = state.equalizer?.takeIf { it.size >= 4 } ?: return
    val busy = PodsService.EQ_COMMAND in state.pending
    var draft by remember(confirmed, busy) { mutableStateOf(confirmed) }
    Section(strings(R.string.equalizer)) {
        ToggleRow(strings(R.string.custom_equalizer), "", confirmed[0] == 2, state.connected && !busy) {
            PodsService.setEqualizer(listOf(if (it) 2 else 1) + confirmed.drop(1))
        }
        if (confirmed[0] == 2) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(strings(R.string.eq_bass), strings(R.string.eq_mids), strings(R.string.eq_treble)).forEachIndexed { index, label ->
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
    val strings = rememberStrings()
    val value = state.controls[0x24]?.getOrNull(1) ?: return
    if (value !in 2..3) return
    Section(strings(R.string.call_controls)) {
        GlasmioListRow(strings(R.string.answer_call), detail = strings(R.string.press_once), showTopSeparator = false)
        RowItem(strings(R.string.mute_unmute), value = if (value == 2) strings(R.string.press_twice) else strings(R.string.press_once)) { navigate("call/mute") }
        RowItem(strings(R.string.end_call), value = if (value == 2) strings(R.string.press_once) else strings(R.string.press_twice)) { navigate("call/end") }
    }
}

@Composable
private fun CallControlChoices(state: PodsState, action: String) {
    val strings = rememberStrings()
    val value = state.controls[0x24]?.getOrNull(1)?.takeIf { it in 2..3 } ?: return
    val onePress = if (action == "end") value == 2 else value == 3
    SettingsCard {
        listOf(strings(R.string.press_once), strings(R.string.press_twice)).forEachIndexed { index, title ->
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
    val strings = rememberStrings()
    Section(state.headset.name) {
        listOf(4 to strings(R.string.left_airpod), 2 to strings(R.string.right_airpod), 8 to strings(R.string.charging_case)).forEachIndexed { index, (id, title) ->
            val battery = batteryPresentation(state.batteries[id], strings = strings)
            GlasmioListRow(title = title, subtitle = battery.note.takeIf { battery.charging },
                detail = battery.label, showTopSeparator = index > 0,
                trailing = { BatteryGlyph(battery) })
        }
    }
}

private fun LazyListScope.ModuleSettings(state: PodsState, onOverlay: () -> Unit, onSystemBluetooth: () -> Unit, navigate: (String) -> Unit, strings: Strings) {
    SettingsCatalog.moduleSections.forEach { section ->
        item(section.key) {
            when (section.key) {
                "appearance" -> {
                    val style by AppearancePreferences.style.collectAsState()
                    val changeStyle = LocalChangeUiStyle.current
                    Section(requireNotNull(section.title)) {
                        section.items.forEach { key ->
                            if (key == "style") GlasmioListRow(SettingsCatalog.moduleLabel(key), showTopSeparator = false, trailing = {
                                GlasmioPopUpButton(
                                    selected = style,
                                    items = UiStyle.entries,
                                    onSelected = changeStyle,
                                    label = { it.label },
                                )
                            })
                            else if (key == "language") RowItem(strings(R.string.app_language),
                                value = strings(currentAppLanguage().labelRes)) { navigate("language") }
                        }
                    }
                }
                "popups" -> Section(requireNotNull(section.title)) {
                    section.items.forEach { key ->
                        SettingsToggle.entries.firstOrNull { it.key == key }?.let { toggle ->
                            ToggleRow(toggle.title, "", toggle.checked(state)) { enabled ->
                                when (toggle) {
                                    SettingsToggle.EAR_AUTOMATION -> DeviceRepository.setEarAutomation(enabled)
                                    SettingsToggle.OPEN_CASE_POPUP -> DeviceRepository.setPopup(enabled)
                                    SettingsToggle.NATIVE_POPUP -> DeviceRepository.setNativePopup(enabled)
                                    SettingsToggle.CONNECTION_POPUP -> DeviceRepository.setConnectionPopup(enabled)
                                    SettingsToggle.BATTERY_ALERTS -> DeviceRepository.setBatteryAlerts(enabled)
                                    SettingsToggle.AUTO_CONNECT -> DeviceRepository.setAutoConnect(enabled)
                                }
                            }
                        }
                    }
                }
                "previews" -> SettingsCard {
                    section.items.forEach { key ->
                        when (key) {
                            "batteryPreview" -> RowItem(SettingsCatalog.moduleLabel(key)) { PodsService.previewPopup() }
                            "islandPreview" -> if (state.nativePopup) RowItem(SettingsCatalog.moduleLabel(key)) { PodsService.previewIsland() }
                        }
                    }
                }
                "systemIntegration" -> {
                    val hostRestart by HostRestartCoordinator.state.collectAsState()
                    Section(requireNotNull(section.title)) {
                        section.items.forEach { key ->
                            when (key) {
                                "nativeScope" -> RowItem(SettingsCatalog.moduleLabel(key), "在 LSPosed 中授权系统设置、小米蓝牙、设备互联和手机管家") { FrameworkBridge.requestNativeScope() }
                                "restartHosts" -> RowItem(if (hostRestart.running) "正在重启作用域…" else SettingsCatalog.moduleLabel(key), hostRestart.message.resolve(strings),
                                    enabled = !hostRestart.running, action = { HostRestartCoordinator.restart() })
                                "overlayPermission" -> RowItem(SettingsCatalog.moduleLabel(key), action = onOverlay)
                                "bluetoothSettings" -> RowItem(SettingsCatalog.moduleLabel(key), action = onSystemBluetooth)
                            }
                        }
                    }
                }
                "moduleInformation" -> {
                    val logExport = LocalLogExport.current
                    SettingsCard {
                        section.items.forEach { key ->
                            when (key) {
                                "connection" -> RowItem(SettingsCatalog.moduleLabel(key)) { navigate("connection") }
                                "diagnostics" -> RowItem(SettingsCatalog.moduleLabel(key)) { navigate("diagnostics") }
                                "exportLogs" -> RowItem(if (logExport.busy) "正在导出日志…" else SettingsCatalog.moduleLabel(key), logExport.status,
                                    enabled = !logExport.busy, action = logExport.export)
                                "about" -> RowItem(SettingsCatalog.moduleLabel(key)) { navigate("about") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSettings() {
    val strings = rememberStrings()
    val context = LocalContext.current
    val selected = currentAppLanguage()
    Column {
        Section(strings(R.string.app_language)) {
            AppLanguage.entries.forEachIndexed { index, language ->
                GlasmioListRow(strings(language.labelRes), showTopSeparator = index > 0, showDisclosure = false,
                    onClick = { setAppLanguage(context, language) },
                    trailing = { if (selected == language) SfIcon("checkmark", null, size = 17.dp, tint = GlasmioTheme.colors.accent) })
            }
        }
        SettingsFootnote(strings(R.string.language_description))
    }
}

private fun LazyListScope.ConnectionSettings(state: PodsState, onConnect: () -> Unit, onSystemBluetooth: () -> Unit, strings: Strings) {
    item("connectionStatus") {
        SettingsCard {
            InfoRow("AirPods", state.displayName)
            InfoRow(strings(R.string.status), if (state.connected) strings(R.string.connected) else strings(R.string.not_connected))
            ToggleRow(strings(R.string.auto_connect), strings(R.string.auto_connect_description), state.autoConnect,
                onChange = DeviceRepository::setAutoConnect)
            RowItem(strings(R.string.system_bluetooth), action = onSystemBluetooth)
            RowItem(if (state.running) strings(R.string.reconnect) else strings(R.string.connect_earbuds)) { if (state.running) PodsService.reconnect() else onConnect() }
        }
    }
    val candidates = if (state.address.isEmpty()) DeviceRepository.candidates() else emptyList()
    if (candidates.size > 1) item("candidates") {
        Section(strings(R.string.choose_airpods)) {
            candidates.forEach { device ->
                val name = try { device.name } catch (_: SecurityException) { null }
                RowItem(name ?: "AirPods") { DeviceRepository.bind(device) }
            }
        }
    }
    item("connectionHelp") { SettingsFootnote("请先在系统蓝牙中配对连接 AirPods，并允许附近设备和后台运行权限。") }
    if (state.running) item("stopService") {
        SettingsCard {
            GlasmioListRow(strings(R.string.stop_earbud_service), titleColor = GlasmioTheme.colors.destructive, showTopSeparator = false, showDisclosure = false,
                onClick = { DeviceRepository.stop() })
        }
    }
}

@Composable
private fun ConnectionNeeded(state: PodsState, navigate: (String) -> Unit) {
    val strings = rememberStrings()
    SettingsCard { RowItem(if (state.connected) strings(R.string.connection_settings) else strings(R.string.connect_airpods)) { navigate("connection") } }
}

private fun LazyListScope.AboutSettings(state: PodsState, navigate: (String) -> Unit, strings: Strings) {
    item("information") {
        SettingsCard {
            InfoRow(strings(R.string.name), state.displayName)
            InfoRow(strings(R.string.model), state.model.ifBlank { "—" })
            com.hyper.podsrx.ui.firmwareRows(state, strings).forEach { row -> InfoRow(row.title, row.value) }
            InfoRow("HyperPods-X", BuildConfig.VERSION_NAME)
        }
    }
    item("licenses") { SettingsCard { RowItem(strings(R.string.open_source_licenses)) { navigate("licenses") } } }
}

private fun LazyListScope.LicenseSettings(strings: Strings) {
    for ((title, file) in listOf(strings(R.string.third_party_notices) to "NOTICES.md", strings(R.string.artwork_sources) to "ARTWORK-SOURCES.md", "GPL-3.0-or-later" to "GPL-3.0.txt", "Apache-2.0" to "Apache-2.0.txt")) {
        item(file) {
            val content = remember(file) { DeviceRepository.app.assets.open("licenses/${file}").bufferedReader().use { it.readText() } }
            Section(title) { Text(content, style = GlasmioTheme.textStyles.footnote, modifier = Modifier.padding(16.dp)) }
        }
    }
}

private fun LazyListScope.DiagnosticsSettings(state: PodsState, strings: Strings) {
    item("environment") {
        Section(strings(R.string.current_environment)) {
            InfoRow(strings(R.string.module_framework), state.framework.resolve(strings))
            InfoRow(strings(R.string.control_channel), if (state.connected) strings(R.string.connected) else strings(R.string.not_connected))
            InfoRow(strings(R.string.native_popup), if (state.nativePopup) state.nativePopupStatus.resolve(strings) else strings(R.string.disabled))
            InfoRow(strings(R.string.state_hub), state.hubSummary(strings))
            state.hostCapabilities.forEach { (process, results) -> InfoRow(process, capabilitySummary(results, strings = strings)) }
            InfoRow(strings(R.string.bound_identity), if (state.identityReady) strings(R.string.confirmed) else strings(R.string.waiting_keys))
            InfoRow(strings(R.string.broadcasts_verified), "${state.broadcasts} / ${state.trustedBroadcasts}")
            InfoRow(strings(R.string.proximity_identity_matches), "${state.proximityCandidates} / ${state.identityMatches}")
            InfoRow(strings(R.string.case_lid), when (state.lidOpen) { true -> strings(R.string.opened); false -> strings(R.string.disabled); null -> strings(R.string.waiting_advertisement) })
            InfoRow(strings(R.string.reported_settings_count), state.controls.size.toString())
            InfoRow(strings(R.string.wearing_status), wearingDescription(state, strings = strings))
            InfoRow(strings(R.string.automatic_ear_detection), if (state.earAutomation) state.earDetectionStatus.resolve(strings) else strings(R.string.disabled))
        }
    }
    item("connectionLog") {
        Section(strings(R.string.connection_history)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.log.takeLast(15).forEach { Text(it, style = GlasmioTheme.textStyles.caption1) }
            }
        }
    }
}
