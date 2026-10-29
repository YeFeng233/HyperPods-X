package com.hyper.podsrx.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class CaseStatusTest {
    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun message(plain: String, with: ByteArray = key): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(with, "AES")) }
        return byteArrayOf(7, 0x11, 6) + cipher.doFinal(hex(plain))
    }

    // Layout follows a closed-lid capture: case 81 %, left 100 % charging, right 81 % charging.
    @Test fun caseAndEarbudsAreReadWithChargeFlags() {
        val values = CaseStatusCodec.decode(message("29200e51e4d15b0200000000b11a0000"), key)!!
        assertEquals(Battery(4, 100, true, true), values.first { it.component == 4 })
        assertEquals(Battery(2, 81, true, true), values.first { it.component == 2 })
        assertEquals(Battery(8, 81, false, true), values.first { it.component == 8 })
    }
    // A Pro 3 capture with 04 in byte 9 and 01 in byte 14: case 98 % charging, left 100 %, right 99 %.
    @Test fun aCaseWithItsFlagBytesIsRead() {
        val values = CaseStatusCodec.decode(message("29200ce2e4e35b02000400" + "00ad050100"), key)!!
        assertEquals(Battery(8, 98, true, true), values.first { it.component == 8 })
        assertEquals(Battery(4, 100, true, true), values.first { it.component == 4 })
        assertEquals(Battery(2, 99, true, true), values.first { it.component == 2 })
    }
    @Test fun earbudsOutsideTheCaseLeaveOnlyTheCase() {
        val values = CaseStatusCodec.decode(message("29200344ff805b02000000002e060000"), key)!!
        assertNull(values.first { it.component == 4 }.level)
        assertNull(values.first { it.component == 2 }.level)
        assertFalse(values.first { it.component == 2 }.charging)
        assertEquals(68, values.first { it.component == 8 }.level)
    }
    @Test fun anotherOwnersCaseIsRejected() {
        val other = ByteArray(16) { (it + 40).toByte() }
        assertNull(CaseStatusCodec.decode(message("29200e51e4d15b0200000000b11a0000", other), key))
    }
    @Test fun malformedInputNeverCrashesScanning() {
        assertNull(CaseStatusCodec.decode(ByteArray(4), key))
        assertNull(CaseStatusCodec.decode(message("29200e51e4d15b0200000000b11a0000"), null))
        assertNull(CaseStatusCodec.decode(message("29200e51e4d15b0200000000b11a0000").also { it[1] = 0x19 }, key))
        assertNull(CaseStatusCodec.decode(message("29200e51e4d15b0200000000b11a0000"), ByteArray(4)))
    }
}
