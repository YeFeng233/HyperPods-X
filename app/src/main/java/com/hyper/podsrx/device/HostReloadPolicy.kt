package com.hyper.podsrx.device

/** A persisted attempt budget, independent of process IDs and displayed version numbers. */
internal class HostReloadPolicy(
    val installation: String,
    previousInstallation: String,
    previousAttempts: Set<String>,
) {
    private val attempts = if (installation == previousInstallation) previousAttempts.toMutableSet() else mutableSetOf()

    fun attemptedProcesses(): Set<String> = attempts.toSet()

    fun claim(process: String, state: String, approvedScope: Set<String>): Boolean {
        val host = hostFor(process) ?: return false
        if (host !in approvedScope || state != "STALE") return false
        return attempts.add(process)
    }

    companion object {
        val hosts = listOf("com.xiaomi.bluetooth", "com.milink.service", "com.android.settings", "com.miui.securitycenter")
        // The security center names its widget provider process "<package>.remote".
        const val WIDGET_PROCESS = "com.miui.securitycenter.remote"
        fun hostFor(process: String): String? = hosts.firstOrNull { process == it || process.startsWith("$it:") }
            ?: "com.miui.securitycenter".takeIf { process == WIDGET_PROCESS }
        fun order(process: String): Int = hostFor(process)?.let(hosts::indexOf) ?: Int.MAX_VALUE
        fun installation(lastUpdateTime: Long, versionCode: Long): String = "$lastUpdateTime:$versionCode"
    }
}
