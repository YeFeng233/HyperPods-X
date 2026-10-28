package com.hyper.podsrx.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.hyper.podsrx.ui.ios.IosSettingsScreen

internal val LocalChangeUiStyle = staticCompositionLocalOf<(Boolean) -> Unit> {
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
    CompositionLocalProvider(LocalChangeUiStyle provides { ios ->
        entryPage = "more"
        AppearancePreferences.setStyle(if (ios) UiStyle.IOS else UiStyle.MIUIX)
    }) {
        key(style.name) {
            if (style == UiStyle.IOS) {
                IosSettingsScreen(externalSettings, entryPage, onConnect, onOverlay, onSystemBluetooth, onFinish)
            } else {
                MiuixSettingsScreen(externalSettings, entryPage, onConnect, onOverlay, onSystemBluetooth, onFinish)
            }
        }
    }
}
