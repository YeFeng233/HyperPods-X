// Adapted from NEORUAA/HyperPods-X PR #1 (ae9fa5e), GPL-3.0-or-later.
package com.hyper.podsrx.ui.ios

import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.Controls
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
    intent: ListeningIntentState? = null,
): ListeningPresentation {
    val mode = intent?.mode?.value ?: state.controls[0x0d]?.firstOrNull()?.takeIf { it in 1..4 }
        ?: if (intent?.strength != null) 4 else null
    val strength = intent?.strength?.value ?: state.adaptiveTarget?.takeIf {
        it in 0..100 && intent?.strengthFailure.isNullOrBlank()
    }
        ?: Controls.adaptiveStrength(state.controls)
    val pending = 0x0d in state.pending || 0x2e in state.pending
    val interactive = state.connected || pending || intent?.hasWork == true
    val modeFailed = intent == null && !pending && state.message.startsWith("聆听模式未确认目标值")
    val strengthFailed = intent == null && !pending && state.message.startsWith("自适应降噪幅度未确认目标值")
    val commonNotice = when {
        !interactive -> "连接耳机后可切换聆听模式"
        mode == null -> "等待读取耳机支持的聆听模式"
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
            intent?.modeFailure?.isNotBlank() == true -> intent.modeFailure
            commonNotice.isNotBlank() -> commonNotice
            modeFailed -> "聆听模式未能更新，请重试。"
            else -> ""
        },
        strengthNotice = when {
            intent?.strengthFailure?.isNotBlank() == true -> intent.strengthFailure
            commonNotice.isNotBlank() -> commonNotice
            strengthFailed -> "自适应音频未能更新，请重试。"
            mode != 4 -> "使用自适应音频后可调整幅度"
            strength == null -> "耳机尚未上报自适应幅度，暂不可调整"
            else -> ""
        },
    )
}

internal fun adaptiveStrengthLabel(value: Int): String = when (value) {
    0 -> "较多噪声"
    50 -> "默认"
    100 -> "较少噪声"
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
    val modeFailure: String = "",
    val strengthFailure: String = "",
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
        modeFailure = "", strengthFailure = "",
    )
}

internal fun requestListeningStrength(intent: ListeningIntentState, value: Int): ListeningIntentState {
    if (value !in 0..100 || intent.strength?.value == value) return intent
    val serial = intent.serial + 1
    return intent.copy(
        serial = serial,
        mode = intent.mode?.let { if (it.value == 4) it.copy(serial = serial) else it },
        strength = ListeningTarget(0x2e, value, serial),
        modeFailure = "", strengthFailure = "",
    )
}

private fun listeningWriteFailed(state: PodsState, control: Int): Boolean = when (state.message) {
    "耳机控制尚未连接", "请先连接耳机", "请在自适应模式下调整降噪幅度",
    "当前设置或取值尚未开放写入", "请等待自适应模式与降噪幅度读取完成",
    "请等待降噪幅度确认后再切换模式", "请等待降噪指令发送完成后再切换模式" -> true
    else -> state.message.startsWith("设置发送失败：") || state.message.startsWith(
        if (control == 0x0d) "聆听模式未确认目标值" else "自适应降噪幅度未确认目标值",
    )
}

private fun finishListeningFlight(intent: ListeningIntentState, failed: Boolean): ListeningIntentState {
    val target = intent.inFlight?.target ?: return intent
    return when {
        target.control == 0x0d && intent.mode == target -> intent.copy(
            mode = null,
            strength = if (failed && intent.strength?.serial == target.serial) null else intent.strength,
            inFlight = null,
            modeFailure = if (failed) "聆听模式未能更新，请重试。" else "",
        )
        target.control == 0x2e && intent.strength == target -> intent.copy(
            strength = null, inFlight = null,
            strengthFailure = if (failed) "自适应音频未能更新，请重试。" else "",
        )
        else -> intent.copy(inFlight = null)
    }
}

internal fun syncListeningIntent(intent: ListeningIntentState, state: PodsState): ListeningIntentState {
    val busy = 0x0d in state.pending || 0x2e in state.pending
    if (!state.connected && !busy && intent.hasWork) return intent.copy(
        mode = null, strength = null, inFlight = null,
        modeFailure = if (intent.mode != null) "耳机已断开连接，请重新连接后重试。" else intent.modeFailure,
        strengthFailure = if (intent.mode == null && intent.strength != null) "耳机已断开连接，请重新连接后重试。" else intent.strengthFailure,
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
