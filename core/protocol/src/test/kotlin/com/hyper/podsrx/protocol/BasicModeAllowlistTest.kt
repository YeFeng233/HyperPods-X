package com.hyper.podsrx.protocol

import kotlin.test.*
import org.junit.Test

class BasicModeAllowlistTest {
    @Test fun appendsOnlyTheSelectedHeadset() {
        assertEquals("aa:bb:cc:dd:ee:01;aa:bb:cc:dd:ee:02;",
            BasicModeAllowlist.add("aa:bb:cc:dd:ee:01;", "AA:BB:CC:DD:EE:02"))
    }

    @Test fun existingEntriesAreUnchanged() {
        val current = "AA:BB:CC:DD:EE:01;aa:bb:cc:dd:ee:02;"
        assertEquals(current, BasicModeAllowlist.add(current, "aa:bb:cc:dd:ee:01"))
    }

    @Test fun aFullListDoesNotEvictOtherDevices() {
        val full = (1..5).joinToString("") { "aa:bb:cc:dd:ee:0$it;" }
        assertEquals(full, BasicModeAllowlist.add(full, "aa:bb:cc:dd:ee:01"))
        assertFailsWith<IllegalStateException> { BasicModeAllowlist.add(full, "aa:bb:cc:dd:ee:06") }
    }

    @Test fun rejectsMalformedInput() {
        assertFailsWith<IllegalArgumentException> { BasicModeAllowlist.add("", "invalid") }
        assertFailsWith<IllegalArgumentException> { BasicModeAllowlist.add("broken", "aa:bb:cc:dd:ee:01") }
    }
}
