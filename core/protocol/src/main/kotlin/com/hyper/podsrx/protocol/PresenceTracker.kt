/* SPDX-License-Identifier: GPL-3.0-or-later
 * HyperPods-X: headset presence for scan duty.
 */
package com.hyper.podsrx.protocol

/** Tells when the bound headset is likely away, so the live scan can idle. */
class PresenceTracker(now: Long, private val idleAfterMs: Long = IDLE_AFTER_MS) {
    private var lastHeardAt = now

    /** The bound headset was heard or its link came up. */
    fun heard(now: Long) { lastHeardAt = now }

    /** A connected control channel counts as hearing the headset, so the silence starts once it drops. */
    fun shouldIdle(now: Long, controlConnected: Boolean): Boolean {
        if (controlConnected) { lastHeardAt = now; return false }
        return now - lastHeardAt >= idleAfterMs
    }

    companion object {
        const val IDLE_AFTER_MS = 180000L
    }
}
