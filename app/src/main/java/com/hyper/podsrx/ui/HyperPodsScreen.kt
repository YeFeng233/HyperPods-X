package com.hyper.podsrx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.hyper.podsrx.R
import com.hyper.podsrx.device.*
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.i18n.rememberStrings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun MiuixSettingsScreen(
    externalSettings: Boolean,
    initialPage: String? = null,
    onConnect: () -> Unit,
    onOverlay: () -> Unit,
    onSystemBluetooth: () -> Unit,
    onFinish: () -> Unit,
) {
    val strings = rememberStrings()
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
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
    val currentDisplayState = rememberUpdatedState(displayState)
    val context = LocalContext.current
    val volumeController = remember(context) { MediaVolumeController(context) }
    val volume by volumeController.state.collectAsStateWithLifecycle()
    DisposableEffect(volumeController) { onDispose { volumeController.close() } }
    val view = LocalView.current
    DisposableEffect(view, state.spatial.calibrationMode) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state.spatial.calibrationMode
        onDispose { view.keepScreenOn = previous }
    }
    val backStack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) {
        mutableStateListOf(SettingsPage.DEVICE.route).apply {
            SettingsCatalog.initialPage(initialPage)?.takeIf { it != SettingsPage.DEVICE }?.let { add(it.route) }
        }
    }
    var nameValue by rememberSaveable(state.address, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.displayName, selection = TextRange(state.displayName.length)))
    }
    var nameError by rememberSaveable(state.address) { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var observedMessage by remember { mutableLongStateOf(state.messageRevision) }
    LaunchedEffect(state.messageRevision, strings) {
        if (state.messageRevision != observedMessage) {
            observedMessage = state.messageRevision
            SettingsCatalog.actionFailureMessage(state, strings)?.let { actionError = it }
        }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigate: (String) -> Unit = { destination ->
        if (backStack.lastOrNull() != destination) {
            if (destination == SettingsPage.NAME.route) {
                nameValue = TextFieldValue(state.displayName, selection = TextRange(state.displayName.length))
                nameError = null
            }
            backStack.add(destination)
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
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onFinish()
    }
    val back: () -> Unit = {
        if (backStack.lastOrNull() == SettingsPage.NAME.route && nameError == null) saveName(false)
        leavePage()
    }
    val finishName: () -> Unit = { if (saveName(true)) leavePage() }
    BackHandler(enabled = backStack.size == 1, onBack = back)
    val theme = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = theme) {
        MiuixListeningIntentHost(displayState) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = back,
                entryProvider = { page ->
                    NavEntry(key = page, contentKey = page) {
                        val entryState = currentDisplayState.value
                        LaunchedEffect(page) {
                            if (page == SettingsPage.SPATIAL.route) PodsService.spatialRefresh()
                        }
                        DisposableEffect(page) {
                            onDispose { if (page == SettingsPage.SPATIAL.route) PodsService.spatialCancelCalibration() }
                        }
                        MiuixPageScaffold(
                            title = SettingsCatalog.title(page, entryState, strings),
                            onBack = back,
                            onMore = if (page == SettingsPage.DEVICE.route) ({ navigate(SettingsPage.MODULE.route) }) else null,
                            startsWithHeading = when (page) {
                                "controls" -> SettingsCatalog.gesturePresenceIds.any { it in entryState.controls }
                                "accessibility" -> SettingsCatalog.accessibilityPresenceIds.any { it in entryState.controls }
                                else -> page in setOf("spatial", "equalizer", "battery", "module", "diagnostics", "licenses")
                            },
                        ) {
                            when (page) {
                                SettingsPage.DEVICE.route -> miuixDevicePage(entryState, volume,
                                    volumeController::setFraction, volumeController::refresh, onConnect, navigate, strings)
                                SettingsPage.NAME.route -> miuixItem("name.editor") {
                                    MiuixNameEditor(nameValue, { nameValue = it; nameError = null }, nameError, finishName)
                                }
                                else -> miuixSettingsPageContent(page, entryState, onConnect, onOverlay, onSystemBluetooth, navigate, strings)
                            }
                            if (page in setOf("module", "connection", "diagnostics")) {
                                miuixFootnote("page.message", entryState.message)
                            }
                        }
                    }
                },
            )
        }
        WindowDialog(show = actionError != null, title = "未能完成操作", summary = actionError,
            onDismissRequest = { actionError = null }) {
            TextButton("好", { actionError = null }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

private fun LazyListScope.miuixDevicePage(
    state: PodsState,
    volume: MediaVolumeState,
    onVolumeChange: (Float) -> Unit,
    onVolumeFinished: () -> Unit,
    onConnect: () -> Unit,
    navigate: (String) -> Unit,
    strings: Strings,
) {
    miuixItem("device.product") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.miuix_airpods_open_case), state.headset.name, contentScale = ContentScale.Fit,
                modifier = Modifier.size(266.dp))
        }
    }
    miuixItem("device.batteries") { MiuixBatteryCard(state) }
    miuixGroupedItems("device.name", listOf(Unit)) {
        RowItem("名称", value = state.displayName,
            enabled = state.address.isNotBlank() && DeviceRepository.permissionsGranted()) { navigate(SettingsPage.NAME.route) }
    }
    miuixHeader("device.listening.title", "聆听模式")
    miuixItem("device.listening") { ListeningControls(state) }
    miuixHeader("device.volume.title", "音量")
    miuixItem("device.volume") {
        SettingsCard {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Slider(
                    value = volume.fraction, onValueChange = onVolumeChange, enabled = volume.maximum > 0,
                    onValueChangeFinished = onVolumeFinished,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "音量" },
                )
            }
        }
    }
    miuixGroupedItems("device.features", SettingsCatalog.rootFeatureLinks, itemKey = { it.page.route }) { link ->
        RowItem(link.title) { navigate(link.page.route) }
    }
    miuixGroupedItems("device.battery", listOf(SettingsCatalog.batteryLink), itemKey = { it.page.route }) { link ->
        RowItem(link.title) { navigate(link.page.route) }
    }
    if (!state.running) miuixGroupedItems("device.connection", listOf(Unit)) {
        RowItem("连接 AirPods") { navigate(SettingsPage.CONNECTION.route); onConnect() }
    }
    val information = listOf(DeviceInformationRow("model", "型号名称", state.headset.name),
        DeviceInformationRow("number", "型号号码", state.model.ifBlank { "—" })) + firmwareRows(state, strings)
    miuixGroupedItems("device.information", information, itemKey = { it.key }, title = "关于") { row ->
        InfoRow(row.title, row.value)
    }
    miuixGroupedItems("device.settings", SettingsCatalog.rootSettingsLinks, itemKey = { it.page.route }) { link ->
        RowItem(link.title) { navigate(link.page.route) }
    }
}

@Composable
private fun MiuixBatteryCard(state: PodsState) {
    val strings = rememberStrings()
    val cellHeight = with(LocalDensity.current) { 20.sp.toDp() }.coerceAtLeast(21.dp)
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(4 to "左", 2 to "右", 8 to "充电盒").forEachIndexed { index, (id, label) ->
                if (index > 0) Box(Modifier.width(.5.dp).height(cellHeight * 3).background(MiuixTheme.colorScheme.dividerLine))
                val battery = batteryPresentation(state.batteries[id], strings)
                Column(Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = label + "，" + battery.label + if (battery.note.isBlank()) "" else "，" + battery.note
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(cellHeight), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1)
                    }
                    Box(Modifier.fillMaxWidth().height(cellHeight), contentAlignment = Alignment.Center) {
                        Text(battery.label, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    Box(Modifier.fillMaxWidth().height(cellHeight), contentAlignment = Alignment.Center) {
                        BatteryGlyph(battery)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixNameEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: String?,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column {
        TextField(value, onValueChange, modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = "名称", singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            trailingIcon = {
                if (value.text.isNotEmpty()) IconButton(onClick = {
                    onValueChange(TextFieldValue())
                    focus.requestFocus()
                }) { Icon(MiuixIcons.Clear, "清除文本") }
            })
        error?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
}
