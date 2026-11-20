package com.hyper.podsrx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Status colors belong to the native renderer, not the shared settings catalog. */
internal object MiuixStatusColors {
    val charging = Color(0xff55b94c)
    val lowBattery = Color(0xffe34d59)
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
internal fun BatteryGlyph(battery: BatteryPresentation) {
    val level = battery.level ?: 0
    val icon = with(MiuixIcons.Glass) {
        if (battery.charging) when {
            level <= 20 -> Battery20Charging
            level <= 50 -> Battery50Charging
            level <= 80 -> Battery80Charging
            else -> Battery1000Charging
        } else when {
            level <= 10 -> Battery10
            level <= 20 -> Battery20
            level <= 50 -> Battery50
            level <= 80 -> Battery80
            else -> Battery100
        }
    }
    val tint = when {
        battery.charging -> MiuixStatusColors.charging
        battery.level == null -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        level <= 20 -> MiuixStatusColors.lowBattery
        else -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
}
