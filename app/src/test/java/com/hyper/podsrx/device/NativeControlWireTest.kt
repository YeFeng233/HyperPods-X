package com.hyper.podsrx.device

import com.hyper.podsrx.hook.NativeControlWire
import org.junit.Assert.*
import org.junit.Test

class NativeControlWireTest {
    private val bound = "AA:BB:CC:DD:EE:01"
    private fun matches(target: String? = bound, model: String = "A3441", enabled: Boolean = true,
        connected: Boolean = true, heartbeat: Long = 1000, now: Long = 1001, address: String? = bound) =
        NativeControlWire.isCurrentTarget(target, address, model, enabled, connected, heartbeat, now)

    @Test fun onlyBoundA3441UsesNativeBridge() {
        assertTrue(matches(target = bound.lowercase()))
        assertFalse(matches(target = "AA:BB:CC:DD:EE:02"))
        assertFalse(matches(target = null))
        assertFalse(matches(address = null))
        assertFalse(matches(address = "", target = ""))
        assertFalse(matches(model = "Xiaomi"))
        assertFalse(matches(model = ""))
    }

    @Test fun stoppedOrDisconnectedDeviceCannotReceiveCommands() {
        assertFalse(matches(enabled = false))
        assertFalse(matches(connected = false))
    }

    @Test fun MissingFutureAndExpiredSnapshotsAreRejected() {
        assertFalse(matches(heartbeat = -1))
        assertFalse(matches(now = 999))
        assertTrue(matches(now = 31_000))
        assertFalse(matches(now = 31_001))
    }

    private fun presentation(now: Long = 2000, until: Long = 6000, route: Boolean = true,
        target: String = bound, model: String = "A3441", heartbeat: Long = 1000) =
        NativeControlWire.isPresentationTarget(target, bound, model, true, false, route, until, heartbeat, now)

    @Test fun plannedReadbackKeepsPresentationButDoesNotAuthorizeCommands() {
        assertTrue(presentation())
        assertFalse(matches(connected = false, now = 2000))
    }

    @Test fun reconnectPresentationRequiresAnActiveAudioRouteAndBoundedLease() {
        assertFalse(presentation(route = false))
        assertTrue(presentation(now = 5999))
        assertFalse(presentation(now = 6000))
        assertFalse(presentation(until = 0))
        assertFalse(presentation(until = 7001))
    }

    @Test fun readbackLeaseCannotExposeAnotherDeviceOrStaleSnapshot() {
        assertFalse(presentation(target = "AA:BB:CC:DD:EE:02"))
        assertFalse(presentation(model = "Xiaomi"))
        assertFalse(presentation(heartbeat = 2001))
        assertFalse(presentation(now = 32_000, until = 33_000))
    }
}
