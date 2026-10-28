package com.hyper.podsrx.ui.ios

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
        assertEquals(listOf(1, 3, 4, 2), IosListeningChoices.map { it.value })
        assertEquals(listOf("关闭", "通透模式", "自适应", "降噪"), IosListeningChoices.map { it.label })
    }

    @Test fun offReportsAreNotRemappedToAnotherMode() {
        val ui = listeningPresentation(adaptive.copy(controls = mapOf(0x0d to listOf(1))))
        assertEquals(1, ui.mode)
        assertTrue(IosListeningChoices.any { it.value == ui.mode })
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

    @Test fun continuousSliderPreservesEveryPercentForAllModels() {
        assertEquals(listOf(0, 50, 100), IosAdaptiveAnchors)
        assertEquals("较多噪声", adaptiveStrengthLabel(0))
        assertEquals("默认", adaptiveStrengthLabel(50))
        assertEquals("较少噪声", adaptiveStrengthLabel(100))
        for (raw in 0..100) {
            val fraction = raw / 100f
            assertEquals(fraction, adaptiveStrengthFraction(raw))
            assertEquals(raw, adaptiveStrengthForWrite(fraction))
        }
        assertEquals((0..100).toSet(), (0..1000).map { adaptiveStrengthForWrite(it / 1000f) }.toSet())
        for ((fraction, expected) in listOf(-1f to 0, .374f to 37, .375f to 38, .714f to 71, .995f to 100, 2f to 100)) {
            assertEquals(expected, adaptiveStrengthForWrite(fraction))
        }
        for (raw in listOf(null, -1, 101)) assertNull(adaptiveStrengthFraction(raw))
        for (fraction in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertNull(adaptiveStrengthForWrite(fraction))
        }
        for (model in listOf("A3441", "A3057", "A3442", "")) {
            for (value in listOf(37, 71)) {
                val state = adaptive.copy(model = model, controls = adaptive.controls + (0x2e to listOf(value)))
                assertEquals(value, listeningPresentation(state).strength)
                assertEquals(value, adaptiveStrengthForWrite(value / 100f))
            }
        }
    }

    @Test fun intermediateAdaptivePayloadDoesNotRetainPreviousTrailingBytes() {
        for (value in listOf(37, 71)) {
            val values = Controls.valuesForWrite(0x2e, value, listOf(50, 9, 8, 7))
            assertEquals(listOf(value), values)
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 46, value.toByte(), 0, 0, 0), Aacp.control(0x2e, values))
        }
    }

    @Test fun returningToReportedStrengthReplacesAnUnconfirmedTarget() {
        assertFalse(Controls.shouldSendAdaptive(50, 50, null))
        assertTrue(Controls.shouldSendAdaptive(37, 50, null))
        assertFalse(Controls.shouldSendAdaptive(37, 50, 37))
        assertTrue(Controls.shouldSendAdaptive(71, 50, 37))
        assertTrue(Controls.shouldSendAdaptive(50, 50, 37))
        assertFalse(Controls.shouldSendAdaptive(50, 50, 50))
        val first = requestListeningStrength(ListeningIntentState(), 37)
        assertEquals(first, requestListeningStrength(first, 37))
        val returned = requestListeningStrength(first, 50)
        assertEquals(50, listeningPresentation(adaptive, intent = returned).strength)
        val state = adaptive.copy(adaptiveTarget = 37)
        val intent = syncListeningIntent(returned, state)
        assertEquals(50, intent.strength?.value)
        assertEquals(50, nextListeningTarget(intent, state)?.value)
    }

    @Test fun presentationPrefersLatestIntentThenSentTargetWithoutChangingReports() {
        for (pending in listOf(emptySet(), setOf(0x2e))) {
            val state = adaptive.copy(adaptiveTarget = 37, pending = pending)
            val ui = listeningPresentation(state)
            assertEquals(37, ui.strength)
            assertEquals(50, Controls.adaptiveStrength(state.controls))
            assertTrue(ui.strengthEnabled)
            assertEquals(pending.isNotEmpty(), ui.pending)
            val latest = requestListeningStrength(ListeningIntentState(), 71)
            assertEquals(71, listeningPresentation(state, intent = latest).strength)
        }
        assertEquals(50, listeningPresentation(adaptive.copy(adaptiveTarget = 101)).strength)
    }

    @Test fun adaptiveIntentProjectsImmediatelyAndQueuesDefaultOnlyAfterModeAck() {
        val original = adaptive.copy(controls = adaptive.controls + (0x0d to listOf(3)) + (0x2e to listOf(37)))
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
        val sending = modeAck.copy(adaptiveTarget = 50, pending = setOf(0x2e))
        intent = syncListeningIntent(intent, sending)
        assertNotNull(intent.inFlight)
        val sent = sending.copy(pending = emptySet())
        intent = syncListeningIntent(intent, sent)
        assertFalse(intent.hasWork)
        assertEquals(50, listeningPresentation(sent, intent = intent).strength)
        assertEquals(37, Controls.adaptiveStrength(sent.controls))
        assertNull(nextListeningTarget(intent, sent))
        assertEquals(intent, expireListeningTarget(intent, strength, sent))
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

    @Test fun rapidStrengthChangesKeepTheLatestProjectionAcrossOlderSendCompletion() {
        var intent = requestListeningStrength(ListeningIntentState(), 37)
        val first = nextListeningTarget(intent, adaptive)!!
        intent = startListeningTarget(intent, first)
        val sendingFirst = adaptive.copy(adaptiveTarget = 37, pending = setOf(0x2e))
        intent = syncListeningIntent(intent, sendingFirst)
        intent = requestListeningStrength(intent, 71)
        assertEquals(71, listeningPresentation(sendingFirst, intent = intent).strength)
        assertNull(nextListeningTarget(intent, sendingFirst))
        val sentFirst = sendingFirst.copy(pending = emptySet())
        intent = syncListeningIntent(intent, sentFirst)
        assertEquals(71, listeningPresentation(sentFirst, intent = intent).strength)
        val second = nextListeningTarget(intent, sentFirst)!!
        assertEquals(71, second.value)
        intent = startListeningTarget(intent, second)
        intent = syncListeningIntent(intent, sentFirst.copy(adaptiveTarget = 71, pending = setOf(0x2e)))
        intent = requestListeningStrength(intent, 50)
        val sentSecond = adaptive.copy(adaptiveTarget = 71)
        intent = syncListeningIntent(intent, sentSecond)
        assertEquals(50, listeningPresentation(sentSecond, intent = intent).strength)
        val last = nextListeningTarget(intent, sentSecond)!!
        assertEquals(50, last.value)
        intent = startListeningTarget(intent, last)
        assertEquals(intent, expireListeningTarget(intent, first, sentSecond))
        intent = syncListeningIntent(intent, adaptive.copy(adaptiveTarget = 50))
        assertFalse(intent.hasWork)
        assertEquals(50, listeningPresentation(adaptive.copy(adaptiveTarget = 50), intent = intent).strength)
    }

    @Test fun modeChangesWaitForTheCurrentStrengthWriteButNotItsDeviceEcho() {
        var intent = requestListeningStrength(ListeningIntentState(), 37)
        val strength = nextListeningTarget(intent, adaptive)!!
        intent = startListeningTarget(intent, strength)
        val sending = adaptive.copy(adaptiveTarget = 37, pending = setOf(0x2e))
        intent = syncListeningIntent(intent, sending)
        intent = requestListeningMode(intent, 3)
        assertEquals(3, listeningPresentation(sending, intent = intent).mode)
        assertNull(nextListeningTarget(intent, sending))
        val sent = sending.copy(pending = emptySet())
        intent = syncListeningIntent(intent, sent)
        assertEquals(3, nextListeningTarget(intent, sent)?.value)
        assertEquals(50, Controls.adaptiveStrength(sent.controls))
    }

    @Test fun strengthFailureRollsBackOnlyTheLatestRequestAndRetryClearsItsNotice() {
        var intent = requestListeningStrength(ListeningIntentState(), 37)
        val first = nextListeningTarget(intent, adaptive)!!
        intent = startListeningTarget(intent, first)
        intent = syncListeningIntent(intent, adaptive.copy(adaptiveTarget = 37, pending = setOf(0x2e)))
        intent = requestListeningStrength(intent, 71)
        val failed = adaptive.copy(message = "设置发送失败：控制通道未连接")
        intent = syncListeningIntent(intent, failed)
        assertEquals(71, listeningPresentation(failed, intent = intent).strength)
        assertEquals("", intent.strengthFailure)
        val latest = nextListeningTarget(intent, failed)!!
        intent = startListeningTarget(intent, latest, failed.message)
        intent = syncListeningIntent(intent, failed)
        assertNotNull(intent.inFlight)
        intent = syncListeningIntent(intent, adaptive.copy(adaptiveTarget = 71, pending = setOf(0x2e)))
        intent = syncListeningIntent(intent, failed)
        assertEquals(50, listeningPresentation(failed, intent = intent).strength)
        assertTrue(intent.strengthFailure.isNotBlank())
        assertEquals("", requestListeningStrength(intent, 83).strengthFailure)
    }

    @Test fun modeReadbackReconnectRetainsQueuedStrengthButRealDisconnectRollsBack() {
        val original = adaptive.copy(controls = adaptive.controls + (0x0d to listOf(3)))
        var intent = requestListeningMode(ListeningIntentState(), 4)
        val mode = nextListeningTarget(intent, original)!!
        intent = startListeningTarget(intent, mode)
        val reading = original.copy(connected = false, controls = emptyMap(), pending = setOf(0x0d))
        intent = syncListeningIntent(intent, reading)
        assertEquals(4, listeningPresentation(reading, intent = intent).mode)
        assertEquals(50, listeningPresentation(reading, intent = intent).strength)
        assertNotNull(intent.inFlight)
        val disconnected = reading.copy(pending = emptySet(), adaptiveTarget = null)
        intent = syncListeningIntent(intent, disconnected)
        assertFalse(intent.hasWork)
        assertFalse(listeningPresentation(disconnected, intent = intent).modeEnabled)
        assertTrue(intent.modeFailure.isNotBlank())
    }

    @Test fun stalledStrengthWriteExpiryShowsReportedValueUntilRetry() {
        var intent = requestListeningStrength(ListeningIntentState(), 37)
        val target = nextListeningTarget(intent, adaptive)!!
        intent = startListeningTarget(intent, target)
        val stalled = adaptive.copy(adaptiveTarget = 37, pending = setOf(0x2e))
        intent = syncListeningIntent(intent, stalled)
        intent = expireListeningTarget(intent, target, stalled)
        val failed = listeningPresentation(stalled, intent = intent)
        assertEquals(50, failed.strength)
        assertTrue(failed.strengthNotice.isNotBlank())
        intent = requestListeningStrength(intent, 71)
        val retry = listeningPresentation(stalled, intent = intent)
        assertEquals(71, retry.strength)
        assertEquals("", retry.strengthNotice)
    }

    @Test fun immediateDebugAckAndIdleExternalReportsRemainTheDisplayedTruth() {
        var intent = requestListeningStrength(ListeningIntentState(), 37)
        val target = nextListeningTarget(intent, adaptive)!!
        intent = startListeningTarget(intent, target)
        val ack = adaptive.copy(controls = adaptive.controls + (0x2e to listOf(37)))
        intent = syncListeningIntent(intent, ack)
        assertFalse(intent.hasWork)
        val updated = ack.copy(controls = ack.controls + (0x0d to listOf(3)) + (0x2e to listOf(71)))
        val ui = listeningPresentation(updated, intent = syncListeningIntent(intent, updated))
        assertEquals(3, ui.mode)
        assertEquals(71, ui.strength)
        assertNull(nextListeningTarget(intent, updated))
    }
}
