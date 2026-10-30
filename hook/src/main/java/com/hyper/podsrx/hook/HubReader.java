package com.hyper.podsrx.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import com.hyper.podsrx.protocol.Reading;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The hub's live state seen through the preference keys the host adapters were written against.
 * Outside the Bluetooth host it pulls the snapshot through the headset data provider and refreshes
 * on the hub's change notice; inside it reads the hub directly. Read-only: configuration still
 * comes from the app's remote preferences, live state never does.
 */
final class HubReader implements SharedPreferences {
    private static final String TAG = "HyperPods-X";
    /** A missing hub is asked again no sooner than this. */
    private static final long RETRY_MS = 2000;

    private final Supplier<HubBundles.State> local;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile HubBundles.State state = HubBundles.State.EMPTY;
    private volatile boolean loaded;
    private volatile long triedAt = Long.MIN_VALUE / 2;
    private volatile Context context;
    private BroadcastReceiver receiver;
    private boolean closed;

    private HubReader(Supplier<HubBundles.State> local) {
        this.local = local;
    }

    /** Inside the Bluetooth host; the hub calls {@link #changed()} itself. */
    static HubReader local(Supplier<HubBundles.State> hub) {
        return new HubReader(hub);
    }

    /**
     * In another host process. Hooks install before the host's Application exists, so the reader
     * attaches to it on first use, or on the main thread right after the Application is created.
     */
    static HubReader remote() {
        HubReader reader = new HubReader(null);
        ReloadLifecycle.add(reader::close);
        reader.main.post(reader::attach);
        return reader;
    }

    private synchronized Context attach() {
        if (context != null || closed) return context;
        Application app;
        try { app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); }
        catch (ReflectiveOperationException | RuntimeException error) { app = null; }
        if (app == null) {
            main.postDelayed(this::attach, 200);
            return null;
        }
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!ReloadLifecycle.isActive() || !HubWire.CHANGED.equals(intent.getAction())) return;
                if (getSentFromUid() != Process.BLUETOOTH_UID) return;
                pull();
                changed();
            }
        };
        app.registerReceiver(receiver, new IntentFilter(HubWire.CHANGED), null, main, Context.RECEIVER_EXPORTED);
        context = app;
        return app;
    }

    private synchronized void close() {
        closed = true;
        if (receiver != null && context != null) {
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        }
        receiver = null;
        main.removeCallbacksAndMessages(null);
        listeners.clear();
    }

    /** The current snapshot; the first read in a process asks the hub synchronously. */
    HubBundles.State state() {
        if (local != null) return local.get();
        if (!loaded && SystemClock.elapsedRealtime() - triedAt >= RETRY_MS) pull();
        return state;
    }

    /** Asks the hub now. It reads one volatile reference there, so this never waits on a lock. */
    void pull() {
        if (local != null) return;
        Context context = attach();
        if (context == null) return;
        triedAt = SystemClock.elapsedRealtime();
        try {
            Bundle answer = context.getContentResolver().call(Uri.parse(HubWire.PROVIDER_URI), HubWire.STATE_METHOD, null, null);
            if (answer == null) return;
            state = HubBundles.State.from(answer);
            loaded = true;
            Compatibility.hubStarted(state.started);
        } catch (RuntimeException error) {
            DiagnosticLog.w(TAG, "Hub snapshot unavailable", error);
        }
    }

    /** Every key may have changed; listeners treat a null key that way. */
    void changed() {
        Runnable notify = () -> { for (OnSharedPreferenceChangeListener listener : listeners) listener.onSharedPreferenceChanged(this, null); };
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run();
        else main.post(notify);
    }

    // The legacy keys

    private static int level(Map<Integer, Reading> readings, int component) {
        Reading reading = readings.get(component);
        Integer level = reading == null ? null : reading.getLevel();
        return level != null && level >= 0 && level <= 100 ? level : -1;
    }

    private static boolean charging(Map<Integer, Reading> readings, int component) {
        Reading reading = readings.get(component);
        return reading != null && reading.getLevel() != null && reading.getCharging();
    }

    /** The settings entry's summary, as the app writes it. */
    static String summary(Context context, Map<Integer, Reading> readings) {
        StringBuilder out = new StringBuilder();
        int[][] parts = {{4, R.string.hook_left}, {2, R.string.hook_right}, {8, R.string.hook_case}};
        for (int[] part : parts) {
            if (out.length() > 0) out.append(" · ");
            Reading reading = readings.get(part[0]);
            out.append(HookStrings.get(context, part[1])).append(' ');
            if (reading == null || reading.getLevel() == null) out.append('—');
            else out.append(reading.getPrecise() ? "" : HookStrings.get(context, R.string.hook_approximately)).append(reading.getLevel()).append('%');
        }
        return out.toString();
    }

    private Object value(HubBundles.State s, String key) {
        HubBundles.Control c = s.control;
        int wear = c.wearCount;
        switch (key) {
            case "address": return s.address;
            case "enabled": return !s.address.isEmpty();
            case "summary": {
                Context host = attach();
                return s.address.isEmpty() || host == null ? null : summary(host, s.readings);
            }
            // Hub state is live by construction; the freshness checks written for preferences pass.
            case "updated": return s.address.isEmpty() ? 0L : System.currentTimeMillis();
            case "panel_heartbeat": return s.controlAlive() ? SystemClock.elapsedRealtime() : -1L;
            case "replace_system_popup": return s.replaceSystemPopup;
            case "native_connected": return s.controlAlive();
            case "native_left": return level(s.readings, 4);
            case "native_right": return level(s.readings, 2);
            case "native_box": case "native_box_history": return level(s.readings, 8);
            case "native_charging": return (charging(s.readings, 4) ? 1 : 0) | (charging(s.readings, 2) ? 2 : 0) | (charging(s.readings, 8) ? 4 : 0);
            case "widget_headset": return WidgetDriver.headsetLevel(s.readings);
            case "panel_model": return c.model;
            case "panel_product": return s.product;
            case "panel_anc": return c.control(0x0d, 0);
            case "panel_anc_available": return c.controls.containsKey(0x0d);
            case "panel_anc_pending": return c.ancPending;
            case "panel_readback_until": return c.refreshUntil;
            case "panel_audio_route": return c.audioRoute;
            case "panel_ear_detection": return c.control(0x0a, 0);
            case "panel_one_bud_anc": return c.control(0x1b, 0);
            case "panel_off_mode": return c.control(0x34, 0);
            case "panel_spatial_enabled": return c.spatialEnabled;
            case "panel_spatial_available": return c.spatialAvailable;
            case "panel_spatial_pending": return c.spatialPending;
            case "panel_wear_count": return wear;
            // AACP reports primary and secondary ears, so a single in-ear event names no side.
            case "panel_left_wearing": case "panel_right_wearing": return wear == 0 ? 0 : wear == 2 ? 1 : -1;
            default: return null;
        }
    }

    @Override public Map<String, ?> getAll() {
        HubBundles.State s = state();
        Map<String, Object> all = new HashMap<>();
        for (String key : new String[] {"address", "enabled", "summary", "updated", "panel_heartbeat", "replace_system_popup",
                "native_connected", "native_left", "native_right", "native_box", "native_box_history", "native_charging",
                "widget_headset", "panel_model", "panel_product", "panel_anc", "panel_anc_available", "panel_anc_pending",
                "panel_readback_until", "panel_audio_route", "panel_ear_detection", "panel_one_bud_anc", "panel_off_mode",
                "panel_spatial_enabled", "panel_spatial_available", "panel_spatial_pending", "panel_wear_count",
                "panel_left_wearing", "panel_right_wearing"}) {
            Object value = value(s, key);
            if (value != null) all.put(key, value);
        }
        return all;
    }

    @Override public String getString(String key, String fallback) {
        Object value = value(state(), key);
        return value instanceof String ? (String) value : fallback;
    }

    @Override public Set<String> getStringSet(String key, Set<String> fallback) { return fallback; }

    @Override public int getInt(String key, int fallback) {
        Object value = value(state(), key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    @Override public long getLong(String key, long fallback) {
        Object value = value(state(), key);
        return value instanceof Long ? (Long) value : fallback;
    }

    @Override public float getFloat(String key, float fallback) { return fallback; }

    @Override public boolean getBoolean(String key, boolean fallback) {
        Object value = value(state(), key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @Override public boolean contains(String key) {
        return value(state(), key) != null;
    }

    @Override public Editor edit() {
        throw new UnsupportedOperationException("Hub state is read-only");
    }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.addIfAbsent(listener);
    }

    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }
}
