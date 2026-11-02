package com.hyper.podsrx.protocol

import kotlin.test.*

class AppleAdvertisementTest {
    private fun status(length: Int) = ByteArray(length + 2).also {
        it[0] = 7
        it[1] = length.toByte()
    }

    @Test fun standaloneAndPrefixedHeadsetStatusAreAccepted() {
        for (length in listOf(0x11, 0x19)) {
            val message = status(length)
            assertTrue(AppleAdvertisement.hasHeadsetStatus(message))
            assertTrue(AppleAdvertisement.hasHeadsetStatus(byteArrayOf(0x12, 2, 1, 0) + message))
        }
    }

    @Test fun caseStatusAfterFindMyMessageKeepsItsFrameBoundary() {
        val message = status(0x11)
        val data = byteArrayOf(0x12, 2, 1, 0) + message + byteArrayOf(0x10, 2, 0, 0)
        val messages = AppleAdvertisement.proximityMessages(data)
        assertEquals(1, messages.size)
        assertContentEquals(message, messages.single())
    }

    @Test fun headsetHeadersInsideAnotherMessagesPayloadAreIgnored() {
        val data = byteArrayOf(0x10, 19) + status(0x11)
        assertFalse(AppleAdvertisement.hasHeadsetStatus(data))
        assertTrue(AppleAdvertisement.proximityMessages(data).isEmpty())
    }

    @Test fun truncatedMessagesCannotPassTheStatusGate() {
        val prefix = byteArrayOf(0x12, 2, 1, 0)
        val message = status(0x11)
        for (length in 0 until message.size) {
            assertFalse(AppleAdvertisement.hasHeadsetStatus(prefix + message.copyOf(length)), "length=$length")
        }
        assertFalse(AppleAdvertisement.hasHeadsetStatus(byteArrayOf(0x12, 30) + message))
    }

    @Test fun unrelatedAndUnsupportedMessagesAreIgnored() {
        assertFalse(AppleAdvertisement.hasHeadsetStatus(byteArrayOf()))
        assertFalse(AppleAdvertisement.hasHeadsetStatus(byteArrayOf(0x12, 2, 1, 0, 0x10, 2, 0, 0)))
        assertFalse(AppleAdvertisement.hasHeadsetStatus(status(4)))
    }
}
