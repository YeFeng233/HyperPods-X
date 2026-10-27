package com.hyper.podsrx.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.device.PodsService
import com.hyper.podsrx.device.PodsState
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioAdaptiveSlider
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import kotlinx.coroutines.delay

private class ListeningIntentController {
    var intent by mutableStateOf(ListeningIntentState())
    fun mode(value: Int) { intent = requestListeningMode(intent, value) }
    fun strength(value: Int) { intent = requestListeningStrength(intent, value) }
}

private val LocalListeningIntentController = staticCompositionLocalOf<ListeningIntentController?> { null }

@Composable
internal fun ListeningIntentHost(state: PodsState, content: @Composable () -> Unit) {
    val controller = rememberListeningIntentController(state,
        { PodsService.setControl(0x0d, it) }, { PodsService.setControl(0x2e, it) })
    CompositionLocalProvider(LocalListeningIntentController provides controller, content = content)
}

@Composable
private fun rememberListeningIntentController(
    state: PodsState,
    onModeChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
): ListeningIntentController {
    val controller = remember(state.address, state.running) { ListeningIntentController() }
    val latestState by rememberUpdatedState(state)
    val modeChange by rememberUpdatedState(onModeChange)
    val strengthChange by rememberUpdatedState(onStrengthChange)
    LaunchedEffect(controller, state, controller.intent) {
        val synced = syncListeningIntent(controller.intent, state)
        controller.intent = synced
        nextListeningTarget(synced, state)?.let { target ->
            val started = startListeningTarget(synced, target)
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
            controller.intent = expireListeningTarget(controller.intent, flight, latestState)
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
    val controller = LocalListeningIntentController.current
        ?: rememberListeningIntentController(state, onModeChange, onStrengthChange)
    val ui = listeningPresentation(state, intent = controller.intent)
    var strengthPreview by remember(controller) { mutableStateOf<Float?>(null) }
    GlasmioSegmentedControl(
        items = Controls.listeningChoices.map { it.value to it.label },
        selected = ui.mode,
        onSelected = { requested ->
            if (ui.canSelectMode(requested) && requested != ui.mode) {
                strengthPreview = null
                controller.mode(requested)
            }
        },
        adaptiveItem = 4,
        adaptiveValue = adaptiveStrengthFraction(ui.strength),
        onAdaptiveValueChange = { strengthPreview = it.takeIf { value -> value.isFinite() } },
        onAdaptiveValueChangeFinished = {
            val target = strengthPreview?.let { adaptiveStrengthForWrite(it, ui.adaptiveDiscrete) }
            strengthPreview = null
            if (ui.strengthEnabled && target != null && target != ui.strength) controller.strength(target)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = ui.modeEnabled,
        adaptiveEnabled = ui.strengthEnabled,
        adaptiveDiscrete = ui.adaptiveDiscrete,
        deferSelection = false,
        hiddenItems = ui.hiddenModes,
        showLabels = true,
        captionOverride = ui.modeNotice.ifBlank { if (ui.showAdaptive) ui.strengthNotice else "" }.takeIf { it.isNotBlank() },
        adaptiveLabels = Controls.adaptiveStrengthAnchors.map(::adaptiveStrengthLabel),
        itemContent = { mode, _ -> ListeningGlyph(mode, GlasmioTheme.colors.content) },
    )
}

@Composable
internal fun AdaptiveStrengthControl(
    state: PodsState,
    onStrengthChange: (Int) -> Unit = { PodsService.setControl(0x2e, it) },
) {
    val controller = LocalListeningIntentController.current ?: rememberListeningIntentController(
        state, { PodsService.setControl(0x0d, it) }, onStrengthChange,
    )
    val ui = listeningPresentation(state, intent = controller.intent)
    var strengthPreview by remember(controller) { mutableStateOf<Float?>(null) }
    GlasmioAdaptiveSlider(
        value = adaptiveStrengthFraction(ui.strength),
        onValueChange = { strengthPreview = it.takeIf { value -> value.isFinite() } },
        onValueChangeFinished = {
            val target = strengthPreview?.let { adaptiveStrengthForWrite(it, ui.adaptiveDiscrete) }
            strengthPreview = null
            if (ui.strengthEnabled && target != null && target != ui.strength) controller.strength(target)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = ui.strengthEnabled,
        discrete = ui.adaptiveDiscrete,
        deferSelection = false,
        showLabels = true,
        labels = Controls.adaptiveStrengthAnchors.map(::adaptiveStrengthLabel),
        captionOverride = ui.strengthNotice.takeIf { it.isNotBlank() },
    )
}
