// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from Mishka's IosLiquidGlassNavigationBar. AppMarket's dock dimensions and lens motion
// are the reference for the floating variant.

package com.hyper.podsrx.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.EdgeLightSource
import top.yukonga.miuix.kmp.material.EdgePeak
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.drawEdgeStroke
import top.yukonga.miuix.kmp.material.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

private val GlassRim = EdgeStroke(
    width = 1.dp,
    bevel = 2.dp,
    color = Color.White.copy(alpha = 0.12f),
    primary = EdgeLightSource(x = 0.5f, y = -0.3f, z = -0.05f, color = Color.White, intensity = 1f),
    secondary = EdgeLightSource(x = 0.5f, y = 0.8f, z = -0.5f, color = Color.White, intensity = 0.4f),
    peak = EdgePeak.Axial,
)

@Composable
private fun rememberLightAngle(): State<Float> {
    val tilt = rememberDeviceTilt()
    return remember(tilt) {
        derivedStateOf {
            val x = tilt.value.gravityX
            val y = tilt.value.gravityY
            val step = (3.0 * PI / 180.0).toFloat()
            if (x * x + y * y <= .01f) (-PI / 2).toFloat()
            else (atan2(y, x) / step).roundToInt() * step
        }
    }
}

private fun rimAt(angle: Float, degrees: Float): EdgeStroke {
    val direction = angle + (degrees * PI / 180.0).toFloat()
    val x = cos(direction)
    val y = sin(direction)
    return GlassRim.copy(
        primary = GlassRim.primary.copy(x = .5f + x, y = .7f + y),
        secondary = GlassRim.secondary.copy(x = .5f - x, y = .7f - y),
    )
}

@Composable
internal fun LiquidGlassBottomBar(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    floating: Boolean,
) {
    val barShape = if (floating) CircleShape else RoundedCornerShape(0.dp)
    val accent = MiuixTheme.colorScheme.primary
    val foreground = MiuixTheme.colorScheme.onSurface
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val dark = isSystemInDarkTheme()
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = backdrop?.let { rememberCombinedBackdrop(it, tabsBackdrop) }
    val light = if (backdrop != null) rememberLightAngle() else null
    val tint = if (backdrop != null) surface.copy(alpha = .4f) else surface

    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    var activeIndex by remember { mutableIntStateOf(selectedIndex) }
    var width by remember { mutableIntStateOf(0) }
    val inset = with(density) { 4.dp.toPx() }
    val slotWidth = (width - inset * 2f) / items.size
    val rubberBand = remember { Animatable(0f) }
    val drag = remember(scope, items.size, density, direction) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..items.lastIndex.toFloat(),
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { position -> position.x in 0f..width.toFloat() },
            onDragStarted = { position ->
                val logicalX = if (direction > 0) position.x else width - position.x
                val tab = ((logicalX - inset) / ((width - inset * 2f) / items.size))
                    .toInt().coerceIn(items.indices)
                updateValue(tab.toFloat())
            },
            onDragStopped = {
                val tab = targetValue.roundToInt()
                if (activeIndex != tab) {
                    activeIndex = tab
                    select(tab)
                }
                updateValue(tab.toFloat())
                scope.launch { rubberBand.animateTo(0f, spring(1f, 300f, .5f)) }
            },
            onDragCancelled = {
                updateValue(activeIndex.toFloat())
                scope.launch { rubberBand.animateTo(0f, spring(1f, 300f, .5f)) }
            },
            onDrag = { _, delta ->
                val tabWidth = (width - inset * 2f) / items.size
                updateValue((targetValue + direction * delta.x / tabWidth).coerceIn(0f, items.lastIndex.toFloat()))
                scope.launch { rubberBand.snapTo(rubberBand.value + delta.x) }
            },
        )
    }
    LaunchedEffect(selectedIndex, drag) {
        if (activeIndex != selectedIndex) {
            activeIndex = selectedIndex
            drag.animateToValue(selectedIndex.toFloat())
        }
    }

    val translatePanel = with(density) { 4.dp.toPx() }
    val panelOffset = remember(floating, rubberBand, translatePanel) {
        {
            if (floating && width > 0) {
                val fraction = (rubberBand.value / width).coerceIn(-1f, 1f)
                translatePanel * fraction.sign * EaseOut.transform(abs(fraction))
            } else 0f
        }
    }
    val highlight = remember(scope, drag, direction, panelOffset) {
        InteractiveHighlight(scope) { size, _ ->
            val center = inset + (drag.value + .5f) * ((width - inset * 2f) / items.size)
            Offset((if (direction > 0) center else size.width - center) + panelOffset(), size.height / 2)
        }
    }
    val dockWidth = if (floating) Modifier.widthIn(max = (items.size * 76 + 8).dp) else Modifier
    val activate: (Int) -> Unit = { index ->
        if (activeIndex != index) {
            activeIndex = index
            select(index)
        }
        drag.animateToValue(index.toFloat())
    }

    Box(
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(bottom = if (floating) 12.dp else 0.dp)
            .padding(horizontal = if (floating) 12.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(dockWidth.fillMaxWidth().height(64.dp).onSizeChanged { width = it.width }, contentAlignment = Alignment.CenterStart) {
            val shell = if (backdrop == null) Modifier.background(surface, barShape) else Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { barShape },
                effects = {
                    padding = maxOf(padding, 40.dp.toPx())
                    vibrancy()
                    blur(4.dp.toPx(), 4.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                onDrawFront = { drawEdgeStroke(barShape, rimAt(light!!.value, -45f), .75f) },
                layerBlock = {
                    if (floating) {
                        val amount = lerp(1f, 1f + 16.dp.toPx() / size.width, drag.pressProgress)
                        scaleX = amount
                        scaleY = amount
                    }
                },
                onDrawSurface = { drawRect(tint) },
            )
            val shadow = if (floating) Modifier.dropShadow(barShape,
                Shadow(radius = 10.dp, color = Color.Black, alpha = if (dark) .2f else .1f)) else Modifier
            Row(
                Modifier.fillMaxSize().selectableGroup().graphicsLayer { translationX = panelOffset() }
                    .then(shadow).then(shell)
                    .then(if (backdrop == null) Modifier else highlight.modifier.then(highlight.gestureModifier))
                    .then(drag.modifier).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Destinations(items, activeIndex, foreground, activate) }

            if (backdrop != null) {
                // The moving pill reads this accent row and the page; the accent row only reads the page.
                Row(
                    Modifier.clearAndSetSemantics {}.alpha(0f).layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset() }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { barShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(24.dp.toPx(), 24.dp.toPx())
                            },
                            onDrawSurface = { drawRect(tint) },
                        )
                        .then(highlight.modifier).fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Destinations(items, activeIndex, accent, scale = { lerp(1f, 1.2f, drag.pressProgress) }) }
            }

            if (slotWidth > 0f) {
                val slotSize = with(density) { slotWidth.toDp() }
                val position = Modifier.padding(horizontal = 4.dp).graphicsLayer {
                    translationX = direction * drag.value * slotWidth + panelOffset()
                }
                if (combinedBackdrop != null) {
                    Box(
                        position.drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { CircleShape },
                            effects = {
                                val press = drag.pressProgress
                                lens(10.dp.toPx() * press, 14.dp.toPx() * press, depthEffect = true, chromaticAberration = .5f)
                            },
                            onDrawFront = { drawEdgeStroke(CircleShape, rimAt(light!!.value, 90f), drag.pressProgress) },
                            layerBlock = {
                                val velocity = drag.velocity / 10f
                                scaleX = drag.scaleX / (1f - (velocity * .75f).coerceIn(-.2f, .2f))
                                scaleY = drag.scaleY * (1f - (velocity * .25f).coerceIn(-.2f, .2f))
                            },
                            onDrawSurface = {
                                val shade = if (dark) Color.White else Color.Black
                                drawRect(shade.copy(alpha = .1f), alpha = 1f - drag.pressProgress)
                                drawRect(Color.Black.copy(alpha = .03f * drag.pressProgress))
                            },
                        ).innerShadow(CircleShape) {
                            InnerShadow(radius = 8.dp * drag.pressProgress, color = Color.Black.copy(alpha = .15f),
                                alpha = drag.pressProgress)
                        }.width(slotSize).height(56.dp),
                    )
                } else {
                    Box(position.clip(CircleShape).background(accent.copy(alpha = .15f), CircleShape)
                        .width(slotSize).height(56.dp)) {
                        Row(
                            Modifier.clearAndSetSemantics {}.wrapContentWidth(Alignment.Start, unbounded = true)
                                .requiredWidth(slotSize * items.size).fillMaxHeight()
                                .graphicsLayer { translationX = -direction * drag.value * slotWidth },
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Destinations(items, activeIndex, accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Destinations(
    items: List<NavigationItem>,
    selected: Int,
    tint: Color,
    activate: ((Int) -> Unit)? = null,
    scale: () -> Float = { 1f },
) {
    items.forEachIndexed { index, item ->
        val interaction = if (activate == null) Modifier else Modifier
            .semantics(mergeDescendants = true) {
                this.selected = index == selected
                role = Role.Tab
                onClick { activate(index); true }
            }
            .onKeyEvent { event ->
                if (event.key != Key.Enter && event.key != Key.NumPadEnter && event.key != Key.Spacebar) false
                else {
                    if (event.type == KeyEventType.KeyUp) activate(index)
                    true
                }
            }.focusable()
        Column(
            Modifier.weight(1f).fillMaxHeight().then(interaction).graphicsLayer {
                scaleX = scale()
                scaleY = scaleX
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        ) {
            Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
            Text(item.label, color = tint, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
