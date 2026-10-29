package com.hyper.podsrx.ui

import com.hyper.podsrx.protocol.Reading
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.SpatialState
import com.hyper.podsrx.device.SpatialMode
import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.protocol.Event
import com.hyper.podsrx.protocol.WearState
import org.junit.Assert.*
import org.junit.Test

class DeviceUiStateTest {
    @Test fun wearingDescriptionNeverTreatsMissingReportsAsNotWearing() {
        assertEquals("未连接耳机", wearingDescription(PodsState()))
        assertEquals("等待耳机上报", wearingDescription(PodsState(connected = true)))
        assertEquals("双耳已佩戴", wearingDescription(PodsState(connected = true,
            wearing = Event.Wearing(WearState.IN_EAR, WearState.IN_EAR))))
        assertEquals("单耳已佩戴", wearingDescription(PodsState(connected = true,
            wearing = Event.Wearing(WearState.IN_CASE, WearState.IN_EAR))))
        assertEquals("未佩戴", wearingDescription(PodsState(connected = true,
            wearing = Event.Wearing(WearState.OUT_OF_EAR, WearState.DISCONNECTED))))
    }

    @Test fun earAutomationUiIsIndependentOfFirmwareControlAvailability() {
        val state = PodsState(connected = true, earAutomation = true)
        assertEquals("等待耳机上报佩戴状态", earDetectionDescription(state))
        assertEquals(earDetectionDescription(state), earDetectionDescription(state.copy(controls = mapOf(0x0a to listOf(2)))))
        assertEquals("自动入耳检测已关闭", earDetectionDescription(state.copy(earAutomation = false)))
        assertTrue(SettingsToggle.EAR_AUTOMATION.checked(state.copy(controls = mapOf(0x0a to listOf(2)))))
        assertFalse(SettingsToggle.EAR_AUTOMATION.checked(state.copy(earAutomation = false, controls = mapOf(0x0a to listOf(1)))))
    }

    @Test fun settingsCatalogFiltersMissingReadbacksWithoutReorderingSupportedControls() {
        val state = PodsState(connected = true, controls = linkedMapOf(
            0x23 to listOf(1), 0x25 to listOf(2), 0xff to listOf(1), 0x17 to emptyList(),
        ), pending = setOf(0x25))
        val available = SettingsCatalog.availableControls(state, SettingsCatalog.swipeControls)
        assertEquals(listOf(0x25, 0x23), available.map { it.id })
        assertFalse(SettingsCatalog.controlEnabled(state, available[0]))
        assertTrue(SettingsCatalog.controlEnabled(state, available[1]))
        assertFalse(SettingsCatalog.controlEnabled(state.copy(connected = false), available[1]))
        assertFalse(SettingsCatalog.controlEnabled(state, available[1].copy(writable = false)))
        assertTrue(SettingsCatalog.availableControls(state, listOf(0x17, 0xff)).isEmpty())
        assertEquals("耳机侧入耳检测", SettingsCatalog.toggleTitle(Controls.all.first { it.id == 0x0a }))
    }

    @Test fun settingsRoutesPreserveExternalAliasesAndDeviceSpecificTitles() {
        val state = PodsState(name = "AirPods", localName = "本机的耳机")
        assertEquals(SettingsPage.MODULE, SettingsCatalog.initialPage("more"))
        assertEquals(SettingsPage.AUDIO, SettingsCatalog.initialPage("advanced"))
        assertEquals(SettingsPage.SPATIAL, SettingsCatalog.initialPage("spatial"))
        assertNull(SettingsCatalog.initialPage("name"))
        assertNull(SettingsCatalog.initialPage("unknown"))
        assertEquals(SettingsPage.AUDIO, SettingsCatalog.page("audio#retained-entry"))
        assertEquals("本机的耳机", SettingsCatalog.title("name", state))
        assertEquals("本机的耳机", SettingsCatalog.title("device", state))
        assertEquals("麦克风", SettingsCatalog.title(SettingsCatalog.controlRoute(0x01), state))
        assertEquals("设置", SettingsCatalog.title("control/255", state))
        assertEquals(SettingsPage.AUDIO.route, SettingsCatalog.parentRoute("control/1"))
        assertEquals(SettingsPage.CONTROLS.route, SettingsCatalog.parentRoute("call/end"))
        assertNull(SettingsCatalog.parentRoute("device"))
    }

    @Test fun localBluetoothAliasTakesPrecedenceOverHeadsetName() {
        assertEquals("AirPods", PodsState().displayName)
        assertEquals("本机的耳机", PodsState(name = "AirPods", localName = "本机的耳机").displayName)
        assertEquals("AirPods", PodsState(localName = " ").displayName)
    }

    @Test fun unknownBatteryIsNotZeroOrCharging() {
        for (reading in listOf(null, Reading(null, true, true, 0), Reading(101, false, true, 0))) {
            val value = batteryPresentation(reading)
            assertEquals("—", value.label)
            assertEquals("未读取", value.note)
            assertNull(value.level)
            assertFalse(value.charging)
        }
    }

    @Test fun approximateCurrentBatteryKeepsItsAccuracyAndChargingState() {
        val value = batteryPresentation(Reading(70, true, false, 0))
        assertEquals("≈70%", value.label)
        assertEquals("充电中", value.note)
        assertTrue(value.charging)
    }

    @Test fun emptyBatteryIsAValidReading() {
        val value = batteryPresentation(Reading(0, false, true, 0))
        assertEquals("0%", value.label)
        assertEquals(0, value.level)
        assertEquals("", value.note)
    }

    @Test fun trackingRequiresConnectionRouteBridgeAndCalibration() {
        val ready = SpatialState(route = true, bridge = true, calibrated = true)
        assertTrue(ready.spatialPresentation(true).trackingEnabled)
        assertFalse(ready.spatialPresentation(false).trackingEnabled)
        assertFalse(ready.copy(route = false).spatialPresentation(true).trackingEnabled)
        assertFalse(ready.copy(bridge = false).spatialPresentation(true).trackingEnabled)
        assertFalse(ready.copy(calibrated = false).spatialPresentation(true).trackingEnabled)
        assertFalse(ready.copy(calibrationMode = true).spatialPresentation(true).trackingEnabled)
        assertFalse(ready.copy(stopping = true).spatialPresentation(true).trackingEnabled)
        assertEquals(SpatialMode.OFF, ready.spatialPresentation(true).selected)
    }

    @Test fun activeOrStartingTrackingCanAlwaysBeTurnedOff() {
        for (state in listOf(SpatialState(trackingRequested = true), SpatialState(nativeActive = true))) {
            assertTrue(state.spatialPresentation(false).offEnabled)
        }
    }

    @Test fun fixedAndOffDoNotRequireHeadTrackingCalibration() {
        val value = SpatialState(route = true, bridge = true).spatialPresentation(true)
        assertTrue(value.fixedEnabled)
        assertTrue(value.offEnabled)
        assertFalse(value.trackingEnabled)
    }

    @Test fun selectionReflectsConfirmedDevicePreferenceAndTracker() {
        val off = SpatialState(bridge = true)
        assertEquals(SpatialMode.OFF, off.spatialPresentation(true).selected)
        val fixed = off.copy(compatible = true)
        assertEquals(SpatialMode.FIXED, fixed.spatialPresentation(true).selected)
        assertEquals(SpatialMode.HEAD_TRACKING, fixed.copy(nativeActive = true).spatialPresentation(true).selected)
        assertNull(fixed.copy(bridge = false).spatialPresentation(true).selected)
    }

    @Test fun pendingTrackingDoesNotPretendTheTrackerIsAlreadyRunning() {
        val state = SpatialState(route = true, bridge = true, calibrated = true, compatible = true,
            trackingRequested = true, trackingStarting = true, requestedMode = SpatialMode.HEAD_TRACKING)
        val value = state.spatialPresentation(true)
        assertEquals(SpatialMode.FIXED, value.selected)
        assertTrue(value.fixedEnabled)
        assertTrue(value.offEnabled)
        assertFalse(value.trackingEnabled)
    }

    @Test fun modeChangesAndCalibrationCannotOverlap() {
        val ready = SpatialState(route = true, bridge = true, calibrated = true)
        for (state in listOf(ready.copy(modeChanging = true, requestedMode = SpatialMode.FIXED),
            ready.copy(stopping = true), ready.copy(calibrationMode = true))) {
            val value = state.spatialPresentation(true)
            assertFalse(value.fixedEnabled)
            assertFalse(value.offEnabled)
            assertFalse(value.trackingEnabled)
        }
    }

    @Test fun endingTrackingLeavesFixedSpatialAudioSelected() {
        val tracked = SpatialState(bridge = true, compatible = true, nativeActive = true)
        assertEquals(SpatialMode.FIXED, tracked.copy(nativeActive = false).spatialPresentation(true).selected)
        assertEquals(SpatialMode.OFF, tracked.copy(nativeActive = false, compatible = false).spatialPresentation(true).selected)
    }

    @Test fun capabilitiesNameOnlyWhatFailed() {
        assertEquals("✓ 2 项已生效", capabilitySummary(mapOf("State hub" to "", "Apple advertisement relay" to "")))
        assertEquals("✗ Widget headset battery：NoSuchMethodException",
            capabilitySummary(mapOf("State hub" to "", "Widget headset battery" to "NoSuchMethodException")))
    }
}
