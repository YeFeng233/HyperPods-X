package com.hyper.podsrx.device

import android.content.Context
import android.content.pm.PackageManager
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RootAccessState { UNKNOWN, CHECKING, GRANTED, DENIED }

data class ScopeSetupState(
    val visible: Boolean = false,
    val installation: String = "",
    val root: RootAccessState = RootAccessState.UNKNOWN,
    val rootMessage: UiText = text(R.string.scope_root_checking),
    val checkingRestart: Boolean = false,
    val restartRequested: Boolean = false,
)

/** Shows one setup sheet for each package installation or update. */
object ScopeSetupCoordinator {
    private const val PREFS = "scope_setup"
    private const val INSTALLATION = "installation"
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepared = AtomicBoolean()
    private val rootCheckBusy = AtomicBoolean()
    private val mutable = MutableStateFlow(ScopeSetupState())
    val state = mutable.asStateFlow()

    fun prepare() {
        if (!prepared.compareAndSet(false, true)) return
        val app = DeviceRepository.app
        val key = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            HostReloadPolicy.installation(info.lastUpdateTime, info.longVersionCode)
        }.getOrNull() ?: return
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(INSTALLATION, "") == key) return
        mutable.value = ScopeSetupState(visible = true, installation = key,
            root = RootAccessState.CHECKING, rootMessage = text(R.string.scope_root_checking))
        checkRoot()
    }

    fun checkRoot() {
        val installation = mutable.value.installation
        if (installation.isBlank() || !rootCheckBusy.compareAndSet(false, true)) return
        mutable.update { it.copy(root = RootAccessState.CHECKING, rootMessage = text(R.string.scope_root_checking)) }
        worker.launch {
            try {
                val result = runCatching {
                    RootCommandSession().use { root -> root.authorize() }
                    true
                }.getOrDefault(false)
                mutable.update {
                    it.copy(root = if (result) RootAccessState.GRANTED else RootAccessState.DENIED,
                        rootMessage = text(if (result) R.string.scope_root_granted else R.string.scope_root_required))
                }
            } finally {
                rootCheckBusy.set(false)
            }
        }
    }

    fun restartScopes(selectedHosts: Set<String>) {
        if (mutable.value.root != RootAccessState.GRANTED) {
            checkRoot()
            return
        }
        mutable.update { it.copy(restartRequested = true, checkingRestart = true) }
        HostRestartCoordinator.restart(selectedHosts)
        worker.launch {
            // The restart coordinator owns root execution and reports its final message.
            while (HostRestartCoordinator.state.value.running) kotlinx.coroutines.delay(250)
            mutable.update { it.copy(checkingRestart = false) }
        }
    }

    fun dismiss() {
        val current = mutable.value
        if (current.installation.isNotBlank()) {
            DeviceRepository.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(INSTALLATION, current.installation).apply()
        }
        mutable.update { it.copy(visible = false, checkingRestart = false) }
    }
}
