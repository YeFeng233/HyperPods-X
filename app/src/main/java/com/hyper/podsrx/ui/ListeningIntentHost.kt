package com.hyper.podsrx.ui

import androidx.compose.runtime.*
import com.hyper.podsrx.device.PodsService
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.ui.ios.ListeningIntentState
import com.hyper.podsrx.ui.ios.expireListeningTarget
import com.hyper.podsrx.ui.ios.nextListeningTarget
import com.hyper.podsrx.ui.ios.requestListeningMode
import com.hyper.podsrx.ui.ios.requestListeningStrength
import com.hyper.podsrx.ui.ios.startListeningTarget
import com.hyper.podsrx.ui.ios.syncListeningIntent
import kotlinx.coroutines.delay

/** Shares the intent reducer and command queue across the earbud pages. */
internal class ListeningController {
    var intent by mutableStateOf(ListeningIntentState())
    fun mode(value: Int) { intent = requestListeningMode(intent, value) }
    fun strength(value: Int) { intent = requestListeningStrength(intent, value) }
}

internal val LocalListeningController = staticCompositionLocalOf<ListeningController?> { null }

@Composable
internal fun ListeningIntentHost(state: PodsState, content: @Composable () -> Unit) {
    val controller = rememberListeningController(state,
        { PodsService.setControl(0x0d, it) }, { PodsService.setControl(0x2e, it) })
    CompositionLocalProvider(LocalListeningController provides controller, content = content)
}

@Composable
internal fun rememberListeningController(
    state: PodsState,
    onModeChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
): ListeningController {
    val controller = remember(state.address, state.running) { ListeningController() }
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
