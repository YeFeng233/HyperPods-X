/* SPDX-License-Identifier: GPL-3.0-or-later
 * Presence transitions informed by LibrePods, Copyright (C) 2025 LibrePods contributors.
 * HyperPods-X: bounded pause ownership and acknowledgement state machine, 2026.
 * See THIRD_PARTY.md for the audited installed APK and implementation references.
 */
package com.hyper.podsrx.protocol

enum class WearState(val wire: Int) {
    IN_EAR(0), OUT_OF_EAR(1), IN_CASE(2), DISCONNECTED(3);

    companion object {
        fun fromWire(value: Int): WearState? = entries.find { it.wire == value }
    }
}

enum class EarPlaybackAction { NONE, PAUSE, PLAY }

/** No initial autoplay; only resume a pause issued and observed within this session. */
class EarPlaybackPolicy(
    private val resumeWindowMs: Long = 300_000,
    private val acknowledgementMs: Long = 1_500
) {
    var inEarCount: Int? = null
        private set
    private var pausedAt: Long? = null
    private var pauseConfirmed = false
    private var reinserted = false

    val ownsPause: Boolean get() = pausedAt != null

    fun reset() {
        inEarCount = null
        releasePause()
    }

    fun wearing(count: Int, playing: Boolean, eligible: Boolean, now: Long): EarPlaybackAction {
        require(count in 0..2)
        if (!eligible) {
            reset()
            return EarPlaybackAction.NONE
        }
        val previous = inEarCount
        inEarCount = count
        updatePlayback(playing, now)
        if (previous == null || previous == count) return resumeIfReady(playing)

        if (count < previous) {
            reinserted = false
            if (playing) {
                pausedAt = now
                pauseConfirmed = false
                return EarPlaybackAction.PAUSE
            }
        } else if (previous == 0 || count == 2) {
            reinserted = true
        }
        return resumeIfReady(playing)
    }

    fun playback(playing: Boolean, eligible: Boolean, now: Long): EarPlaybackAction {
        if (!eligible) {
            reset()
            return EarPlaybackAction.NONE
        }
        updatePlayback(playing, now)
        return resumeIfReady(playing)
    }

    private fun updatePlayback(playing: Boolean, now: Long) {
        val start = pausedAt ?: return
        val age = now - start
        if (age < 0 || age > resumeWindowMs || (!pauseConfirmed && age > acknowledgementMs)) {
            releasePause()
        } else if (!playing) {
            pauseConfirmed = true
        } else if (pauseConfirmed) {
            // Playback restarted without our PLAY: the user/player now owns it.
            releasePause()
        }
    }

    private fun resumeIfReady(playing: Boolean): EarPlaybackAction {
        if (ownsPause && pauseConfirmed && reinserted && !playing && (inEarCount ?: 0) > 0) {
            releasePause()
            return EarPlaybackAction.PLAY
        }
        return EarPlaybackAction.NONE
    }

    private fun releasePause() {
        pausedAt = null
        pauseConfirmed = false
        reinserted = false
    }
}
