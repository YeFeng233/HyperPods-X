package com.hyper.podsrx.protocol

import kotlin.test.*

class PresenceTrackerTest {
    @Test fun idlesAfterSilenceWithoutAControlChannel() {
        val tracker = PresenceTracker(now = 0, idleAfterMs = 180000)
        assertFalse(tracker.shouldIdle(179999, controlConnected = false))
        assertTrue(tracker.shouldIdle(180000, controlConnected = false))
        tracker.heard(180000)
        assertFalse(tracker.shouldIdle(180001, controlConnected = false))
    }

    @Test fun silenceStartsWhenTheControlChannelDrops() {
        val tracker = PresenceTracker(now = 0, idleAfterMs = 180000)
        assertFalse(tracker.shouldIdle(500000, controlConnected = true))
        assertFalse(tracker.shouldIdle(679999, controlConnected = false))
        assertTrue(tracker.shouldIdle(680000, controlConnected = false))
    }
}
