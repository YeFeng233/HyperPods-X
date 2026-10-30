// Adapted from NEORUAA/HyperPods-X PR #1 (ae9fa5e), GPL-3.0-or-later.
package com.hyper.podsrx.ui.ios

import com.hyper.podsrx.R
import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.ui.SettingsCatalog
import kotlin.math.roundToInt

internal data class ListeningPresentation(
    val mode: Int?, val modeEnabled: Boolean, val showAdaptive: Boolean,
    val strength: Int?, val strengthEnabled: Boolean, val pending: Boolean,
    val hiddenModes: Set<Int>, val modeNotice: String, val strengthNotice: String,
) {
    fun canSelectMode(requested: Int): Boolean = modeEnabled &&
        requested !in hiddenModes && IosListeningChoices.any { it.value == requested }
}

internal fun listeningPresentation(
    state: PodsState,
    strings: Strings,
    intent: ListeningIntentState? = null,
): ListeningPresentation {
    val mode = intent?.mode?.value ?: state.controls[0x0d]?.firstOrNull()?.takeIf { it in 1..4 }
        ?: if (intent?.strength != null) 4 else null
    val strength = intent?.strength?.value ?: state.adaptiveTarget?.takeIf {
        it in 0..100 && (intent == null || intent.strengthFailure == 0)
    }
        ?: Controls.adaptiveStrength(state.controls)
    val pending = 0x0d in state.pending || 0x2e in state.pending
    val interactive = state.connected || pending || intent?.hasWork == true
    val modeFailed = intent == null && !pending && state.messageCode == R.string.setting_unconfirmed && state.messageControl == 0x0d
    val strengthFailed = intent == null && !pending && state.messageCode == R.string.setting_unconfirmed && state.messageControl == 0x2e
    val commonNotice = when {
        !interactive -> strings(R.string.connect_to_change_listening)
        mode == null -> strings(R.string.waiting_listening_modes)
        else -> ""
    }
    return ListeningPresentation(
        mode = mode,
        modeEnabled = interactive && mode != null,
        showAdaptive = mode == 4,
        strength = strength,
        strengthEnabled = interactive && mode == 4 && strength != null,
        pending = pending,
        hiddenModes = if (mode == 4 || state.controls[0x34]?.firstOrNull() == 2) setOf(1) else emptySet(),
        modeNotice = when {
            intent != null && intent.modeFailure != 0 -> strings(intent.modeFailure)
            commonNotice.isNotBlank() -> commonNotice
            modeFailed -> strings(R.string.listening_update_failed)
            else -> ""
        },
        strengthNotice = when {
            intent != null && intent.strengthFailure != 0 -> strings(intent.strengthFailure)
            commonNotice.isNotBlank() -> commonNotice
            strengthFailed -> strings(R.string.adaptive_update_failed)
            mode != 4 -> strings(R.string.adaptive_mode_required)
            strength == null -> strings(R.string.adaptive_strength_not_reported)
            else -> ""
        },
    )
}

internal fun adaptiveStrengthLabel(value: Int, strings: Strings): String = when (value) {
    0 -> strings(R.string.more_noise)
    50 -> strings(R.string.default_option)
    100 -> strings(R.string.less_noise)
    else -> value.toString()
}

internal fun adaptiveStrengthFraction(value: Int?): Float? =
    value?.takeIf { it in 0..100 }?.div(100f)

internal fun adaptiveStrengthForWrite(fraction: Float): Int? =
    fraction.takeIf { it.isFinite() }?.let { (it.coerceIn(0f, 1f) * 100).roundToInt() }

internal data class ListeningTarget(val control: Int, val value: Int, val serial: Long)
internal data class ListeningFlight(
    val target: ListeningTarget,
    val sawPending: Boolean = false,
    val initialMessage: String = "",
)
internal data class ListeningIntentState(
    val serial: Long = 0,
    val mode: ListeningTarget? = null,
    val strength: ListeningTarget? = null,
    val inFlight: ListeningFlight? = null,
    val modeFailure: Int = 0,
    val strengthFailure: Int = 0,
) {
    val hasWork get() = mode != null || strength != null || inFlight != null
}

internal fun requestListeningMode(intent: ListeningIntentState, mode: Int): ListeningIntentState {
    if (IosListeningChoices.none { it.value == mode }) return intent
    val serial = intent.serial + 1
    return intent.copy(
        serial = serial,
        mode = ListeningTarget(0x0d, mode, serial),
        strength = if (mode == 4) ListeningTarget(0x2e, 50, serial) else null,
        modeFailure = 0, strengthFailure = 0,
    )
}

internal fun requestListeningStrength(intent: ListeningIntentState, value: Int): ListeningIntentState {
    if (value !in 0..100 || intent.strength?.value == value) return intent
    val serial = intent.serial + 1
    return intent.copy(
        serial = serial,
        mode = intent.mode?.let { if (it.value == 4) it.copy(serial = serial) else it },
        strength = ListeningTarget(0x2e, value, serial),
        modeFailure = 0, strengthFailure = 0,
    )
}

private fun listeningWriteFailed(state: PodsState, control: Int): Boolean = when (state.messageCode) {
    R.string.control_not_connected, R.string.connect_earbuds_first, R.string.select_adaptive_before_adjusting,
    R.string.setting_not_writable, R.string.wait_adaptive_readback,
    R.string.wait_strength_confirmation, R.string.wait_noise_command -> true
    R.string.setting_send_failed, R.string.setting_unconfirmed -> state.messageControl == control
    else -> false
}

private fun finishListeningFlight(intent: ListeningIntentState, failed: Boolean): ListeningIntentState {
    val target = intent.inFlight?.target ?: return intent
    return when {
        target.control == 0x0d && intent.mode == target -> intent.copy(
            mode = null,
            strength = if (failed && intent.strength?.serial == target.serial) null else intent.strength,
            inFlight = null,
            modeFailure = if (failed) R.string.listening_update_failed else 0,
        )
        target.control == 0x2e && intent.strength == target -> intent.copy(
            strength = null, inFlight = null,
            strengthFailure = if (failed) R.string.adaptive_update_failed else 0,
        )
        else -> intent.copy(inFlight = null)
    }
}

internal fun syncListeningIntent(intent: ListeningIntentState, state: PodsState): ListeningIntentState {
    val busy = 0x0d in state.pending || 0x2e in state.pending
    if (!state.connected && !busy && intent.hasWork) return intent.copy(
        mode = null, strength = null, inFlight = null,
        modeFailure = if (intent.mode != null) R.string.earbuds_disconnected_retry else intent.modeFailure,
        strengthFailure = if (intent.mode == null && intent.strength != null) R.string.earbuds_disconnected_retry else intent.strengthFailure,
    )
    var next = intent
    intent.inFlight?.let { flight ->
        val pending = flight.target.control in state.pending
        val updated = flight.copy(sawPending = flight.sawPending || pending)
        next = next.copy(inFlight = updated)
        if (!pending) {
            val actual = state.controls[flight.target.control]?.firstOrNull()
            val sent = flight.target.control == 0x2e && state.adaptiveTarget == flight.target.value
            val confirmed = actual == flight.target.value &&
                (flight.target.control != 0x2e || state.adaptiveTarget == null)
            when {
                sent || confirmed -> next = finishListeningFlight(next, false)
                (updated.sawPending || state.message != flight.initialMessage) &&
                    listeningWriteFailed(state, flight.target.control) -> next = finishListeningFlight(next, true)
            }
        }
    }
    if (next.inFlight == null && !busy && state.connected) {
        if (next.mode?.value == state.controls[0x0d]?.firstOrNull()) next = next.copy(mode = null)
        if (state.controls[0x0d]?.firstOrNull() == 4 && next.strength?.let {
                !Controls.shouldSendAdaptive(it.value, Controls.adaptiveStrength(state.controls), state.adaptiveTarget)
            } == true) next = next.copy(strength = null)
    }
    return next
}

internal fun nextListeningTarget(intent: ListeningIntentState, state: PodsState): ListeningTarget? {
    if (intent.inFlight != null || !state.connected || 0x0d in state.pending) return null
    val actualMode = state.controls[0x0d]?.firstOrNull()?.takeIf { it in 1..4 } ?: return null
    intent.mode?.let { if (it.value != actualMode) return it.takeIf { 0x2e !in state.pending } }
    return intent.strength?.takeIf {
        Controls.canAdjustAdaptive(state.connected, state.controls, state.pending) &&
            Controls.shouldSendAdaptive(it.value, Controls.adaptiveStrength(state.controls), state.adaptiveTarget)
    }
}

internal fun startListeningTarget(
    intent: ListeningIntentState,
    target: ListeningTarget,
    initialMessage: String = "",
): ListeningIntentState {
    val desired = if (target.control == 0x0d) intent.mode else intent.strength
    return if (intent.inFlight == null && desired == target)
        intent.copy(inFlight = ListeningFlight(target, initialMessage = initialMessage)) else intent
}

internal fun expireListeningTarget(intent: ListeningIntentState, target: ListeningTarget, state: PodsState): ListeningIntentState =
    if (intent.inFlight?.target == target) {
        val synced = syncListeningIntent(intent, state)
        if (synced.inFlight?.target == target) finishListeningFlight(synced, true) else synced
    } else intent

internal val IosListeningChoices = SettingsCatalog.listeningChoices
internal val IosAdaptiveAnchors = SettingsCatalog.adaptiveAnchors
