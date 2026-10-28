package com.hyper.podsrx.device

import android.content.pm.PackageManager
import com.hyper.podsrx.hook.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class HostRestartState(
    val running: Boolean = false,
    val message: String = "重启系统设置、小米蓝牙和设备互联，需要 Root 权限",
)

/** Root is requested only by this explicit UI action; never by startup or automatic reload. */
object HostRestartCoordinator {
    private const val TAG = "HyperPods-Restart"
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean()
    private val mutable = MutableStateFlow(HostRestartState())
    val state = mutable.asStateFlow()

    fun restart() {
        if (!busy.compareAndSet(false, true)) return
        mutable.value = HostRestartState(true, "等待 Root 授权，请在权限弹窗中允许")
        worker.launch {
            var submitted = false
            try {
                val service = FrameworkBridge.service
                    ?: error("请先在 LSPosed 中启用模块并授权作用域")
                val hosts = HostRestartPolicy.hosts.filter { it.pkg in service.scope }
                check(hosts.isNotEmpty()) { "请先启用系统适配并授权作用域" }
                RootCommandSession().use { root ->
                    root.authorize()
                    check(FrameworkBridge.service === service) { "模块服务已断开，请重试" }
                    // Resolve only the three known packages in this app's Android user.
                    val targets = hosts.filter { it.pkg in service.scope }.map { host ->
                        val info = DeviceRepository.app.packageManager.getPackageInfo(host.pkg,
                            PackageManager.PackageInfoFlags.of((PackageManager.GET_ACTIVITIES or
                                PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS).toLong()))
                        val uid = checkNotNull(info.applicationInfo).uid
                        check(uid / 100000 == android.os.Process.myUid() / 100000) { "无法重启其他用户的作用域" }
                        val names = (info.activities.orEmpty().map { it.processName } + info.services.orEmpty().map { it.processName } +
                            info.providers.orEmpty().map { it.processName } + info.receivers.orEmpty().map { it.processName } + host.pkg)
                            .filterNotNull().filter { HostRestartPolicy.acceptsName(host.pkg, it) }.toSet()
                        Target(host, uid, names)
                    }
                    check(targets.isNotEmpty()) { "作用域已取消授权" }
                    mutable.value = HostRestartState(true, "正在重启已授权作用域，系统耳机界面会暂时关闭")
                    FrameworkBridge.publish()
                    val before = snapshot(root)
                    val previous = before.filter { process -> targets.any { HostRestartPolicy.matches(process, it.host.pkg, it.uid, it.names) } }
                    DiagnosticLog.i(TAG, "Manual restart: hosts=${targets.joinToString { it.host.pkg }} processes=${previous.size}")
                    submitted = true
                    for (process in previous) {
                        val result = root.execute(HostRestartPolicy.stopCommand(process))
                        check(result.code == 0 && result.output.none { it.startsWith("FAILED:") }) { "部分作用域进程无法结束" }
                    }
                    var after = snapshot(root)
                    repeat(6) {
                        if (previous.any { it in after }) { delay(400); after = snapshot(root) }
                    }
                    check(previous.none { it in after }) { "部分旧进程尚未退出，请稍后重试" }
                    for (target in targets) {
                        val component = target.host.service ?: continue
                        if (after.none { it.uid == target.uid && it.name == target.host.pkg }) {
                            // Root still obeys background-service restrictions on this ROM.
                            // Grant only a short wakeup window; do not change battery settings.
                            val wake = root.execute("cmd deviceidle tempwhitelist -u ${target.uid / 100000} -d 10000 ${target.host.pkg}")
                            DiagnosticLog.i(TAG, "Wake host: ${target.host.pkg} exit=${wake.code}")
                            val result = root.execute("am start-service --user ${target.uid / 100000} -n ${target.host.pkg}/$component")
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
                        after.none { it.uid == target.uid && it.name == target.host.pkg } }.map { it.host.label }
                    FrameworkBridge.publish()
                    PodsService.spatialRefresh()
                    HostReloadCoordinator.onServiceBound(service)
                    val stopped = if (previous.isEmpty()) "作用域当前没有旧进程" else "旧作用域进程已结束"
                    finish(when {
                        waiting.isNotEmpty() -> "$stopped；${waiting.joinToString("、")}尚未恢复，请手动打开后重试"
                        targets.any { it.host.service != null } -> "$stopped，后台进程已恢复；请重新打开系统页面"
                        else -> "$stopped；请重新打开系统页面以加载模块"
                    })
                }
            } catch (error: Exception) {
                DiagnosticLog.w(TAG, "Manual restart ${if (submitted) "incomplete" else "not submitted"}: ${error.javaClass.simpleName}")
                val detail = when {
                    error is TimeoutException && !submitted -> "Root 授权超时，未重启作用域"
                    error is TimeoutException -> "重启超时，部分宿主可能已退出，请重新打开系统页面"
                    !submitted && (error is java.io.IOException || error.message?.contains("Root") == true) ->
                        "未获得 Root 权限，未重启作用域；请在 Root 管理器中授权"
                    else -> error.message ?: "重启未完成，请查看日志"
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
        check(reply.code == 0) { "无法读取宿主进程，未能确认重启结果" }
        val processes = reply.output.mapNotNull(HostRestartPolicy::parse)
        // A successful ps always includes at least itself. An unsupported format
        // must not turn an empty parse into a false "all old processes exited".
        check(processes.isNotEmpty()) { "无法解析宿主进程，未能确认重启结果" }
        return processes
    }
    private fun finish(message: String) {
        mutable.value = HostRestartState(true, message)
        DiagnosticLog.i(TAG, message)
        DeviceRepository.message(message)
    }
}
