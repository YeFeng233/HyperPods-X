package com.hyper.podsrx.ui

import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.protocol.Aacp
import com.hyper.podsrx.protocol.Event
import com.hyper.podsrx.protocol.WearState
import org.junit.Assert.*
import org.junit.Test

class ListeningControlsTest {
    private val adaptive = PodsState(connected = true, controls = mapOf(0x0d to listOf(4), 0x2e to listOf(50, 0, 0, 0)))

    @Test fun selectorMatchesIosFourModeOrder() {
        assertEquals(listOf(1, 3, 4, 2), Controls.listeningChoices.map { it.value })
        assertEquals(listOf("关闭", "通透模式", "自适应", "降噪"), Controls.listeningChoices.map { it.label })
    }

    @Test fun offReportsAreNotRemappedToAnotherMode() {
        val ui = listeningPresentation(adaptive.copy(controls = mapOf(0x0d to listOf(1))))
        assertEquals(1, ui.mode)
        assertTrue(Controls.listeningChoices.any { it.value == ui.mode })
        assertTrue(ui.modeEnabled)
        assertFalse(ui.showAdaptive)
        assertFalse(ui.strengthEnabled)
        assertEquals("", ui.modeNotice)
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
            assertEquals("", ui.modeNotice)
            assertTrue(ui.strengthNotice.contains("未上报"))
        }
        assertNull(listeningPresentation(adaptive.copy(controls = adaptive.controls - 0x2e)).strength)
    }

    @Test fun pendingReadbackKeepsControlsInteractiveWhileIdleDisconnectDisablesThem() {
        val disconnected = listeningPresentation(adaptive.copy(connected = false))
        assertFalse(disconnected.modeEnabled)
        assertFalse(disconnected.strengthEnabled)
        assertFalse(disconnected.canSelectMode(4))
        for (id in listOf(0x0d, 0x2e)) {
            val ui = listeningPresentation(adaptive.copy(pending = setOf(id)))
            assertTrue(ui.pending)
            assertTrue(ui.modeEnabled)
            assertTrue(ui.strengthEnabled)
            assertTrue(ui.canSelectMode(4))
            assertEquals(50, ui.strength)
        }
        assertTrue(listeningPresentation(adaptive.copy(pending = setOf(0x28))).strengthEnabled)
    }

    @Test fun staleWearReportsDoNotBlockAnyValidModeRequest() {
        val transparency = adaptive.copy(controls = adaptive.controls + (0x0d to listOf(3)))
        val insufficient = listOf(
            Event.Wearing(WearState.OUT_OF_EAR, WearState.IN_CASE),
            Event.Wearing(WearState.IN_EAR, WearState.OUT_OF_EAR),
            Event.Wearing(WearState.DISCONNECTED, WearState.IN_EAR),
        )
        for (wearing in insufficient) {
            val state = transparency.copy(wearing = wearing)
            val ui = listeningPresentation(state)
            assertEquals(3, ui.mode)
            assertTrue(ui.modeEnabled)
            for (mode in 1..4) assertTrue(ui.canSelectMode(mode))
            assertFalse(ui.canSelectMode(0))
            assertFalse(ui.canSelectMode(5))
            assertEquals("", ui.modeNotice)
        }
        for (wearing in listOf(null, Event.Wearing(WearState.IN_EAR, WearState.IN_EAR))) {
            val ui = listeningPresentation(transparency.copy(wearing = wearing))
            assertEquals(3, ui.mode)
            assertTrue(ui.canSelectMode(4))
            assertEquals("", ui.modeNotice)
        }
    }

    @Test fun captionNoticesReplaceOnlyTheAffectedSelectorLabels() {
        val missingStrength = listeningPresentation(adaptive.copy(controls = adaptive.controls - 0x2e))
        assertEquals("", missingStrength.modeNotice)
        assertTrue(missingStrength.strengthNotice.contains("未上报"))
        val otherMode = listeningPresentation(adaptive.copy(controls = adaptive.controls + (0x0d to listOf(2))))
        assertEquals("", otherMode.modeNotice)
        assertEquals("使用自适应音频后可调整幅度", otherMode.strengthNotice)
        val pending = listeningPresentation(adaptive.copy(pending = setOf(0x2e)))
        assertEquals("", pending.modeNotice)
        assertEquals("", pending.strengthNotice)
        val readback = listeningPresentation(adaptive.copy(connected = false, pending = setOf(0x0d)))
        assertTrue(readback.modeEnabled)
        assertEquals("", readback.modeNotice)
        val disconnected = listeningPresentation(adaptive.copy(connected = false))
        assertEquals("连接耳机后可切换聆听模式", disconnected.modeNotice)
        assertEquals(disconnected.modeNotice, disconnected.strengthNotice)
        val unknownMode = listeningPresentation(adaptive.copy(controls = adaptive.controls - 0x0d))
        assertEquals("等待读取耳机支持的聆听模式", unknownMode.modeNotice)
        assertEquals(unknownMode.modeNotice, unknownMode.strengthNotice)
    }

    @Test fun confirmationFailureDoesNotInferAdaptiveWearRequirements() {
        val state = adaptive.copy(
            controls = adaptive.controls + (0x0d to listOf(3)),
            message = "聆听模式未确认目标值，已保留设备回读结果",
        )
        for (wearing in listOf(null,
            Event.Wearing(WearState.IN_EAR, WearState.OUT_OF_EAR),
            Event.Wearing(WearState.IN_EAR, WearState.IN_EAR))) {
            assertEquals("聆听模式未能更新，请重试。", listeningPresentation(state.copy(wearing = wearing)).modeNotice)
        }
        assertEquals("", listeningPresentation(state.copy(message = "")).modeNotice)
        assertEquals("", listeningPresentation(state.copy(pending = setOf(0x0d))).modeNotice)
        val strengthFailure = listeningPresentation(adaptive.copy(message = "自适应降噪幅度未确认目标值，已保留设备回读结果"))
        assertEquals("", strengthFailure.modeNotice)
        assertEquals("自适应音频未能更新，请重试。", strengthFailure.strengthNotice)
    }

    @Test fun expandedAdaptiveHidesOffAndOtherModesRestoreTheReportedPolicy() {
        for (mode in 1..4) {
            val controls = adaptive.controls + (0x0d to listOf(mode))
            for (permission in listOf(null, emptyList(), listOf(0), listOf(1))) {
                val reports = if (permission == null) controls else controls + (0x34 to permission)
                val ui = listeningPresentation(adaptive.copy(controls = reports))
                assertEquals(if (mode == 4) setOf(1) else emptySet<Int>(), ui.hiddenModes)
                assertEquals(mode != 4, ui.canSelectMode(1))
            }
            val unavailable = listeningPresentation(adaptive.copy(controls = controls + (0x34 to listOf(2))))
            assertEquals(setOf(1), unavailable.hiddenModes)
            assertFalse(unavailable.canSelectMode(1))
            for (available in listOf(2, 3, 4)) assertTrue(unavailable.canSelectMode(available))
        }
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
        assertFalse(Controls.allowed(0x2e, emptyList()))
        assertEquals(listOf(20, 80, 0, 0), Controls.valuesForWrite(0x1f, 20, listOf(22, 80, 0, 0)))
    }

    @Test fun adaptivePayloadMatchesLibrePodsSingleByteWithZeroPadding() {
        val values = Controls.valuesForWrite(0x2e, 100, listOf(50, 9, 8, 7))
        assertEquals(listOf(100), values)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 46, 100, 0, 0, 0), Aacp.control(0x2e, values))
    }

    @Test fun adaptiveSliderPreservesContinuousReportsAndProtocolValues() {
        assertEquals(listOf(0, 50, 100), Controls.adaptiveStrengthAnchors)
        assertEquals("较多噪声", adaptiveStrengthLabel(0))
        assertEquals("默认", adaptiveStrengthLabel(50))
        assertEquals("较少噪声", adaptiveStrengthLabel(100))
        for (raw in 0..100) {
            val fraction = raw / 100f
            assertEquals(fraction, adaptiveStrengthFraction(raw))
            assertEquals(if (raw >= 99) 100 else raw / 10 * 10, adaptiveStrengthForWrite(fraction))
        }
        for (raw in listOf(null, -1, 101)) assertNull(adaptiveStrengthFraction(raw))
        for (position in -100..1100) {
            val raw = adaptiveStrengthForWrite(position / 1000f)!!
            assertTrue(Controls.allowed(0x2e, listOf(raw)))
            assertEquals(0, raw % 10)
        }
        for ((fraction, expected) in listOf(0f to 0, .399f to 30, .4f to 40, .989f to 90, .99f to 100)) {
            assertEquals(expected, adaptiveStrengthForWrite(fraction))
        }
        assertEquals(0, adaptiveStrengthForWrite(-1f))
        assertEquals(100, adaptiveStrengthForWrite(2f))
        for (fraction in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertNull(adaptiveStrengthForWrite(fraction))
        }
    }

    @Test fun adaptiveIntentProjectsImmediatelyAndQueuesDefaultOnlyAfterModeAck() {
        val original = adaptive.copy(controls = adaptive.controls + (0x0d to listOf(3)) + (0x2e to listOf(40)))
        val idle = ListeningIntentState()
        assertNull(nextListeningTarget(idle, original))
        var intent = requestListeningMode(idle, 4)
        val ui = listeningPresentation(original, intent = intent)
        assertEquals(4, ui.mode)
        assertEquals(50, ui.strength)
        assertTrue(ui.modeEnabled)
        assertTrue(ui.strengthEnabled)
        assertEquals(setOf(1), ui.hiddenModes)
        val mode = nextListeningTarget(intent, original)!!
        assertEquals(0x0d, mode.control)
        intent = startListeningTarget(intent, mode)
        intent = syncListeningIntent(intent, original.copy(pending = setOf(0x0d)))
        val modeAck = original.copy(controls = original.controls + (0x0d to listOf(4)))
        intent = syncListeningIntent(intent, modeAck)
        val strength = nextListeningTarget(intent, modeAck)!!
        assertEquals(0x2e, strength.control)
        assertEquals(50, strength.value)
        intent = startListeningTarget(intent, strength)
        val strengthAck = modeAck.copy(controls = modeAck.controls + (0x2e to listOf(50)))
        intent = syncListeningIntent(intent, strengthAck)
        assertFalse(intent.hasWork)
        assertNull(nextListeningTarget(intent, strengthAck))
        assertNull(nextListeningTarget(idle, modeAck))
    }

    @Test fun rapidModeIntentKeepsLatestProjectionAcrossOlderAckAndFailure() {
        val original = adaptive.copy(controls = adaptive.controls + (0x0d to listOf(1)))
        var intent = requestListeningMode(ListeningIntentState(), 3)
        val first = nextListeningTarget(intent, original)!!
        intent = startListeningTarget(intent, first)
        intent = syncListeningIntent(intent, original.copy(pending = setOf(0x0d)))
        intent = requestListeningMode(intent, 2)
        assertEquals(2, listeningPresentation(original, intent = intent).mode)
        val failedFirst = syncListeningIntent(intent, original.copy(message = "聆听模式未确认目标值，已保留设备回读结果"))
        assertEquals(2, listeningPresentation(original, intent = failedFirst).mode)
        assertEquals("", failedFirst.modeFailure)
        val firstAck = original.copy(controls = original.controls + (0x0d to listOf(3)))
        intent = syncListeningIntent(intent, firstAck)
        assertEquals(2, listeningPresentation(firstAck, intent = intent).mode)
        val second = nextListeningTarget(intent, firstAck)!!
        assertEquals(2, second.value)
        intent = startListeningTarget(intent, second)
        assertEquals(intent, expireListeningTarget(intent, first, firstAck))
        intent = expireListeningTarget(intent, second, firstAck)
        assertEquals(3, listeningPresentation(firstAck, intent = intent).mode)
        assertTrue(intent.modeFailure.isNotBlank())
        assertEquals("", requestListeningMode(intent, 2).modeFailure)
    }

    @Test fun knownA3441UsesDiscreteUiWithoutChangingActualReportsOrProtocolRange() {
        val known = listeningPresentation(adaptive.copy(model = "A3441", controls = adaptive.controls + (0x2e to listOf(40))))
        assertTrue(known.adaptiveDiscrete)
        assertEquals(40, known.strength)
        assertTrue(Controls.allowed(0x2e, listOf(40)))
        for (model in listOf("", "A3057", "A3442")) {
            assertFalse(listeningPresentation(adaptive.copy(model = model, name = "AirPods 5 A3441")).adaptiveDiscrete)
        }
        for (position in -100..1100) {
            assertTrue(adaptiveStrengthForWrite(position / 1000f, true) in listOf(0, 50, 100))
        }
        for ((fraction, expected) in listOf(0f to 0, .309f to 0, .31f to 50, .4f to 50, .7f to 50, .701f to 100, 1f to 100)) {
            assertEquals(expected, adaptiveStrengthForWrite(fraction, true))
        }
        assertEquals(40, adaptiveStrengthForWrite(.4f))
    }

    @Test fun strengthIntentCoalescesAndSurvivesPendingReadbackUntilLatestFailure() {
        var intent = requestListeningStrength(ListeningIntentState(), 40)
        val first = nextListeningTarget(intent, adaptive)!!
        intent = startListeningTarget(intent, first)
        val reading = adaptive.copy(connected = false, controls = emptyMap(), pending = setOf(0x2e))
        intent = syncListeningIntent(intent, reading)
        intent = requestListeningStrength(intent, 100)
        assertEquals(100, listeningPresentation(reading, intent = intent).strength)
        val visibleReadback = reading.copy(controls = adaptive.controls)
        assertTrue(listeningPresentation(visibleReadback, intent = intent).strengthEnabled)
        val oldAck = adaptive.copy(controls = adaptive.controls + (0x2e to listOf(40)))
        intent = syncListeningIntent(intent, oldAck)
        assertEquals(100, listeningPresentation(oldAck, intent = intent).strength)
        val latest = nextListeningTarget(intent, oldAck)!!
        intent = startListeningTarget(intent, latest)
        intent = syncListeningIntent(intent, oldAck.copy(pending = setOf(0x2e)))
        intent = syncListeningIntent(intent, oldAck.copy(message = "自适应降噪幅度未确认目标值，已保留设备回读结果"))
        assertEquals(40, listeningPresentation(oldAck, intent = intent).strength)
        assertTrue(intent.strengthFailure.isNotBlank())
        assertEquals("", requestListeningStrength(intent, 100).strengthFailure)
    }

    @Test fun idleExternalReportsRemainTheDisplayedTruth() {
        val idle = ListeningIntentState()
        val updated = adaptive.copy(controls = adaptive.controls + (0x0d to listOf(3)) + (0x2e to listOf(40)))
        val ui = listeningPresentation(updated, intent = syncListeningIntent(idle, updated))
        assertEquals(3, ui.mode)
        assertEquals(40, ui.strength)
        assertNull(nextListeningTarget(idle, updated))
    }

}
