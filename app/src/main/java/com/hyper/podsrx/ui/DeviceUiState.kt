package com.hyper.podsrx.ui

import com.hyper.podsrx.device.Reading
import com.hyper.podsrx.device.SpatialState
import com.hyper.podsrx.device.SpatialMode
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.Controls
import kotlin.math.roundToInt

internal data class ListeningPresentation(
    val mode: Int?, val modeEnabled: Boolean, val showAdaptive: Boolean,
    val strength: Int?, val strengthEnabled: Boolean, val pending: Boolean, val notice: String
)

internal fun listeningPresentation(state: PodsState): ListeningPresentation {
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
            pending -> "正在等待耳机确认…"
            !state.connected -> "连接耳机后可切换聆听模式"
            mode == null -> "等待读取耳机支持的聆听模式"
            mode == 1 -> "耳机当前处于关闭模式，请选择一种聆听模式"
            mode == 4 && Controls.adaptiveStrength(state.controls) == null -> "耳机尚未上报自适应幅度，暂不可调整"
            else -> ""
        }
    )
}

// The integrated LibrePods selector increases raw ANC strength toward the right.
// Its separate "allowed noise" slider uses the opposite direction, not this one.
internal fun adaptiveStrengthAt(fraction: Float): Int {
    require(fraction.isFinite())
    // This headset confirms 0 / 50 / 100; intermediate writes were not accepted.
    return Controls.adaptiveStrengths[(fraction.coerceIn(0f, 1f) * 2).roundToInt()]
}

internal fun adaptiveStrengthLabel(value: Int): String = when (value) {
    0 -> "偏通透"
    50 -> "均衡"
    100 -> "偏降噪"
    else -> value.toString()
}

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
