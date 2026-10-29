package com.hyper.podsrx.protocol

import kotlin.test.*

class BatteryAlertsTest {
    /** Dockings start from earbuds out of the case at least this long. */
    private val S = BatteryAlerts.SETTLE_MS
    private fun r(level: Int?, charging: Boolean = false) = Reading(level, charging, true, 0L)
    private fun buds(left: Int?, right: Int?, charging: Boolean = false, case: Int? = null) = buildMap {
        put(4, r(left, charging)); put(2, r(right, charging))
        if (case != null) put(8, r(case))
    }

    @Test fun lowAlertsOnceUntilRecharged() {
        val alerts = BatteryAlerts()
        assertEquals(emptyList(), alerts.observe(buds(40, 22), 0))
        assertEquals(listOf(BatteryAlert.Low), alerts.observe(buds(40, 20), 1000))
        assertEquals(emptyList(), alerts.observe(buds(39, 15), 2000))
        assertEquals(listOf(BatteryAlert.LowCleared), alerts.observe(buds(39, 15, charging = true), 3000).filter { it !is BatteryAlert.Docked })
        assertTrue(alerts.lowArmed)
    }

    @Test fun lowRearmsOnlyWellAboveTheThreshold() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(20, 50), 0)
        assertEquals(emptyList(), alerts.observe(buds(21, 50), 1000))
        assertEquals(emptyList(), alerts.observe(buds(20, 50), 2000))
        assertFalse(alerts.lowArmed)
    }

    @Test fun anEarbudOnTheChargerDoesNotCountAsLow() {
        val alerts = BatteryAlerts()
        val map = mapOf(4 to r(10, charging = true), 2 to r(60))
        assertEquals(emptyList(), alerts.observe(map, 0))
    }

    @Test fun missingReadingsChangeNothing() {
        val alerts = BatteryAlerts(lowArmed = false, fullArmed = true)
        assertEquals(emptyList(), alerts.observe(emptyMap(), 0))
        assertFalse(alerts.lowArmed)
        assertTrue(alerts.fullArmed)
    }

    @Test fun fullAlertsWhenTheChargingEarbudsFinish() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(90, 92, charging = true), 0)
        assertTrue(alerts.fullArmed)
        assertEquals(emptyList(), alerts.observe(mapOf(4 to r(100, true), 2 to r(98, true)), 1000))
        assertEquals(listOf(BatteryAlert.Full), alerts.observe(buds(100, 100, charging = true), 2000))
        assertEquals(emptyList(), alerts.observe(buds(100, 100), 3000))
    }

    @Test fun anEarbudWornElsewhereDoesNotHoldTheFullAlert() {
        val alerts = BatteryAlerts(fullArmed = true)
        assertEquals(listOf(BatteryAlert.Full), alerts.observe(mapOf(4 to r(100, true), 2 to r(60)), 0))
    }

    @Test fun toppingUpFromNinetyNineDoesNotAlertAgain() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(80, 80, charging = true), 0)
        alerts.observe(buds(100, 100, charging = true), 1000)
        assertEquals(emptyList(), alerts.observe(buds(99, 100, charging = true), 2000))
        assertEquals(emptyList(), alerts.observe(buds(100, 100, charging = true), 3000))
    }

    @Test fun dockingShowsTheCaseLevelAndFollowsIt() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70, case = 30), -S)
        assertEquals(listOf(BatteryAlert.Docked(30, false)), alerts.observe(buds(70, 70, charging = true, case = 30), 1000))
        assertEquals(listOf(BatteryAlert.Docked(34, true)), alerts.observe(buds(70, 70, charging = true, case = 34), 5000))
        assertEquals(emptyList(), alerts.observe(buds(70, 70, charging = true, case = 33), 1000 + BatteryAlerts.FOLLOW_MS + 1))
    }

    @Test fun theSecondEarbudJoinsTheShownDocking() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70, case = 30), -S)
        assertEquals(listOf(BatteryAlert.Docked(30, false)), alerts.observe(mapOf(4 to r(70, true), 2 to r(70), 8 to r(30)), 1000))
        assertEquals(emptyList(), alerts.observe(buds(70, 70, charging = true, case = 30), 3000))
    }

    @Test fun puttingAnEarbudBackIsANewDocking() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70, case = 30), -S)
        assertEquals(listOf(BatteryAlert.Docked(30, false)), alerts.observe(buds(70, 70, charging = true, case = 30), 1000))
        alerts.observe(buds(70, 70, case = 30), 2000)
        assertEquals(listOf(BatteryAlert.Docked(30, false)), alerts.observe(buds(70, 70, charging = true, case = 30), 2000 + S))
    }

    @Test fun aShortRunOffTheChargerIsNoise() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70, case = 30), -S)
        assertEquals(listOf(BatteryAlert.Docked(30, false)), alerts.observe(buds(70, 70, charging = true, case = 30), 0))
        alerts.observe(buds(100, 100, case = 30), 2000)
        assertEquals(emptyList(), alerts.observe(buds(100, 100, charging = true, case = 30), 2000 + S - 1))
    }

    @Test fun aFullerCaseDoesNotAlert() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70, case = 40), -S)
        assertEquals(listOf(BatteryAlert.DockSkipped(40)), alerts.observe(buds(70, 70, charging = true, case = 40), 1000))
        assertEquals(emptyList(), alerts.observe(buds(70, 70, charging = true, case = 39), 2000))
    }

    @Test fun dockingWaitsForTheCaseLevel() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70), -S)
        assertEquals(emptyList(), alerts.observe(buds(70, 70, charging = true), 1000))
        assertEquals(listOf(BatteryAlert.Docked(25, false)), alerts.observe(buds(70, 70, charging = true, case = 25), 3000))
    }

    @Test fun dockingGivesUpWithoutACaseLevel() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70), -S)
        alerts.observe(buds(70, 70, charging = true), 1000)
        alerts.observe(buds(70, 70, charging = true), 2000 + BatteryAlerts.CASE_WAIT_MS)
        assertEquals(emptyList(), alerts.observe(buds(70, 70, charging = true, case = 25), 3000 + BatteryAlerts.CASE_WAIT_MS))
    }

    @Test fun firstSightOfAChargingEarbudIsNoDocking() {
        val alerts = BatteryAlerts()
        assertEquals(emptyList(), alerts.observe(buds(70, 70, charging = true, case = 30), 0))
    }

    @Test fun aShortBlindSpanStillDocks() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70, case = 30), -S)
        alerts.sight(false, 1000)
        alerts.sight(true, 1000 + BatteryAlerts.DOCK_WINDOW_MS - 1)
        assertEquals(listOf(BatteryAlert.Docked(30, false)), alerts.observe(buds(70, 70, charging = true, case = 30), 2000 + BatteryAlerts.DOCK_WINDOW_MS))
    }

    @Test fun aLongBlindSpanSkipsTheDocking() {
        val alerts = BatteryAlerts()
        alerts.observe(buds(70, 70, case = 30), -S)
        alerts.sight(false, 1000)
        val back = 1000 + 8 * 3600_000L
        alerts.sight(true, back)
        assertEquals(emptyList(), alerts.observe(buds(70, 70, charging = true, case = 30), back + 2000))
    }

    @Test fun aBlindSpanBeforeTheLastIdleReadingDoesNotMatter() {
        val alerts = BatteryAlerts()
        alerts.sight(false, 0)
        alerts.sight(true, 3600_000L)
        alerts.observe(buds(70, 70, case = 30), 3600_000L + 1000)
        assertEquals(listOf(BatteryAlert.Docked(30, false)), alerts.observe(buds(70, 70, charging = true, case = 30), 3600_000L + 1000 + S))
    }
}
