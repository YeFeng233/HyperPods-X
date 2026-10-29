/* SPDX-License-Identifier: GPL-3.0-or-later
 * HyperPods-X: battery notifications decided from the merged readings.
 */
package com.hyper.podsrx.protocol

sealed interface BatteryAlert {
    /** The lower earbud off the charger reached [BatteryAlerts.LOW]. */
    data object Low : BatteryAlert
    /** The earbuds charge again or recovered; a shown low alert is withdrawn. */
    data object LowCleared : BatteryAlert
    /** The earbuds on the charger all finished at 100. */
    data object Full : BatteryAlert
    /**
     * An earbud went into a case below [BatteryAlerts.CASE_LOW]; [update] refreshes the shown alert
     * without alerting again.
     */
    data class Docked(val caseLevel: Int, val update: Boolean) : BatteryAlert
    /** An earbud went into a case at or above [BatteryAlerts.CASE_LOW]; only logged. */
    data class DockSkipped(val caseLevel: Int) : BatteryAlert
}

/**
 * Turns battery readings into one-shot alerts. Only the two arm flags need to outlive the host;
 * the rest follows the readings of this run. Without the app and with the screen off nothing
 * refreshes the readings, so the caller reports those blind spans through [sight].
 */
class BatteryAlerts(lowArmed: Boolean = true, fullArmed: Boolean = false) {
    var lowArmed = lowArmed
        private set
    var fullArmed = fullArmed
        private set

    private val charging = HashMap<Int, Boolean>()
    /** When each earbud was last seen off the charger. */
    private val idleAt = HashMap<Int, Long>()
    /** When each earbud's current run off the charger began. */
    private val idleSince = HashMap<Int, Long>()
    private var sighted = true
    private var blindFrom = 0L
    private var blindTo = Long.MIN_VALUE
    /** The current docking, while it may still show or follow the case level. */
    private var dockedAt: Long? = null
    private var shownCase: Int? = null

    /** Whether anything refreshes the readings: the screen is on or the control channel is up. */
    fun sight(live: Boolean, now: Long) {
        if (live == sighted) return
        sighted = live
        if (live) blindTo = now else blindFrom = now
    }

    fun observe(readings: Map<Int, Reading>, now: Long): List<BatteryAlert> {
        val out = ArrayList<BatteryAlert>()
        val buds = EARBUDS.mapNotNull { c -> readings[c]?.takeIf { it.level != null }?.let { c to it } }

        val lowest = buds.filter { !it.second.charging }.minOfOrNull { it.second.level!! }
        if (lowArmed && lowest != null && lowest <= LOW) {
            lowArmed = false
            out += BatteryAlert.Low
        } else if (!lowArmed && buds.isNotEmpty() && buds.all { it.second.charging || it.second.level!! >= LOW_REARM }) {
            lowArmed = true
            out += BatteryAlert.LowCleared
        }

        if (buds.any { it.second.charging && it.second.level!! <= FULL_REARM }) fullArmed = true
        if (fullArmed && buds.any { it.second.level == FULL } && buds.none { it.second.charging && it.second.level!! < FULL }) {
            fullArmed = false
            out += BatteryAlert.Full
        }

        for ((component, reading) in buds) {
            val was = charging.put(component, reading.charging)
            if (!reading.charging) {
                idleAt[component] = now
                if (was != false) idleSince[component] = now
                continue
            }
            if (was != false || blindLonger(idleAt[component], now)) continue
            val since = idleSince[component] ?: continue
            // The advertisement, the case status and the control channel disagree about an earbud
            // at rest in the case; a short run off the charger is that noise, not a docking.
            if (now - since < SETTLE_MS) continue
            // An earbud out since before the current docking is the second of the pair joining it.
            val current = dockedAt
            if (current != null && now - current <= FOLLOW_MS && since < current) continue
            dockedAt = now
            shownCase = null
        }

        val docked = dockedAt
        if (docked != null) {
            val case = readings[CASE]?.level
            val shown = shownCase
            when {
                shown == null && case != null && now - docked <= CASE_WAIT_MS -> {
                    if (case < CASE_LOW) { shownCase = case; out += BatteryAlert.Docked(case, false) }
                    else { dockedAt = null; out += BatteryAlert.DockSkipped(case) }
                }
                shown == null && now - docked > CASE_WAIT_MS -> dockedAt = null
                now - docked > FOLLOW_MS -> dockedAt = null
                shown != null && case != null && case != shown -> { shownCase = case; out += BatteryAlert.Docked(case, true) }
            }
        }
        return out
    }

    /** A docking seen after a blind span longer than [DOCK_WINDOW_MS] may be hours old. */
    private fun blindLonger(idle: Long?, now: Long): Boolean {
        if (idle == null) return true
        val end = if (sighted) blindTo else now
        if (end < idle) return false
        return end - maxOf(blindFrom, idle) > DOCK_WINDOW_MS
    }

    companion object {
        const val LOW = 20
        /** Re-arms well above [LOW], so a reading flickering at the edge alerts once. */
        const val LOW_REARM = 30
        const val FULL = 100
        /** A charge topping up from 99 does not count as a new one. */
        const val FULL_REARM = 95
        /** A docking alerts only while the case is below this. */
        const val CASE_LOW = 40
        const val DOCK_WINDOW_MS = 5 * 60_000L
        /** An earbud must stay off the charger this long before going back in counts as a docking. */
        const val SETTLE_MS = 5_000L
        /** A docking waits this long for the case level, which comes with the next status. */
        const val CASE_WAIT_MS = 60_000L
        /** The shown case level follows a more precise reading this long. */
        const val FOLLOW_MS = 2 * 60_000L
        private val EARBUDS = listOf(4, 2)
        private const val CASE = 8
    }
}
