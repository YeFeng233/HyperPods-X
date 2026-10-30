package com.hyper.podsrx.protocol

import kotlin.test.*
import org.junit.Test

class AacpInitializationTest {
    @Test fun hostNegotiates12AndSubscribesBeforeRequestingKeys() {
        val steps = AacpInitialization.host()
        assertContentEquals(byteArrayOf(0,0,4,0,1,0,2,0,0,0,0,0,0,0,0,0), steps[0].packet)
        assertContentEquals(Aacp.header + byteArrayOf(15,0,-1,-1,-2,-1), steps[1].packet)
        assertContentEquals(Aacp.notifications(), steps[2].packet)
        assertContentEquals(Aacp.keyRequest(1), steps[3].packet)
        assertContentEquals(Aacp.keyRequest(4), steps[4].packet)
        assertTrue(steps.all { it.delayMs >= 100 })
    }

    @Test fun initializationNeverChangesAHeadsetSetting() {
        assertFalse(AacpInitialization.host().any { it.packet[4] == 9.toByte() })
    }

    @Test fun packetsAreFreshOnEverySession() {
        AacpInitialization.host().forEach { it.packet.fill(0) }
        assertEquals(2, AacpInitialization.host()[0].packet[6].toInt())
        assertContentEquals(Aacp.notifications(), AacpInitialization.host()[2].packet)
    }
}
