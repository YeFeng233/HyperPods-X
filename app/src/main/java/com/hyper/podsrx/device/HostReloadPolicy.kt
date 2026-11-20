package com.hyper.podsrx.device

import com.hyper.podsrx.hook.HubWire

/** A persisted attempt budget, independent of process IDs and displayed version numbers. */
internal class HostReloadPolicy(
    val installation: String,
    previousInstallation: String,
    previousAttempts: Set<String>,
    private val aosp: Boolean = false,
) {
    private val attempts = if (installation == previousInstallation) previousAttempts.toMutableSet() else mutableSetOf()

    fun attemptedProcesses(): Set<String> = attempts.toSet()

    fun claim(process: String, state: String, approvedScope: Set<String>): Boolean {
        val host = hostFor(process, aosp) ?: return false
        if (host !in approvedScope || state != "STALE") return false
        return attempts.add(process)
    }

    companion object {
        val hosts = listOf("com.xiaomi.bluetooth", "com.milink.service", "com.android.settings", "com.android.systemui", "com.miui.securitycenter")
        // The security center names its widget provider process "<package>.remote".
        const val WIDGET_PROCESS = "com.miui.securitycenter.remote"
        fun hostFor(process: String, aosp: Boolean = false): String? = HubWire.AOSP_HOST.takeIf { aosp && process == it }
            ?: hosts.firstOrNull { process == it || process.startsWith("$it:") }
            ?: "com.miui.securitycenter".takeIf { process == WIDGET_PROCESS }
        fun order(process: String, aosp: Boolean = false): Int = hostFor(process, aosp)?.let(hosts::indexOf) ?: Int.MAX_VALUE
        fun installation(lastUpdateTime: Long, versionCode: Long): String = "$lastUpdateTime:$versionCode"
    }
}
