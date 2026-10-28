package com.hyper.podsrx.protocol

import kotlin.test.*

class BatteryLedgerTest {
    private fun b(component: Int, level: Int?, charging: Boolean = false, connected: Boolean = true) =
        Battery(component, level, charging, connected)

    @Test fun firstReadingIsAChange() {
        val ledger = BatteryLedger()
        assertTrue(ledger.apply(listOf(b(4, 80)), precise = false, control = false, now = 1000))
        assertEquals(Reading(80, false, false, 1000, false), ledger.readings[4])
    }

    @Test fun repeatOnlyRefreshesTheTime() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(4, 80)), precise = false, control = false, now = 1000)
        assertFalse(ledger.apply(listOf(b(4, 80)), precise = false, control = false, now = 5000))
        assertEquals(5000, ledger.readings[4]!!.updated)
    }

    @Test fun unknownLevelKeepsTheLastReading() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(8, 60)), precise = true, control = false, now = 1000)
        assertFalse(ledger.apply(listOf(b(8, null)), precise = true, control = false, now = 2000))
        assertEquals(60, ledger.readings[8]!!.level)
    }

    @Test fun disconnectWithdrawsOnlyTheControlChannelsOwnReading() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(8, 50)), precise = true, control = true, now = 1000)
        ledger.apply(listOf(b(2, 70)), precise = false, control = false, now = 1000)
        assertTrue(ledger.apply(listOf(b(8, null, connected = false)), precise = true, control = true, now = 2000))
        assertNull(ledger.readings[8])
        // The right earbud reading came from an advertisement, so a control disconnect leaves it alone.
        assertFalse(ledger.apply(listOf(b(2, null, connected = false)), precise = true, control = true, now = 2000))
        assertEquals(70, ledger.readings[2]!!.level)
    }

    @Test fun recentPreciseReadingWinsButChargingFlipsAtOnce() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(4, 83)), precise = true, control = true, now = 1000)
        assertFalse(ledger.apply(listOf(b(4, 80)), precise = false, control = false, now = 2000))
        assertEquals(83, ledger.readings[4]!!.level)
        assertTrue(ledger.apply(listOf(b(4, 80, charging = true)), precise = false, control = false, now = 3000))
        assertEquals(Reading(83, true, true, 1000, true), ledger.readings[4])
    }

    @Test fun stalePreciseReadingYieldsToAnAdvertisement() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(4, 83)), precise = true, control = true, now = 1000)
        assertTrue(ledger.apply(listOf(b(4, 80)), precise = false, control = false, now = 1000 + BatteryLedger.PRECISE_WINS_MS))
        assertEquals(Reading(80, false, false, 31000, false), ledger.readings[4])
    }

    @Test fun broadcastReadingsExpireAfterTheirAge() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(2, 70)), precise = false, control = false, now = 0)
        assertFalse(ledger.expire(60000, controlFreshAt = 0, now = 59999))
        assertTrue(ledger.expire(60000, controlFreshAt = 0, now = 60000))
        assertTrue(ledger.readings.isEmpty())
    }

    @Test fun controlReadingsAgeOnlyFromTheDisconnect() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(4, 90)), precise = true, control = true, now = 0)
        // Connected: the caller passes now as the fresh time.
        assertFalse(ledger.expire(60000, controlFreshAt = 500000, now = 500000))
        // Lost at 500000: kept until a full age has passed since then.
        assertFalse(ledger.expire(60000, controlFreshAt = 500000, now = 559999))
        assertTrue(ledger.expire(60000, controlFreshAt = 500000, now = 560000))
    }

    @Test fun contentIgnoresTimes() {
        val one = mapOf(4 to Reading(80, false, false, 1))
        val two = mapOf(4 to Reading(80, false, false, 2))
        assertEquals(one.content(), two.content())
    }

    @Test fun mergedLeavesTheInputUntouched() {
        val current = mapOf(4 to Reading(80, false, false, 1))
        BatteryLedger.merged(current, listOf(b(4, 50)), precise = false, control = false, now = 2)
        assertEquals(80, current[4]!!.level)
    }

    @Test fun touchRestartsEveryAge() {
        val ledger = BatteryLedger()
        ledger.apply(listOf(b(2, 70)), precise = false, control = false, now = 0)
        // The screen came back on after the scans were off; nothing was heard meanwhile.
        ledger.touch(100000)
        assertFalse(ledger.expire(60000, controlFreshAt = 0, now = 159999))
        assertTrue(ledger.expire(60000, controlFreshAt = 0, now = 160000))
    }
}
