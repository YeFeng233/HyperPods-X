package com.hyper.podsrx.ui.ios

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.BuildConfig
import com.hyper.podsrx.R
import com.hyper.podsrx.device.*
import com.hyper.podsrx.i18n.AppLanguage
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.i18n.currentAppLanguage
import com.hyper.podsrx.i18n.setAppLanguage
import com.hyper.podsrx.ui.*
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.preference.GlasmioListPreference
import io.github.neoruaa.glasmio.preference.GlasmioSwitchPreference
import io.github.neoruaa.glasmio.ui.*

internal fun LazyListScope.iosPageContent(
    page: String,
    state: PodsState,
    strings: Strings,
    navigate: (String) -> Unit,
    onConnect: () -> Unit,
    onSystemBluetooth: () -> Unit,
) {
    when {
        page == "device" -> iosDevicePage(state, strings, navigate, onConnect)
        page == "connection" -> connectionPage(state, strings, onConnect, onSystemBluetooth)
        page == "module" -> moduleSettings(state, strings, navigate)
        page == "audio" -> audioSettings(state, strings, navigate, onSystemBluetooth)
        page == "controls" -> gestureSettings(state, strings, navigate)
        page == "accessibility" -> accessibilitySettings(state, strings, navigate)
        page == "spatial" -> spatialSettings(state, strings)
        page == "equalizer" -> equalizerSettings(state, strings)
        page == "diagnostics" -> diagnosticsSettings(state, strings)
        page == "about" -> aboutSettings(strings, navigate)
        page == "licenses" -> licenseSettings(strings)
        page.startsWith("call/") -> callChoices(state, strings, page.substringAfter('/'))
        page.startsWith("control/") -> controlChoices(state, strings, page.substringAfter('/').toInt())
    }
}

internal fun LazyListScope.iosGroup(
    key: String,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    item(key) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { GlasmioSectionTitle(it) }
            GlasmioGroupedList(content = content)
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, separator: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    GlasmioSwitchPreference(title = { Text(title) }, checked = checked, onCheckedChange = onChange,
        isEnabled = enabled, showTopSeparator = separator)
}

@SuppressLint("MissingPermission")
private fun LazyListScope.connectionPage(
    state: PodsState, strings: Strings, onConnect: () -> Unit, onSystemBluetooth: () -> Unit,
) {
    iosGroup("connection") {
        GlasmioListRow(strings(R.string.status),
            detail = strings(if (state.connected) R.string.connected else R.string.earbuds_not_connected),
            showTopSeparator = false)
        SwitchRow(strings(R.string.auto_connect), state.autoConnect, true, onChange = DeviceRepository::setAutoConnect)
        GlasmioListRow(strings(R.string.system_bluetooth), onClick = onSystemBluetooth)
        GlasmioListRow(strings(if (state.running) R.string.reconnect else R.string.connect_airpods),
            onClick = if (state.running) PodsService::reconnect else onConnect, showDisclosure = false)
    }
    val candidates = if (state.address.isBlank()) DeviceRepository.candidates() else emptyList()
    if (candidates.size > 1) iosGroup("candidates", strings(R.string.choose_airpods_to_manage)) {
        candidates.forEachIndexed { index, device ->
            GlasmioListRow(device.name ?: "AirPods", showTopSeparator = index > 0, onClick = { DeviceRepository.bind(device) })
        }
    }
    if (state.messageCode == R.string.pair_airpods_first) item("pair") {
        GlasmioGroupedTableFooter(state.messageText.resolve(strings))
    }
    if (state.running) iosGroup("stop") {
        GlasmioListRow(strings(R.string.stop_earbud_service), onClick = DeviceRepository::stop,
            titleColor = ColorStop, showDisclosure = false, showTopSeparator = false)
    }
    iosGroup("information", strings(R.string.device_information)) {
        GlasmioListRow(strings(R.string.name), detail = state.displayName, showTopSeparator = false)
        GlasmioListRow(strings(R.string.model_name), detail = state.headset.name)
        GlasmioListRow(strings(R.string.model_number), detail = state.model.ifBlank { "—" })
        GlasmioListRow(strings(R.string.firmware_version), detail = state.firmware.ifBlank { "—" })
    }
}

private val ColorStop = androidx.compose.ui.graphics.Color(0xffd95151)

private fun LazyListScope.moduleSettings(state: PodsState, strings: Strings, navigate: (String) -> Unit) {
    SettingsCatalog.tabbedModuleSections.forEach { section ->
        val keys = section.items.filter {
            when (it) {
                "islandPreview" -> state.nativePopup
                else -> true
            }
        }
        if (keys.isEmpty()) return@forEach
        val title = when (section.key) {
            "appearance" -> strings(R.string.appearance)
            "earbudControls" -> strings(R.string.feature_settings)
            "popups" -> strings(R.string.popups_and_alerts)
            else -> null
        }
        iosGroup(section.key, title) {
            keys.forEachIndexed { index, key ->
                val separator = index > 0
                val toggle = SettingsCatalog.popupToggles.firstOrNull { it.key == key }
                if (toggle != null) {
                    val (label, change) = when (toggle) {
                        SettingsToggle.OPEN_CASE_POPUP -> R.string.lid_popup to DeviceRepository::setPopup
                        SettingsToggle.NATIVE_POPUP ->
                            R.string.hyperos_native_popup to DeviceRepository::setNativePopup
                        SettingsToggle.CONNECTION_POPUP -> R.string.connection_battery_card to DeviceRepository::setConnectionPopup
                        SettingsToggle.BATTERY_ALERTS -> R.string.battery_alert to DeviceRepository::setBatteryAlerts
                        SettingsToggle.EAR_AUTOMATION -> R.string.automatic_ear_detection to DeviceRepository::setEarAutomation
                        SettingsToggle.AUTO_CONNECT -> R.string.auto_connect to DeviceRepository::setAutoConnect
                    }
                    SwitchRow(strings(label), toggle.checked(state), separator, onChange = change)
                } else when (key) {
                    "style" -> GlasmioListPreference(
                        title = { Text(strings(R.string.switch_ui_style)) }, selectedValue = UiStyle.IOS,
                        values = UiStyle.entries, valueText = { it.label }, onValueSelected = LocalChangeUiStyle.current,
                        showTopSeparator = separator,
                    )
                    "language" -> {
                        val context = LocalContext.current
                        GlasmioListPreference(
                            title = { Text(strings(R.string.app_language)) }, selectedValue = currentAppLanguage(),
                            values = AppLanguage.entries, valueText = { strings(it.labelRes) },
                            onValueSelected = { setAppLanguage(context, it) }, showTopSeparator = separator,
                        )
                    }
                    "islandPreview" -> GlasmioListRow(
                        strings(R.string.preview_island),
                        showTopSeparator = separator, onClick = PodsService::previewIsland, showDisclosure = false,
                    )
                    "exportLogs" -> {
                        val export = LocalLogExport.current
                        GlasmioListRow(strings(if (export.busy) R.string.exporting_logs else R.string.export_logs),
                            subtitle = export.status.takeIf(String::isNotBlank), showTopSeparator = separator,
                            enabled = !export.busy, onClick = export.export, showDisclosure = false)
                    }
                    else -> GlasmioListRow(iosPageTitle(key, state, strings), showTopSeparator = separator, onClick = { navigate(key) })
                }
            }
        }
    }
}

internal fun iosPageTitle(page: String, state: PodsState, strings: Strings): String = when (page) {
    "device" -> state.displayName
    "name" -> strings(R.string.name)
    "connection" -> strings(R.string.tab_connection)
    "audio" -> strings(R.string.audio_and_routing)
    "controls" -> strings(R.string.controls_and_gestures)
    "accessibility" -> strings(R.string.accessibility)
    "spatial" -> strings(R.string.spatial_audio)
    "equalizer" -> strings(R.string.equalizer)
    "diagnostics" -> strings(R.string.connection_diagnostics)
    "about" -> strings(R.string.about)
    "licenses" -> strings(R.string.open_source_licenses)
    "call/mute" -> strings(R.string.mute_unmute)
    "call/end" -> strings(R.string.end_call)
    else -> SettingsCatalog.title(page, state, strings)
}

private fun LazyListScope.audioSettings(state: PodsState, strings: Strings, navigate: (String) -> Unit, onSystemBluetooth: () -> Unit) {
    SettingsCatalog.audioSectionOrder.forEach { key ->
        when (key) {
            "equalizer" -> state.equalizer?.takeIf { it.size >= 4 }?.let { eq ->
                iosGroup(key) {
                    GlasmioListRow(strings(R.string.equalizer), detail = strings(if (eq[0] == 2) R.string.custom else R.string.mode_off),
                        showTopSeparator = false, onClick = { navigate(key) })
                }
            }
            "earDetection" -> iosGroup(key) {
                SwitchRow(strings(R.string.automatic_ear_detection), state.earAutomation, false, onChange = DeviceRepository::setEarAutomation)
            }
            "spatial" -> iosGroup(key) {
                GlasmioListRow(strings(R.string.spatial_audio), detail = state.spatial.mode?.label(strings) ?: "—",
                    showTopSeparator = false, onClick = { navigate(key) })
            }
            "systemAudio" -> iosGroup(key) {
                GlasmioListRow(strings(R.string.audio_device_permissions), showTopSeparator = false, onClick = onSystemBluetooth)
            }
            else -> controlGroup(state, strings, SettingsCatalog.audioControlGroups.first { it.key == key }, navigate)
        }
    }
}

private fun LazyListScope.gestureSettings(state: PodsState, strings: Strings, navigate: (String) -> Unit) {
    if (SettingsCatalog.gesturePresenceIds.none { it in state.controls }) {
        missingControls(state, strings)
        return
    }
    state.controls[0x24]?.getOrNull(1)?.takeIf { it in 2..3 }?.let { value ->
        val once = strings(R.string.press_once)
        val twice = strings(R.string.press_twice)
        iosGroup("callControls", strings(R.string.call_controls)) {
            GlasmioListRow(strings(R.string.answer_call), detail = once, showTopSeparator = false)
            GlasmioListRow(strings(R.string.mute_unmute), detail = if (value == 3) once else twice,
                onClick = { navigate("call/mute") })
            GlasmioListRow(strings(R.string.end_call), detail = if (value == 2) once else twice,
                onClick = { navigate("call/end") })
        }
    }
    SettingsCatalog.gestureControlGroups.forEach { controlGroup(state, strings, it, navigate) }
}

private fun LazyListScope.accessibilitySettings(state: PodsState, strings: Strings, navigate: (String) -> Unit) {
    if (SettingsCatalog.accessibilityPresenceIds.none { it in state.controls }) {
        missingControls(state, strings)
        return
    }
    SettingsCatalog.accessibilityControlGroups.forEach { group ->
        when (group) {
            SettingsCatalog.pressSpeed, SettingsCatalog.pressDuration -> {
                val id = group.controlIds.single()
                if (id in state.controls) controlChoices(state, strings, id, group.title, group.choiceLabels)
            }
            SettingsCatalog.adaptiveAudio -> if (0x2e in state.controls) item(group.key) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlasmioSectionTitle(strings(R.string.adaptive_audio))
                    IosAdaptiveStrength(state, strings)
                }
            }
            else -> controlGroup(state, strings, group, navigate)
        }
    }
}

private fun LazyListScope.missingControls(state: PodsState, strings: Strings) {
    item("missing-controls") {
        GlasmioGroupedTableFooter(strings(if (state.connected) R.string.settings_not_reported else R.string.earbuds_not_connected))
    }
}

private fun LazyListScope.controlGroup(
    state: PodsState, strings: Strings, group: SettingsControlGroup, navigate: (String) -> Unit,
) {
    val specs = SettingsCatalog.availableControls(state, group)
    if (specs.isNotEmpty()) iosGroup(group.key, group.title) {
        specs.forEachIndexed { index, spec -> ControlRow(state, strings, spec, index > 0, navigate) }
    }
}

@Composable
private fun ControlRow(state: PodsState, strings: Strings, spec: ControlSpec, separator: Boolean, navigate: (String) -> Unit) {
    val current = state.controls.getValue(spec.id).first()
    val enabled = SettingsCatalog.controlEnabled(state, spec)
    val range = spec.range
    when {
        range != null -> {
            val pending = spec.id in state.pending
            val submitted = if (pending) PodsService.pendingControlValue(spec.id) else null
            var draft by remember(state.address, spec.id, current, pending, submitted) {
                mutableFloatStateOf((submitted ?: current).toFloat())
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings(spec.titleRes), style = GlasmioTheme.textStyles.body)
                    Text(draft.toInt().toString(), style = GlasmioTheme.textStyles.body, color = GlasmioTheme.colors.secondaryContent)
                }
                GlasmioSlider(draft, { draft = it }, enabled = enabled,
                    valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = {
                        if (draft.toInt() != current) PodsService.setControl(spec.id, draft.toInt())
                    })
            }
        }
        spec.toggle -> SwitchRow(SettingsCatalog.toggleTitle(spec, strings), current == 1, separator, enabled) {
            PodsService.setControl(spec.id, if (it) 1 else 2)
        }
        else -> GlasmioListRow(strings(spec.titleRes),
            detail = spec.choices.firstOrNull { it.value == current }?.let { strings(it.labelRes) } ?: "—",
            showTopSeparator = separator, enabled = enabled, onClick = { navigate(SettingsCatalog.controlRoute(spec.id)) })
    }
}

private fun LazyListScope.controlChoices(state: PodsState, strings: Strings, id: Int, title: String? = null, labels: Map<Int, String> = emptyMap()) {
    val spec = Controls.all.first { it.id == id }
    val current = state.controls[id]?.firstOrNull() ?: return
    val enabled = SettingsCatalog.controlEnabled(state, spec)
    iosGroup("choices/$id", title) {
        spec.choices.forEachIndexed { index, choice ->
            ChoiceRow(labels[choice.value] ?: strings(choice.labelRes), current == choice.value, enabled, index > 0) {
                if (choice.value != current) PodsService.setControl(id, choice.value)
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, checked: Boolean, enabled: Boolean, separator: Boolean, onClick: () -> Unit) {
    GlasmioListRow(title, modifier = Modifier.semantics { selected = checked }, showTopSeparator = separator,
        enabled = enabled, onClick = onClick, showDisclosure = false,
        trailing = { if (checked) SfIcon("checkmark", null, size = 17.dp, tint = GlasmioTheme.colors.accent) })
}

private fun LazyListScope.callChoices(state: PodsState, strings: Strings, action: String) {
    val value = state.controls[0x24]?.getOrNull(1)?.takeIf { it in 2..3 } ?: return
    val end = action == "end"
    val onePress = if (end) value == 2 else value == 3
    val enabled = state.connected && 0x24 !in state.pending
    iosGroup("callChoices") {
        ChoiceRow(strings(R.string.press_once), onePress, enabled, false) { PodsService.setCallControl(end) }
        ChoiceRow(strings(R.string.press_twice), !onePress, enabled, true) { PodsService.setCallControl(!end) }
    }
}

private fun LazyListScope.spatialSettings(state: PodsState, strings: Strings) {
    val spatial = state.spatial
    val presentation = spatial.spatialPresentation(state.connected, strings)
    val tracking = spatial.trackingRequested || spatial.nativeActive
    val ready = state.connected && spatial.route && spatial.bridge && !spatial.stopping && !spatial.modeChanging
    iosGroup("spatialModes") {
        SpatialMode.entries.forEachIndexed { index, option ->
            ChoiceRow(option.label(strings), presentation.selected == option, presentation.canSelect(option), index > 0) {
                PodsService.spatialMode(option)
            }
        }
    }
    iosGroup("headTracking", strings(R.string.spatial_head_tracking)) {
        if (spatial.calibrationMode) {
            val direction = listOf(R.string.calibration_turn_right, R.string.calibration_look_up, R.string.calibration_tilt_right)
                .getOrElse(spatial.calibrationStep) { R.string.calibration_face_forward }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings(R.string.step_of_three, spatial.calibrationStep + 1), style = GlasmioTheme.textStyles.headline)
                Text(when {
                    spatial.calibrationPreparing -> strings(R.string.calibration_connecting_ios)
                    spatial.collecting -> strings(R.string.calibration_collect_ios, strings(direction))
                    else -> strings(R.string.calibration_prepare_ios, strings(direction))
                }, style = GlasmioTheme.textStyles.body)
            }
            GlasmioListRow(strings(if (spatial.collecting) R.string.finish_step else R.string.start),
                enabled = !spatial.calibrationPreparing, onClick = PodsService::spatialCalibrate, showDisclosure = false)
            GlasmioListRow(strings(R.string.cancel_calibration), onClick = PodsService::spatialCancelCalibration, showDisclosure = false)
        } else {
            GlasmioListRow(strings(if (spatial.calibrated) R.string.recalibrate else R.string.three_axis_calibration),
                enabled = ready && !tracking, onClick = PodsService::spatialBeginCalibration, showTopSeparator = false)
            GlasmioListRow(strings(R.string.recenter_facing_phone), enabled = spatial.nativeActive && !spatial.trackingStarting,
                onClick = PodsService::spatialRecenter, showDisclosure = false)
        }
    }
    if (spatial.notice.isNotBlank()) item("spatialNotice") { GlasmioGroupedTableFooter(spatial.notice.resolve(strings)) }
}

private fun LazyListScope.equalizerSettings(state: PodsState, strings: Strings) {
    val eq = state.equalizer?.takeIf { it.size >= 4 } ?: return
    val busy = PodsService.EQ_COMMAND in state.pending
    val enabled = state.connected && !busy
    iosGroup("equalizer") {
        SwitchRow(strings(R.string.custom_equalizer), eq[0] == 2, false, enabled) {
            PodsService.setEqualizer(listOf(if (it) 2 else 1) + eq.drop(1))
        }
        if (eq[0] == 2) listOf(R.string.eq_bass, R.string.eq_mids, R.string.eq_treble).forEachIndexed { index, label ->
            val band = index + 1
            var draft by remember(eq, busy) { mutableFloatStateOf(eq[band].toFloat()) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings(label), style = GlasmioTheme.textStyles.body)
                GlasmioSlider(draft, { draft = it }, enabled = enabled, valueRange = 0f..100f, onValueChangeFinished = {
                    PodsService.setEqualizer(eq.toMutableList().also { it[band] = draft.toInt() })
                })
            }
        }
    }
}

private fun LazyListScope.diagnosticsSettings(state: PodsState, strings: Strings) {
    val rows = buildList {
        add(strings(R.string.module_framework) to state.framework.resolve(strings))
        add(strings(R.string.control_channel) to strings(if (state.connected) R.string.connected else R.string.not_connected))
        add(strings(R.string.native_popup) to if (state.nativePopup) state.nativePopupStatus.resolve(strings) else strings(R.string.disabled))
        add(strings(R.string.state_hub) to state.hubSummary(strings))
        state.hostCapabilities.forEach { (process, results) -> add(process to capabilitySummary(results, strings)) }
        add(strings(R.string.bound_identity) to strings(if (state.identityReady) R.string.confirmed else R.string.waiting_keys))
        add(strings(R.string.broadcasts_verified) to "${state.broadcasts} / ${state.trustedBroadcasts}")
        add(strings(R.string.proximity_identity_matches) to "${state.proximityCandidates} / ${state.identityMatches}")
        val lid = when (state.lidOpen) { true -> R.string.opened; false -> R.string.closed; null -> R.string.waiting_advertisement }
        add(strings(R.string.case_lid) to strings(lid))
        add(strings(R.string.reported_settings_count) to state.controls.size.toString())
        add(strings(R.string.wearing_status) to wearingDescription(state, strings))
        add(strings(R.string.automatic_ear_detection) to if (state.earAutomation) state.earDetectionStatus.resolve(strings) else strings(R.string.disabled))
    }
    iosGroup("environment", strings(R.string.current_environment)) {
        rows.forEachIndexed { index, (title, value) -> GlasmioListRow(title, detail = value, showTopSeparator = index > 0) }
    }
    if (state.log.isNotEmpty()) iosGroup("log", strings(R.string.connection_history)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.log.takeLast(15).forEach { Text(it, style = GlasmioTheme.textStyles.footnote) }
        }
    }
}

private fun LazyListScope.aboutSettings(strings: Strings, navigate: (String) -> Unit) {
    item("app") {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_app_mark), null, Modifier.size(72.dp), tint = GlasmioTheme.colors.content)
            Text(strings(R.string.app_name), style = GlasmioTheme.textStyles.title1)
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = GlasmioTheme.textStyles.footnote, color = GlasmioTheme.colors.secondaryContent)
        }
    }
    iosGroup("links", strings(R.string.open_source_projects)) {
        val uri = LocalUriHandler.current
        GlasmioListRow(strings(R.string.app_name), showTopSeparator = false,
            onClick = { uri.openUri("https://github.com/YeFeng233/HyperPods-X") })
        GlasmioListRow("Glasmio", onClick = { uri.openUri("https://github.com/NEORUAA/Glasmio") })
        GlasmioListRow(strings(R.string.open_source_licenses), onClick = { navigate("licenses") })
    }
}

private fun LazyListScope.licenseSettings(strings: Strings) {
    listOf(
        strings(R.string.third_party_notices) to "NOTICES.md",
        strings(R.string.artwork_rights) to "ARTWORK-SOURCES.md",
        "GPL-3.0-or-later" to "GPL-3.0.txt",
        "Apache-2.0" to "Apache-2.0.txt",
    ).forEach { (title, file) ->
        iosGroup(file, title) {
            val content = remember(file) { DeviceRepository.app.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
            Text(content, style = GlasmioTheme.textStyles.footnote, modifier = Modifier.padding(16.dp))
        }
    }
}
