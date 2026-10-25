package com.hyper.podsrx.protocol

import kotlin.test.*

class ConversationPolicyTest {
    private val none = ConversationAction.NONE
    private val start = ConversationAction.START
    private val release = ConversationAction.RELEASE

    @Test fun eachKnownStartAndEndOwnsAndReleasesExactlyOneOperation() {
        for (begin in listOf(1, 2, 3)) for (end in listOf(6, 7, 8, 9)) {
            val policy = ConversationPolicy()
            assertEquals(start, policy.status(begin, true, 100))
            assertTrue(policy.active)
            assertEquals(release, policy.status(end, true, 200))
            assertFalse(policy.active)
            assertEquals(none, policy.status(end, true, 201))
            assertEquals(none, policy.cancel())
        }
    }

    @Test fun unknownAndEndReportsCannotStartAnOperation() {
        val policy = ConversationPolicy()
        for (status in listOf(-1, 0, 4, 5, 6, 7, 8, 9, 10, 255)) {
            assertEquals(none, policy.status(status, true, 100))
            assertFalse(policy.active)
        }
    }

    @Test fun repeatedStartsAndUnknownReportsDoNotStackOrRenewLease() {
        val policy = ConversationPolicy(maxHoldMs = 1000)
        assertEquals(start, policy.status(1, true, 100))
        assertEquals(none, policy.status(2, true, 500))
        assertEquals(none, policy.status(3, true, 900))
        assertEquals(none, policy.status(4, true, 1099))
        assertTrue(policy.active)
        assertEquals(release, policy.check(true, 1100))
        assertFalse(policy.active)
        assertEquals(none, policy.check(true, 1101))
    }

    @Test fun timeoutOnAStartEventReleasesWithoutReacquiring() {
        for (begin in listOf(1, 2, 3)) {
            val policy = ConversationPolicy(maxHoldMs = 1000)
            assertEquals(start, policy.status(1, true, 0))
            assertEquals(release, policy.status(begin, true, 1000))
            assertFalse(policy.active)
            assertEquals(none, policy.check(true, 1001))
            assertEquals(start, policy.status(begin, true, 1002))
            assertTrue(policy.active)
        }
    }

    @Test fun alpha3StateThreeCanStartAndStateSevenEndsWithoutLaterReports() {
        val policy = ConversationPolicy()
        assertEquals(start, policy.status(3, true, 100))
        assertEquals(none, policy.status(3, true, 200))
        assertEquals(release, policy.status(7, true, 300))
        assertFalse(policy.active)
        assertEquals(none, policy.status(7, true, 301))
        assertEquals(none, policy.check(true, 400))
    }

    @Test fun unknownReportsCanTriggerExpiryButCannotReenterAfterwards() {
        val policy = ConversationPolicy(maxHoldMs = 1000)
        policy.status(2, true, 0)
        assertEquals(release, policy.status(255, true, 1000))
        assertEquals(none, policy.status(255, true, 1001))
        assertFalse(policy.active)
    }

    @Test fun leaseExpiresWhenNoMoreReportsArrive() {
        val policy = ConversationPolicy()
        policy.status(1, true, 0)
        assertEquals(none, policy.check(true, 59_999))
        assertEquals(release, policy.check(true, 60_000))
        assertEquals(none, policy.check(true, 120_000))
        assertFalse(policy.active)
    }

    @Test fun losingEligibilityReleasesAndRegainingItNeedsANewStart() {
        val policy = ConversationPolicy()
        policy.status(1, true, 100)
        assertEquals(release, policy.check(false, 200))
        assertEquals(none, policy.check(false, 201))
        assertEquals(none, policy.check(true, 300))
        assertFalse(policy.active)
        assertEquals(start, policy.status(2, true, 400))
    }

    @Test fun ineligibleReportsCannotStartOrKeepAnOperation() {
        val policy = ConversationPolicy()
        assertEquals(none, policy.status(1, false, 0))
        assertEquals(start, policy.status(1, true, 100))
        assertEquals(release, policy.status(2, false, 200))
        assertEquals(none, policy.status(1, false, 300))
        assertFalse(policy.active)
    }

    @Test fun cancellingForFocusFailureOrDisconnectIsIdempotent() {
        val policy = ConversationPolicy()
        policy.status(1, true, 100)
        assertEquals(release, policy.cancel())
        assertEquals(none, policy.cancel())
        assertEquals(none, policy.status(6, true, 200))
        assertEquals(none, policy.check(true, 300))
        assertFalse(policy.active)
        assertEquals(start, policy.status(1, true, 400))
    }

    @Test fun timeRegressionReleasesEvenWhenTimestampIsAfterStart() {
        val policy = ConversationPolicy()
        policy.status(1, true, 1000)
        assertEquals(none, policy.check(true, 1800))
        assertEquals(release, policy.status(2, true, 1600))
        assertFalse(policy.active)
        assertEquals(none, policy.check(true, 1700))
        assertEquals(start, policy.status(1, true, 1800))
    }

    @Test fun clockRollbackBeforeStartAlsoReleases() {
        val policy = ConversationPolicy()
        policy.status(1, true, 1000)
        assertEquals(release, policy.check(true, 999))
        assertEquals(none, policy.cancel())
    }

    @Test fun leaseMustHaveAPositiveBound() {
        assertFailsWith<IllegalArgumentException> { ConversationPolicy(maxHoldMs = 0) }
        assertFailsWith<IllegalArgumentException> { ConversationPolicy(maxHoldMs = -1) }
    }
}
