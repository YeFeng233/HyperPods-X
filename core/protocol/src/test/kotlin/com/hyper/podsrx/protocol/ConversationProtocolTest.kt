package com.hyper.podsrx.protocol

import kotlin.test.*

class ConversationProtocolTest {
    private fun report(subtype: Int = 1, status: Int = 1): ByteArray =
        Aacp.header + byteArrayOf(0x4b, 0, 2, 0, subtype.toByte(), status.toByte())

    @Test fun reportsPreserveRawUnsignedSubtypeAndStatus() {
        for (status in listOf(0, 1, 2, 3, 6, 8, 9, 255)) {
            assertEquals(Event.Conversation(1, status), Aacp.decode(report(status = status)))
        }
        assertEquals(Event.Conversation(255, 128), Aacp.decode(report(255, 128)))
    }

    @Test fun decoderRejectsShortCorruptAndTrailingData() {
        val packet = report()
        for (length in 0 until packet.size) assertNull(Aacp.decode(packet.copyOf(length)))
        assertNull(Aacp.decode(packet + byteArrayOf(0)))
        assertNull(Aacp.decode(packet.copyOf().also { it[0] = 0 }))
        assertNull(Aacp.decode(packet.copyOf().also { it[5] = 1 }))
        assertNull(Aacp.decode(packet.copyOf().also { it[6] = 1 }))
        assertNull(Aacp.decode(packet.copyOf().also { it[7] = 1 }))
    }

    @Test fun everySplitAndSingleByteReadsKeepTheWholeReport() {
        val packet = report(status = 8)
        for (split in 1 until packet.size) {
            val framer = AacpFramer()
            assertTrue(framer.feed(packet.copyOf(split)).isEmpty())
            assertContentEquals(packet, framer.feed(packet.copyOfRange(split, packet.size)).single())
        }
        val framer = AacpFramer()
        val frames = packet.flatMap { framer.feed(byteArrayOf(it)) }
        assertEquals(1, frames.size)
        assertEquals(Event.Conversation(1, 8), Aacp.decode(frames.single()))
    }

    @Test fun conversationAndExistingReportsCanShareARead() {
        val conversation = report()
        val battery = Aacp.header + byteArrayOf(4, 0, 1, 4, 0, 80, 2, 0)
        val control = Aacp.control(0x28, listOf(1))
        val motionAck = Aacp.header + byteArrayOf(0x17, 0, 0, 0, 16, 0, 8, 0, 8, 6, 16, 1, 0x4a, 2, 8, 16)
        val frames = AacpFramer().feed(battery + conversation + motionAck + control + report(status = 9))
        assertEquals(5, frames.size)
        assertEquals(80, (Aacp.decode(frames[0]) as Event.Batteries).values.single().level)
        assertEquals(Event.Conversation(1, 1), Aacp.decode(frames[1]))
        assertEquals(Event.MotionAcknowledgement(16), Aacp.decode(frames[2]))
        assertEquals(Event.Control(0x28, listOf(1, 0, 0, 0)), Aacp.decode(frames[3]))
        assertEquals(Event.Conversation(1, 9), Aacp.decode(frames[4]))
    }

    @Test fun unsupportedPayloadUsesDeclaredLengthEvenWithAnEmbeddedHeader() {
        val payload = byteArrayOf(7) + Aacp.header + byteArrayOf(255.toByte(), 0)
        val unknown = Aacp.header + byteArrayOf(0x4b, 0, payload.size.toByte(), 0) + payload
        val control = Aacp.control(13, listOf(3))
        val framer = AacpFramer()
        assertTrue(framer.feed(unknown.copyOf(11)).isEmpty())
        val frames = framer.feed(unknown.copyOfRange(11, unknown.size) + control)
        assertEquals(2, frames.size)
        assertContentEquals(unknown, frames[0])
        assertEquals(Event.Unknown(0x4b, unknown.size), Aacp.decode(frames[0]))
        assertContentEquals(control, frames[1])
    }

    @Test fun malformedLengthDoesNotDiscardTheNextReports() {
        val control = Aacp.control(13, listOf(3))
        for (length in listOf(0, 65, 256, 65535)) {
            val invalid = Aacp.header + byteArrayOf(0x4b, 0, length.toByte(), (length ushr 8).toByte())
            assertNull(Aacp.decode(invalid))
            val framer = AacpFramer()
            val frames = framer.feed(invalid + control + report(status = 6))
            assertEquals(2, frames.size, "length=$length")
            assertContentEquals(control, frames[0])
            assertEquals(Event.Conversation(1, 6), Aacp.decode(frames[1]))
        }
    }

    @Test fun unsupportedOpcodeHighByteDoesNotBecomeAConversationOrDiscardNextFrame() {
        val invalid = report().also { it[5] = 1 }
        val control = Aacp.control(0x28, listOf(2))
        assertNull(Aacp.decode(invalid))
        val frames = AacpFramer().feed(invalid + control)
        assertEquals(1, frames.size)
        assertContentEquals(control, frames.single())
    }

    @Test fun recoveryPreservesASplitFollowingHeaderWithoutInventingAHandshake() {
        val invalid = Aacp.header + byteArrayOf(0x4b, 0, 0, 0)
        val control = Aacp.control(0x28, listOf(1))
        for (prefix in 0..3) {
            val framer = AacpFramer()
            assertTrue(framer.feed(invalid + control.copyOf(prefix)).isEmpty())
            val frames = framer.feed(control.copyOfRange(prefix, control.size))
            assertEquals(1, frames.size, "prefix=$prefix")
            assertContentEquals(control, frames.single())
        }
    }

    @Test fun opaqueSubtypeWithoutStatusIsNotInventedAsSpeaking() {
        val unknown = Aacp.header + byteArrayOf(0x4b, 0, 1, 0, 1)
        val frames = AacpFramer().feed(unknown + report(status = 2))
        assertEquals(2, frames.size)
        assertEquals(Event.Unknown(0x4b, 9), Aacp.decode(frames[0]))
        assertEquals(Event.Conversation(1, 2), Aacp.decode(frames[1]))
    }

    private fun audioSource(state: Int = 2): ByteArray =
        Aacp.header + byteArrayOf(0x0e, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, state.toByte())

    @Test fun audioSourcePreservesOnlyKnownStateWithoutThePeerAddress() {
        for (state in 0..2) {
            val event = Aacp.decode(audioSource(state))
            assertEquals(Event.AudioSource(state), event)
            assertEquals("AudioSource(state=$state)", event.toString())
        }
    }

    @Test fun audioSourceRejectsWrongLengthsStatesHeadersAndOpcodeHighBytes() {
        val packet = audioSource()
        for (length in 0 until packet.size) assertNull(Aacp.decode(packet.copyOf(length)))
        assertNull(Aacp.decode(packet + byteArrayOf(0)))
        assertNull(Aacp.decode(packet.copyOf().also { it[0] = 0 }))
        assertNull(Aacp.decode(packet.copyOf().also { it[5] = 1 }))
        for (state in 3..255) assertNull(Aacp.decode(audioSource(state)))
    }

    @Test fun audioSourceSurvivesEverySplitWithAnEmbeddedHeaderInItsAddress() {
        val packet = audioSource().also { Aacp.header.copyInto(it, destinationOffset = 6) }
        for (split in 1 until packet.size) {
            val framer = AacpFramer()
            assertTrue(framer.feed(packet.copyOf(split)).isEmpty())
            val frames = framer.feed(packet.copyOfRange(split, packet.size))
            assertContentEquals(packet, frames.single())
            assertEquals(Event.AudioSource(2), Aacp.decode(frames.single()))
        }
    }

    @Test fun audioSourceAndOtherReportsCanShareAReadIncludingInvalidSourceState() {
        val battery = Aacp.header + byteArrayOf(4, 0, 1, 4, 0, 80, 2, 0)
        val control = Aacp.control(0x28, listOf(1))
        val invalidOpcode = audioSource().also { it[5] = 1 }
        val frames = AacpFramer().feed(
            battery + audioSource(2) + report() + audioSource(3) + invalidOpcode + control + audioSource(0)
        )
        assertEquals(7, frames.size)
        assertEquals(80, (Aacp.decode(frames[0]) as Event.Batteries).values.single().level)
        assertEquals(Event.AudioSource(2), Aacp.decode(frames[1]))
        assertEquals(Event.Conversation(1, 1), Aacp.decode(frames[2]))
        assertNull(Aacp.decode(frames[3]))
        assertNull(Aacp.decode(frames[4]))
        assertEquals(Event.Control(0x28, listOf(1, 0, 0, 0)), Aacp.decode(frames[5]))
        assertEquals(Event.AudioSource(0), Aacp.decode(frames[6]))
    }
}
