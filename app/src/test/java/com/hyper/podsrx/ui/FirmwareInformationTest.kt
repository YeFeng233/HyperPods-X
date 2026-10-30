package com.hyper.podsrx.ui

import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.i18n.TestStrings
import org.junit.Assert.*
import org.junit.Test

class FirmwareInformationTest {
    @Test fun internalVersionAndIdentifierStayHiddenFromUserInformation() {
        val state = PodsState(model = "A3441", internalFirmware = "90.123.456", firmwareIdentifier = "9123456")
        val chinese = firmwareRows(state, TestStrings.chinese)
        assertEquals(listOf("固件版本"), chinese.map { it.title })
        assertEquals(listOf("耳机未提供"), chinese.map { it.value })
        val english = firmwareRows(state, TestStrings.english)
        assertEquals(listOf("Firmware Version"), english.map { it.title })
        assertEquals("Not reported by earbuds", english.first().value)
        assertEquals(chinese.map { it.key }, english.map { it.key })
    }

    @Test fun publicBuildIsShownDirectlyAndMissingMetadataStaysUnknown() {
        val known = firmwareRows(PodsState(model = "A3441", firmware = "9A123"), TestStrings.english)
        assertEquals(1, known.size)
        assertEquals("9A123", known.single().value)
        assertEquals("\u2014", firmwareRows(PodsState(), TestStrings.english).single().value)
    }

    @Test fun calibrationRetainsTheExistingInternalBuildIdentity() {
        val state = PodsState(internalFirmware = "90.123.456", firmwareIdentifier = "9123456")
        assertEquals("90.123.456", state.firmwareIdentity)
        assertEquals(state.firmwareIdentity, state.copy(firmware = "9A123").firmwareIdentity)
        assertNotEquals(state.firmwareIdentity, state.copy(internalFirmware = "90.123.457").firmwareIdentity)
        assertEquals("9A123", PodsState(firmware = "9A123").firmwareIdentity)
        assertEquals("9123456", PodsState(firmwareIdentifier = "9123456").firmwareIdentity)
        assertEquals("", PodsState().firmwareIdentity)
    }
}
