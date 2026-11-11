package com.hyper.podsrx.protocol

import org.junit.Assert.*
import org.junit.Test

class NativeHeadsetPolicyTest {
    @Test fun nativeModesRoundTripWithoutTreatingAdaptiveAsANativeMode() {
        val expected = mapOf(0 to 2, 1 to 3, 2 to 1)
        for ((native, aacp) in expected) {
            assertEquals(aacp, NativeHeadsetPolicy.toAacpMode(native))
            assertEquals(native, NativeHeadsetPolicy.toNativeMode(aacp))
        }
        for (invalid in listOf(-1, 3, 4, 255)) assertEquals(0, NativeHeadsetPolicy.toAacpMode(invalid))
        for (unsupported in listOf(-1, 0, 4, 255)) assertEquals(-1, NativeHeadsetPolicy.toNativeMode(unsupported))
    }

    @Test fun onlyTheBoundLiveSessionsL2capFeatureIsGuarded() {
        val bound = "AA:BB:CC:DD:EE:FF"
        assertTrue(NativeHeadsetPolicy.guardNativeChannel("ConnectL2cap", bound.lowercase(), bound, true))
        for (feature in listOf(null, "", "NoiseControl", "EarDetection"))
            assertFalse(NativeHeadsetPolicy.guardNativeChannel(feature, bound, bound, true))
        for (target in listOf(null, "", "AA:BB:CC:DD:EE:00"))
            assertFalse(NativeHeadsetPolicy.guardNativeChannel("ConnectL2cap", target, bound, true))
        assertFalse(NativeHeadsetPolicy.guardNativeChannel("ConnectL2cap", bound, bound, false))
        assertFalse(NativeHeadsetPolicy.guardNativeChannel("ConnectL2cap", "", "", true))
    }

    @Test fun offlineStatePreservesSlotOrderBooleanStringsAndUnknownBatteries() {
        assertArrayEquals(arrayOf("true", "0", "false", "100", "-", "true", "false", "true", "1B20", "true"),
            NativeHeadsetPolicy.offlineState(1, 0, 0, 100, -1, 5, "1B20", true))
        assertArrayEquals(arrayOf("false", "-", "false", "-", "50", "false", "true", "false", "1B20", "false"),
            NativeHeadsetPolicy.offlineState(-1, 101, -1, -5, 50, 2, "1B20", false))
    }
}
