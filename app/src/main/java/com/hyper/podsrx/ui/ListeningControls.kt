package com.hyper.podsrx.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.device.PodsService
import com.hyper.podsrx.device.PodsState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ListeningControls(
    state: PodsState,
    onModeChange: (Int) -> Unit = { PodsService.setControl(0x0d, it) },
    onStrengthChange: (Int) -> Unit = { PodsService.setControl(0x2e, it) }
) {
    val ui = listeningPresentation(state)
    var draft by remember(state.address, state.connected, ui.mode, ui.strength, ui.pending) {
        mutableIntStateOf(ui.strength ?: 50)
    }
    var editing by remember(state.address, state.connected, ui.mode, ui.strength, ui.pending) {
        mutableStateOf(false)
    }
    val change: (Int) -> Unit = { draft = it; editing = true }
    val finish = {
        if (ui.strengthEnabled && draft != ui.strength) onStrengthChange(draft)
        editing = false
    }
    val cancel = { draft = ui.strength ?: 50; editing = false }
    val shownStrength = if (editing) draft else ui.strength
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceSecondary
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "左" else "右"
    val track = secondary.copy(alpha = .09f)
    SettingsCard {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)) {
            val compact = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
            val expanded = ui.showAdaptive && !compact
            val adaptiveWeight by animateFloatAsState(if (expanded) 3f else 1f, tween(240), label = "adaptiveWidth")
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().selectableGroup().drawBehind {
                    drawRoundRect(track, size = Size(size.width, 68.dp.toPx()), cornerRadius = CornerRadius(34.dp.toPx()))
                }) {
                    Controls.listeningChoices.forEach { choice ->
                        val selected = ui.mode == choice.value
                        Column(Modifier.weight(if (choice.value == 4) adaptiveWeight else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (choice.value == 4 && expanded) {
                                AdaptiveSlider(shownStrength, ui.strengthEnabled, change, finish, cancel)
                                Text("自适应", fontSize = 15.sp, color = primary, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {})
                            } else {
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                    .selectable(selected, enabled = ui.modeEnabled, role = Role.RadioButton) {
                                        if (!selected) onModeChange(choice.value)
                                    }.alpha(if (ui.modeEnabled || selected) 1f else .45f),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(56.dp).then(if (selected) Modifier.border(1.5.dp, primary, CircleShape) else Modifier)
                                            .padding(4.dp).clip(CircleShape).background(if (selected) primary else Color.Transparent),
                                            contentAlignment = Alignment.Center) {
                                            ListeningGlyph(choice.value, if (selected) Color.White else secondary)
                                        }
                                    }
                                    Text(choice.label, fontSize = 15.sp, color = if (selected) primary else MiuixTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
                                }
                            }
                        }
                    }
                }
                if (ui.showAdaptive) {
                    if (compact) AdaptiveSlider(shownStrength, ui.strengthEnabled, change, finish, cancel)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("更通透", fontSize = 12.sp, color = secondary)
                        Text(when {
                            editing -> "${adaptiveStrengthLabel(draft)} · 松手应用"
                            ui.strength == null -> "降噪幅度未读取"
                            else -> "降噪幅度 · ${adaptiveStrengthLabel(ui.strength)}"
                        }, fontSize = 12.sp, color = primary, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text("更降噪", fontSize = 12.sp, color = secondary)
                    }
                    Text("向${direction}滑动增强降噪，支持三档调节。", fontSize = 12.sp, color = secondary,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (ui.notice.isNotBlank()) Text(ui.notice, fontSize = 12.sp, color = secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

/** A single accessible slider; horizontal drags don't steal vertical page scrolling. */
@Composable
private fun AdaptiveSlider(value: Int?, enabled: Boolean, onChange: (Int) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val latestFinish by rememberUpdatedState(onFinish)
    val latestCancel by rememberUpdatedState(onCancel)
    val latestValue by rememberUpdatedState(value ?: 50)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val insetPx = with(LocalDensity.current) { 34.dp.toPx() }
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceSecondary
    val surface = MiuixTheme.colorScheme.surface
    val thumbColor = primary.copy(alpha = .16f).compositeOver(surface)
    var focused by remember { mutableStateOf(false) }
    val updateFromPosition: (Float, Int) -> Unit = { x, width ->
        val fraction = (x - insetPx) / (width - insetPx * 2).coerceAtLeast(1f)
        latestChange(adaptiveStrengthAt(if (rtl) 1f - fraction else fraction))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(68.dp).clip(CircleShape)
        .background(primary.copy(alpha = if (enabled) .11f else .05f))
        .then(if (focused) Modifier.border(2.dp, primary, CircleShape) else Modifier)
        .semantics {
            contentDescription = "自适应降噪幅度"
            selected = true
            stateDescription = if (value == null) "未读取" else "${adaptiveStrengthLabel(value)}，向${if (rtl) "左" else "右"}增强降噪"
            if (value != null) progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..100f, 1)
            if (!enabled) disabled() else setProgress { target ->
                if (!target.isFinite()) false else {
                    latestChange(adaptiveStrengthAt(target / 100f)); latestFinish(); true
                }
            }
        }
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false else {
                val next = when (event.key) {
                    Key.DirectionLeft -> latestValue + if (rtl) 50 else -50
                    Key.DirectionRight -> latestValue + if (rtl) -50 else 50
                    Key.DirectionUp -> latestValue + 50
                    Key.DirectionDown -> latestValue - 50
                    Key.MoveHome -> 0
                    Key.MoveEnd -> 100
                    else -> null
                }
                if (next == null) false else {
                    latestChange(adaptiveStrengthAt(next / 100f)); latestFinish(); true
                }
            }
        }.focusable(enabled)
        .pointerInput(enabled, rtl, insetPx) {
            if (enabled) detectHorizontalDragGestures(
                onDragStart = { updateFromPosition(it.x, size.width) },
                onHorizontalDrag = { event, _ -> event.consume(); updateFromPosition(event.position.x, size.width) },
                onDragEnd = { latestFinish() }, onDragCancel = { latestCancel() }
            )
        }
        .pointerInput(enabled, rtl, insetPx) {
            if (enabled) detectTapGestures { updateFromPosition(it.x, size.width); latestFinish() }
        }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            StrengthRing(dotted = true, secondary)
            Canvas(Modifier.size(4.dp).clearAndSetSemantics {}) { drawCircle(secondary.copy(alpha = .4f)) }
            StrengthRing(dotted = false, secondary)
        }
        if (value != null) Box(Modifier.align(Alignment.CenterStart)
            .offset(x = 4.dp + (maxWidth - 68.dp) * (value / 100f))
            .size(60.dp).clip(CircleShape).background(thumbColor)
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
