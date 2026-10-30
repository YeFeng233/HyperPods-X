package com.hyper.podsrx.ui

import com.hyper.podsrx.R
import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.SpatialMode
import com.hyper.podsrx.device.SpatialState
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.protocol.Reading
import kotlin.math.roundToInt

internal data class ListeningPresentation(
    val mode: Int?, val modeEnabled: Boolean, val showAdaptive: Boolean,
    val strength: Int?, val strengthEnabled: Boolean, val pending: Boolean, val notice: String
)

internal fun listeningPresentation(state: PodsState, strings: Strings): ListeningPresentation {
    val mode = state.controls[0x0d]?.firstOrNull()?.takeIf { it in 1..4 }
    val pending = 0x0d in state.pending || 0x2e in state.pending
    return ListeningPresentation(
        mode = mode,
        modeEnabled = state.connected && mode != null && !pending,
        showAdaptive = mode == 4,
        strength = Controls.adaptiveStrength(state.controls),
        strengthEnabled = Controls.canAdjustAdaptive(state.connected, state.controls, state.pending),
        pending = pending,
        notice = when {
            0x0d in state.pending -> strings(R.string.waiting_confirmation)
            0x2e in state.pending -> strings(R.string.sending_noise_strength)
            !state.connected -> strings(R.string.connect_to_change_listening)
            mode == null -> strings(R.string.waiting_listening_modes)
            mode == 4 && Controls.adaptiveStrength(state.controls) == null -> strings(R.string.adaptive_strength_not_reported)
            else -> ""
        }
    )
}

// LibrePods' integrated selector maps thumb travel directly to the 0..100 wire value.
internal fun adaptiveStrengthAt(fraction: Float): Int {
    require(fraction.isFinite())
    return (fraction.coerceIn(0f, 1f) * 100).roundToInt()
}

internal fun adaptiveStrengthLabel(value: Int, strings: Strings): String = when (value) {
    0 -> strings(R.string.strength_transparency)
    50 -> strings(R.string.strength_balanced)
    100 -> strings(R.string.strength_cancellation)
    else -> "${value} / 100"
}

// From here on both UI styles render the same derivations. The listening controls above differ by
// design: the iOS style keeps its own intent model in ui/ios/DeviceUiState.kt.

internal data class BatteryPresentation(
    val label: String,
    val note: String,
    val level: Int?,
    val charging: Boolean
)

internal fun batteryPresentation(reading: Reading?, strings: Strings): BatteryPresentation {
    val level = reading?.level?.takeIf { it in 0..100 }
    return BatteryPresentation(
        label = level?.let { "${if (reading.precise) "" else "≈"}${it}%" } ?: "—",
        note = when {
            level == null -> strings(R.string.not_read)
            reading.charging -> strings(R.string.charging)
            else -> ""
        },
        level = level,
        charging = level != null && reading.charging
    )
}

internal data class SpatialPresentation(
    val selected: SpatialMode?, val fixedEnabled: Boolean, val trackingEnabled: Boolean,
    val offEnabled: Boolean, val description: String
)

internal fun SpatialMode.label(strings: Strings): String = when (this) {
    SpatialMode.OFF -> strings(R.string.mode_off)
    SpatialMode.FIXED -> strings(R.string.spatial_fixed)
    SpatialMode.HEAD_TRACKING -> strings(R.string.spatial_head_tracking)
}

/** One process's hook results: all installed, or each failing component with its exception. */
internal fun capabilitySummary(results: Map<String, String>, strings: Strings): String {
    val failed = results.filterValues { it.isNotEmpty() }
    return if (failed.isEmpty()) strings(R.string.capabilities_active, results.size)
    else failed.entries.joinToString(strings(R.string.diagnostic_separator)) { (component, error) -> strings(R.string.capability_failed, component, error) }
}

internal fun wearingDescription(state: PodsState, strings: Strings): String = when {
    !state.connected -> strings(R.string.earbuds_not_connected)
    state.wearing == null -> strings(R.string.waiting_earbud_report)
    state.wearing.inEarCount == 2 -> strings(R.string.wearing_both)
    state.wearing.inEarCount == 1 -> strings(R.string.wearing_one)
    else -> strings(R.string.wearing_none)
}

internal fun earDetectionDescription(state: PodsState, strings: Strings): String = when {
    !state.earAutomation -> strings(R.string.ear_detection_disabled)
    !state.connected -> strings(R.string.ear_detection_connection_help)
    state.wearing == null -> strings(R.string.waiting_wearing_report)
    else -> "${wearingDescription(state, strings)} · ${state.earDetectionStatus.resolve(strings)}"
}

internal fun SpatialState.spatialPresentation(connected: Boolean, strings: Strings): SpatialPresentation {
    val idle = !stopping && !modeChanging && !calibrationMode
    val ready = connected && route && bridge && idle
    return SpatialPresentation(
        selected = mode,
        fixedEnabled = ready,
        trackingEnabled = ready && calibrated && !trackingStarting,
        offEnabled = idle && (ready || trackingRequested || nativeActive),
        description = when {
            modeChanging -> strings(R.string.switching_to_mode, requestedMode?.label(strings) ?: strings(R.string.selected_mode))
            stopping -> strings(R.string.turning_off)
            trackingStarting -> strings(R.string.tracking_starting_face_phone)
            nativeActive -> strings(R.string.tracking_active_description)
            !connected || !route -> strings(R.string.spatial_connect_and_wear)
            !bridge -> strings(R.string.connecting_system_spatial)
            calibrationMode -> strings(R.string.calibration_blocks_modes)
            !calibrated -> strings(R.string.spatial_calibration_required)
            mode == SpatialMode.FIXED -> strings(R.string.spatial_fixed_description)
            else -> strings(R.string.spatial_off_description)
        }
    )
}
