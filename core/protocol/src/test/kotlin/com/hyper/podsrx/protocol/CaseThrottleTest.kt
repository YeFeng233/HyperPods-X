package com.hyper.podsrx.protocol

import kotlin.test.*

class CaseThrottleTest {
    private val values = listOf(Battery(8, 60, false, true))

    @Test fun repeatsPassOnlyAfterTheRefresh() {
        val throttle = CaseThrottle(refreshMs = 20000)
        assertFalse(throttle.seen)
        assertTrue(throttle.admit(values, 0))
        assertTrue(throttle.seen)
        assertFalse(throttle.admit(values, 19999))
        assertTrue(throttle.admit(values, 20000))
    }

    @Test fun aChangePassesAtOnce() {
        val throttle = CaseThrottle()
        throttle.admit(values, 0)
        assertTrue(throttle.admit(listOf(Battery(8, 61, false, true)), 1))
    }

    @Test fun resetForgetsTheLastStatus() {
        val throttle = CaseThrottle()
        throttle.admit(values, 0)
        throttle.reset()
        assertFalse(throttle.seen)
        assertTrue(throttle.admit(values, 1))
    }
}
