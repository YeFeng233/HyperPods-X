package com.hyper.podsrx.hook;

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
    /** The widget forgets the grip when the launcher restarts; a slow repeat brings it back. */
    private static final long RESEND_MS = 60_000;
    /** Lets the hub's change notice reach the security center before the widget asks again. */
    private static final long HEADSET_DELAY_MS = 600;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int headset = Integer.MIN_VALUE, caseLevel = Integer.MIN_VALUE;
    private boolean charging;
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
        if (nextHeadset == headset && nextCase == caseLevel && nextCharging == charging && !resend) return;
        boolean headsetChanged = nextHeadset != headset;
        headset = nextHeadset;
        caseLevel = nextCase;
        charging = nextCharging;
        sentAt = now;
        try {
            context.sendBroadcast(new Intent(GRIP).putExtra(GRIP_CONNECTED, nextCase >= 0 ? 1 : 0)
                .putExtra("batteryLevel", Math.max(nextCase, 0))
                .putExtra("batteryStats", nextCharging ? "Charging" : "Discharging"));
        } catch (RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "Widget case refresh skipped", error);
        }
        if (!headsetChanged) return;
        main.removeCallbacksAndMessages(null);
        main.postDelayed(() -> {
            if (!ReloadLifecycle.isActive()) return;
            try {
                context.sendBroadcast(new Intent(HEADSET).putExtra("android.intent.extra.show_bluetooth_handsfree_battery", 1));
            } catch (RuntimeException error) {
                DiagnosticLog.w("HyperPods-X", "Widget headset refresh skipped", error);
            }
        }, HEADSET_DELAY_MS);
    }

    void close() {
        main.removeCallbacksAndMessages(null);
    }
}
