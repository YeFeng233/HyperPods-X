package com.hyper.podsrx.device

import com.hyper.podsrx.R

internal object HostRestartPolicy {
    data class Host(val pkg: String, val labelRes: Int, val service: String? = null)
    data class Process(val pid: Int, val uid: Int, val name: String)
    data class Service(val name: String, val process: String, val enabled: Boolean,
        val exported: Boolean, val permission: String? = null)
    val hosts = listOf(
        Host("com.xiaomi.bluetooth", R.string.host_xiaomi_bluetooth, "com.android.bluetooth.ble.app.MiuiNearbyService"),
        Host("com.milink.service", R.string.host_interconnectivity, "com.milink.server.MiLinkProcessAliveService"),
        Host("com.android.settings", R.string.host_settings),
        Host("com.miui.securitycenter", R.string.host_security),
    )
    private val auxiliary = setOf("com.milink.runtime", "com.milink.crossdeviceservice", "com.xiaomi.vtcamera")

    /** Only the existing start-service contracts are used; bound headset services need their client. */
    fun wakeService(host: Host, services: List<Service>): Service? = services.firstOrNull {
        it.name == host.service && it.enabled && it.exported && it.permission.isNullOrEmpty() &&
            it.name.matches(Regex("[A-Za-z0-9_.]+")) && acceptsName(host.pkg, it.process)
    }

    fun recoveryProcess(host: Host, services: List<Service>): String? {
        if (host.service == null) return null
        wakeService(host, services)?.let { return it.process }
        if (host.pkg == "com.milink.service") {
            services.firstOrNull { it.name == "com.miui.headset.runtime.HeadsetLocalService" &&
                it.enabled && acceptsName(host.pkg, it.process) }?.let { return it.process }
        }
        return host.pkg
    }

    fun recovered(processes: List<Process>, uid: Int, expectedProcess: String?): Boolean =
        expectedProcess == null || processes.any { it.uid == uid && it.name == expectedProcess }

    fun acceptsName(pkg: String, name: String): Boolean = hosts.any { it.pkg == pkg } &&
        name.matches(Regex("[A-Za-z0-9_.:]+")) &&
        // Only the widget provider process of the security center is hooked.
        if (pkg == "com.miui.securitycenter") name == HostReloadPolicy.WIDGET_PROCESS
        else name == pkg || name.startsWith("${pkg}:") || pkg == "com.milink.service" && name in auxiliary

    fun parse(line: String): Process? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 3) return null
        val pid = parts[0].toIntOrNull()?.takeIf { it > 1 } ?: return null
        val uid = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Process(pid, uid, parts[2])
    }

    fun matches(process: Process, pkg: String, uid: Int, declared: Set<String>): Boolean =
        process.uid == uid && process.name in declared && acceptsName(pkg, process.name)

    fun stopCommand(process: Process): String {
        require(process.pid > 1 && process.uid >= 0 && hosts.any { acceptsName(it.pkg, process.name) })
        // Recheck both identities immediately before signaling: a stale PID must not kill its successor.
        return """
            if [ -r /proc/${process.pid}/status ] &&
               [ "${'$'}(awk '/^Uid:/{print ${'$'}2; exit}' /proc/${process.pid}/status)" = '${process.uid}' ] &&
               [ "${'$'}(tr '\000' '\n' < /proc/${process.pid}/cmdline | head -n 1)" = '${process.name}' ]; then
                if kill -TERM ${process.pid}; then echo STOPPED:${process.pid}; else echo FAILED:${process.pid}; fi
            else
                echo GONE:${process.pid}
            fi
        """.trimIndent()
    }
}
