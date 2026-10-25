package com.hyper.podsrx.ui

import com.hyper.podsrx.device.Reading
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.SpatialState
import com.hyper.podsrx.device.SpatialMode
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
    }

    @Test fun localBluetoothAliasTakesPrecedenceOverHeadsetName() {
        assertEquals("AirPods", PodsState().displayName)
        assertEquals("本机的耳机", PodsState(name = "AirPods", localName = "本机的耳机").displayName)
        assertEquals("AirPods", PodsState(localName = " ").displayName)
    }

    @Test fun unknownBatteryIsNotZeroOrCharging() {
        for (reading in listOf(null, Reading(null, true, true, 0, true), Reading(101, false, true, 0, true))) {
            val value = batteryPresentation(reading)
            assertEquals("—", value.label)
            assertEquals("未读取", value.note)
            assertNull(value.level)
            assertFalse(value.charging)
        }
    }

    @Test fun staleBatteryIsMarkedAndDoesNotShowLiveCharging() {
        val value = batteryPresentation(Reading(80, true, true, 0, false))
        assertEquals("80%", value.label)
        assertEquals("上次更新", value.note)
        assertFalse(value.charging)
    }

    @Test fun approximateCurrentBatteryKeepsItsAccuracyAndChargingState() {
        val value = batteryPresentation(Reading(70, true, false, 0, true))
        assertEquals("≈70%", value.label)
        assertEquals("充电中", value.note)
        assertTrue(value.charging)
    }

    @Test fun emptyBatteryIsAValidReading() {
        val value = batteryPresentation(Reading(0, false, true, 0, true))
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
}
