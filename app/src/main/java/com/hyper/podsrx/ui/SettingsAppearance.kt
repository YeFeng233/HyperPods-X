package com.hyper.podsrx.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.hyper.podsrx.ui.ios.IosSettingsScreen
import com.hyper.podsrx.device.ScopeSetupCoordinator

internal val LocalChangeUiStyle = staticCompositionLocalOf<(UiStyle) -> Unit> {
    error("Settings appearance host is required")
}

@Composable
fun HyperPodsScreen(
    externalSettings: Boolean,
    initialPage: String? = null,
    onConnect: () -> Unit,
    onOverlay: () -> Unit,
    onSystemBluetooth: () -> Unit,
    onFinish: () -> Unit,
) {
    val style by AppearancePreferences.style.collectAsState()
    var entryPage by rememberSaveable { mutableStateOf(initialPage) }
    val logExport = rememberLogExport()
    LaunchedEffect(Unit) { ScopeSetupCoordinator.prepare() }
    CompositionLocalProvider(LocalLogExport provides logExport, LocalChangeUiStyle provides { selected ->
        if (selected != style) {
            entryPage = "more"
            AppearancePreferences.setStyle(selected)
        }
    }) {
        key(style.name) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                if (style == UiStyle.IOS) {
                    IosSettingsScreen(externalSettings, entryPage, onConnect, onOverlay, onSystemBluetooth, onFinish)
                } else {
                    MiuixSettingsScreen(externalSettings, entryPage, onConnect, onOverlay, onSystemBluetooth, onFinish)
                }
                ScopeSetupSheet()
            }
        }
    }
}
