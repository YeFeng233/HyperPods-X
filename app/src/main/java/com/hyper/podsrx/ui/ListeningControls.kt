package com.hyper.podsrx.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.R
import com.hyper.podsrx.device.PodsService
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.i18n.rememberStrings
import com.hyper.podsrx.ui.ios.listeningPresentation as intentPresentation
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ListeningControls(
    state: PodsState,
    onModeChange: (Int) -> Unit = { PodsService.setControl(0x0d, it) },
    onStrengthChange: (Int) -> Unit = { PodsService.setControl(0x2e, it) },
) {
    val strings = rememberStrings()
    val controller = LocalListeningController.current
        ?: rememberListeningController(state, onModeChange, onStrengthChange)
    val ui = intentPresentation(state, strings, controller.intent)
    val caption = ui.modeNotice.ifBlank { if (ui.showAdaptive) ui.strengthNotice else "" }
    val choices = SettingsCatalog.listeningChoices.filter { it.value !in ui.hiddenModes || it.value == ui.mode }
    val labels = choices.map { strings(if (it.value == 3) R.string.mode_transparency else it.labelRes) }
    val selected = choices.indexOfFirst { it.value == ui.mode }
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (selected >= 0) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxWidth()) {
                    // The glass samples the card surface behind it.
                    Spacer(Modifier.matchParentSize().layerBackdrop(backdrop)
                        .background(MiuixTheme.colorScheme.surfaceContainer))
                    GlassSegmentedTabRow(
                        tabs = labels,
                        selectedIndex = selected,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { index ->
                            val target = choices[index].value
                            if (target != ui.mode && ui.canSelectMode(target)) controller.mode(target)
                        },
                    )
                }
            }
            if (caption.isNotBlank()) ListeningNotice(caption)
            AnimatedVisibility(
                visible = ui.showAdaptive,
                enter = expandVertically(animationSpec = folmeSpring(damping = .9f, response = .38f)) + fadeIn(),
                exit = shrinkVertically(animationSpec = folmeSpring(damping = .9f, response = .38f)) + fadeOut(),
            ) {
                AdaptiveStrengthControl(state, showNotice = false, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
internal fun AdaptiveStrengthControl(state: PodsState, showNotice: Boolean = true, modifier: Modifier = Modifier) {
    val strings = rememberStrings()
    val controller = LocalListeningController.current ?: rememberListeningController(state,
        { PodsService.setControl(0x0d, it) }, { PodsService.setControl(0x2e, it) })
    val ui = intentPresentation(state, strings, controller.intent)
    val notice = ui.modeNotice.ifBlank { ui.strengthNotice }
    Column(modifier.fillMaxWidth()) {
        Slider(value = (ui.strength ?: 50).toFloat(), enabled = ui.strengthEnabled,
            valueRange = 0f..100f, onValueChange = { value ->
                if (ui.strengthEnabled) controller.strength(adaptiveStrengthAt(value / 100f))
            })
        if (notice.isBlank()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SettingsCatalog.adaptiveAnchors.forEach { anchor ->
                Text(com.hyper.podsrx.ui.ios.adaptiveStrengthLabel(anchor, strings), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }
        else if (showNotice) ListeningNotice(notice)
    }
}

@Composable
private fun ListeningNotice(message: String) {
    Text(message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 22.dp)
            .semantics { liveRegion = LiveRegionMode.Polite })
}
