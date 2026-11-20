package com.hyper.podsrx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.hyper.podsrx.R
import com.hyper.podsrx.i18n.rememberStrings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun rememberSurfaceBackdrop(): LayerBackdrop {
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

@Composable
internal fun PageBackdropBox(content: @Composable () -> Unit) {
    val backdrop = rememberSurfaceBackdrop()
    CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content() }
    }
}

internal fun Modifier.railInset(inset: () -> Dp): Modifier = layout { measurable, constraints ->
    val width = inset().roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = -width))
    layout(placeable.width + width, placeable.height) { placeable.placeRelative(width, 0) }
}

private val CutoutAndNavigationSides
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal)

@Composable
internal fun MiuixPageScaffold(
    title: String,
    expandable: Boolean,
    railInset: () -> Dp,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    startsWithHeading: Boolean = false,
    tabBottomPadding: Dp? = null,
    scrollState: LazyListState = rememberLazyListState(),
    titleAlpha: () -> Float = { 1f },
    barAlpha: () -> Float = { 1f },
    background: @Composable (PaddingValues) -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val strings = rememberStrings()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val contentScrolled by remember(scrollState) { derivedStateOf { scrollState.canScrollBackward } }
    val backdrop = rememberSurfaceBackdrop()
    val surface = MiuixTheme.colorScheme.surface
    val bandBrush = remember(surface) { GlassTopAppBarDefaults.bandBrush(surface) }
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl

    PageBackdropBox {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                GlassTopAppBar(
                    title = title,
                    expandable = expandable,
                    isContentScrolled = contentScrolled,
                    backdrop = backdrop,
                    bandBrush = bandBrush,
                    bandOverhang = GlassTopAppBarDefaults.BandOverhang,
                    progressiveBlur = true,
                    titleAlpha = titleAlpha,
                    backgroundAlpha = barAlpha,
                    scrollBehavior = scrollBehavior,
                    contentModifier = Modifier.windowInsetsPadding(CutoutAndNavigationSides).railInset(railInset),
                    navigationIcon = {
                        if (onBack != null) GlassIconButton(onClick = onBack) {
                            Icon(MiuixIcons.Glass.ChevronBackward, strings(R.string.back),
                                Modifier.size(28.dp).graphicsLayer { scaleX = if (mirror) -1f else 1f })
                        }
                    },
                    actions = {
                        if (onSettings != null) GlassIconButton(onClick = onSettings) {
                            Icon(MiuixIcons.Glass.SettingsOutline, strings(R.string.settings), Modifier.size(28.dp))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                background(padding)
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize()
                        .layerBackdrop(backdrop)
                        .windowInsetsPadding(CutoutAndNavigationSides)
                        .railInset(railInset)
                        .scrollEndHaptic()
                        .overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = tabBottomPadding ?: 0.dp),
                ) {
                    if (!startsWithHeading) item("page-top") { Spacer(Modifier.height(12.dp)) }
                    content()
                    item("page-bottom") {
                        Spacer(if (tabBottomPadding == null) Modifier.height(24.dp).navigationBarsPadding() else Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.miuixHeader(key: String, title: String) {
    item(key) { SmallTitle(title, Modifier.semantics { heading() }) }
}

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

/** Individual lazy rows share the outline and press clipping of one miuix card. */
internal fun <T> LazyListScope.miuixGroupedItems(
    keyPrefix: String,
    items: List<T>,
    title: String? = null,
    itemKey: (T) -> Any = { it.toString() },
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    title?.let { miuixHeader("$keyPrefix.title", it) }
    itemsIndexed(items = items, key = { _, row -> "$keyPrefix.${itemKey(row)}" }) { index, row ->
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
