package com.hyper.podsrx.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRestartPolicyTest {
    private val bluetooth = "com.xiaomi.bluetooth"
    private val settings = "com.android.settings"
    private val milink = "com.milink.service"

    @Test fun sharedUidsNeverSelectBluetoothStackOrSystemServer() {
        val bluetoothNames = setOf(bluetooth, "$bluetooth:ui", "com.android.bluetooth")
        val settingsNames = setOf(settings, "$settings:remote", "system_server")
        assertTrue(HostRestartPolicy.matches(process(1002, bluetooth), bluetooth, 1002, bluetoothNames))
        assertFalse(HostRestartPolicy.matches(process(1002, "com.android.bluetooth"), bluetooth, 1002, bluetoothNames))
        assertFalse(HostRestartPolicy.matches(process(1000, "system_server"), settings, 1000, settingsNames))
        assertThrows(IllegalArgumentException::class.java) { HostRestartPolicy.stopCommand(process(1000, "system_server")) }
        assertThrows(IllegalArgumentException::class.java) { HostRestartPolicy.stopCommand(process(1002, "com.android.bluetooth")) }
    }

    @Test fun targetUidMustIncludeTheSameAndroidUser() {
        val declared = setOf(milink, "$milink:core")
        assertFalse(HostRestartPolicy.matches(process(1_010_183, "$milink:core"), milink, 10_183, declared))
        assertFalse(HostRestartPolicy.matches(process(10_183, "$milink:core"), milink, 1_010_183, declared))
        assertTrue(HostRestartPolicy.matches(process(1_010_183, "$milink:core"), milink, 1_010_183, declared))
    }

    @Test fun matchingPackagePrefixDoesNotAuthorizeUndeclaredProcesses() {
        assertFalse(HostRestartPolicy.matches(process(10_183, "$milink:unknown"), milink, 10_183, setOf(milink)))
        assertFalse(HostRestartPolicy.matches(process(10_183, "$milink.fake"), milink, 10_183, setOf("$milink.fake")))
        assertFalse(HostRestartPolicy.matches(process(10_183, "com.other.app"), "com.other.app", 10_183, setOf("com.other.app")))
    }

    @Test fun declaredMilinkAuxiliaryNamesAreLimitedToTheirOwningPackage() {
        val names = setOf("com.milink.runtime", "com.milink.crossdeviceservice", "com.xiaomi.vtcamera")
        for (name in names) {
            assertTrue(HostRestartPolicy.matches(process(10_183, name), milink, 10_183, names))
            assertFalse(HostRestartPolicy.matches(process(1002, name), bluetooth, 1002, names))
            assertFalse(HostRestartPolicy.matches(process(10_183, name), milink, 10_183, emptySet()))
        }
    }

    @Test fun shellMetacharactersAreRejectedBeforeAStopCommandCanBeBuilt() {
        val names = listOf("$bluetooth;id", "$bluetooth'", "$bluetooth\nreboot", "$bluetooth\$(id)",
            "$bluetooth`id`", "$bluetooth:ui && id", "$bluetooth|id")
        for (name in names) {
            assertFalse(HostRestartPolicy.acceptsName(bluetooth, name))
            assertThrows(IllegalArgumentException::class.java) { HostRestartPolicy.stopCommand(process(1002, name)) }
        }
    }

    @Test fun snapshotParserAcceptsNumericRowsAndRejectsHeadersAndAmbiguousRows() {
        assertEquals(process(1002, bluetooth), HostRestartPolicy.parse("  4321  1002  $bluetooth  "))
        assertEquals(process(1_010_183, "$milink:core"), HostRestartPolicy.parse("4321\t1010183\t$milink:core"))
        for (line in listOf("PID UID NAME", "", "0 1002 $bluetooth", "1 1002 $bluetooth",
            "-5 1002 $bluetooth", "4321 -1 $bluetooth", "4321 bluetooth $bluetooth",
            "4321 1002 $bluetooth extra", "99999999999999 1002 $bluetooth")) {
            assertNull(HostRestartPolicy.parse(line))
        }
    }

    @Test fun stopRechecksPidUidAndExactCommandLineBeforeTerminating() {
        val command = HostRestartPolicy.stopCommand(process(1002, "$bluetooth:ui"))
        assertTrue(command.contains("/proc/4321/status"))
        assertTrue(command.contains("= '1002'"))
        assertTrue(command.contains("/proc/4321/cmdline"))
        assertTrue(command.contains("= '$bluetooth:ui'"))
        assertTrue(command.contains("kill -TERM 4321"))
        assertFalse(command.contains("killall"))
        assertFalse(command.contains("force-stop"))
        assertThrows(IllegalArgumentException::class.java) {
            HostRestartPolicy.stopCommand(HostRestartPolicy.Process(1, 1002, bluetooth))
        }
    }

    @Test fun securityCenterRestartIsLimitedToTheWidgetProvider() {
        val center = "com.miui.securitycenter"
        assertTrue(HostRestartPolicy.acceptsName(center, "$center.remote"))
        assertFalse(HostRestartPolicy.acceptsName(center, center))
        assertFalse(HostRestartPolicy.acceptsName(center, "$center:ui"))
    }

    private fun process(uid: Int, name: String) = HostRestartPolicy.Process(4321, uid, name)
}
