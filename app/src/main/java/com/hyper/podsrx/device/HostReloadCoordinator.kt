package com.hyper.podsrx.device

import android.content.Context
import android.os.Bundle
import com.hyper.podsrx.hook.DiagnosticLog
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
    private const val TAG = "HyperPods-Reload"
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile var status: String = "等待模块服务"
        private set

    fun onServiceBound(service: XposedService) = schedule(service, emptySet())

    fun onScopeApproved(service: XposedService, approved: List<String>) =
        schedule(service, approved.intersect(HostReloadPolicy.hosts.toSet()))

    fun onServiceDied() { status = "模块服务已断开，等待重新连接" }

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
                        status = "无法确认宿主更新状态，请重新打开相关宿主"
                    }
                }
            }
        }
    }

    private suspend fun inspect(service: XposedService, justApproved: Set<String>) {
        if (service.apiVersion < XposedService.API_102) {
            status = "框架不支持热重载，请重启相关宿主"
            DiagnosticLog.i(TAG, status)
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
            status = "框架仍在更新宿主适配，请稍后重新打开卡片"
            DiagnosticLog.i(TAG, status)
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
                status = "无法保存更新记录，请重新打开相关宿主"
                return
            }
            status = "正在更新宿主适配"
            DiagnosticLog.i(TAG, "Requesting hot reload: process=$process state=${target.state}")
            val completion = CompletableDeferred<HotReloadResult>()
            try {
                service.hotReloadModule(target, Bundle().apply { putString("reason", "package_update") }) { _, result ->
                    DiagnosticLog.i(TAG, "Hot reload result: process=$process status=${result.status()}"
                        + (result.message()?.let { " detail=$it" } ?: ""))
                    completion.complete(result)
                }
                val result = withTimeoutOrNull(12_000) { completion.await() }
                if (result == null) {
                    DiagnosticLog.w(TAG, "Hot reload timed out: process=$process; no automatic resubmission")
                    break // The request may still be running; do not cascade into its dependents.
                }
                if (result.status() != HotReloadResult.Status.SUCCEEDED &&
                    result.status() != HotReloadResult.Status.PROCESS_DIED) break
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DiagnosticLog.w(TAG, "Hot reload request rejected: process=$process", error)
                break
            }
        }

        if (FrameworkBridge.service !== service) return
        targets = stableTargets(service, granted)
        if (FrameworkBridge.service !== service) return
        reportTargets(targets)
        val missing = justApproved.filter { host -> targets.none { HostReloadPolicy.hostFor(it.processName) == host } }
        status = when {
            targets.any { it.state == HookedTarget.State.RELOADING } -> "框架仍在更新宿主适配，请稍后重新打开卡片"
            targets.any { it.state == HookedTarget.State.FAILED || it.state == HookedTarget.State.STALE } ->
                "部分宿主未完成热重载；旧版或使用中的适配可能拒绝更新，请重启相关宿主"
            missing.isNotEmpty() -> "作用域已授权；首次启用请重启对应宿主，以加载模块"
            targets.isEmpty() -> "尚未发现已加载模块的宿主；首次启用请重启相关宿主"
            else -> "已运行宿主的模块代码已是最新"
        }
        DiagnosticLog.i(TAG, status)
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
