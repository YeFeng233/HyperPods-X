package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.hyper.podsrx.protocol.Reading;
import java.util.Map;

/**
 * Feeds the security center "device battery" widget from the hub's readings. The case rides the
 * camera-grip slot; the headset slot is answered by the widget provider hook, and this only sends
 * the broadcasts that make the widget read it again. Apps may not send either broadcast. Main
 * thread only.
 */
final class WidgetDriver {
    private static final String GRIP = "miui.intent.action.ACTION_HANDLE_BATTERY_STATE_CHANGED";
    private static final String GRIP_CONNECTED = "miui.intent.extra.EXTRA_HANDLE_CONNECT_STATE";
    private static final String HEADSET = "android.intent.action.BLUETOOTH_HANDSFREE_BATTERY_CHANGED";
    private static final String HEADSET_SHOW = "android.intent.extra.show_bluetooth_handsfree_battery";
    private static final String HEADSET_LEVEL = "android.intent.extra.bluetooth_handsfree_battery_level";
    /** The widget forgets the grip when the launcher restarts; a slow repeat brings it back. */
    private static final long RESEND_MS = 60_000;
    /** Lets the hub's change notice reach the security center before the widget asks again. */
    private static final long HEADSET_DELAY_MS = 600;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int headset = Integer.MIN_VALUE, caseLevel = Integer.MIN_VALUE;
    private boolean charging;
    /** Null on the first update, so an old status-bar indicator is explicitly withdrawn. */
    private Boolean headsetShown;
    private int headsetShownLevel = Integer.MIN_VALUE;
    private long sentAt = Long.MIN_VALUE / 2;

    WidgetDriver(Context context) {
        this.context = context;
    }

    /** The mean of the earbuds, as the widget shows one headset level; -1 when neither is known. */
    static int headsetLevel(Map<Integer, Reading> readings) {
        int sum = 0, count = 0;
        for (int component : new int[] {4, 2}) {
            Reading reading = readings.get(component);
            Integer level = reading == null ? null : reading.getLevel();
            if (level != null && level >= 0 && level <= 100) { sum += level; count++; }
        }
        return count == 0 ? -1 : sum / count;
    }

    void update(HubBundles.State state) {
        boolean bound = !state.address.isEmpty();
        int nextHeadset = bound ? headsetLevel(state.readings) : -1;
        Reading box = bound ? state.readings.get(8) : null;
        int nextCase = box != null && box.getLevel() != null && box.getLevel() >= 0 && box.getLevel() <= 100 ? box.getLevel() : -1;
        boolean nextCharging = nextCase >= 0 && box.getCharging();
        long now = SystemClock.elapsedRealtime();
        boolean resend = nextCase >= 0 && now - sentAt > RESEND_MS;
        // Evaluate physical connection on every update, but avoid repeating unchanged broadcasts.
        boolean widgetChanged = nextHeadset != headset || nextCase != caseLevel || nextCharging != charging || resend;
        // Case advertisements can retain valid earbud levels long after both buds are docked.
        boolean bothCharging = charging(state.readings, 4) && charging(state.readings, 2);
        boolean showHeadset = nextHeadset >= 0 && !bothCharging && boundDeviceConnected(state);
        boolean headsetChanged = headsetShown == null || headsetShown != showHeadset
            || (showHeadset && headsetShownLevel != nextHeadset);
        if (!widgetChanged && !headsetChanged) return;
        headset = nextHeadset;
        headsetShown = showHeadset;
        headsetShownLevel = showHeadset ? nextHeadset : -1;
        caseLevel = nextCase;
        charging = nextCharging;
        if (widgetChanged) {
            sentAt = now;
            try {
                context.sendBroadcast(new Intent(GRIP).putExtra(GRIP_CONNECTED, nextCase >= 0 ? 1 : 0)
                    .putExtra("batteryLevel", Math.max(nextCase, 0))
                    .putExtra("batteryStats", nextCharging ? "Charging" : "Discharging"));
            } catch (RuntimeException error) {
                DiagnosticLog.w("HyperPods-X", "Widget case refresh skipped", error);
            }
        }
        if (!headsetChanged) return;
        main.removeCallbacksAndMessages(null);
        main.postDelayed(() -> {
            if (!ReloadLifecycle.isActive()) return;
            try {
                // The status bar also consumes this broadcast; send a real level on connection,
                // and explicitly withdraw it after docking, disconnect or loss of readings.
                // This extra is a BOOLEAN in MIUI/HyperOS. Passing int 0/1 makes
                // getBooleanExtra(..., true) fall back to true, leaving a 0%-style icon.
                Intent notice = new Intent(HEADSET).putExtra(HEADSET_SHOW, showHeadset);
                if (showHeadset) notice.putExtra(HEADSET_LEVEL, toSystemBatteryLevel(nextHeadset));
                context.sendBroadcast(notice);
            } catch (RuntimeException error) {
                DiagnosticLog.w("HyperPods-X", "Widget headset refresh skipped", error);
            }
        }, HEADSET_DELAY_MS);
    }

    /** MIUI SystemUI's handsfree battery slot uses 0..9 for ten 10%-wide levels. */
    private static int toSystemBatteryLevel(int percentage) {
        return Math.max(0, Math.min(9, (percentage - 1) / 10));
    }

    /** The bound device may remain physically connected while the app control channel restarts. */
    private boolean boundDeviceConnected(HubBundles.State state) {
        if (!BluetoothAdapter.checkBluetoothAddress(state.address)) return false;
        try {
            BluetoothManager bluetooth = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bluetooth == null ? null : bluetooth.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            BluetoothDevice device = adapter.getRemoteDevice(state.address);
            // System API on Android; use reflection because the public SDK omits it.
            Object connected = BluetoothDevice.class.getMethod("isConnected").invoke(device);
            if (connected instanceof Boolean) return (Boolean) connected;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // On a ROM that blocks the system method, use the module's live AACP channel.
            return state.controlAlive();
        }
        return state.controlAlive();
    }

    private static boolean charging(Map<Integer, Reading> readings, int component) {
        Reading r = readings.get(component);
        return r != null && r.getLevel() != null && r.getCharging();
    }

    void close() {
        main.removeCallbacksAndMessages(null);
    }
}
