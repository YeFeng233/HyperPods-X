package com.hyper.podsrx.protocol

import kotlin.test.*

class AacpInformationTest {
    private fun fields(version1: String = "", version2: String = "", version3: String = "") = mutableListOf(
        "Test AirPods", "A3441", "Apple Inc.", "TEST_SERIAL", version1, version2,
        "1.0.0", "com.apple.accessory.updater.app.71", "TEST_LEFT", "TEST_RIGHT", version3,
    )
    private fun packet(fields: List<String>, terminated: Boolean = true) =
        Aacp.header + byteArrayOf(0x1d, 0, 2, 1, 0, 4, 0) +
            (fields.joinToString("\u0000") + if (terminated) "\u0000" else "").toByteArray()
    private fun decode(fields: List<String>) = assertIs<Event.Information>(Aacp.decode(packet(fields)))

    @Test fun internalVersionAndNumericIdentifierDoNotBecomePublicFirmware() {
        val info = decode(fields("90.1234567890000000.1234", "90.1234567890000000.1234", "9123456"))
        assertEquals("A3441", info.model)
        assertEquals("Test AirPods", info.name)
        assertEquals("", info.firmware)
        assertEquals("90.1234567890000000.1234", info.internalFirmware)
        assertEquals("9123456", info.firmwareIdentifier)
    }

    @Test fun publicBuildUsesThirdVersionSlotBeforeLegacySlots() {
        assertEquals("9A123", decode(fields("8B123", "8B123", "9A123")).firmware)
        assertEquals("8B123", decode(fields("8B123")).firmware)
        assertEquals("8B123a", decode(fields("", "8B123a")).firmware)
    }

    @Test fun emptyFieldsKeepTheirPositions() {
        val source = fields("", "90.123.45", "9A123").also { it[0] = ""; it[3] = ""; it[8] = ""; it[9] = "" }
        val info = decode(source)
        assertEquals("AirPods", info.name)
        assertEquals("9A123", info.firmware)
        assertEquals("90.123.45", info.internalFirmware)
        assertEquals("", info.firmwareIdentifier)
    }

    @Test fun serialNumbersHardwareAndUnknownFieldsCannotSupplyFirmware() {
        val source = fields().also {
            for (index in listOf(3, 6, 7, 8, 9)) it[index] = "9A123"
            it += "9A456"
            it += "90.123.45"
        }
        val info = decode(source)
        assertEquals("", info.firmware)
        assertEquals("", info.internalFirmware)
        assertEquals("", info.firmwareIdentifier)
    }

    @Test fun missingVersionsAndUnterminatedFinalVersionAreNotGuessed() {
        val short = decode(fields().take(4))
        assertEquals("", short.firmware)
        assertEquals("", short.internalFirmware)
        val unterminated = assertIs<Event.Information>(Aacp.decode(packet(fields(version3 = "9A123"), terminated = false)))
        assertEquals("", unterminated.firmware)
    }

    @Test fun malformedAndOversizedVersionFieldsAreRejected() {
        for (value in listOf("9A123\n", " 9A123", "9a123", "9A123/private", "9".repeat(80), "90." + "1".repeat(64))) {
            val info = decode(fields(value, value, value))
            assertEquals("", info.firmware)
            assertEquals("", info.internalFirmware)
            assertEquals("", info.firmwareIdentifier)
        }
        assertNull(Aacp.decode(packet(listOf("no model"))))
        assertNull(Aacp.decode(packet(fields().also { it[0] = "x".repeat(4096) })))
    }

    @Test fun modelLikeDeviceNameDoesNotShiftMetadataSlots() {
        val info = decode(fields("8B123").also { it[0] = "A1234" })
        assertEquals("A1234", info.name)
        assertEquals("A3441", info.model)
        assertEquals("8B123", info.firmware)
    }
}
