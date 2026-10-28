/* SPDX-License-Identifier: GPL-3.0-or-later
 * HyperPods-X: battery readings merged from the control channel and advertisements.
 */
package com.hyper.podsrx.protocol

/** [updated] is on the caller's clock; [control] marks a reading the control channel reported. */
data class Reading(val level: Int?, val charging: Boolean, val precise: Boolean, val updated: Long, val control: Boolean = false)

/** Readings without their refresh times, which tells a real change from a repeat. */
fun Map<Int, Reading>.content(): Map<Int, Reading> = mapValues { it.value.copy(updated = 0L) }

/**
 * Merges battery reports by component. The app and the Bluetooth host share these rules; the pure
 * [merged] and [kept] serve a caller that owns its own state, the instance one that does not.
 */
class BatteryLedger {
    var readings: Map<Int, Reading> = emptyMap()
        private set

    /** Returns whether the content changed; a repeat only refreshes the times. */
    fun apply(values: List<Battery>, precise: Boolean, control: Boolean, now: Long): Boolean {
        val next = merged(readings, values, precise, control, now)
        val changed = next.content() != readings.content()
        readings = next
        return changed
    }

    /** Returns whether any reading was dropped. */
    fun expire(maxAgeMs: Long, controlFreshAt: Long, now: Long): Boolean {
        val next = kept(readings, maxAgeMs, controlFreshAt, now)
        val expired = next.size != readings.size
        readings = next
        return expired
    }

    /** Restarts every reading's age, as when the screen comes back on after the scans were stopped. */
    fun touch(now: Long) { readings = readings.mapValues { it.value.copy(updated = now) } }

    fun content(): Map<Int, Reading> = readings.content()

    fun clear() { readings = emptyMap() }

    companion object {
        /** A precise reading this recent wins over a coarse advertisement. */
        const val PRECISE_WINS_MS = 30000L

        /** Unknown values keep the last real reading; advertisements refresh it within seconds. */
        fun merged(current: Map<Int, Reading>, values: List<Battery>, precise: Boolean, control: Boolean, now: Long): Map<Int, Reading> {
            val result = current.toMutableMap()
            for (b in values) {
                val old = result[b.component]
                // The control channel withdraws its own reading once a component drops off, such as a
                // case left behind; a broadcast reading ages out on its own.
                if (control && !b.connected) { if (old?.control == true) result.remove(b.component); continue }
                if (b.level == null) continue
                // A recent precise control report wins over a coarse advertisement, though not over
                // its charging flag: a plug-in or unplug must show at once.
                if (!precise && old?.precise == true && now - old.updated < PRECISE_WINS_MS) {
                    if (b.charging != old.charging) result[b.component] = old.copy(charging = b.charging)
                    continue
                }
                result[b.component] = Reading(b.level, b.charging, precise, now, control)
            }
            return result
        }

        /**
         * Drops readings nothing has refreshed within [maxAgeMs]. The control channel reports only
         * changes, so its readings count as fresh until [controlFreshAt].
         */
        fun kept(current: Map<Int, Reading>, maxAgeMs: Long, controlFreshAt: Long, now: Long): Map<Int, Reading> =
            current.filterValues { now - (if (it.control) maxOf(it.updated, controlFreshAt) else it.updated) < maxAgeMs }
    }
}
