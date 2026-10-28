/* SPDX-License-Identifier: GPL-3.0-or-later
 * HyperPods-X: the AACP setting commands in flight and the rules that admit and settle them.
 */
package com.hyper.podsrx.protocol

/**
 * Tracks the one desired value per setting, from acceptance until the headset reports it back or
 * the caller gives up. It holds no transport and no clock: the caller writes the packets, runs the
 * timers named by the constants below and reflects [Effects] in its own state.
 *
 * [allowed] judges an ordinary control id and value list; the equalizer and call controls have
 * their own rules here.
 */
class ControlCommands(private val allowed: (Int, List<Int>) -> Boolean, private val effects: Effects) {
    data class Command(val id: Int, val values: List<Int>, val token: Long)

    /** The settings the caller last heard from the headset. */
    data class View(
        val connected: Boolean,
        val readingBack: Boolean,
        val controls: Map<Int, List<Int>>,
        val pending: Set<Int>,
        val equalizer: List<Int>?
    )

    enum class Rejection { NOT_CONNECTED, READING_BACK, ADAPTIVE_NOT_READY, MODE_WHILE_ADAPTIVE, NOT_ALLOWED }

    sealed interface Admission {
        data class Accepted(val command: Command) : Admission
        data class Rejected(val reason: Rejection) : Admission
        /** Already wanted, or the slider has not moved; nothing to send. */
        data object Unchanged : Admission
    }

    enum class Send { SEND, STALE, NOT_ADAPTIVE }

    /** What the headset's report did to the command in flight. */
    data class Report(val expected: Command?, val matched: Boolean)

    /** Called with the commands' lock held, so the caller's pending state never runs ahead of them. */
    interface Effects {
        fun accepted(command: Command)
        fun completed(command: Command)
        /** An adaptive strength went out; it is not awaited, so it stops showing as pending. */
        fun adaptiveSent()
        /** The mode left adaptive, which cancels any strength still wanted. */
        fun adaptiveRevoked()
    }

    private val lock = Any()
    private val desired = HashMap<Int, Command>()
    private var sequence = 0L

    @Volatile private var readingBack = false
    @Volatile private var readbackStartedInSession = 0L
    private var readbackScheduled = false

    fun enqueue(id: Int, values: List<Int>, view: View): Admission {
        if (!view.connected) return Admission.Rejected(Rejection.NOT_CONNECTED)
        if (view.readingBack) return Admission.Rejected(Rejection.READING_BACK)
        if (id == ADAPTIVE && !canAdjustAdaptive(view.connected, view.controls, view.pending))
            return Admission.Rejected(Rejection.ADAPTIVE_NOT_READY)
        if (id == MODE && ADAPTIVE in view.pending) return Admission.Rejected(Rejection.MODE_WHILE_ADAPTIVE)
        val admitted = when (id) {
            CALL -> values.size == 4 && values.all { it in 0..255 } && values[1] in 2..3 && view.controls[CALL]?.getOrNull(1) in 2..3
            EQUALIZER -> view.equalizer != null && values.size == 4 && values[0] in 1..2 && values.drop(1).all { it in 0..100 }
            else -> allowed(id, values) && view.controls.containsKey(id)
        }
        if (!admitted) return Admission.Rejected(Rejection.NOT_ALLOWED)
        synchronized(lock) {
            val command = Command(id, values.toList(), ++sequence)
            if (id == ADAPTIVE) {
                if (!shouldSendAdaptive(values.first(), adaptiveStrength(view.controls), desired[id]?.values?.firstOrNull()))
                    return Admission.Unchanged
                desired[id] = command
            } else if (desired.putIfAbsent(id, command) != null) return Admission.Unchanged
            effects.accepted(command)
            return Admission.Accepted(command)
        }
    }

    fun isCurrent(command: Command): Boolean = synchronized(lock) { desired[command.id] === command }

    /** The value still wanted for [id]; it survives a readback reconnect. */
    fun desiredValue(id: Int): Int? = synchronized(lock) { desired[id]?.values?.firstOrNull() }

    /** Checked right before the write; a strength outside adaptive mode is settled unsent. */
    fun beforeSend(command: Command, controls: Map<Int, List<Int>>): Send {
        if (!isCurrent(command)) return Send.STALE
        if (command.id == ADAPTIVE && controls[MODE]?.firstOrNull() != 4) {
            complete(command)
            return Send.NOT_ADAPTIVE
        }
        return Send.SEND
    }

    /** Returns whether the write awaits a report: a readback and a timeout. */
    fun sent(command: Command): Boolean {
        if (command.id != ADAPTIVE) return true
        // LibrePods streams strength writes without waiting for a matching reply. Keep the sent
        // target separate from the earbud's actual control report.
        synchronized(lock) { if (desired[command.id] === command) effects.adaptiveSent() }
        return false
    }

    /** Returns false when something else already settled [command]. */
    fun complete(command: Command): Boolean = synchronized(lock) {
        if (!desired.remove(command.id, command)) false else { effects.completed(command); true }
    }

    fun onControl(id: Int, value: List<Int>): Report {
        val expected = synchronized(lock) { desired[id] }
        val matched = expected != null && value.take(expected.values.size) == expected.values
        if (expected != null && matched) complete(expected)
        if (id == MODE && value.firstOrNull() != 4) synchronized(lock) {
            desired.remove(ADAPTIVE)
            effects.adaptiveRevoked()
        }
        return Report(expected, matched)
    }

    fun onEqualizer(values: List<Int>) {
        val expected = synchronized(lock) { desired[EQUALIZER] } ?: return
        if (expected.values == values) complete(expected)
    }

    /**
     * The channel dropped. A strength is never replayed; other wishes survive only a readback
     * reconnect. [update] runs under the lock, so a new acceptance cannot slip in between.
     */
    fun disconnected(update: (preserve: Boolean) -> Unit) = synchronized(lock) {
        val preserve = readingBack
        desired.remove(ADAPTIVE)
        if (!preserve) desired.clear()
        update(preserve)
    }

    // Readback: after a write the channel is reopened so the headset reports every setting again.

    /** Claims the one readback that may run at a time. */
    fun claimReadback(command: Command): Boolean = synchronized(lock) {
        if (command.id == ADAPTIVE || desired[command.id] !== command || readbackScheduled) false
        else { readbackScheduled = true; true }
    }

    fun releaseReadback() = synchronized(lock) { readbackScheduled = false }

    fun prepareReadback(session: Long) {
        readingBack = true
        readbackStartedInSession = session
    }

    /** A new session opened; returns whether a readback asked for it. */
    fun opened(): Boolean {
        val was = readingBack
        readingBack = false
        return was
    }

    /** A mode report in a session after the readback began completes the readback. */
    fun endsReadback(id: Int, session: Long): Boolean = id == MODE && session > readbackStartedInSession

    companion object {
        const val MODE = 0x0d
        const val ADAPTIVE = 0x2e
        const val CALL = 0x24
        /** Not an AACP control id; the equalizer has its own packet. */
        const val EQUALIZER = 256
        /**
         * A write the headset has not reported by then reopens the channel so it reports again. Mode
         * changes usually report within a second, but a switch to transparency can take longer, and
         * reopening at 1.2 s dropped it and left the request pending until the timeout.
         */
        const val READBACK_AFTER_MS = 3000L
        /** A write nothing confirmed by then is given up, keeping what the headset reported. */
        const val CONFIRM_TIMEOUT_MS = 12000L
        /** How long the reopened channel may take to report the settings again. */
        const val READBACK_WINDOW_MS = 5000L

        fun adaptiveStrength(controls: Map<Int, List<Int>>): Int? =
            controls[ADAPTIVE]?.firstOrNull()?.takeIf { it in 0..100 }

        fun canAdjustAdaptive(connected: Boolean, controls: Map<Int, List<Int>>, pending: Set<Int>): Boolean =
            connected && controls[MODE]?.firstOrNull() == 4 && adaptiveStrength(controls) != null && MODE !in pending

        fun shouldSendAdaptive(value: Int, reported: Int?, target: Int?): Boolean =
            value != target && (target != null || value != reported)
    }
}
