/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Adapted for HyperPods-X, 2026, from NoiseControlSettings.kt on android/rewrite,
 * commit 80aa7461df414470f379ebdfe540b2e8889fcbe2 (blob 7f86743f9b945c95915f581ad4eaecd9aacf5754).
 * Reuses the adaptive thumb's continuous travel/strength mapping and drag handling.
 * Miuix colors and existing glyphs replace the upstream theme/backdrop; accessibility,
 * RTL, cancellation callbacks and confirmed readback are supplied by HyperPods-X.
 * See THIRD_PARTY.md and LICENSE for source and license terms.
 */
package com.hyper.podsrx.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LibrePodsAdaptiveSlider(
    value: Int?, enabled: Boolean, onChange: (Int) -> Unit, onFinish: () -> Unit,
    onCancel: () -> Unit, onDragging: (Boolean) -> Unit
) {
    val currentValue by rememberUpdatedState(value ?: 50)
    val currentChange by rememberUpdatedState(onChange)
    val currentFinish by rememberUpdatedState(onFinish)
    val currentCancel by rememberUpdatedState(onCancel)
    val currentDragging by rememberUpdatedState(onDragging)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val thumbWidth = with(density) { 60.dp.toPx() }
    val margin = with(density) { 4.dp.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    val maxTravel by rememberUpdatedState((width - thumbWidth - margin * 2).coerceAtLeast(0f))
    val offset = remember { Animatable(0f) }
    val animationScope = rememberCoroutineScope()
    var isInitialized by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isDragging) 1.12f else 1f, spring(), label = "adaptiveThumbPress")
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceSecondary
    val thumbColor = primary.copy(alpha = .16f).compositeOver(MiuixTheme.colorScheme.surface)

    LaunchedEffect(value, maxTravel, isDragging) {
        if (!isDragging && maxTravel > 0f && value != null) {
            val targetOffset = value * maxTravel / 100f
            if (!isInitialized) {
                offset.snapTo(targetOffset)
                isInitialized = true
            } else offset.animateTo(targetOffset)
        }
    }

    val changeOffset: (Float) -> Unit = { target ->
        val safeOffset = target.coerceIn(0f, maxTravel)
        dragOffset = safeOffset
        if (maxTravel > 0f) currentChange(adaptiveStrengthAt(safeOffset / maxTravel))
        animationScope.launch { offset.snapTo(safeOffset) }
    }
    val currentChangeOffset by rememberUpdatedState(changeOffset)
    Box(Modifier.fillMaxWidth().height(68.dp).onSizeChanged { width = it.width.toFloat() }
        .clip(CircleShape).background(primary.copy(alpha = if (enabled) .11f else .05f))
        .then(if (focused) Modifier.border(2.dp, primary, CircleShape) else Modifier)
        .semantics {
            contentDescription = "自适应降噪幅度"
            selected = true
            stateDescription = if (value == null) "未读取" else "${adaptiveStrengthLabel(value)}，向${if (rtl) "左" else "右"}增强降噪"
            if (value != null) progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..100f, 0)
            if (!enabled) disabled() else setProgress { target ->
                if (!target.isFinite()) false else {
                    currentChange(adaptiveStrengthAt(target / 100f))
                    currentFinish()
                    true
                }
            }
        }
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false else {
                val next = when (event.key) {
                    Key.DirectionLeft -> currentValue + if (rtl) 1 else -1
                    Key.DirectionRight -> currentValue + if (rtl) -1 else 1
                    Key.DirectionUp -> currentValue + 1
                    Key.DirectionDown -> currentValue - 1
                    Key.MoveHome -> 0
                    Key.MoveEnd -> 100
                    else -> null
                }
                if (next == null) false else {
                    currentChange(next.coerceIn(0, 100))
                    currentFinish()
                    true
                }
            }
        }.focusable(enabled)
        .pointerInput(enabled, rtl) {
            if (enabled) detectHorizontalDragGestures(
                onDragStart = {
                    isDragging = true
                    dragOffset = offset.value
                    currentDragging(true)
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    currentChangeOffset(dragOffset + if (rtl) -dragAmount else dragAmount)
                },
                onDragEnd = {
                    currentFinish()
                    isDragging = false
                    currentDragging(false)
                },
                onDragCancel = {
                    currentCancel()
                    isDragging = false
                    currentDragging(false)
                }
            )
        }
        .pointerInput(enabled, rtl) {
            if (enabled) detectTapGestures { tap ->
                val x = if (rtl) size.width - tap.x else tap.x
                currentChangeOffset(x - margin - thumbWidth / 2f)
                currentFinish()
            }
        }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            StrengthRing(dotted = true, secondary)
            Canvas(Modifier.size(4.dp).clearAndSetSemantics {}) { drawCircle(secondary.copy(alpha = .4f)) }
            StrengthRing(dotted = false, secondary)
        }
        if (value != null) Box(Modifier.align(Alignment.CenterStart)
            .offset { IntOffset((margin + offset.value).roundToInt(), 0) }
            .size(60.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(thumbColor)
            .border(1.5.dp, primary.copy(alpha = if (enabled) .65f else .25f), CircleShape), contentAlignment = Alignment.Center) {
            ListeningGlyph(4, if (enabled) primary else secondary)
        }
    }
}

@Composable
private fun StrengthRing(dotted: Boolean, color: Color) {
    Canvas(Modifier.size(18.dp).clearAndSetSemantics {}) {
        drawCircle(color, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 2.7.dp.toPx())) else null))
    }
}
