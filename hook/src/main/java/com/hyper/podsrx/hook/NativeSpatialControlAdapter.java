package com.hyper.podsrx.hook;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;

/** Keeps the native spatial button on the same per-device renderer as the app. */
final class NativeSpatialControlAdapter {
    private static final Uri REPOSITORY = Uri.parse("content://com.android.bluetooth.ble.app.headsetdata.provider/airpodsRepository");
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Object profile;
    private int lastState = -2;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    private NativeSpatialControlAdapter(SharedPreferences preferences) { this.preferences = preferences; }

    static void install(XposedModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        NativeSpatialControlAdapter adapter = new NativeSpatialControlAdapter(preferences);
        Class<?> type = Class.forName("com.miui.headset.runtime.ProfileContext", false, loader);
        Method write = type.getDeclaredMethod("setAudioEffectState", BluetoothDevice.class, String.class, int.class);
        Method read = type.getDeclaredMethod("getAudioSpatialEffectState", BluetoothDevice.class);
        Method active = type.getMethod("getActiveDevice");
        Method callback = type.getMethod("getAudioEffectListener");
        Method invoke = callback.getReturnType().getMethod("invoke", Object.class);
        Method application = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication");
        module.hook(read).intercept(chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (!adapter.matches(device)) return chain.proceed();
            adapter.profile = chain.getThisObject();
            return adapter.state();
        });
        module.hook(write).intercept(chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (!adapter.matches(device)) return chain.proceed();
            adapter.profile = chain.getThisObject();
            int value = (int) chain.getArg(2);
            if ((value != 0 && value != 1) || adapter.state() < 0 || preferences.getBoolean("panel_spatial_pending", false)) {
                adapter.main.post(() -> adapter.refresh(active, callback, invoke, true));
                return null;
            }
            try {
                Application context = (Application) application.invoke(null);
                if (context == null) {
                    adapter.main.post(() -> adapter.refresh(active, callback, invoke, true));
                    return null;
                }
                Bundle request = new Bundle();
                request.putParcelable(BluetoothDevice.EXTRA_DEVICE, device);
                request.putString("extra_key", "hyperpods_spatial");
                request.putString("extra_value", value == 1 ? "01" : "00");
                Bundle answer = context.getContentResolver().call(REPOSITORY, "airpodsRepository", "send_command", request);
                if (answer == null || answer.getInt("extra_command_state", 0) != 1)
                    adapter.main.post(() -> adapter.refresh(active, callback, invoke, true));
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Native spatial command unavailable", error);
                adapter.main.post(() -> adapter.refresh(active, callback, invoke, true));
            }
            return null;
        });
        adapter.listener = (prefs, key) -> {
            if (key == null || key.startsWith("panel_spatial_") || "native_connected".equals(key)) {
                boolean force = key == null || "panel_spatial_pending".equals(key);
                adapter.main.post(() -> adapter.refresh(active, callback, invoke, force));
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(adapter.listener);
    }

    private void refresh(Method active, Method callback, Method invoke, boolean force) {
        Object current = profile;
        if (current == null || preferences.getBoolean("panel_spatial_pending", false)) return;
        try {
            if (!matches((BluetoothDevice) active.invoke(current))) return;
            int state = state();
            if (!force && state == lastState) return;
            Object listener = callback.invoke(current);
            if (listener != null) {
                invoke.invoke(listener, Integer.valueOf(state));
                lastState = state;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("HyperPods-X", "Native spatial state unavailable", error);
        }
    }

    private boolean matches(BluetoothDevice device) {
        long age = SystemClock.elapsedRealtime() - preferences.getLong("panel_heartbeat", -1);
        return device != null && age >= 0 && age <= 30000 && preferences.getBoolean("enabled", false) &&
            preferences.getBoolean("native_connected", false) && "A3441".equals(preferences.getString("panel_model", "")) &&
            device.getAddress().equalsIgnoreCase(preferences.getString("address", ""));
    }

    private int state() {
        if (!preferences.getBoolean("panel_spatial_available", false)) return -1;
        return preferences.getBoolean("panel_spatial_enabled", false) ? 1 : 0;
    }
}
