package com.hyper.podsrx.protocol

import com.hyper.podsrx.protocol.ControlCommands.Admission
import com.hyper.podsrx.protocol.ControlCommands.Command
import com.hyper.podsrx.protocol.ControlCommands.Rejection
import com.hyper.podsrx.protocol.ControlCommands.Send
import kotlin.test.*

class ControlCommandsTest {
    private val log = mutableListOf<String>()
    // Stands in for the app's Controls.allowed: a strength range and a four-way mode choice.
    private val commands = ControlCommands({ id, values -> values.first() in (if (id == 0x2e) 0..100 else 1..4) }, object : ControlCommands.Effects {
        override fun accepted(command: Command) { log += "accepted ${command.id}" }
        override fun completed(command: Command) { log += "completed ${command.id}" }
        override fun adaptiveSent() { log += "adaptiveSent" }
        override fun adaptiveRevoked() { log += "adaptiveRevoked" }
    })

    private val adaptive = mapOf(0x0d to listOf(4), 0x2e to listOf(50))
    private fun view(
        controls: Map<Int, List<Int>> = adaptive, pending: Set<Int> = emptySet(),
        connected: Boolean = true, readingBack: Boolean = false, equalizer: List<Int>? = listOf(1, 50, 50, 50)
    ) = ControlCommands.View(connected, readingBack, controls, pending, equalizer)

    private fun accept(id: Int, values: List<Int>, view: ControlCommands.View = view()): Command =
        (commands.enqueue(id, values, view) as Admission.Accepted).command

    @Test fun admissionNeedsAConnectedSettledChannel() {
        assertEquals(Admission.Rejected(Rejection.NOT_CONNECTED), commands.enqueue(0x0d, listOf(2), view(connected = false)))
        assertEquals(Admission.Rejected(Rejection.READING_BACK), commands.enqueue(0x0d, listOf(2), view(readingBack = true)))
        assertTrue(log.isEmpty())
    }

    @Test fun strengthNeedsAdaptiveModeAndAReportedStrength() {
        val noStrength = mapOf(0x0d to listOf(4))
        assertEquals(Admission.Rejected(Rejection.ADAPTIVE_NOT_READY), commands.enqueue(0x2e, listOf(30), view(noStrength)))
        assertEquals(Admission.Rejected(Rejection.ADAPTIVE_NOT_READY),
            commands.enqueue(0x2e, listOf(30), view(mapOf(0x0d to listOf(2), 0x2e to listOf(50)))))
        assertEquals(Admission.Rejected(Rejection.ADAPTIVE_NOT_READY), commands.enqueue(0x2e, listOf(30), view(pending = setOf(0x0d))))
    }

    @Test fun modeWaitsForAStrengthInFlight() {
        assertEquals(Admission.Rejected(Rejection.MODE_WHILE_ADAPTIVE), commands.enqueue(0x0d, listOf(2), view(pending = setOf(0x2e))))
    }

    @Test fun valuesAreJudgedPerControl() {
        // The injected rule and a reported control are both required.
        assertEquals(Admission.Rejected(Rejection.NOT_ALLOWED), commands.enqueue(0x0d, listOf(9), view()))
        assertEquals(Admission.Rejected(Rejection.NOT_ALLOWED), commands.enqueue(0x1b, listOf(1), view()))
        // Equalizer: state 1..2, bands 0..100, and only after the headset reported one.
        assertIs<Admission.Accepted>(commands.enqueue(256, listOf(2, 0, 100, 50), view()))
        assertEquals(Admission.Rejected(Rejection.NOT_ALLOWED), commands.enqueue(256, listOf(3, 0, 0, 0), view()))
        assertEquals(Admission.Rejected(Rejection.NOT_ALLOWED), commands.enqueue(256, listOf(1, 0, 0, 0), view(equalizer = null)))
        // Call control: four bytes, second one 2 or 3, on a headset that reports it that way.
        val call = adaptive + (0x24 to listOf(0, 3, 0, 0))
        assertIs<Admission.Accepted>(commands.enqueue(0x24, listOf(0, 2, 0, 0), view(call)))
        assertEquals(Admission.Rejected(Rejection.NOT_ALLOWED), commands.enqueue(0x24, listOf(0, 4, 0, 0), view(call)))
    }

    @Test fun aSecondWishWaitsForTheFirst() {
        val first = accept(0x0d, listOf(2))
        assertEquals(Admission.Unchanged, commands.enqueue(0x0d, listOf(3), view()))
        assertEquals(2, commands.desiredValue(0x0d))
        assertTrue(commands.isCurrent(first))
    }

    @Test fun strengthReplacesTheTargetButSkipsNoOps() {
        // Already reported, nothing in flight.
        assertEquals(Admission.Unchanged, commands.enqueue(0x2e, listOf(50), view()))
        val first = accept(0x2e, listOf(30))
        assertEquals(Admission.Unchanged, commands.enqueue(0x2e, listOf(30), view()))
        val second = accept(0x2e, listOf(70))
        assertFalse(commands.isCurrent(first))
        assertTrue(commands.isCurrent(second))
        assertEquals(Send.STALE, commands.beforeSend(first, adaptive))
    }

    @Test fun strengthOutsideAdaptiveModeIsSettledUnsent() {
        val command = accept(0x2e, listOf(30))
        assertEquals(Send.NOT_ADAPTIVE, commands.beforeSend(command, mapOf(0x0d to listOf(2))))
        assertNull(commands.desiredValue(0x2e))
        assertEquals(listOf("accepted 46", "completed 46"), log)
    }

    @Test fun strengthIsNotAwaitedOtherWritesAre() {
        val strength = accept(0x2e, listOf(30))
        assertEquals(Send.SEND, commands.beforeSend(strength, adaptive))
        assertFalse(commands.sent(strength))
        assertTrue("adaptiveSent" in log)
        // The target stays wanted until the mode changes or a newer value replaces it.
        assertTrue(commands.isCurrent(strength))
        val mode = accept(0x0d, listOf(2))
        assertTrue(commands.sent(mode))
    }

    @Test fun matchingReportCompletesByPrefix() {
        val command = accept(0x0d, listOf(3))
        val other = commands.onControl(0x0d, listOf(2, 0, 0, 0))
        assertFalse(other.matched)
        assertTrue(commands.isCurrent(command))
        val report = commands.onControl(0x0d, listOf(3, 0, 0, 0))
        assertEquals(command, report.expected)
        assertTrue(report.matched)
        assertFalse(commands.isCurrent(command))
        // The timeout finds nothing left to settle.
        assertFalse(commands.complete(command))
    }

    @Test fun leavingAdaptiveRevokesTheStrength() {
        val strength = accept(0x2e, listOf(30))
        commands.onControl(0x0d, listOf(2))
        assertFalse(commands.isCurrent(strength))
        assertTrue("adaptiveRevoked" in log)
        log.clear()
        commands.onControl(0x0d, listOf(4))
        assertTrue(log.isEmpty())
    }

    @Test fun equalizerCompletesOnAnExactReport() {
        val command = accept(256, listOf(2, 10, 20, 30))
        commands.onEqualizer(listOf(2, 10, 20, 31))
        assertTrue(commands.isCurrent(command))
        commands.onEqualizer(listOf(2, 10, 20, 30))
        assertFalse(commands.isCurrent(command))
    }

    @Test fun onlyOneReadbackRunsAndOnlyForTheCurrentWrite() {
        val mode = accept(0x0d, listOf(2))
        val strength = accept(0x2e, listOf(30))
        assertFalse(commands.claimReadback(strength))
        assertTrue(commands.claimReadback(mode))
        assertFalse(commands.claimReadback(mode))
        commands.releaseReadback()
        assertTrue(commands.claimReadback(mode))
        commands.releaseReadback()
        commands.complete(mode)
        assertFalse(commands.claimReadback(mode))
    }

    @Test fun readbackSessionsAreTrackedAcrossReconnects() {
        assertFalse(commands.opened())
        commands.prepareReadback(session = 7)
        assertTrue(commands.opened())
        assertFalse(commands.opened())
        assertFalse(commands.endsReadback(0x0d, 7))
        assertTrue(commands.endsReadback(0x0d, 8))
        assertFalse(commands.endsReadback(0x0a, 8))
    }

    @Test fun disconnectKeepsWishesOnlyForAReadback() {
        val mode = accept(0x0d, listOf(2))
        val strength = accept(0x2e, listOf(30))
        commands.prepareReadback(session = 1)
        var preserved: Boolean? = null
        commands.disconnected { preserved = it }
        assertEquals(true, preserved)
        assertTrue(commands.isCurrent(mode))
        assertFalse(commands.isCurrent(strength))
        commands.opened()
        commands.disconnected { preserved = it }
        assertEquals(false, preserved)
        assertFalse(commands.isCurrent(mode))
    }
}
