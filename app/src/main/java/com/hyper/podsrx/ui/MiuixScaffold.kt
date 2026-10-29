package com.hyper.podsrx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Each retained page owns its title, scroll physics, insets and backdrop. */
@Composable
internal fun MiuixPageScaffold(
    title: String,
    onBack: () -> Unit,
    onMore: (() -> Unit)? = null,
    startsWithHeading: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidth = with(density) { window.width.toDp() }
    val windowHeight = with(density) { window.height.toDp() }
    val compactAppBar = windowWidth >= 600.dp || windowHeight < 480.dp
    val centered = ((windowWidth - 800.dp) / 2).coerceAtLeast(0.dp)
    val direction = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout).asPaddingValues()
    val start = maxOf(centered, horizontalInsets.calculateStartPadding(direction))
    val end = maxOf(centered, horizontalInsets.calculateEndPadding(direction))
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val barModifier = Modifier.textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 25f,
        colors = BlurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha = .8f)))),
    )
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        topBar = {
            val navigation: @Composable () -> Unit = { NavigationButton("返回", true, onBack) }
            val actions: @Composable RowScope.() -> Unit = {
                onMore?.let { NavigationButton("更多设置", false, it) }
            }
            if (compactAppBar) {
                SmallTopAppBar(title = title, modifier = barModifier, color = Color.Transparent,
                    navigationIcon = navigation, actions = actions)
            } else {
                TopAppBar(title = title, modifier = barModifier, color = Color.Transparent,
                    navigationIcon = navigation, actions = actions, scrollBehavior = scrollBehavior)
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding)
                .scrollEndHaptic().overScrollVertical()
                .then(if (compactAppBar) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection))
                .layerBackdrop(backdrop),
            contentPadding = PaddingValues(start = start, end = end, top = padding.calculateTopPadding()),
        ) {
            if (!startsWithHeading) item("page-top-spacing") { Spacer(Modifier.height(12.dp)) }
            content()
            item("page-bottom-spacing") { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

internal fun LazyListScope.miuixHeader(key: String, title: String) {
    item(key) { SmallTitle(title, Modifier.semantics { heading() }) }
}

/** Composite widgets keep their own native card; this helper owns only outer page spacing. */
internal fun LazyListScope.miuixItem(key: String, content: @Composable () -> Unit) {
    item(key) { Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) { content() } }
}

internal fun LazyListScope.miuixFootnote(key: String, text: String) {
    if (text.isBlank()) return
    item(key) {
        Text(text, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp))
    }
}

/** Dynamic preference groups remain independently lazy while sharing one squircle outline. */
internal fun <T> LazyListScope.miuixGroupedItems(
    keyPrefix: String,
    items: List<T>,
    title: String? = null,
    itemKey: (T) -> Any = { it.toString() },
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    title?.let { miuixHeader(keyPrefix + ".title", it) }
    itemsIndexed(items = items, key = { _, row -> keyPrefix + "." + itemKey(row) }) { index, row ->
        val first = index == 0
        val last = index == items.lastIndex
        val color = MiuixTheme.colorScheme.surfaceContainer
        val segment = if (first || last) Modifier.squircleSurface(
            color = color,
            topStart = if (first) 16.dp else 0.dp,
            topEnd = if (first) 16.dp else 0.dp,
            bottomStart = if (last) 16.dp else 0.dp,
            bottomEnd = if (last) 16.dp else 0.dp,
        ) else Modifier.background(color)
        Box(Modifier.padding(horizontal = 12.dp).padding(bottom = if (last) 12.dp else 0.dp)
            .fillMaxWidth().then(segment)) { itemContent(row) }
    }
}
