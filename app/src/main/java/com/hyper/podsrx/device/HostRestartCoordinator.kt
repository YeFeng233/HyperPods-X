package com.hyper.podsrx.device

import android.content.pm.PackageManager
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.DiagnosticLog
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HostRestartState(
    val running: Boolean = false,
    val message: UiText = text(R.string.restart_scope_description),
)

/** Root is requested only by this explicit UI action; never by startup or automatic reload. */
object HostRestartCoordinator {
    private val strings get() = DeviceRepository.app.stringResolver()
    private const val TAG = "HyperPods-Restart"
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean()
    private val mutable = MutableStateFlow(HostRestartState())
    val state = mutable.asStateFlow()

    fun restart() {
        if (!busy.compareAndSet(false, true)) return
        mutable.value = HostRestartState(true, text(R.string.waiting_root_authorization))
        worker.launch {
            var submitted = false
            try {
                val service = FrameworkBridge.service
                    ?: error(strings(R.string.enable_and_authorize_module))
                val hosts = HostRestartPolicy.hosts.filter { it.pkg in service.scope }
                check(hosts.isNotEmpty()) { strings(R.string.enable_system_integration_first) }
                RootCommandSession().use { root ->
                    root.authorize()
                    check(FrameworkBridge.service === service) { strings(R.string.module_disconnected_retry) }
                    // Resolve only the three known packages in this app's Android user.
                    val targets = hosts.filter { it.pkg in service.scope }.map { host ->
                        val info = DeviceRepository.app.packageManager.getPackageInfo(host.pkg,
                            PackageManager.PackageInfoFlags.of((PackageManager.GET_ACTIVITIES or
                                PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS).toLong()))
                        val uid = checkNotNull(info.applicationInfo).uid
                        check(uid / 100000 == android.os.Process.myUid() / 100000) { strings(R.string.cannot_restart_other_user) }
                        val names = (info.activities.orEmpty().map { it.processName } + info.services.orEmpty().map { it.processName } +
                            info.providers.orEmpty().map { it.processName } + info.receivers.orEmpty().map { it.processName } + host.pkg)
                            .filterNotNull().filter { HostRestartPolicy.acceptsName(host.pkg, it) }.toSet()
                        Target(host, uid, names)
                    }
                    check(targets.isNotEmpty()) { strings(R.string.scope_authorization_revoked) }
                    mutable.value = HostRestartState(true, text(R.string.restarting_authorized_scopes))
                    FrameworkBridge.publish()
                    val before = snapshot(root)
                    val previous = before.filter { process -> targets.any { HostRestartPolicy.matches(process, it.host.pkg, it.uid, it.names) } }
                    DiagnosticLog.i(TAG, "Manual restart: hosts=${targets.joinToString { it.host.pkg }} processes=${previous.size}")
                    submitted = true
                    for (process in previous) {
                        val result = root.execute(HostRestartPolicy.stopCommand(process))
                        check(result.code == 0 && result.output.none { it.startsWith("FAILED:") }) { strings(R.string.scope_stop_failed) }
                    }
                    var after = snapshot(root)
                    repeat(6) {
                        if (previous.any { it in after }) { delay(400); after = snapshot(root) }
                    }
                    check(previous.none { it in after }) { strings(R.string.old_processes_still_running) }
                    for (target in targets) {
                        val component = target.host.service ?: continue
                        if (after.none { it.uid == target.uid && it.name == target.host.pkg }) {
                            // Root still obeys background-service restrictions on this ROM.
                            // Grant only a short wakeup window; do not change battery settings.
                            val wake = root.execute("cmd deviceidle tempwhitelist -u ${target.uid / 100000} -d 10000 ${target.host.pkg}")
                            DiagnosticLog.i(TAG, "Wake host: ${target.host.pkg} exit=${wake.code}")
                            val result = root.execute("am start-service --user ${target.uid / 100000} -n ${target.host.pkg}/${component}")
                            DiagnosticLog.i(TAG, "Start host: ${target.host.pkg} exit=${result.code}")
                            if (result.code != 0) DiagnosticLog.w(TAG, result.output.joinToString("\n").take(2000))
                        }
                    }
                    delay(1500)
                    after = snapshot(root)
                    repeat(6) {
                        if (targets.any { target -> target.host.service != null &&
                                after.none { it.uid == target.uid && it.name == target.host.pkg } }) {
                            delay(500)
                            after = snapshot(root)
                        }
                    }
                    val waiting = targets.filter { target -> target.host.service != null &&
                        after.none { it.uid == target.uid && it.name == target.host.pkg } }.map { it.host.pkg }
                    FrameworkBridge.publish()
                    PodsService.spatialRefresh()
                    HostReloadCoordinator.onServiceBound(service)
                    val stopped = if (previous.isEmpty()) text(R.string.no_old_scope_processes) else text(R.string.old_scope_processes_stopped)
                    finish(when {
                        waiting.isNotEmpty() -> text(R.string.restart_hosts_waiting, stopped, waiting.joinToString(", "))
                        targets.any { it.host.service != null } -> text(R.string.restart_background_restored, stopped)
                        else -> text(R.string.restart_reopen_system, stopped)
                    })
                }
            } catch (error: Exception) {
                DiagnosticLog.w(TAG, "Manual restart ${if (submitted) "incomplete" else "not submitted"}: ${error.javaClass.simpleName}")
                val detail = when {
                    error is TimeoutException && !submitted -> text(R.string.root_authorization_timeout)
                    error is TimeoutException -> text(R.string.restart_timeout)
                    !submitted && (error is java.io.IOException || error.message?.contains("Root") == true) ->
                        text(R.string.root_not_granted)
                    else -> text(error.message ?: strings(R.string.restart_incomplete))
                }
                finish(detail)
            } finally {
                mutable.value = mutable.value.copy(running = false)
                busy.set(false)
            }
        }
    }

    private data class Target(val host: HostRestartPolicy.Host, val uid: Int, val names: Set<String>)
    private fun snapshot(root: RootCommandSession): List<HostRestartPolicy.Process> {
        val reply = root.execute("ps -A -o PID,UID,NAME")
        check(reply.code == 0) { strings(R.string.host_process_read_failed) }
        val processes = reply.output.mapNotNull(HostRestartPolicy::parse)
        // A successful ps always includes at least itself. An unsupported format
        // must not turn an empty parse into a false "all old processes exited".
        check(processes.isNotEmpty()) { strings(R.string.host_process_parse_failed) }
        return processes
    }
    private fun finish(message: UiText) {
        mutable.value = HostRestartState(true, message)
        DiagnosticLog.i(TAG, message.resolve(strings))
        DeviceRepository.message(message)
    }
}
