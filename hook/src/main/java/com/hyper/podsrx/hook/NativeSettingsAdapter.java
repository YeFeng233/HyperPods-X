package com.hyper.podsrx.hook;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;

/** Opens the module's settings from the bound headset's native More Settings action. */
final class NativeSettingsAdapter {
    private NativeSettingsAdapter() {}

    static void install(XposedModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        Class<?> profile = Class.forName("com.miui.headset.runtime.ProfileContext", false, loader);
        Method open = profile.getDeclaredMethod("switchToHeadsetActivity", BluetoothDevice.class);
        Method application = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication");
        module.hook(open).intercept(chain -> {
            try {
                String address = target((BluetoothDevice) chain.getArg(0), preferences);
                if (address != null) {
                    Application context = (Application) application.invoke(null);
                    if (context != null) {
                        context.startActivity(new Intent()
                            .setClassName(NativeControlWire.APP, NativeControlWire.APP + ".DeviceSettingsActivity")
                            .putExtra("device_address", address)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return null;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Module headset settings unavailable", error);
            }
            return chain.proceed();
        });
    }

    private static String target(BluetoothDevice device, SharedPreferences preferences) {
        long age = SystemClock.elapsedRealtime() - preferences.getLong("panel_heartbeat", -1);
        String address = preferences.getString("address", "");
        if (device == null || address == null || address.isEmpty() || age < 0 || age > 30000 ||
            !preferences.getBoolean("enabled", false) || !preferences.getBoolean("native_connected", false) ||
            !"A3441".equals(preferences.getString("panel_model", "")) ||
            !address.equalsIgnoreCase(device.getAddress())) return null;
        return address;
    }
}
