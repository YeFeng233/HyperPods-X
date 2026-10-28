package com.hyper.podsrx.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * The battery widget refreshes from broadcasts that apps may not send: the camera-grip one that
 * carries the case, and the hands-free battery one that makes it query the headset slot again.
 * The Bluetooth host sends them whenever the app publishes a new widget sequence, either through
 * its direct broadcast or, after a host restart, from the module preferences.
 */
final class WidgetRelayHost {
    private static final String GRIP = "miui.intent.action.ACTION_HANDLE_BATTERY_STATE_CHANGED";
    private static final String GRIP_CONNECTED = "miui.intent.extra.EXTRA_HANDLE_CONNECT_STATE";
    private static final String HEADSET = "android.intent.action.BLUETOOTH_HANDSFREE_BATTERY_CHANGED";
    // The provider reads the module preferences, which reach the security center a moment later.
    private static final long PROVIDER_DELAY_MS = 600;
    // Remote preferences hold listeners weakly.
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    // The direct broadcast and the preference listener both report each sequence.
    private static long sent = Long.MIN_VALUE;
    private static int headset = Integer.MIN_VALUE;

    private static final class Slots {
        final long sequence; final int headset; final int caseLevel; final boolean charging;
        Slots(long sequence, int headset, int caseLevel, boolean charging) {
            this.sequence = sequence; this.headset = headset; this.caseLevel = caseLevel; this.charging = charging;
        }
        static Slots of(SharedPreferences preferences) {
            boolean enabled = preferences.getBoolean("enabled", false);
            return new Slots(preferences.getLong(WidgetWire.SEQUENCE, 0),
                enabled ? preferences.getInt(WidgetWire.HEADSET, -1) : -1,
                enabled ? preferences.getInt(WidgetWire.CASE, -1) : -1,
                preferences.getBoolean(WidgetWire.CASE_CHARGING, false));
        }
        static Slots of(Intent intent) {
            return new Slots(intent.getLongExtra(WidgetWire.SEQUENCE, 0), intent.getIntExtra(WidgetWire.HEADSET, -1),
                intent.getIntExtra(WidgetWire.CASE, -1), intent.getBooleanExtra(WidgetWire.CASE_CHARGING, false));
        }
    }

    static void install(Context context, SharedPreferences preferences) {
        Handler main = new Handler(Looper.getMainLooper());
        listener = (prefs, key) -> {
            if (ReloadLifecycle.isActive() && WidgetWire.SEQUENCE.equals(key)) relay(context, () -> Slots.of(prefs), main);
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (ReloadLifecycle.isActive()) relay(context, () -> Slots.of(intent), main);
            }
        };
        context.registerReceiver(receiver, new IntentFilter(WidgetWire.ACTION), NativePopupHost.PERMISSION, main, Context.RECEIVER_EXPORTED);
        ReloadLifecycle.add(() -> {
            preferences.unregisterOnSharedPreferenceChangeListener(listener);
            listener = null;
            context.unregisterReceiver(receiver);
            main.removeCallbacksAndMessages(null);
        });
        // A restarted host catches the widget up with the last published slots.
        relay(context, () -> Slots.of(preferences), main);
        DiagnosticLog.i("HyperPods-X", "Widget battery relay ready");
    }

    private interface Source { Slots read(); }

    // A refused broadcast must never take the Bluetooth process down with it.
    private static void relay(Context context, Source source, Handler main) {
        try { send(context, source.read(), main); }
        catch (RuntimeException error) { DiagnosticLog.w("HyperPods-X", "Widget battery relay skipped", error); }
    }

    private static void send(Context context, Slots slots, Handler main) {
        if (slots.sequence == sent) return;
        sent = slots.sequence;
        boolean present = slots.caseLevel >= 0 && slots.caseLevel <= 100;
        context.sendBroadcast(new Intent(GRIP).putExtra(GRIP_CONNECTED, present ? 1 : 0)
            .putExtra("batteryLevel", present ? slots.caseLevel : 0)
            .putExtra("batteryStats", present && slots.charging ? "Charging" : "Discharging"));
        if (slots.headset == headset) return;
        headset = slots.headset;
        main.postDelayed(() -> {
            if (!ReloadLifecycle.isActive()) return;
            try {
                context.sendBroadcast(new Intent(HEADSET).putExtra("android.intent.extra.show_bluetooth_handsfree_battery", 1));
            } catch (RuntimeException error) { DiagnosticLog.w("HyperPods-X", "Widget headset refresh skipped", error); }
        }, PROVIDER_DELAY_MS);
    }
}
