package com.hyper.podsrx.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    var draft by remember(state.address) {
        mutableIntStateOf(ui.strength ?: 50)
    }
    var editing by remember(state.address) {
        mutableStateOf(false)
    }
    var dragging by remember(state.address) { mutableStateOf(false) }
    val pending = 0x2e in state.pending
    val submitted = state.adaptiveTarget
    val submit by rememberUpdatedState(newValue = {
        // The service deduplicates against the latest target, including same-frame reversals.
        if (ui.strengthEnabled) onStrengthChange(draft)
    })
    LaunchedEffect(state.address, state.connected, ui.mode, ui.strength, pending, submitted, dragging) {
        if (!state.connected || ui.mode != 4) {
            editing = false
            dragging = false
        } else if (!dragging) {
            if (submitted != null) {
                draft = submitted
                editing = true
            } else {
                draft = ui.strength ?: 50
                editing = false
            }
        }
    }
    val change: (Int) -> Unit = {
        draft = it
        editing = true
        submit()
    }
    val finish = { submit() }
    val cancel = {
        draft = submitted ?: ui.strength ?: 50
        editing = submitted != null
    }
    val shownStrength = if (editing) draft else submitted ?: ui.strength
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
                                LibrePodsAdaptiveSlider(shownStrength, ui.strengthEnabled, change, finish, cancel) { dragging = it }
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
                    if (compact) LibrePodsAdaptiveSlider(shownStrength, ui.strengthEnabled, change, finish, cancel) { dragging = it }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("更通透", fontSize = 12.sp, color = secondary)
                        Text(when {
                            pending -> "目标 ${adaptiveStrengthLabel(draft)} · 正在发送"
                            submitted != null && !dragging -> "${adaptiveStrengthLabel(submitted)} · 已发送"
                            editing -> "${adaptiveStrengthLabel(draft)} · 正在调节"
                            ui.strength == null -> "降噪幅度未读取"
                            else -> "降噪幅度 · ${adaptiveStrengthLabel(ui.strength)}"
                        }, fontSize = 12.sp, color = primary, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text("更降噪", fontSize = 12.sp, color = secondary)
                    }
                    Text("向${direction}滑动增强降噪，连续调节。", fontSize = 12.sp, color = secondary,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    if (submitted != null) Text("耳机当前回读：${ui.strength?.let { adaptiveStrengthLabel(it) } ?: "等待读取"}", fontSize = 12.sp,
                        color = secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (ui.notice.isNotBlank()) Text(ui.notice, fontSize = 12.sp, color = secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
