package com.hyper.podsrx.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyper.podsrx.R
import com.hyper.podsrx.device.FrameworkBridge
import com.hyper.podsrx.device.HostRestartCoordinator
import com.hyper.podsrx.device.RootAccessState
import com.hyper.podsrx.device.ScopeSetupCoordinator
import com.hyper.podsrx.i18n.rememberStrings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

private data class ScopeEntry(val packageName: String, val label: String)

@Composable
internal fun ScopeSetupSheet() {
    val setup by ScopeSetupCoordinator.state.collectAsStateWithLifecycle()
    val restart by HostRestartCoordinator.state.collectAsStateWithLifecycle()
    val strings = rememberStrings()
    if (!setup.visible) return
    val theme = remember { ThemeController(ColorSchemeMode.System) }
    val scopes = remember(strings) {
        listOf(
            ScopeEntry("com.android.settings", strings(R.string.scope_system_settings)),
            ScopeEntry("com.xiaomi.bluetooth", strings(R.string.scope_xiaomi_bluetooth)),
            ScopeEntry("com.milink.service", strings(R.string.scope_device_interconnect)),
            ScopeEntry("com.miui.securitycenter", strings(R.string.scope_security_center)),
        )
    }
    val authorized = FrameworkBridge.service?.scope?.toSet().orEmpty()
    var selected by remember(setup.installation, authorized) { mutableStateOf(authorized) }
    MiuixTheme(controller = theme) {
        WindowDialog(
            show = setup.visible,
            title = strings(R.string.scope_setup_title),
            onDismissRequest = ScopeSetupCoordinator::dismiss,
        ) {
            Column(Modifier.fillMaxWidth()) {
                scopes.forEach { entry ->
                    val checked = entry.packageName in selected
                    BasicComponent(
                        title = entry.label,
                        role = Role.Checkbox,
                        onClick = { selected = if (checked) selected - entry.packageName else selected + entry.packageName },
                        modifier = Modifier.semantics { this.selected = checked },
                        endActions = {
                            Checkbox(
                                if (checked) androidx.compose.ui.state.ToggleableState.On
                                else androidx.compose.ui.state.ToggleableState.Off,
                                onClick = { selected = if (checked) selected - entry.packageName else selected + entry.packageName },
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        },
                    )
                }
                if (setup.root == RootAccessState.DENIED) {
                    Text(
                        strings(R.string.scope_root_required),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixStatusColors.lowBattery,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            .clickable(onClick = ScopeSetupCoordinator::checkRoot),
                        textAlign = TextAlign.End,
                    )
                }
                if (setup.restartRequested && !restart.running) {
                    Text(restart.message.resolve(strings), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = ScopeSetupCoordinator::dismiss,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(),
                    ) { Text(strings(R.string.scope_setup_later)) }
                    Button(
                        onClick = { ScopeSetupCoordinator.restartScopes(selected) },
                        enabled = setup.root == RootAccessState.GRANTED && selected.isNotEmpty() &&
                            !setup.checkingRestart && !restart.running,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(if (setup.checkingRestart || restart.running) strings(R.string.restarting_scopes)
                        else strings(R.string.scope_setup_restart))
                    }
                }
            }
        }
    }
}
