package com.hyper.podsrx.device

import com.hyper.podsrx.R

internal object HostRestartPolicy {
    data class Host(val pkg: String, val labelRes: Int, val service: String? = null)
    data class Process(val pid: Int, val uid: Int, val name: String)
    val hosts = listOf(
        Host("com.xiaomi.bluetooth", R.string.host_xiaomi_bluetooth, "com.android.bluetooth.ble.app.MiuiNearbyService"),
        Host("com.milink.service", R.string.host_interconnectivity, "com.milink.server.MiLinkProcessAliveService"),
        Host("com.android.settings", R.string.host_settings),
        Host("com.miui.securitycenter", R.string.host_security),
    )
    private val auxiliary = setOf("com.milink.runtime", "com.milink.crossdeviceservice", "com.xiaomi.vtcamera")

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
