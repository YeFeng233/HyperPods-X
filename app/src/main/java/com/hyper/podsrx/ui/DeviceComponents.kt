package com.hyper.podsrx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.R
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.preference.GlasmioSwitchPreference
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle

internal val ChargingGreen = Color(0xff34c759)

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    GlasmioGroupedList(content = content)
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        GlasmioSectionTitle(title, Modifier.semantics { heading() })
        SettingsCard(content)
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = GlasmioTheme.colors
    CompositionLocalProvider(LocalGlassColors provides colors.copy(accent = ChargingGreen)) {
        GlasmioSwitchPreference(title = { Text(title) }, description = subtitle.takeIf { it.isNotBlank() },
            checked = checked, onCheckedChange = onChange, isEnabled = enabled)
    }
}

@Composable
internal fun RowItem(title: String, subtitle: String = "", enabled: Boolean = true, value: String = "", action: () -> Unit) {
    GlasmioListRow(title = title, subtitle = subtitle.takeIf { it.isNotBlank() },
        detail = value.takeIf { it.isNotBlank() }, enabled = enabled, onClick = action)
}

@Composable
internal fun InfoRow(title: String, value: String) {
    GlasmioListRow(title = title, detail = value, showDisclosure = false)
}

@Composable
internal fun Action(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlasmioButton(onClick = onClick, modifier = modifier, size = GlasmioButtonSize.Large,
        emphasis = GlassEmphasis.Prominent) { Text(label) }
}

@Composable
internal fun BatteryGlyph(battery: BatteryPresentation) {
    val symbol = when {
        battery.charging -> "battery.100percent.bolt"
        battery.level == null -> "battery.0percent"
        battery.level >= 88 -> "battery.100percent"
        battery.level >= 63 -> "battery.75percent"
        battery.level >= 38 -> "battery.50percent"
        battery.level >= 13 -> "battery.25percent"
        else -> "battery.0percent"
    }
    val tint = when {
        battery.charging -> ChargingGreen
        battery.level != null && battery.level <= 20 -> Color(0xffd95151)
        else -> GlasmioTheme.colors.secondaryContent
    }
    SfIcon(symbol, null, modifier = Modifier.height(13.dp), size = 25.dp, tint = tint)
}

@Composable
internal fun ListeningGlyph(mode: Int, color: Color) {
    val resource = when (mode) {
        2 -> R.drawable.listening_anc
        3 -> R.drawable.listening_transparency
        4 -> R.drawable.listening_adaptive
        else -> R.drawable.listening_off
    }
    if (mode == 1) {
        // Off has a gray outer ring; a flat tint would erase its distinction from ANC.
        val filter = if (GlasmioTheme.colors.isDark) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))) else null
        Image(painterResource(resource), contentDescription = null, colorFilter = filter, modifier = Modifier.size(30.dp))
    } else Icon(painterResource(resource), contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
}
