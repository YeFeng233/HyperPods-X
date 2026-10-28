package com.hyper.podsrx.ui

import com.hyper.podsrx.device.SpatialMode
import org.junit.Assert.*
import org.junit.Test

class SpatialModeSliderTest {
    @Test fun dragClampsAndUsesThreeEqualSegments() {
        for (position in listOf(-1f, 0f, .32f)) assertEquals(SpatialMode.OFF, spatialModeAt(position))
        for (position in listOf(.34f, .5f, .66f)) assertEquals(SpatialMode.FIXED, spatialModeAt(position))
        for (position in listOf(.67f, 1f, 2f)) assertEquals(SpatialMode.HEAD_TRACKING, spatialModeAt(position))
    }

    @Test fun rtlUsesLogicalOrderAndInvalidCoordinatesNeverSelect() {
        assertEquals(SpatialMode.HEAD_TRACKING, spatialModeAt(0f, true))
        assertEquals(SpatialMode.FIXED, spatialModeAt(.5f, true))
        assertEquals(SpatialMode.OFF, spatialModeAt(1f, true))
        for (position in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) assertNull(spatialModeAt(position))
    }

    @Test fun uncalibratedOrPendingModesCannotBeSelected() {
        val ui = SpatialPresentation(SpatialMode.FIXED, true, false, true, "")
        assertTrue(ui.canSelect(SpatialMode.OFF))
        assertTrue(ui.canSelect(SpatialMode.FIXED))
        assertFalse(ui.canSelect(SpatialMode.HEAD_TRACKING))
        val pending = ui.copy(fixedEnabled = false, offEnabled = false)
        assertFalse(SpatialMode.entries.any { pending.canSelect(it) })
    }
}
