package com.hyper.podsrx.ui.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutBackground(backdrop: LayerBackdrop, wide: Boolean, alpha: () -> Float) {
    val surface = MiuixTheme.colorScheme.surface
    val dark = isSystemInDarkTheme()
    val device = if (wide) DeviceType.PAD else DeviceType.PHONE
    val preset = remember(device, dark) { BgEffectConfig.get(device, dark) }
    val painter = remember { BgEffectPainter() }
    val colorStage = remember(preset) { Animatable(0f) }
    val time = remember { mutableFloatStateOf(0f) }
    val visible by remember(alpha) { derivedStateOf { alpha() > 0f } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle, preset, visible) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                val offset = time.floatValue
                val start = withFrameNanos { it }
                var last = start
                while (isActive) {
                    val now = withFrameNanos { it }
                    if (now - last >= 1_000_000_000L / 60L) {
                        time.floatValue = offset + (now - start) / 1_000_000_000f
                        last = now
                    }
                }
            }
            var next = colorStage.value.toInt() + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong())
                colorStage.animateTo(next++, spring(dampingRatio = 0.9f, stiffness = 35f))
            }
        }
    }
    Spacer(Modifier.fillMaxSize().layerBackdrop(backdrop).drawBehind {
        drawRect(surface)
        val opacity = alpha()
        if (opacity > 0f) {
            val seconds = time.floatValue
            painter.updateResolution(size.width, size.height)
            painter.updateBoundIfNeeded(size.height * 0.8f, size.height, size.width)
            painter.updatePresetIfNeeded(device, dark)
            painter.updateColors(preset, colorStage.value)
            painter.updateAnimTime(seconds)
            painter.updatePointsAnim(seconds, preset)
            drawRect(painter.brush, alpha = opacity)
        }
    })
}
