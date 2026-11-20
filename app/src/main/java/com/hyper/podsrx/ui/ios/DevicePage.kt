package com.hyper.podsrx.ui.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.R
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.caseArtwork
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.ui.LocalListeningController
import com.hyper.podsrx.ui.batteryPresentation
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*

internal fun LazyListScope.iosDevicePage(
    state: PodsState,
    strings: Strings,
    navigate: (String) -> Unit,
    onConnect: () -> Unit,
) {
    item("product") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(state.headset.caseArtwork), null, Modifier.size(224.dp))
        }
    }
    item("batteries") {
        GlasmioGroupedList {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                listOf(4 to R.string.left, 2 to R.string.right, 8 to R.string.charging_case).forEach { (id, labelRes) ->
                    val battery = batteryPresentation(state.batteries[id], strings)
                    val label = strings(labelRes)
                    val symbol = when {
                        battery.charging -> Icons.Filled.BatteryChargingFull
                        battery.level == null -> Icons.Filled.Battery0Bar
                        battery.level >= 88 -> Icons.Filled.BatteryFull
                        battery.level >= 63 -> Icons.Filled.Battery5Bar
                        battery.level >= 38 -> Icons.Filled.Battery3Bar
                        battery.level >= 13 -> Icons.Filled.Battery1Bar
                        else -> Icons.Filled.Battery0Bar
                    }
                    val color = when {
                        battery.charging -> Color(0xff34c759)
                        battery.level != null && battery.level <= 20 -> Color(0xffd95151)
                        else -> GlasmioTheme.colors.secondaryContent
                    }
                    Column(
                        Modifier.weight(1f).clearAndSetSemantics {
                            contentDescription = listOf(label, battery.label, battery.note)
                                .filter(String::isNotBlank).joinToString(" · ")
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(symbol, null, modifier = Modifier.size(24.dp, 16.dp).rotate(90f), tint = color)
                        Text(battery.label, style = GlasmioTheme.textStyles.headline)
                        Text(label, style = GlasmioTheme.textStyles.footnote, color = GlasmioTheme.colors.secondaryContent)
                    }
                }
            }
        }
    }
    if (!state.running) iosGroup("connect") {
        GlasmioListRow(strings(R.string.connect_airpods), showTopSeparator = false, onClick = {
            navigate("connection")
            onConnect()
        })
    }
    iosGroup("deviceName") {
        GlasmioListRow(strings(R.string.name), detail = state.displayName, showTopSeparator = false,
            enabled = state.address.isNotBlank() && DeviceRepository.permissionsGranted(),
            onClick = { navigate("name") })
    }
    item("listening") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlasmioSectionTitle(strings(R.string.listening_mode))
            IosListeningControls(state, strings)
        }
    }
}

@Composable
private fun IosListeningControls(state: PodsState, strings: Strings) {
    val controller = requireNotNull(LocalListeningController.current)
    val ui = listeningPresentation(state, strings, controller.intent)
    GlasmioSegmentedControl(
        items = IosListeningChoices.map { it.value to strings(it.labelRes) },
        selected = ui.mode,
        onSelected = controller::mode,
        adaptiveItem = 4,
        adaptiveValue = adaptiveStrengthFraction(ui.strength),
        onAdaptiveValueChange = { adaptiveStrengthForWrite(it)?.let(controller::strength) },
        modifier = Modifier.fillMaxWidth(),
        enabled = ui.modeEnabled,
        adaptiveEnabled = ui.strengthEnabled,
        hiddenItems = ui.hiddenModes,
        showLabels = true,
        captionOverride = ui.modeNotice.ifBlank { if (ui.showAdaptive) ui.strengthNotice else "" }.takeIf(String::isNotBlank),
        adaptiveLabels = IosAdaptiveAnchors.map { adaptiveStrengthLabel(it, strings) },
        itemContent = { mode, _ -> ListeningIcon(mode) },
        glassTrack = true,
    )
}

@Composable
private fun ListeningIcon(mode: Int) {
    val drawable = when (mode) {
        2 -> R.drawable.listening_anc
        3 -> R.drawable.listening_transparency
        4 -> R.drawable.listening_adaptive
        else -> R.drawable.listening_off
    }
    val painter = painterResource(drawable)
    if (mode == 1) {
        val filter = if (GlasmioTheme.colors.isDark) InvertIconColors else null
        Image(painter, null, Modifier.size(28.dp), colorFilter = filter)
    } else Icon(painter, null, Modifier.size(28.dp), tint = GlasmioTheme.colors.content)
}

private val InvertIconColors = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)))

@Composable
internal fun IosAdaptiveStrength(state: PodsState, strings: Strings) {
    val controller = requireNotNull(LocalListeningController.current)
    val ui = listeningPresentation(state, strings, controller.intent)
    GlasmioAdaptiveSlider(
        value = adaptiveStrengthFraction(ui.strength),
        onValueChange = { adaptiveStrengthForWrite(it)?.let(controller::strength) },
        modifier = Modifier.fillMaxWidth(),
        enabled = ui.strengthEnabled,
        showLabels = true,
        labels = IosAdaptiveAnchors.map { adaptiveStrengthLabel(it, strings) },
        captionOverride = ui.strengthNotice.takeIf(String::isNotBlank),
    )
}
