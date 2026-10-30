package com.hyper.podsrx.device

import android.content.Context
import android.os.Bundle
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.DiagnosticLog
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Observe framework auto-reload first; submit at most one fallback per installation and process. */
object HostReloadCoordinator {
    private val strings get() = DeviceRepository.app.stringResolver()
    private const val TAG = "HyperPods-Reload"
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile var status: UiText = text(R.string.waiting_module_service)
        private set

    fun onServiceBound(service: XposedService) = schedule(service, emptySet())

    fun onScopeApproved(service: XposedService, approved: List<String>) =
        schedule(service, approved.intersect(HostReloadPolicy.hosts.toSet()))

    fun onServiceDied() { status = text(R.string.module_service_wait_reconnect) }

    private fun schedule(service: XposedService, justApproved: Set<String>) {
        worker.launch {
            lock.withLock {
                if (FrameworkBridge.service !== service) return@withLock
                try {
                    // Package replacement and autoHotReload run independently. Give the framework
                    // the first opportunity, then inspect its authoritative generation state.
                    delay(2_000)
                    if (FrameworkBridge.service === service) inspect(service, justApproved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DiagnosticLog.w(TAG, "Host reload inspection unavailable", error)
                    if (FrameworkBridge.service === service) {
                        status = text(R.string.host_update_unknown)
                    }
                }
            }
        }
    }

    private suspend fun inspect(service: XposedService, justApproved: Set<String>) {
        if (service.apiVersion < XposedService.API_102) {
            status = text(R.string.hot_reload_unsupported)
            DiagnosticLog.i(TAG, status.resolve(strings))
            return
        }
        val app = DeviceRepository.app
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val installation = HostReloadPolicy.installation(info.lastUpdateTime, info.longVersionCode)
        val preferences = app.getSharedPreferences("host_reload", Context.MODE_PRIVATE)
        val policy = HostReloadPolicy(installation, preferences.getString("installation", "") ?: "",
            preferences.getStringSet("attempted_processes", emptySet()) ?: emptySet())
        val granted = service.scope.toSet().intersect(HostReloadPolicy.hosts.toSet())
        var targets = stableTargets(service, granted)
        if (FrameworkBridge.service !== service) return
        reportTargets(targets)
        if (targets.any { it.state == HookedTarget.State.RELOADING }) {
            status = text(R.string.host_update_in_progress)
            DiagnosticLog.i(TAG, status.resolve(strings))
            return
        }

        // Snapshot handles are always obtained from the framework. Process names are only used
        // to order work and to impose a conservative attempt budget; never fabricate an identity.
        val processes = targets.map { it.processName }.distinct()
            .sortedWith(compareBy<String> { HostReloadPolicy.order(it) }.thenBy { it })
        for (process in processes) {
            if (FrameworkBridge.service !== service) return
            val target = service.runningTargets.firstOrNull {
                it.processName == process && HostReloadPolicy.hostFor(it.processName)?.let(granted::contains) == true
            } ?: continue
            if (target.state == HookedTarget.State.RELOADING || target.state == HookedTarget.State.FAILED) break
            if (target.state != HookedTarget.State.STALE) continue
            if (!policy.claim(process, target.state.name, granted)) break
            // Persist before submission: restarting the module app must not reset the attempt
            // budget and repeatedly reload a host whose callback failed or never replied.
            if (!preferences.edit().putString("installation", installation)
                    .putStringSet("attempted_processes", policy.attemptedProcesses()).commit()) {
                DiagnosticLog.w(TAG, "Reload skipped: could not persist attempt budget")
                status = text(R.string.host_update_record_failed)
                return
            }
            status = text(R.string.updating_host_integration)
            DiagnosticLog.i(TAG, "Requesting hot reload: process=${process} state=${target.state}")
            val completion = CompletableDeferred<HotReloadResult>()
            try {
                service.hotReloadModule(target, Bundle().apply { putString("reason", "package_update") }) { _, result ->
                    DiagnosticLog.i(TAG, "Hot reload result: process=${process} status=${result.status()}"
                        + (result.message()?.let { " detail=${it}" } ?: ""))
                    completion.complete(result)
                }
                val result = withTimeoutOrNull(12_000) { completion.await() }
                if (result == null) {
                    DiagnosticLog.w(TAG, "Hot reload timed out: process=${process}; no automatic resubmission")
                    break // The request may still be running; do not cascade into its dependents.
                }
                if (result.status() != HotReloadResult.Status.SUCCEEDED &&
                    result.status() != HotReloadResult.Status.PROCESS_DIED) break
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DiagnosticLog.w(TAG, "Hot reload request rejected: process=${process}", error)
                break
            }
        }

        if (FrameworkBridge.service !== service) return
        targets = stableTargets(service, granted)
        if (FrameworkBridge.service !== service) return
        reportTargets(targets)
        val missing = justApproved.filter { host -> targets.none { HostReloadPolicy.hostFor(it.processName) == host } }
        status = when {
            targets.any { it.state == HookedTarget.State.RELOADING } -> text(R.string.host_update_in_progress)
            targets.any { it.state == HookedTarget.State.FAILED || it.state == HookedTarget.State.STALE } ->
                text(R.string.hot_reload_incomplete)
            missing.isNotEmpty() -> text(R.string.scope_first_restart)
            targets.isEmpty() -> text(R.string.no_loaded_hosts)
            else -> text(R.string.host_module_current)
        }
        DiagnosticLog.i(TAG, status.resolve(strings))
        if (missing.isNotEmpty() || targets.any { it.state == HookedTarget.State.FAILED || it.state == HookedTarget.State.STALE }) {
            DeviceRepository.message(status)
        }
    }

    /** Bounded observation; never call reload on a target already being reloaded. */
    private suspend fun stableTargets(service: XposedService, granted: Set<String>): List<HookedTarget> {
        var targets = emptyList<HookedTarget>()
        repeat(6) { attempt ->
            if (FrameworkBridge.service !== service) return emptyList()
            targets = service.runningTargets.filter { HostReloadPolicy.hostFor(it.processName)?.let(granted::contains) == true }
            if (targets.none { it.state == HookedTarget.State.RELOADING } || attempt == 5) return targets
            delay(1_000)
        }
        return targets
    }

    private fun reportTargets(targets: List<HookedTarget>) {
        targets.forEach {
            DiagnosticLog.i(TAG, "Host generation: process=${it.processName} state=${it.state} loadedVersion=${it.loadedVersionCode}")
        }
    }
}
