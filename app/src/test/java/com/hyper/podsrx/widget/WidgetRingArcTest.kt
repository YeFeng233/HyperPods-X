package com.hyper.podsrx.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRingArcTest {
    @Test
    fun absentLevelDrawsNoArc() = assertEquals(0f, WidgetRingArc.sweep(null), 0.01f)

    @Test
    fun emptyLevelDrawsNoArc() = assertEquals(0f, WidgetRingArc.sweep(0), 0.01f)

    @Test
    fun halfLevelDrawsHalfRing() = assertEquals(180f, WidgetRingArc.sweep(50), 0.01f)

    @Test
    fun fullLevelClosesTheRing() = assertEquals(360f, WidgetRingArc.sweep(100), 0.01f)

    @Test
    fun outOfRangeLevelStopsAtFullRing() = assertEquals(360f, WidgetRingArc.sweep(140), 0.01f)
}
