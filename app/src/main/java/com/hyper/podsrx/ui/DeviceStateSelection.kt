package com.hyper.podsrx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.PodsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A setting observes its own value, without invalidating the theme or page shell. */
@Composable
internal fun <T> rememberDeviceValue(selector: (PodsState) -> T): State<T> {
    val values = remember(selector) { DeviceRepository.state.map(selector).distinctUntilChanged() }
    val initial = remember(selector) { selector(DeviceRepository.state.value) }
    return values.collectAsState(initial = initial)
}
