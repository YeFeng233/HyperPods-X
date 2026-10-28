package com.hyper.podsrx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyper.podsrx.device.SpatialMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

/** Only a completed gesture sends a command; selection otherwise follows confirmed state. */
@Composable
internal fun SpatialModeSlider(ui: SpatialPresentation, onSelect: (SpatialMode) -> Unit) {
    val latest by rememberUpdatedState(ui)
    val submit by rememberUpdatedState(onSelect)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var preview by remember { mutableStateOf<SpatialMode?>(null) }
    val selected = preview?.takeIf { ui.canSelect(it) } ?: ui.selected
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceSecondary
    val track = secondary.copy(alpha = .09f)
    Row(Modifier.fillMaxWidth().selectableGroup().drawBehind {
        drawRoundRect(track, size = Size(size.width, 68.dp.toPx()), cornerRadius = CornerRadius(34.dp.toPx()))
    }.pointerInput(rtl) {
        detectHorizontalDragGestures(
            onDragStart = { point -> preview = spatialModeAt(point.x / size.width, rtl)?.takeIf { latest.canSelect(it) } },
            onDragCancel = { preview = null },
            onDragEnd = {
                val target = preview
                preview = null
                if (target != null && latest.canSelect(target) && target != latest.selected) submit(target)
            },
        ) { change, _ ->
            change.consume()
            preview = spatialModeAt(change.position.x / size.width, rtl)?.takeIf { latest.canSelect(it) }
        }
    }) {
        SpatialMode.entries.forEach { option ->
            val active = selected == option
            val enabled = ui.canSelect(option)
            Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                .selectable(ui.selected == option, enabled, Role.RadioButton) {
                    if (latest.canSelect(option) && option != latest.selected) submit(option)
                }.alpha(if (enabled || active) 1f else .45f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp).then(if (active) Modifier.border(1.5.dp, primary, CircleShape) else Modifier)
                        .padding(4.dp).clip(CircleShape).background(if (active) primary else Color.Transparent),
                        contentAlignment = Alignment.Center) { SpatialGlyph(option, if (active) Color.White else secondary) }
                }
                Text(option.label(), fontSize = 15.sp, color = if (active) primary else MiuixTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
            }
        }
    }
}

@Composable
private fun SpatialGlyph(mode: SpatialMode, color: Color) {
    Canvas(Modifier.size(30.dp)) {
        scale(size.width / 32f, size.height / 32f, pivot = Offset.Zero) {
            if (mode == SpatialMode.OFF) {
                drawArc(color, -50f, 280f, false, Offset(5f, 5f), Size(22f, 22f), style = Stroke(2.2f, cap = StrokeCap.Round))
                drawLine(color, Offset(16f, 2f), Offset(16f, 15f), 2.2f, StrokeCap.Round)
            } else {
                drawCircle(color, 5f, Offset(16f, 11f))
                drawRoundRect(color, Offset(8f, 20f), Size(16f, 9f), CornerRadius(4f))
                if (mode == SpatialMode.FIXED) {
                    for (x in listOf(2f, 30f)) drawLine(color, Offset(x, 8f), Offset(x, 21f), 2f, StrokeCap.Round)
                } else {
                    drawArc(color, -160f, 140f, false, Offset(1f, 1f), Size(30f, 24f), style = Stroke(1.8f, cap = StrokeCap.Round))
                    drawLine(color, Offset(30f, 8f), Offset(25f, 6f), 1.8f, StrokeCap.Round)
                    drawLine(color, Offset(30f, 8f), Offset(30f, 3f), 1.8f, StrokeCap.Round)
                }
            }
        }
    }
}
