package com.hyper.podsrx.ui

import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.protocol.Aacp
import org.junit.Assert.*
import org.junit.Test

class ListeningControlsTest {
    private val adaptive = PodsState(connected = true, controls = mapOf(0x0d to listOf(4), 0x2e to listOf(50, 0, 0, 0)))

    @Test fun selectorStartsWithOffThenTransparencyAdaptiveCancellation() {
        assertEquals(listOf(1, 3, 4, 2), Controls.listeningChoices.map { it.value })
        assertEquals(listOf("关闭", "通透", "自适应", "降噪"), Controls.listeningChoices.map { it.label })
    }

    @Test fun offReportsAreNotRemappedToAnotherMode() {
        val ui = listeningPresentation(adaptive.copy(controls = mapOf(0x0d to listOf(1))))
        assertEquals(1, ui.mode)
        assertEquals(1, Controls.listeningChoices.first().value)
        assertTrue(ui.modeEnabled)
        assertFalse(ui.showAdaptive)
        assertFalse(ui.strengthEnabled)
        assertEquals("", ui.notice)
    }

    @Test fun adaptiveSliderOnlyAppearsForReportedAdaptiveMode() {
        for (mode in 1..4) {
            val ui = listeningPresentation(adaptive.copy(controls = adaptive.controls + (0x0d to listOf(mode))))
            assertEquals(mode == 4, ui.showAdaptive)
            assertEquals(mode == 4, ui.strengthEnabled)
        }
    }

    @Test fun missingInvalidAndEmptyModeReportsNeverEnableControls() {
        for (report in listOf(emptyList(), listOf(0), listOf(5), listOf(255))) {
            val ui = listeningPresentation(adaptive.copy(controls = adaptive.controls + (0x0d to report)))
            assertNull(ui.mode)
            assertFalse(ui.modeEnabled)
            assertFalse(ui.showAdaptive)
            assertFalse(ui.strengthEnabled)
        }
    }

    @Test fun missingOrInvalidStrengthIsNotDisplayedAsDefaultFifty() {
        for (report in listOf(emptyList(), listOf(-1), listOf(101), listOf(255))) {
            val ui = listeningPresentation(adaptive.copy(controls = adaptive.controls + (0x2e to report)))
            assertNull(ui.strength)
            assertTrue(ui.showAdaptive)
            assertTrue(ui.modeEnabled)
            assertFalse(ui.strengthEnabled)
            assertTrue(ui.notice.contains("未上报"))
        }
        assertNull(listeningPresentation(adaptive.copy(controls = adaptive.controls - 0x2e)).strength)
    }

    @Test fun sliderUsesDirectAncScaleNotInvertedAllowedNoiseScale() {
        assertEquals(0, adaptiveStrengthAt(0f))
        assertEquals(24, adaptiveStrengthAt(.24f))
        assertEquals(26, adaptiveStrengthAt(.26f))
        assertEquals(50, adaptiveStrengthAt(.5f))
        assertEquals(74, adaptiveStrengthAt(.74f))
        assertEquals(76, adaptiveStrengthAt(.76f))
        assertEquals(100, adaptiveStrengthAt(1f))
        assertEquals(0, adaptiveStrengthAt(-.5f))
        assertEquals(100, adaptiveStrengthAt(1.5f))
        assertEquals(51, adaptiveStrengthAt(.506f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPointerFractionIsRejected() { adaptiveStrengthAt(Float.NaN) }

    @Test fun pendingStrengthKeepsTheSliderEnabledForNewerTargets() {
        val disconnected = listeningPresentation(adaptive.copy(connected = false))
        assertFalse(disconnected.modeEnabled)
        assertFalse(disconnected.strengthEnabled)
        for (id in listOf(0x0d, 0x2e)) {
            val ui = listeningPresentation(adaptive.copy(pending = setOf(id)))
            assertTrue(ui.pending)
            assertFalse(ui.modeEnabled)
            assertEquals(id == 0x2e, ui.strengthEnabled)
            assertEquals(50, ui.strength)
        }
        assertTrue(listeningPresentation(adaptive.copy(pending = setOf(0x28))).strengthEnabled)
    }

    @Test fun reconnectAndUnconfirmedWriteKeepReportedState() {
        val reconnecting = listeningPresentation(adaptive.copy(connected = false, controls = emptyMap(), pending = setOf(0x2e)))
        assertNull(reconnecting.mode)
        assertNull(reconnecting.strength)
        assertFalse(reconnecting.strengthEnabled)
        val timedOut = listeningPresentation(adaptive)
        assertEquals(50, timedOut.strength)
        assertTrue(timedOut.strengthEnabled)
    }

    @Test fun strengthBoundsAreWritableAndOtherControlBytesArePreserved() {
        for (value in 0..100) {
            assertTrue(Controls.allowed(0x2e, listOf(value)))
            assertEquals(value, listeningPresentation(adaptive.copy(controls = adaptive.controls + (0x2e to listOf(value)))).strength)
        }
        assertFalse(Controls.allowed(0x2e, listOf(-1)))
        assertFalse(Controls.allowed(0x2e, listOf(101)))
        assertTrue(Controls.allowed(0x2e, listOf(40)))
        assertTrue(Controls.allowed(0x2e, listOf(60)))
        assertFalse(Controls.allowed(0x2e, emptyList()))
        assertEquals(listOf(20, 80, 0, 0), Controls.valuesForWrite(0x1f, 20, listOf(22, 80, 0, 0)))
    }

    @Test fun adaptivePayloadMatchesLibrePodsSingleByteWithZeroPadding() {
        val values = Controls.valuesForWrite(0x2e, 100, listOf(50, 9, 8, 7))
        assertEquals(listOf(100), values)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 46, 100, 0, 0, 0), Aacp.control(0x2e, values))
    }

    @Test fun continuousPositionsPreserveIntermediateValuesAndStayWithinProtocolBounds() {
        assertEquals((0..100).toSet(), (0..1000).map { adaptiveStrengthAt(it / 1000f) }.toSet())
        for (position in 0..1000) assertTrue(Controls.allowed(0x2e, listOf(adaptiveStrengthAt(position / 1000f))))
        assertEquals("偏通透", adaptiveStrengthLabel(0))
        assertEquals("均衡", adaptiveStrengthLabel(50))
        assertEquals("偏降噪", adaptiveStrengthLabel(100))
        assertEquals("37 / 100", adaptiveStrengthLabel(37))
    }

    @Test fun intermediateAdaptivePayloadDoesNotRetainPreviousTrailingBytes() {
        val values = Controls.valuesForWrite(0x2e, 37, listOf(50, 9, 8, 7))
        assertEquals(listOf(37), values)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 46, 37, 0, 0, 0), Aacp.control(0x2e, values))
    }

    @Test fun returningToReportedStrengthReplacesAnUnconfirmedTarget() {
        assertFalse(Controls.shouldSendAdaptive(50,50,null))
        assertTrue(Controls.shouldSendAdaptive(37,50,null))
        assertFalse(Controls.shouldSendAdaptive(37,50,37))
        assertTrue(Controls.shouldSendAdaptive(71,50,37))
        assertTrue(Controls.shouldSendAdaptive(50,50,37))
        assertFalse(Controls.shouldSendAdaptive(50,50,50))
    }

    @Test fun sentStrengthNeverReplacesTheReportedDeviceControl() {
        for(pending in listOf(emptySet(),setOf(0x2e))){
            val state=adaptive.copy(adaptiveTarget=37,pending=pending)
            val ui=listeningPresentation(state)
            assertEquals(50,ui.strength)
            assertEquals(37,state.adaptiveTarget)
            assertTrue(ui.strengthEnabled)
            assertEquals(pending.isNotEmpty(),ui.pending)
        }
    }
}
