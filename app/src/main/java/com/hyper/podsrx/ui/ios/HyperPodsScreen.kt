package com.hyper.podsrx.ui.ios

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Earbuds
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import com.hyper.podsrx.R
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.PodsService
import com.hyper.podsrx.i18n.rememberStrings
import com.hyper.podsrx.ui.ListeningIntentHost
import com.hyper.podsrx.ui.SettingsCatalog
import com.hyper.podsrx.ui.SettingsPage
import com.hyper.podsrx.ui.rememberReportedControls
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosPageHorizontalPadding
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.icons.GlasmioIconResolver
import io.github.neoruaa.glasmio.icons.LocalGlasmioIconResolver
import io.github.neoruaa.glasmio.icons.MaterialGlasmioIconResolver
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.*
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavDisplay
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavHeaderMotion
import io.github.neoruaa.glasmio.scaffold.navigation.iosPopTransitionSpec
import io.github.neoruaa.glasmio.scaffold.navigation.iosPredictivePopTransitionSpec
import io.github.neoruaa.glasmio.scaffold.navigation.iosTransitionSpec
import io.github.neoruaa.glasmio.ui.*

internal fun actionFailureMessage(state: com.hyper.podsrx.device.PodsState, strings: com.hyper.podsrx.i18n.Strings): String? =
    SettingsCatalog.actionFailureMessage(state, strings)

private val rootPages = setOf(SettingsPage.DEVICE.route, SettingsPage.CONNECTION.route, SettingsPage.MODULE.route)

// The tab API takes SfSymbol. Supply Android vectors for the app's device destinations.
private val IosIcons = GlasmioIconResolver { name ->
    when (name) {
        SfSymbol.RadioWaves.systemName -> Icons.Filled.Bluetooth
        SfSymbol.Waveform.systemName -> Icons.Filled.Earbuds
        else -> MaterialGlasmioIconResolver.resolve(name)
    }
}

@Composable
internal fun IosSettingsScreen(
    externalSettings: Boolean,
    initialPage: String?,
    onConnect: () -> Unit,
    onSystemBluetooth: () -> Unit,
    onFinish: () -> Unit,
) {
    val strings = rememberStrings()
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    val displayState = rememberReportedControls(state)
    val currentState = rememberUpdatedState(displayState)
    val view = LocalView.current
    DisposableEffect(view, state.spatial.calibrationMode) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state.spatial.calibrationMode
        onDispose { view.keepScreenOn = previous }
    }
    val backStack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() }, restore = { it.toMutableStateList() },
        ),
    ) {
        val initial = SettingsCatalog.initialPage(initialPage)?.takeUnless { it == SettingsPage.BATTERY }
        generateSequence(initial ?: SettingsPage.DEVICE) { page ->
            if (page in setOf(SettingsPage.AUDIO, SettingsPage.CONTROLS, SettingsPage.ACCESSIBILITY)) SettingsPage.MODULE
            else page.parent
        }
            .map { it.route }.toList().asReversed().toMutableStateList()
    }
    val route = backStack.last()
    val showTabs = !externalSettings && route in rootPages
    val selectedTab = backStack.last { it in rootPages }
    val homeList = rememberLazyListState()
    val connectionList = rememberLazyListState()
    val settingsList = rememberLazyListState()
    val rootLists = remember(homeList, connectionList, settingsList) {
        mapOf("device" to homeList, "connection" to connectionList, "module" to settingsList)
    }
    val barScroll = rememberGlasmioNavigationScrollState(
        listState = rootLists[selectedTab] ?: homeList, enabled = showTabs,
    )
    val tabLayout = if (externalSettings) GlasmioNavigationLayout.BottomBar else glasmioNavigationLayout()
    val topTabs = tabLayout == GlasmioNavigationLayout.TopTabs
    val sidebar = rememberGlasmioAdaptiveNavigationState()
    val tabs = remember(strings) {
        listOf(
            GlasmioTabItem("device", strings(R.string.tab_earbuds), SfSymbol.Waveform),
            GlasmioTabItem("connection", strings(R.string.tab_connection), SfSymbol.RadioWaves),
            GlasmioTabItem("module", strings(R.string.settings), SfSymbol.Gearshape),
        )
    }
    val backdrop = rememberLayerBackdrop()
    val motion = remember {
        GlasmioNavHeaderMotion(backStack.last()) { from, to, pop ->
            if (!externalSettings && from in rootPages && to in rootPages) GlasmioNavigationOperation.Tab
            else if (pop) GlasmioNavigationOperation.Pop else GlasmioNavigationOperation.Push
        }
    }
    val scrollStates = remember { mutableStateMapOf<String, GlasmioHeaderScrollState>() }
    SideEffect {
        val frame = motion.frame.value
        scrollStates.keys.retainAll(backStack + listOf(frame.fromEntryKey, frame.toEntryKey))
    }
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current.density
    var name by rememberSaveable(state.address, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.displayName))
    }
    var nameError by rememberSaveable { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var seenMessage by remember { mutableLongStateOf(state.messageRevision) }
    LaunchedEffect(state.messageRevision) {
        if (seenMessage != state.messageRevision) {
            seenMessage = state.messageRevision
            SettingsCatalog.actionFailureMessage(state, strings)?.let { error = it }
        }
    }
    val selectTab: (String) -> Unit = { page ->
        if (backStack.last() != page) {
            backStack.clear()
            backStack.add("device")
            if (page != "device") backStack.add(page)
            barScroll.expand()
        }
    }
    val navigate: (String) -> Unit = { page ->
        if (!externalSettings && page in rootPages) selectTab(page)
        else if (page != backStack.last()) {
            if (page == "name") {
                name = TextFieldValue(state.displayName, selection = TextRange(state.displayName.length))
                nameError = null
            }
            if (page in backStack) while (backStack.last() != page) backStack.removeAt(backStack.lastIndex)
            else backStack.add(page)
        }
    }
    val back: () -> Unit = {
        when {
            backStack.size > 1 -> backStack.removeAt(backStack.lastIndex)
            else -> onFinish()
        }
    }
    val saveName: () -> Unit = {
        val value = name.text.trim()
        nameError = when {
            value.isBlank() -> R.string.enter_name
            value.length > 100 -> R.string.name_length_limit
            value == state.displayName || DeviceRepository.rename(value) -> null
            else -> R.string.rename_failed_retry
        }
        if (nameError == null) back()
    }
    val title: (String) -> String = { page ->
        if (page == "module") strings(R.string.settings) else iosPageTitle(page, currentState.value, strings)
    }
    BackHandler(backStack.size == 1, onBack = back)

    GlasmioTheme(isDark = isSystemInDarkTheme()) {
        CompositionLocalProvider(LocalGlasmioIconResolver provides IosIcons) {
            val background = GlasmioTheme.colors.groupedBackground
            val expanded = remember { mutableFloatStateOf(0f) }
            ListeningIntentHost(displayState) {
                GlasmioTextSelectionHost(backdrop = backdrop) {
                    GlasmioAdaptiveNavigationScaffold(
                        state = sidebar,
                        layout = tabLayout,
                        contentBackdrop = backdrop,
                        sidebarTransition = GlasmioSidebarTransition.SplitView,
                        sidebar = {
                            Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()
                                .padding(horizontal = 10.dp)) {
                                GlasmioSidebarToolbar(
                                    leading = { Text(strings(R.string.app_name), style = IosTypography.headline,
                                        modifier = Modifier.padding(start = 12.dp)) },
                                    trailing = { GlasmioIconButton(onClick = sidebar::toggleSidebar) { GlasmioSidebarToggleIcon() } },
                                )
                                tabs.forEach { item ->
                                    GlasmioSidebarRow(item.label, item.symbol, selectedTab == item.key,
                                        onClick = { selectTab(item.key) })
                                }
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize().nestedScroll(barScroll.nestedScrollConnection)) {
                            GlasmioNavigationScaffold(
                                contentBackdrop = backdrop,
                                motion = motion,
                                header = { key ->
                                    val page = key as String
                                    val leading = when {
                                        externalSettings || page !in rootPages -> listOf(GlasmioHeaderGroup("back", listOf(
                                            GlasmioHeaderAction("back", "chevron.left", strings(R.string.back), back) {
                                                SfIcon(SfSymbol.ChevronBack, null, mirrored = true)
                                            },
                                        )))
                                        topTabs -> listOf(GlasmioHeaderGroup("sidebar", listOf(
                                            GlasmioHeaderAction("sidebar", "sidebar.left", strings(R.string.ios_sidebar),
                                                sidebar::toggleSidebar) { GlasmioSidebarToggleIcon() },
                                        )))
                                        else -> emptyList()
                                    }
                                    val trailing = when {
                                        page == "name" -> listOf(GlasmioHeaderGroup("save", listOf(
                                            GlasmioHeaderAction("save", "checkmark", strings(R.string.ok), saveName) {
                                                SfIcon("checkmark", null)
                                            },
                                        )))
                                        externalSettings && page == "device" -> listOf(GlasmioHeaderGroup("settings", listOf(
                                            GlasmioHeaderAction("settings", "gearshape", strings(R.string.settings), { navigate("module") }) {
                                                SfIcon(SfSymbol.Gearshape, null)
                                            },
                                        )))
                                        else -> emptyList()
                                    }
                                    GlasmioHeaderSpec(title(page), leading = leading, trailing = trailing, scrollState = scrollStates[page])
                                },
                                topBar = if (topTabs && showTabs) ({ availableWidth ->
                                    GlasmioNavigationBar(tabs, selectedTab, selectTab, {}, expanded, CompactBottomControlSize,
                                        Modifier.widthIn(max = minOf(360.dp, availableWidth)), backdrop = backdrop)
                                }) else null,
                                topBarPreferredWidth = if (topTabs && showTabs) 360.dp else null,
                                bottomBar = {
                                    AnimatedVisibility(
                                        visible = showTabs && !topTabs,
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                        enter = fadeIn(),
                                        exit = fadeOut(),
                                    ) {
                                        GlasmioNavigationBar(
                                            items = tabs,
                                            selectedKey = selectedTab, onSelected = selectTab, onExpand = barScroll::expand,
                                            compactProgress = barScroll.compactProgress, compactSize = CompactBottomControlSize,
                                            backdrop = backdrop,
                                            modifier = Modifier.fillMaxWidth()
                                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                                                .padding(horizontal = 20.dp)
                                                .glasmioNavigationScrollHint(barScroll.scrollHintProgress)
                                                .padding(bottom = 8.dp),
                                        )
                                    }
                                },
                            ) { headerPadding ->
                                GlasmioNavDisplay(
                                    motion = motion,
                                    backStack = backStack,
                                    modifier = Modifier.fillMaxSize(),
                                    onBack = back,
                                    transitionSpec = {
                                        if (!externalSettings && betweenRootPages()) fadeIn() togetherWith fadeOut()
                                        else iosTransitionSpec<String>(layoutDirection, density).invoke(this)
                                    },
                                    popTransitionSpec = {
                                        if (!externalSettings && betweenRootPages()) fadeIn() togetherWith fadeOut()
                                        else iosPopTransitionSpec<String>(layoutDirection, density).invoke(this)
                                    },
                                    predictivePopTransitionSpec = { edge ->
                                        if (!externalSettings && betweenRootPages()) fadeIn() togetherWith fadeOut()
                                        else iosPredictivePopTransitionSpec<String>(layoutDirection, density).invoke(this, edge)
                                    },
                                    entryProvider = { page ->
                                        NavEntry(key = page, contentKey = page) {
                                            val list = rootLists[page] ?: rememberLazyListState()
                                            val collapse = if (page == "name") 1f else rememberGlasmioHeaderCollapse(list)
                                            val edge = rememberGlasmioHeaderScrollEdge(list)
                                            val currentCollapse = rememberUpdatedState(collapse)
                                            val scroll = remember { GlasmioHeaderScrollState({ currentCollapse.value }) }
                                            SideEffect { scrollStates[page] = scroll }
                                            DisposableEffect(page) {
                                                if (page == "spatial") PodsService.spatialRefresh()
                                                onDispose { if (page == "spatial") PodsService.spatialCancelCalibration() }
                                            }
                                            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                                if (!externalSettings && !topTabs && page in rootPages) NavigationBarSurfaceHeight + 8.dp else 0.dp
                                            val data = currentState.value
                                            IosPage(page, title(page), list, headerPadding, bottom, collapse, edge, background) {
                                                if (page == "name") {
                                                    item("name") {
                                                        GlasmioGroupedList {
                                                            GlasmioTextField(name, { name = it; nameError = null },
                                                                placeholder = strings(R.string.name),
                                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                                keyboardActions = KeyboardActions(onDone = { saveName() }))
                                                        }
                                                    }
                                                    nameError?.let { item("name-error") { GlasmioGroupedTableFooter(strings(it)) } }
                                                } else iosPageContent(page, data, strings, navigate, onConnect, onSystemBluetooth)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    val dismiss = { error = null }
                    GlasmioAlertDialog(
                        onDismissRequest = dismiss, title = error.orEmpty(), backdrop = backdrop, visible = error != null,
                        buttons = listOf(GlasmioAlertButtonSpec(strings(R.string.ok), dismiss)),
                    )
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<Scene<String>>.betweenRootPages(): Boolean =
    initialState.entries.lastOrNull()?.contentKey in rootPages &&
        targetState.entries.lastOrNull()?.contentKey in rootPages

@Composable
private fun IosPage(
    page: String,
    title: String,
    list: LazyListState,
    headerPadding: PaddingValues,
    bottom: Dp,
    collapse: Float,
    edge: Float,
    background: androidx.compose.ui.graphics.Color,
    content: LazyListScope.() -> Unit,
) {
    GlasmioBackdropHost(sampledColor = background, modifier = Modifier.fillMaxSize(), overlayContent = {
        val pageBackdrop = rememberLayerBackdrop()
        CompositionLocalProvider(LocalBlurBackdrop provides pageBackdrop) {
            Box(Modifier.fillMaxSize()) {
                GlasmioPageBody(
                    modifier = Modifier.glassBackdropSource(pageBackdrop).trackBackdropPosition(pageBackdrop),
                    contentPadding = PaddingValues(
                        start = IosPageHorizontalPadding, top = headerPadding.calculateTopPadding(),
                        end = IosPageHorizontalPadding, bottom = bottom + 24.dp,
                    ),
                    backgroundColor = background,
                    topBarBlurProgress = edge,
                ) { padding ->
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                        contentPadding = padding,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        if (page != "name") item("large-title") {
                            Text(title, style = IosTypography.largeTitle, fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().graphicsLayer {
                                    alpha = 1f - collapse
                                    scaleX = 1f - 0.04f * collapse
                                    scaleY = scaleX
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }.blur(6.dp * collapse).offset(y = (-10).dp).padding(horizontal = 4.dp, vertical = 6.dp))
                        }
                        content()
                    }
                }
                GlasmioPageTitle(page)
            }
        }
    })
}
