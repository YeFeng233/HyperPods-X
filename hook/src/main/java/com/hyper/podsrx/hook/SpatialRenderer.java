package com.hyper.podsrx.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Spatializer;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

/** Activates HyperOS rendering while the saved AirPods preference is applicable. */
final class SpatialRenderer {
    private static final String TAG = "HyperPods-X";
    private static final String RENDERER_ENABLED = "spatial_audio_feature_enable";
    private static final String NATIVE_PREFERENCE = "last_spatial_status";
    private static final String RESTORE_ON_EXIT = "restore_on_exit";

    private final Context context;
    private final SharedPreferences preferences;
    private final SharedPreferences rendererState;
    private final AudioManager audio;
    private final Spatializer spatializer;
    private final SpatialOutputPreference outputPreference;
    private Method removeListener;
    private Object routeListener;
    private boolean closed;

    SpatialRenderer(Context context, SharedPreferences preferences, AudioManager audio,
            Spatializer spatializer, SpatialOutputPreference outputPreference) {
        this.context = context;
        this.preferences = preferences;
        rendererState = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("hyperpods_spatial_renderer", Context.MODE_PRIVATE);
        this.audio = audio;
        this.spatializer = spatializer;
        this.outputPreference = outputPreference;
    }

    void start() {
        try {
            Class<?> listener = Class.forName("android.media.AudioManager$OnDevicesForAttributesChangedListener");
            Method addListener = AudioManager.class.getMethod("addOnDevicesForAttributesChangedListener",
                AudioAttributes.class, Executor.class, listener);
            removeListener = AudioManager.class.getMethod("removeOnDevicesForAttributesChangedListener", listener);
            Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                (proxy, method, args) -> switch (method.getName()) {
                    case "onDevicesForAttributesChanged" -> { onRouteChanged(); yield null; }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "HyperPods-X spatial route listener";
                    default -> null;
                });
            addListener.invoke(audio, SpatialOutputPreference.MEDIA, context.getMainExecutor(), callback);
            routeListener = callback;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            DiagnosticLog.w(TAG, "Spatial route listener unavailable", error);
        }
        context.getMainExecutor().execute(this::onRouteChanged);
    }

    private void onRouteChanged() {
        try {
            sync();
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w(TAG, "Spatial renderer route update failed", error);
        }
    }

    synchronized void sync() throws ReflectiveOperationException {
        if (closed || !ReloadLifecycle.canStartOperation()) return;
        int enabled = setting(RENDERER_ENABLED, -1);
        if (enabled != 0 && enabled != 1) return;

        String address = preferences.getString("address", "");
        boolean boundOutput = preferences.getBoolean("enabled", false) && !address.isEmpty() &&
            SpatialOutputPreference.isBoundMediaOutput(audio, address);
        if (!boundOutput || !outputPreference.isEnabled(spatializer, outputPreference.a2dpDevice(address))) {
            restoreNativeRenderer();
            return;
        }
        if (enabled == 0 && routeListener != null && SpatialOutputPreference.isBoundMediaOutput(audio, address)) {
            // The framework persists its renderer gate. Record the override before changing it,
            // so a restarted Bluetooth host can also restore the native preference.
            if (setting(NATIVE_PREFERENCE, 0) == 0 &&
                    !rendererState.edit().putBoolean(RESTORE_ON_EXIT, true).commit()) {
                throw new IllegalStateException("Cannot record the spatial renderer override");
            }
            setRendererEnabled(true);
            DiagnosticLog.i(TAG, "Spatial renderer enabled for the bound AirPods");
        }
    }

    private void restoreNativeRenderer() throws ReflectiveOperationException {
        if (!rendererState.getBoolean(RESTORE_ON_EXIT, false)) return;
        // MiSound stores the general preference separately from the renderer's current setting.
        if (setting(NATIVE_PREFERENCE, 0) == 0 && setting(RENDERER_ENABLED, 0) == 1) {
            setRendererEnabled(false);
            DiagnosticLog.i(TAG, "Restored the native spatial renderer setting");
        }
        rendererState.edit().remove(RESTORE_ON_EXIT).apply();
    }

    private int setting(String key, int fallback) {
        return Settings.Global.getInt(context.getContentResolver(), key, fallback);
    }

    private void setRendererEnabled(boolean enabled) throws ReflectiveOperationException {
        Spatializer.class.getMethod("setEnabled", boolean.class).invoke(spatializer, enabled);
    }

    synchronized void close() {
        closed = true;
        if (routeListener != null) {
            try {
                removeListener.invoke(audio, routeListener);
            } catch (ReflectiveOperationException | RuntimeException error) {
                DiagnosticLog.w(TAG, "Spatial route listener removal failed", error);
            }
            routeListener = null;
        }
        try {
            restoreNativeRenderer();
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w(TAG, "Spatial renderer restore failed", error);
        }
    }
}
