package com.hyper.podsrx.ui

import com.hyper.podsrx.device.SpatialMode

internal fun spatialModeAt(fraction: Float, rtl: Boolean = false): SpatialMode? {
    if (!fraction.isFinite()) return null
    val position = if (rtl) 1f - fraction else fraction
    return SpatialMode.entries[(position.coerceIn(0f, 1f) * 3).toInt().coerceAtMost(2)]
}

internal fun SpatialPresentation.canSelect(mode: SpatialMode): Boolean = when (mode) {
    SpatialMode.OFF -> offEnabled
    SpatialMode.FIXED -> fixedEnabled
    SpatialMode.HEAD_TRACKING -> trackingEnabled
}
