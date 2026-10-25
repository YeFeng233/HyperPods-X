package com.hyper.podsrx.device

import com.hyper.podsrx.protocol.Aacp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimeControlTest {
    @Test fun chimeRangeRejectsValuesBelowDeviceMinimumAndAboveMaximum() {
        assertEquals(15..100, Controls.all.single { it.id == 0x1f }.range)
        val current = listOf(80, 80, 0, 0)
        for (value in listOf(0, 9, 10, 13, 14, 101, 255)) {
            assertFalse("Chime volume $value must be rejected",
                Controls.allowed(0x1f, Controls.valuesForWrite(0x1f, value, current)))
        }
        for (value in listOf(15, 16, 19, 20, 22, 40, 59, 60, 100)) {
            assertTrue("Chime volume $value must be accepted",
                Controls.allowed(0x1f, Controls.valuesForWrite(0x1f, value, current)))
        }
    }

    @Test fun boundaryWritesPreserveAllOtherConfigurationBytesThroughEncoding() {
        // Nonzero tail bytes detect accidental padding or overwriting of unrelated fields.
        val current = listOf(80, 80, 7, 9)
        for (value in listOf(15, 100)) {
            val values = Controls.valuesForWrite(0x1f, value, current)
            assertEquals(listOf(value, 80, 7, 9), values)
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, value.toByte(), 80, 7, 9),
                Aacp.control(0x1f, values))
        }
        assertEquals(listOf(80, 80, 7, 9), current)
    }
}
