package com.hyper.podsrx.ui

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Status colors belong to the native renderer, not the shared settings catalog. */
internal object MiuixStatusColors {
    val charging = Color(0xff55b94c)
    val lowBattery = Color(0xffe34d59)

    /** Native day/night foregrounds with uniform opacity across unselected modes. */
    fun listeningIcon(mode: Int, selected: Boolean, dark: Boolean): Color {
        val foreground = if (selected || dark) Color.White else if (mode == 3) Color.Black else Color(0xff0a0a0a)
        return foreground.copy(alpha = if (selected) 1f else .6f)
    }
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), content = content)
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SwitchPreference(title = title, summary = subtitle.takeIf(String::isNotBlank),
        checked = checked, enabled = enabled, onCheckedChange = onChange)
}

@Composable
internal fun RowItem(title: String, subtitle: String = "", enabled: Boolean = true, value: String = "", action: () -> Unit) {
    ArrowPreference(title = title, summary = subtitle.takeIf(String::isNotBlank), enabled = enabled,
        onClick = action, endActions = {
            if (value.isNotBlank()) Text(value, fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantActions
                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp))
        })
}

@Composable
internal fun InfoRow(title: String, value: String) {
    BasicComponent(title = title, endActions = {
        Text(value, fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 220.dp))
    })
}

@Composable
internal fun Action(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(label, onClick, modifier, colors = ButtonDefaults.textButtonColorsPrimary())
}

@Composable
internal fun NavigationButton(label: String, back: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(horizontal = 8.dp)) {
        Icon(if (back) MiuixIcons.Back else MiuixIcons.More, label,
            tint = MiuixTheme.colorScheme.onBackground)
    }
}

@Composable
internal fun BatteryGlyph(battery: BatteryPresentation) {
    // The source font has ten native fill levels, followed by ten charging variants.
    val level = battery.level
    val step = ((level ?: 0).coerceIn(1, 100) - 1) / 10
    val codePoint = (if (battery.charging) 0xf03eb else 0xf03e1) + step
    val glyph = remember(codePoint) { String(Character.toChars(codePoint)) }
    val resources = LocalContext.current.resources
    val typeface = remember(resources) { resources.getFont(R.font.hyperos_battery_symbols) }
    val color = when {
        battery.charging -> MiuixStatusColors.charging
        level != null && level <= 20 -> MiuixStatusColors.lowBattery
        else -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
    Box(Modifier.size(26.dp, 16.dp).clearAndSetSemantics {}.drawWithCache {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            // This is an icon: its dp size is independent of adjacent text's font scaling.
            textSize = 13.dp.toPx()
            this.color = color.toArgb()
        }
        val bounds = Rect()
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        val x = (size.width - bounds.left - bounds.right) / 2f
        val baseline = (size.height - bounds.top - bounds.bottom) / 2f
        onDrawBehind {
            if (level != null) drawContext.canvas.nativeCanvas.drawText(glyph, x, baseline, paint)
        }
    })
}
