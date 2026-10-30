package com.hyper.podsrx.widget

import com.hyper.podsrx.protocol.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {
    private fun level(value: Int?, charging: Boolean = false) = Reading(value, charging, true, 0L)

    @Test fun columnsComeFromTheAacpComponentIds() {
        val snapshot = WidgetSnapshot.of("AA:BB:CC:DD:EE:FF", mapOf(4 to level(88), 2 to level(76), 8 to level(54)))
        assertEquals(WidgetCell(88, false), snapshot.left)
        assertEquals(WidgetCell(76, false), snapshot.right)
        assertEquals(WidgetCell(54, false), snapshot.case)
        assertTrue(snapshot.connected)
    }

    @Test fun chargingNeedsALevel() {
        val snapshot = WidgetSnapshot.of("AA:BB", mapOf(8 to level(60, charging = true), 2 to level(null, charging = true)))
        assertEquals(WidgetCell(60, true), snapshot.case)
        assertEquals(WidgetCell(null, false), snapshot.right)
    }

    @Test fun anUnboundAddressLeavesTheWidgetDimmed() {
        val snapshot = WidgetSnapshot.of("", mapOf(4 to level(99)))
        assertFalse(snapshot.connected)
        assertEquals(WidgetCell(99, false), snapshot.left)
    }

    @Test fun levelsOutsideTheRangeReadAsMissing() {
        val snapshot = WidgetSnapshot.of("AA:BB", mapOf(4 to level(120), 2 to level(-1)))
        assertEquals(WidgetCell(null, false), snapshot.left)
        assertEquals(WidgetCell(null, false), snapshot.right)
    }

    @Test fun textCarriesThePercentSign() {
        assertEquals("88%", WidgetCell(88, false).text)
        assertEquals("—", WidgetCell(null, true).text)
        assertEquals("—", WidgetSnapshot.EMPTY.case.text)
    }
}
