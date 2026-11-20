package com.hyper.podsrx.ui

import androidx.compose.runtime.*
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.PodsService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A setting observes its own value, without invalidating the theme or page shell. */
@Composable
internal fun <T> rememberDeviceValue(selector: (PodsState) -> T): State<T> {
    val values = remember(selector) { DeviceRepository.state.map(selector).distinctUntilChanged() }
    val initial = remember(selector) { selector(DeviceRepository.state.value) }
    return values.collectAsState(initial = initial)
}

/** Retain the reported controls while the service rereads them after a settings write. */
@Composable
internal fun rememberReportedControls(state: PodsState): PodsState {
    var controls by remember(state.address, state.running) { mutableStateOf<Map<Int, List<Int>>>(emptyMap()) }
    var equalizer by remember(state.address, state.running) { mutableStateOf<List<Int>?>(null) }
    SideEffect {
        if (state.running) {
            controls = controls + state.controls
            state.equalizer?.let { equalizer = it }
        }
    }
    if (!state.running || state.pending.isEmpty()) return state
    val visibleControls = controls + state.controls
    val waitingForEqualizer = state.equalizer == null && equalizer != null
    return state.copy(
        controls = visibleControls,
        equalizer = state.equalizer ?: equalizer,
        pending = state.pending + (visibleControls.keys - state.controls.keys) +
            if (waitingForEqualizer) setOf(PodsService.EQ_COMMAND) else emptySet(),
    )
}
