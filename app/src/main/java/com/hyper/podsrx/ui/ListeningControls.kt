package com.hyper.podsrx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyper.podsrx.R
import com.hyper.podsrx.device.PodsService
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.i18n.rememberStrings
import com.hyper.podsrx.ui.ios.ListeningIntentState
import com.hyper.podsrx.ui.ios.expireListeningTarget
import com.hyper.podsrx.ui.ios.nextListeningTarget
import com.hyper.podsrx.ui.ios.requestListeningMode
import com.hyper.podsrx.ui.ios.requestListeningStrength
import com.hyper.podsrx.ui.ios.startListeningTarget
import com.hyper.podsrx.ui.ios.syncListeningIntent
import com.hyper.podsrx.ui.ios.listeningPresentation as intentPresentation
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both renderers use the same tested intent reducer and the existing device command queue. */
private class MiuixListeningController {
    var intent by mutableStateOf(ListeningIntentState())
    fun mode(value: Int) { intent = requestListeningMode(intent, value) }
    fun strength(value: Int) { intent = requestListeningStrength(intent, value) }
}

private val LocalMiuixListeningController = staticCompositionLocalOf<MiuixListeningController?> { null }

@Composable
internal fun MiuixListeningIntentHost(state: PodsState, content: @Composable () -> Unit) {
    val controller = rememberMiuixListeningController(state,
        { PodsService.setControl(0x0d, it) }, { PodsService.setControl(0x2e, it) })
    CompositionLocalProvider(LocalMiuixListeningController provides controller, content = content)
}

@Composable
private fun rememberMiuixListeningController(
    state: PodsState,
    onModeChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
): MiuixListeningController {
    val controller = remember(state.address, state.running) { MiuixListeningController() }
    val currentState by rememberUpdatedState(state)
    val modeChange by rememberUpdatedState(onModeChange)
    val strengthChange by rememberUpdatedState(onStrengthChange)
    LaunchedEffect(controller, state, controller.intent) {
        val synced = syncListeningIntent(controller.intent, state)
        controller.intent = synced
        nextListeningTarget(synced, state)?.let { target ->
            val started = startListeningTarget(synced, target, state.message)
            if (started.inFlight?.target == target) {
                controller.intent = started
                if (target.control == 0x0d) modeChange(target.value) else strengthChange(target.value)
            }
        }
    }
    val flight = controller.intent.inFlight?.target
    LaunchedEffect(controller, flight) {
        if (flight != null) {
            delay(13_000)
            controller.intent = expireListeningTarget(controller.intent, flight, currentState)
        }
    }
    return controller
}

@Composable
internal fun ListeningControls(
    state: PodsState,
    onModeChange: (Int) -> Unit = { PodsService.setControl(0x0d, it) },
    onStrengthChange: (Int) -> Unit = { PodsService.setControl(0x2e, it) },
) {
    val strings = rememberStrings()
    val controller = LocalMiuixListeningController.current
        ?: rememberMiuixListeningController(state, onModeChange, onStrengthChange)
    val ui = intentPresentation(state, strings, controller.intent)
    val caption = ui.modeNotice.ifBlank { if (ui.showAdaptive) ui.strengthNotice else "" }
    SettingsCard {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                SettingsCatalog.listeningChoices.forEach { choice ->
                    val selected = ui.mode == choice.value
                    // Adaptive audio does not change the native selector's four-item layout.
                    val enabled = ui.modeEnabled && (choice.value != 1 || state.controls[0x34]?.firstOrNull() != 2)
                    Column(Modifier.weight(1f)
                        .graphicsLayer { alpha = if (enabled || selected) 1f else .4f },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) {
                            NativeListeningIcon(choice.value, strings(choice.labelRes), selected, enabled) {
                                if (!selected) controller.mode(choice.value)
                            }
                        }
                        if (caption.isBlank()) Text(strings(choice.labelRes), fontSize = 15.sp, lineHeight = 20.sp,
                            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp).heightIn(min = 22.dp).clearAndSetSemantics {})
                    }
                }
            }
            if (caption.isNotBlank()) ListeningNotice(caption)
            if (ui.showAdaptive) {
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cellWidth = maxWidth / SettingsCatalog.listeningChoices.size
                    val firstDiameter = if (ui.mode == SettingsCatalog.listeningChoices.first().value) 59.8.dp else 48.dp
                    val lastDiameter = if (ui.mode == SettingsCatalog.listeningChoices.last().value) 59.8.dp else 48.dp
                    AdaptiveStrengthControl(state, showNotice = false,
                        modifier = Modifier.padding(
                            start = ((cellWidth - firstDiameter) / 2).coerceAtLeast(0.dp),
                            end = ((cellWidth - lastDiameter) / 2).coerceAtLeast(0.dp),
                        ))
                }
            }
        }
    }
}

@Composable
private fun NativeListeningIcon(mode: Int, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val resource = when (mode) {
        2 -> R.drawable.listening_anc
        3 -> R.drawable.listening_transparency
        4 -> R.drawable.listening_adaptive
        else -> R.drawable.listening_off
    }
    Box(Modifier.size(if (selected) 59.8.dp else 48.dp)
        .clip(CircleShape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Image(painterResource(if (selected) R.drawable.miuix_listening_selected else R.drawable.miuix_listening_background),
            null, modifier = Modifier.matchParentSize())
        // SrcIn tint keeps each PNG pixel's alpha, including the off symbol's faint ring.
        Icon(painterResource(resource), null, Modifier.size(30.dp),
            tint = MiuixStatusColors.listeningIcon(mode, selected, dark))
    }
}

@Composable
internal fun AdaptiveStrengthControl(state: PodsState, showNotice: Boolean = true, modifier: Modifier = Modifier) {
    val strings = rememberStrings()
    val controller = LocalMiuixListeningController.current ?: rememberMiuixListeningController(state,
        { PodsService.setControl(0x0d, it) }, { PodsService.setControl(0x2e, it) })
    val ui = intentPresentation(state, strings, controller.intent)
    val notice = ui.modeNotice.ifBlank { ui.strengthNotice }
    Column(modifier.fillMaxWidth()) {
        Slider(value = (ui.strength ?: 50).toFloat(), enabled = ui.strengthEnabled,
            valueRange = 0f..100f, onValueChange = { value ->
                if (ui.strengthEnabled) controller.strength(adaptiveStrengthAt(value / 100f))
            })
        if (notice.isBlank()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SettingsCatalog.adaptiveAnchors.forEach { anchor ->
                Text(com.hyper.podsrx.ui.ios.adaptiveStrengthLabel(anchor, strings), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
        else if (showNotice) ListeningNotice(notice)
    }
}

@Composable
private fun ListeningNotice(message: String) {
    Text(message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 22.dp)
            .semantics { liveRegion = LiveRegionMode.Polite })
}
