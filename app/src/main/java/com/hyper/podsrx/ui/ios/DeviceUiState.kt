// Adapted from NEORUAA/HyperPods-X PR #1 (ae9fa5e), GPL-3.0-or-later.
package com.hyper.podsrx.ui.ios

import com.hyper.podsrx.device.Reading
import com.hyper.podsrx.device.SpatialState
import com.hyper.podsrx.device.SpatialMode
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.Controls
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

internal data class BatteryPresentation(
    val label: String,
    val note: String,
    val level: Int?,
    val charging: Boolean
)

internal fun batteryPresentation(reading: Reading?): BatteryPresentation {
    val level = reading?.level?.takeIf { it in 0..100 }
    return BatteryPresentation(
        label = level?.let { "${if (reading.precise) "" else "≈"}$it%" } ?: "—",
        note = when {
            level == null -> "未读取"
            !reading.current -> "上次更新"
            reading.charging -> "充电中"
            else -> ""
        },
        level = level,
        charging = level != null && reading.current && reading.charging
    )
}

internal data class SpatialPresentation(
    val selected: SpatialMode?, val fixedEnabled: Boolean, val trackingEnabled: Boolean,
    val offEnabled: Boolean, val description: String
)

internal fun SpatialMode.label(): String = when (this) {
    SpatialMode.OFF -> "关闭"
    SpatialMode.FIXED -> "固定"
    SpatialMode.HEAD_TRACKING -> "头部追踪"
}

internal fun wearingDescription(state: PodsState): String = when {
    !state.connected -> "未连接耳机"
    state.wearing == null -> "等待耳机上报"
    state.wearing.inEarCount == 2 -> "双耳已佩戴"
    state.wearing.inEarCount == 1 -> "单耳已佩戴"
    else -> "未佩戴"
}

internal fun earDetectionDescription(state: PodsState): String = when {
    !state.earAutomation -> "自动入耳检测已关闭"
    !state.connected -> "请连接耳机；若收不到状态，请先暂停其他耳机管理应用"
    state.wearing == null -> "等待耳机上报佩戴状态"
    else -> "${wearingDescription(state)} · ${state.earDetectionStatus}"
}

internal fun SpatialState.spatialPresentation(connected: Boolean): SpatialPresentation {
    val idle = !stopping && !modeChanging && !calibrationMode
    val ready = connected && route && bridge && idle
    return SpatialPresentation(
        selected = mode,
        fixedEnabled = ready,
        trackingEnabled = ready && calibrated && !trackingStarting,
        offEnabled = idle && (ready || trackingRequested || nativeActive),
        description = when {
            modeChanging -> "正在切换为${requestedMode?.label() ?: "所选模式"}…"
            stopping -> "正在关闭…"
            trackingStarting -> "正在开启，请正视手机"
            nativeActive -> "已开启动态头部追踪，转头时保持声源方向"
            !connected || !route -> "连接并佩戴耳机后可用"
            !bridge -> "正在连接系统空间音频"
            calibrationMode -> "正在校准，完成或取消后可切换模式"
            !calibrated -> "关闭与固定模式可直接使用；头部追踪需先完成校准"
            mode == SpatialMode.FIXED -> "固定空间音频，声场不随头部动作调整"
            else -> "空间音频已关闭"
        }
    )
}

internal val IosListeningChoices = listOf(
    com.hyper.podsrx.device.Choice(1, "关闭"), com.hyper.podsrx.device.Choice(3, "通透模式"),
    com.hyper.podsrx.device.Choice(4, "自适应"), com.hyper.podsrx.device.Choice(2, "降噪"),
)
internal val IosAdaptiveAnchors = listOf(0, 50, 100)
