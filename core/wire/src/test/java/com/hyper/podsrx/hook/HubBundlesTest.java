package com.hyper.podsrx.hook;

import static org.junit.Assert.*;

import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import com.hyper.podsrx.protocol.Battery;
import com.hyper.podsrx.protocol.Reading;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class HubBundlesTest {
    /** Crosses a real parcel, as a transaction does. */
    private static Bundle parcel(Bundle in) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(in);
            parcel.setDataPosition(0);
            Bundle out = parcel.readBundle(HubBundlesTest.class.getClassLoader());
            assertNotNull(out);
            return out;
        } finally {
            parcel.recycle();
        }
    }

    private static Map<Integer, Reading> readings() {
        Map<Integer, Reading> readings = new LinkedHashMap<>();
        readings.put(4, new Reading(83, true, true, 1234L, true));
        readings.put(8, new Reading(null, false, false, 99L, false));
        return readings;
    }

    @Test public void readingsSurviveTheTrip() {
        assertEquals(readings(), HubBundles.readings(parcel(HubBundles.readings(readings()))));
    }

    @Test public void batteriesKeepUnknownLevelsAndDisconnects() {
        List<Battery> batteries = List.of(new Battery(4, 90, false, true), new Battery(8, null, false, false));
        assertEquals(batteries, HubBundles.batteries(parcel(HubBundles.batteries(batteries))));
    }

    @Test public void controlSurvivesTheTrip() {
        Map<Integer, int[]> controls = new LinkedHashMap<>();
        controls.put(0x0d, new int[] {2});
        controls.put(0x34, new int[] {1, 0});
        HubBundles.Control control = new HubBundles.Control(true, "A3064", "AirPods", "8A356",
            List.of(new Battery(2, 70, true, true)), controls, true, 5000L, true, true, false, true, 2);
        HubBundles.Control read = HubBundles.Control.from(parcel(control.toBundle()));
        assertTrue(read.connected);
        assertEquals("A3064", read.model);
        assertEquals("8A356", read.firmware);
        assertEquals(control.batteries, read.batteries);
        assertEquals(2, read.control(0x0d, 0));
        assertArrayEquals(new int[] {1, 0}, read.controls.get(0x34));
        assertEquals(7, read.control(0x0a, 7));
        assertTrue(read.ancPending);
        assertEquals(5000L, read.refreshUntil);
        assertTrue(read.spatialEnabled);
        assertFalse(read.spatialAvailable);
        assertTrue(read.spatialPending);
        assertEquals(2, read.wearCount);
    }

    @Test public void stateSurvivesTheTrip() {
        HubBundles.State state = new HubBundles.State("AA:BB:CC:DD:EE:FF", readings(), HubBundles.LID_OPEN,
            0x2720, HubBundles.Control.DISCONNECTED, true, true, HubBundles.SCAN_IDLE, 10, 8, 6, 4);
        HubBundles.State read = HubBundles.State.from(parcel(state.toBundle()));
        assertEquals(state.address, read.address);
        assertEquals(readings(), read.readings);
        assertEquals(HubBundles.LID_OPEN, read.lid);
        assertEquals(0x2720, read.product);
        assertTrue(read.appAlive);
        assertFalse(read.controlAlive());
        assertTrue(read.replaceSystemPopup);
        assertEquals(HubBundles.SCAN_IDLE, read.scanMode);
        assertEquals(10, read.broadcasts);
        assertEquals(4, read.matches);
    }

    @Test public void helloCarriesTheCallbackBinder() {
        Binder callback = new Binder();
        HubBundles.Hello read = HubBundles.Hello.from(parcel(
            new HubBundles.Hello(HubWire.VERSION, callback, "AA:BB:CC:DD:EE:FF", null, null).toBundle()));
        assertEquals(HubWire.VERSION, read.version);
        assertSame(callback, read.callback);
        assertEquals("AA:BB:CC:DD:EE:FF", read.address);
    }

    @Test public void keysSurviveTheTrip() {
        byte[] irk = new byte[16], enc = new byte[16];
        irk[0] = 1; enc[15] = 2;
        HubBundles.Keys read = HubBundles.Keys.from(parcel(new HubBundles.Keys("AA", irk, enc).toBundle()));
        assertArrayEquals(irk, read.irk);
        assertArrayEquals(enc, read.enc);
    }

    @Test public void refreshTimesAreNotAChange() {
        Map<Integer, Reading> later = new LinkedHashMap<>();
        later.put(4, new Reading(83, true, true, 9999L, true));
        later.put(8, new Reading(null, false, false, 5L, false));
        HubBundles.State one = new HubBundles.State("AA", readings(), HubBundles.LID_OPEN, -1,
            HubBundles.Control.DISCONNECTED, true, false, HubBundles.SCAN_LIVE, 1, 1, 1, 1);
        HubBundles.State two = new HubBundles.State("AA", later, HubBundles.LID_OPEN, -1,
            HubBundles.Control.DISCONNECTED, true, false, HubBundles.SCAN_LIVE, 9, 9, 9, 9);
        // Diagnostic counters change with every advertisement and are pulled, not announced.
        assertTrue(one.sameContent(two));
        later.put(4, new Reading(82, true, true, 9999L, true));
        HubBundles.State three = new HubBundles.State("AA", later, HubBundles.LID_OPEN, -1,
            HubBundles.Control.DISCONNECTED, true, false, HubBundles.SCAN_LIVE, 1, 1, 1, 1);
        assertFalse(one.sameContent(three));
    }

    @Test public void controlComparesPanelValuesByContent() {
        Map<Integer, int[]> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        a.put(0x0d, new int[] {2});
        b.put(0x0d, new int[] {2});
        HubBundles.Control one = new HubBundles.Control(true, "m", "n", "f", List.of(), a, false, 0, false, false, false, false, 2);
        HubBundles.Control two = new HubBundles.Control(true, "m", "n", "f", List.of(), b, false, 0, false, false, false, false, 2);
        assertTrue(one.sameContent(two));
        b.put(0x0d, new int[] {3});
        HubBundles.Control three = new HubBundles.Control(true, "m", "n", "f", List.of(), b, false, 0, false, false, false, false, 2);
        assertFalse(one.sameContent(three));
    }

    @Test public void missingPayloadsReadAsDefaults() {
        assertSame(HubBundles.State.EMPTY, HubBundles.State.from(null));
        assertSame(HubBundles.Control.DISCONNECTED, HubBundles.Control.from(null));
        HubBundles.State empty = HubBundles.State.from(new Bundle());
        assertEquals(HubBundles.LID_UNKNOWN, empty.lid);
        assertEquals(-1, empty.product);
        assertEquals(-1, empty.control.wearCount);
        assertTrue(HubBundles.batteries(new Bundle()).isEmpty());
    }
}
