package com.hyper.podsrx.protocol

import kotlin.test.*

class EarPlaybackTest {
    private val none = EarPlaybackAction.NONE
    private val pause = EarPlaybackAction.PAUSE
    private val play = EarPlaybackAction.PLAY

    @Test fun allFourWireStatesArePreservedWithoutAssumingLeftPrimary() {
        for (first in 0..3) for (second in 0..3) {
            val event = Aacp.decode(Aacp.header + byteArrayOf(6, 0, first.toByte(), second.toByte())) as Event.Wearing
            assertEquals(first, event.primary.wire)
            assertEquals(second, event.secondary.wire)
            assertEquals(listOf(first, second).count { it == 0 }, event.inEarCount)
        }
    }

    @Test fun invalidOrTruncatedWearingPacketsCannotBeMisreadAsRemoval() {
        for (value in listOf(4, 255)) {
            assertNull(Aacp.decode(Aacp.header + byteArrayOf(6, 0, value.toByte(), 0)))
            assertNull(Aacp.decode(Aacp.header + byteArrayOf(6, 0, 0, value.toByte())))
        }
        assertNull(Aacp.decode(Aacp.header + byteArrayOf(6, 0, 0)))
        assertNull(Aacp.decode(Aacp.header + byteArrayOf(6, 0, 0, 0, 1)))
    }

    @Test fun wearingFramesSurviveFragmentationAndAdjacentControls() {
        val packet = Aacp.header + byteArrayOf(6, 0, 0, 1)
        val framer = AacpFramer()
        assertTrue(framer.feed(packet.copyOf(7)).isEmpty())
        val frames = framer.feed(packet.drop(7).toByteArray() + Aacp.control(13, listOf(2)))
        assertEquals(2, frames.size)
        assertEquals(1, (Aacp.decode(frames[0]) as Event.Wearing).inEarCount)
        assertTrue(Aacp.decode(frames[1]) is Event.Control)
    }

    @Test fun firstReportNeverChangesPlayback() {
        for (count in 0..2) for (playing in listOf(true, false)) {
            assertEquals(none, EarPlaybackPolicy().wearing(count, playing, true, 0))
        }
    }

    @Test fun removingOneFromBothPausesAndReplacingItResumes() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        assertEquals(pause, policy.wearing(1, true, true, 1000))
        assertEquals(none, policy.playback(false, true, 1100))
        assertEquals(play, policy.wearing(2, false, true, 2000))
        assertFalse(policy.ownsPause)
    }

    @Test fun removingAndReplacingASingleWornBudWorks() {
        val policy = EarPlaybackPolicy()
        policy.wearing(1, true, true, 0)
        assertEquals(pause, policy.wearing(0, true, true, 1000))
        assertEquals(play, policy.wearing(1, false, true, 2000))
    }

    @Test fun removingBothAtOnceAndReinsertingOneWorks() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        assertEquals(pause, policy.wearing(0, true, true, 1000))
        assertEquals(play, policy.wearing(1, false, true, 2000))
    }

    @Test fun repeatedReportsDoNotSendMoreMediaKeys() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        assertEquals(pause, policy.wearing(1, true, true, 1000))
        assertEquals(none, policy.wearing(1, true, true, 1010))
        assertEquals(none, policy.wearing(1, false, true, 1100))
        assertEquals(play, policy.wearing(2, false, true, 2000))
        assertEquals(none, policy.wearing(2, false, true, 2001))
    }

    @Test fun alreadyPausedMediaNeverAutoplays() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, false, true, 0)
        assertEquals(none, policy.wearing(1, false, true, 1000))
        assertEquals(none, policy.wearing(2, false, true, 2000))
        assertFalse(policy.ownsPause)
    }

    @Test fun fastReinsertionWaitsForTheAsynchronousPause() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        assertEquals(pause, policy.wearing(1, true, true, 1000))
        assertEquals(none, policy.wearing(2, true, true, 1200))
        assertEquals(play, policy.playback(false, true, 1400))
        assertEquals(none, policy.playback(false, true, 1450))
    }

    @Test fun pauseWhichNeverTookEffectCannotOwnAFutureManualPause() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        policy.wearing(1, true, true, 1000)
        assertEquals(none, policy.playback(true, true, 2600))
        assertFalse(policy.ownsPause)
        assertEquals(none, policy.wearing(2, false, true, 3000))
    }

    @Test fun removingTheSecondBudDoesNotLoseAnAlreadyConfirmedPause() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        policy.wearing(1, true, true, 1000)
        policy.playback(false, true, 1100)
        assertEquals(none, policy.wearing(0, false, true, 5000))
        assertEquals(play, policy.wearing(1, false, true, 6000))
    }

    @Test fun userPlaybackOverridesAnOwnedPause() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        policy.wearing(1, true, true, 1000)
        policy.playback(false, true, 1100)
        policy.playback(true, true, 2000)
        policy.playback(false, true, 3000)
        assertEquals(none, policy.wearing(2, false, true, 4000))
    }

    @Test fun disablingRouteChangesAndCallsDiscardPauseOwnership() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        policy.wearing(1, true, true, 1000)
        assertEquals(none, policy.playback(false, false, 1100))
        assertFalse(policy.ownsPause)
        assertEquals(none, policy.wearing(2, false, true, 2000))
    }

    @Test fun reconnectAndReenableOnlyEstablishANewBaseline() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        policy.wearing(1, true, true, 1000)
        policy.reset()
        assertEquals(none, policy.wearing(2, false, true, 2000))
        assertEquals(none, policy.wearing(0, true, false, 2100))
        assertEquals(none, policy.wearing(2, true, true, 3000))
    }

    @Test fun oldPauseExpiresEvenWhenNoNewReportsArrive() {
        val policy = EarPlaybackPolicy(resumeWindowMs = 5000)
        policy.wearing(2, true, true, 0)
        policy.wearing(1, true, true, 1000)
        policy.playback(false, true, 1100)
        assertEquals(none, policy.wearing(2, false, true, 6001))
        assertFalse(policy.ownsPause)
    }

    @Test fun removingAgainBeforePauseAckCancelsQueuedResume() {
        val policy = EarPlaybackPolicy()
        policy.wearing(2, true, true, 0)
        policy.wearing(1, true, true, 1000)
        policy.wearing(2, true, true, 1100)
        policy.wearing(0, false, true, 1200)
        assertEquals(none, policy.playback(false, true, 1300))
    }
}
