package com.hyper.podsrx.protocol

import com.hyper.podsrx.protocol.HeadsetIconPolicy.Side
import kotlin.test.Test
import kotlin.test.assertEquals

class HeadsetIconPolicyTest {
    private val policy = HeadsetIconPolicy()

    @Test fun leftEarStaysKnownWhenTheRightEarComesOutAndReturns() {
        assertEquals(Side.PAIR, policy.update(readings(left = true, right = true)))
        assertEquals(Side.LEFT, policy.update(readings(left = false, right = true)))
        assertEquals(Side.LEFT, policy.update(readings(left = false, right = true)))
        assertEquals(Side.PAIR, policy.update(readings(left = false, right = false)))
        assertEquals(Side.LEFT, policy.update(readings(left = false, right = true)))
    }

    @Test fun rightEarRemovalCanBeFollowedByDockingAndLeftEarRemoval() {
        policy.update(readings(left = true, right = true))
        assertEquals(Side.RIGHT, policy.update(readings(left = true, right = false)))
        assertEquals(Side.PAIR, policy.update(readings(left = true, right = true)))
        assertEquals(Side.LEFT, policy.update(readings(left = false, right = true)))
    }

    @Test fun anInitialChargingMismatchOrMissingReadingDoesNotIdentifyAnEar() {
        assertEquals(Side.PAIR, policy.update(readings(left = false, right = true)))
        policy.update(readings(left = true, right = true))
        assertEquals(Side.PAIR, policy.update(readings(left = null, right = true)))
        assertEquals(Side.PAIR, policy.update(readings(left = false, right = true)))
    }

    @Test fun resettingForAnotherHeadsetDiscardsChargingHistory() {
        policy.update(readings(left = true, right = true))
        policy.reset()
        assertEquals(Side.PAIR, policy.update(readings(left = true, right = false)))
    }

    private fun readings(left: Boolean?, right: Boolean?): Map<Int, Reading> = buildMap {
        if (left != null) put(Side.LEFT.component, Reading(60, left, true, 0))
        if (right != null) put(Side.RIGHT.component, Reading(60, right, true, 0))
    }
}
