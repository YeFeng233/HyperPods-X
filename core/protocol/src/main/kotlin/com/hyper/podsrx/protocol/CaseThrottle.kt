/* SPDX-License-Identifier: GPL-3.0-or-later
 * HyperPods-X: case status repeat filter.
 */
package com.hyper.podsrx.protocol

/** The case repeats its status every couple of seconds; only changes and a slow refresh pass. */
class CaseThrottle(private val refreshMs: Long = REFRESH_MS) {
    private var last: List<Battery>? = null
    private var lastAt = 0L

    /** Whether a status has passed since the last [reset]. */
    val seen: Boolean get() = last != null

    fun admit(values: List<Battery>, now: Long): Boolean {
        if (values == last && now - lastAt < refreshMs) return false
        last = values
        lastAt = now
        return true
    }

    fun reset() { last = null }

    companion object {
        // Must stay well under the 60 s reading age so an unchanged case keeps its readings fresh.
        const val REFRESH_MS = 20000L
    }
}
