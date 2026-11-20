package com.hyper.podsrx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.hyper.podsrx.R
import com.hyper.podsrx.device.*
import com.hyper.podsrx.i18n.rememberStrings
import com.hyper.podsrx.ui.liquid.LiquidGlassBottomBar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.*
import top.yukonga.miuix.kmp.icon.glass.BluetoothDiscovery
import top.yukonga.miuix.kmp.icon.glass.BluetoothHeadphones
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage

private data class PodsRoute(val path: String) : NavKey
private const val RootPage = "root"
private val tabs = listOf(SettingsPage.DEVICE, SettingsPage.CONNECTION, SettingsPage.MODULE)


@Composable
internal fun MiuixSettingsScreen(
    externalSettings: Boolean,
    initialPage: String? = null,
    onConnect: () -> Unit,
    onSystemBluetooth: () -> Unit,
    onFinish: () -> Unit,
) {
    val strings = rememberStrings()
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    val displayState = rememberReportedControls(state)
    val currentDisplayState = rememberUpdatedState(displayState)
    val view = LocalView.current
    DisposableEffect(view, state.spatial.calibrationMode) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state.spatial.calibrationMode
        onDispose { view.keepScreenOn = previous }
    }
    val windowSize = LocalWindowInfo.current.containerSize
    val wide = with(LocalDensity.current) { windowSize.width.toDp() >= 600.dp }
    val expandable = !wide && with(LocalDensity.current) { windowSize.height.toDp() >= 480.dp }
    val initial = SettingsCatalog.initialPage(initialPage)?.takeUnless { it == SettingsPage.BATTERY }
    val initialTab = tabs.indexOf(initial).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialTab) { tabs.size }
    val pagerScope = rememberCoroutineScope()
    var tabMotion by remember { mutableStateOf<Job?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { selectedTab = it } }
    val switchTab: (Int) -> Unit = { index ->
        tabMotion?.cancel()
        selectedTab = index
        tabMotion = pagerScope.launch {
            if (wide) pager.scrollToPage(index) else pager.springAnimateToPage(index)
        }
    }
    val backStack = rememberSaveable(
        saver = listSaver<SnapshotStateList<NavKey>, String>(
            save = { routes -> routes.map { (it as PodsRoute).path } },
            restore = { paths -> paths.filterNot { it == "battery" }
                .map<String, NavKey>(::PodsRoute).toMutableStateList() },
        ),
    ) {
        mutableStateListOf<NavKey>(PodsRoute(if (externalSettings) SettingsPage.DEVICE.route else RootPage)).apply {
            if (initial != null && initial != SettingsPage.DEVICE && (externalSettings || initial !in tabs))
                add(PodsRoute(initial.route))
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
        val route = PodsRoute(destination)
        val tab = tabs.indexOfFirst { it.route == destination }
        if (!externalSettings && backStack.last() == PodsRoute(RootPage) && tab >= 0) {
            switchTab(tab)
        } else if (backStack.last() != route) {
            if (destination == SettingsPage.NAME.route) {
                nameValue = TextFieldValue(state.displayName, selection = TextRange(state.displayName.length))
                nameError = null
            }
            val existing = backStack.indexOf(route)
            if (existing < 0) backStack.add(route)
            else while (backStack.last() != route) backStack.removeAt(backStack.lastIndex)
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
        when {
            backStack.size > 1 -> backStack.removeAt(backStack.lastIndex)
            !externalSettings && selectedTab != 0 -> switchTab(0)
            else -> onFinish()
        }
    }
    val back: () -> Unit = {
        if (backStack.last() == PodsRoute(SettingsPage.NAME.route) && nameError == null) saveName(false)
        leavePage()
    }
    val finishName: () -> Unit = { if (saveName(true)) leavePage() }
    BackHandler(enabled = backStack.size == 1, onBack = back)
    val useFloatingBar by AppearancePreferences.floatingBottomBar.collectAsStateWithLifecycle()
    val useGlassBar by AppearancePreferences.glassBottomBar.collectAsStateWithLifecycle()
    val theme = remember { ThemeController(ColorSchemeMode.System) }
    val swipe = if (LocalLayoutDirection.current == LayoutDirection.Rtl)
        NavSwipeDirection.RightToLeft else NavSwipeDirection.LeftToRight
    MiuixTheme(controller = theme) {
        PageBackdropBox {
            Scaffold(contentWindowInsets = WindowInsets(0)) {
                ListeningIntentHost(displayState) {
                    val rail = if (wide && !externalSettings) rememberGlassNavigationRailState() else null
                    val railBackdrop = if (rail != null) rememberSurfaceBackdrop() else null
                    val railInset: () -> Dp = {
                        if (rail != null) (GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth) *
                            rail.progress else 0.dp
                    }
                    val navigation = listOf(
                        GlassNavigationItem(icon = MiuixIcons.Glass.BluetoothHeadphones, label = strings(R.string.tab_earbuds)),
                        GlassNavigationItem(icon = MiuixIcons.Glass.BluetoothDiscovery, label = strings(R.string.tab_connection)),
                        GlassNavigationItem(icon = MiuixIcons.Glass.SettingsOutline, label = strings(R.string.settings)),
                    )
                    val pageContent: @Composable (String, Dp?) -> Unit = { page, bottom ->
                        val entryState = currentDisplayState.value
                        LaunchedEffect(page) {
                            if (page == SettingsPage.SPATIAL.route) PodsService.spatialRefresh()
                        }
                        DisposableEffect(page) {
                            onDispose { if (page == SettingsPage.SPATIAL.route) PodsService.spatialCancelCalibration() }
                        }
                        if (page == SettingsPage.ABOUT.route) {
                            AboutPage(wide, railInset, back) { navigate(SettingsPage.LICENSES.route) }
                        } else MiuixPageScaffold(
                            title = if (page == SettingsPage.MODULE.route) strings(R.string.settings)
                                else if (page == SettingsPage.CONNECTION.route) strings(R.string.tab_connection)
                                else SettingsCatalog.title(page, entryState, strings),
                            expandable = expandable,
                            railInset = railInset,
                            onBack = if (bottom == null) back else null,
                            onSettings = if (externalSettings && page == SettingsPage.DEVICE.route)
                                ({ navigate(SettingsPage.MODULE.route) }) else null,
                            startsWithHeading = when (page) {
                                "controls" -> SettingsCatalog.gesturePresenceIds.any { it in entryState.controls }
                                "accessibility" -> SettingsCatalog.accessibilityPresenceIds.any { it in entryState.controls }
                                else -> page in setOf("spatial", "equalizer", "diagnostics", "licenses")
                            },
                            tabBottomPadding = bottom,
                        ) {
                            when (page) {
                                SettingsPage.DEVICE.route -> miuixDevicePage(entryState, strings, onConnect, navigate)
                                SettingsPage.NAME.route -> miuixItem("name.editor") {
                                    MiuixNameEditor(nameValue, { nameValue = it; nameError = null }, nameError, finishName)
                                }
                                else -> miuixSettingsPageContent(page, entryState, onConnect, onSystemBluetooth, navigate, strings, useFloatingBar, useGlassBar)
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        NavDisplay(
                            backStack = backStack,
                            modifier = Modifier.fillMaxSize().then(if (railBackdrop != null)
                                Modifier.layerBackdrop(railBackdrop) else Modifier),
                            onBack = back,
                            effects = NavDisplayEffects(cornerClipRadius = if (rail == null)
                                rememberNavSystemCornerRadius() else 0.dp),
                        ) {
                            entry<PodsRoute>(swipeDismiss = swipe) { route ->
                                if (route.path == RootPage) {
                                    val barBackdrop = if (rail == null && useGlassBar && isRuntimeShaderSupported())
                                        rememberSurfaceBackdrop() else null
                                    val fling = PagerDefaults.flingBehavior(pager, snapAnimationSpec = PagerNavigationSpringSpec)
                                    Scaffold(
                                        contentWindowInsets = WindowInsets(0),
                                        bottomBar = {
                                            if (rail == null) {
                                                PodsBottomBar(navigation, selectedTab, switchTab, useFloatingBar, barBackdrop)
                                            }
                                        },
                                    ) { padding ->
                                        val bottom = if (rail == null) padding.calculateBottomPadding()
                                            else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                                        HorizontalPager(
                                            state = pager,
                                            modifier = if (barBackdrop == null) Modifier.fillMaxSize()
                                                else Modifier.fillMaxSize().layerBackdrop(barBackdrop),
                                            flingBehavior = fling,
                                            verticalAlignment = Alignment.Top,
                                            overscrollEffect = null,
                                        ) { index -> pageContent(tabs[index].route, bottom) }
                                    }
                                } else pageContent(route.path, null)
                            }
                        }
                        if (rail != null) GlassNavigationRail(
                            items = navigation,
                            selectedIndex = if (backStack.size == 1) selectedTab else -1,
                            onSelect = { index ->
                                while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                switchTab(index)
                            },
                            backdrop = railBackdrop,
                            state = rail,
                        )
                    }
                }
                GlassDialog(show = actionError != null, backdrop = LocalPageBackdrop.current,
                    summary = actionError, onDismissRequest = { actionError = null }) {
                    GlassDialogButton(onClick = { actionError = null }, modifier = Modifier.fillMaxWidth(),
                        role = GlassDialogButtonRole.Primary) {
                        Text(strings(R.string.ok), style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}

@Composable
private fun PodsBottomBar(
    items: List<GlassNavigationItem>,
    selected: Int,
    select: (Int) -> Unit,
    floating: Boolean,
    backdrop: LayerBackdrop?,
) {
    if (floating || backdrop != null) {
        val destinations = remember(items) { items.map { NavigationItem(label = it.label.orEmpty(), icon = it.icon) } }
        LiquidGlassBottomBar(destinations, selected, select, backdrop, floating)
    } else NavigationBar {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selected, onClick = { select(index) }, icon = item.icon,
                label = item.label.orEmpty(),
            )
        }
    }
}

private fun LazyListScope.miuixDevicePage(
    state: PodsState,
    strings: com.hyper.podsrx.i18n.Strings,
    onConnect: () -> Unit,
    navigate: (String) -> Unit,
) {
    miuixItem("device.product") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(state.headset.caseArtwork), null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(266.dp))
        }
    }
    miuixItem("device.batteries") { MiuixBatteryCard(state) }
    if (!state.running) {
        miuixGroupedItems("device.connect", listOf(Unit)) {
            RowItem(strings(R.string.connect_airpods)) {
                navigate(SettingsPage.CONNECTION.route)
                onConnect()
            }
        }
    }
    miuixGroupedItems("device.name", listOf(Unit)) {
        RowItem(strings(R.string.name), value = state.displayName,
            enabled = state.address.isNotBlank() && DeviceRepository.permissionsGranted()) { navigate(SettingsPage.NAME.route) }
    }
    miuixHeader("device.listening.title", strings(R.string.listening_mode))
    miuixItem("device.listening") { ListeningControls(state) }
}

@Composable
private fun MiuixBatteryCard(state: PodsState) {
    val strings = rememberStrings()
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf(4 to R.string.left, 2 to R.string.right, 8 to R.string.charging_case).forEach { (id, labelRes) ->
                val battery = batteryPresentation(state.batteries[id], strings)
                val label = strings(labelRes)
                Column(
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = listOf(label, battery.label, battery.note).filter(String::isNotBlank).joinToString(" · ")
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BatteryGlyph(battery)
                    Text(battery.label, style = MiuixTheme.textStyles.headline1)
                    Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
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
