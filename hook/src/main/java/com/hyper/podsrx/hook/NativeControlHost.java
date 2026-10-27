package com.hyper.podsrx.hook;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.util.Locale;
import java.lang.reflect.Method;

/** Projects the app-owned session into HyperOS's existing headset interfaces. */
final class NativeControlHost {
    private static final String AUTHORITY = "com.android.bluetooth.ble.app.headsetdata.provider";
    private static final Uri STATE = Uri.parse("content://" + AUTHORITY + "/airpodsstate");
    // This is a presentation template only. Never write it to AirpodsModel: doing
    // so would let AirCoreManager open another AACP socket and race the app.
    private static final String TEMPLATE = "1B20";
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile PendingIntent target;
    private volatile String registeredAddress = "";
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final Runnable notify = this::notifyState;

    private NativeControlHost(Context context, SharedPreferences preferences) {
        this.context = context;
        this.preferences = preferences;
    }

    static void install(XposedModule module, Context context, SharedPreferences preferences) throws Exception {
        NativeControlHost host = new NativeControlHost(context, preferences);
        ClassLoader loader = context.getClassLoader();
        Class<?> state = Class.forName("com.android.bluetooth.ble.app.offlinefind.OfflineFindProvider", false, loader);
        Class<?> provider = Class.forName("com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider", false, loader);
        Method identity = state.getDeclaredMethod("isAppleHeadset", String.class, Context.class);
        Method stateRead = state.getDeclaredMethod("getAirPodsState", String.class, Context.class);
        Method providerCall = provider.getDeclaredMethod("call", String.class, String.class, Bundle.class);
        module.hook(identity).intercept(chain ->
            host.matches((String) chain.getArg(0)) ? true : chain.proceed());
        module.hook(stateRead).intercept(chain ->
            host.matches((String) chain.getArg(0)) ? host.state() : chain.proceed());
        module.hook(providerCall).intercept(chain -> {
            if ("getAirpodsState".equals(chain.getArg(0))) {
                Object original = chain.proceed();
                if (!(original instanceof Bundle result) || !host.matches((String) chain.getArg(1))) return original;
                String[] values = host.state();
                result.putString("modelName", TEMPLATE);
                result.putString("connectState", "2");
                String[] keys = {"isLeftWearing", "leftBattery", "isRightWearing", "rightBattery", "boxBattery",
                    "isLeftCharging", "isRightCharging", "isBoxCharging"};
                for (int i = 0; i < keys.length; i++) result.putString(keys[i], values[i]);
                return result;
            }
            if (!"airpodsRepository".equals(chain.getArg(0))) return chain.proceed();
            Bundle request = (Bundle) chain.getArg(2);
            if (request == null) return chain.proceed();
            BluetoothDevice device;
            try { device = request.getParcelable(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class); }
            catch (RuntimeException malformed) { return chain.proceed(); }
            if (device == null || !host.matches(device.getAddress())) return chain.proceed();
            // Probe through the original read path, preserving BOTH its provider
            // permission and repository package/signature policy. Never execute
            // the original native send_command for this app-owned session.
            Object authorized = chain.proceed(new Object[] {"airpodsRepository", "get_state", new Bundle(request)});
            if (!(authorized instanceof Bundle)) return authorized;
            Bundle answer = host.repository((String) chain.getArg(1), request);
            return answer != null ? answer : chain.proceed();
        });
        host.register();
        Log.i("HyperPods-X", "Native fusion headset control bridge ready");
    }

    private void register() {
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent request) {
                long age = SystemClock.elapsedRealtime() - request.getLongExtra("created", -1);
                if (!NativeControlWire.REGISTER.equals(request.getAction()) || age < 0 || age > 2000) return;
                String address = request.getStringExtra("address");
                PendingIntent callback = request.getParcelableExtra("target", PendingIntent.class);
                if (!BluetoothAdapter.checkBluetoothAddress(address) || callback == null ||
                        !NativeControlWire.APP.equals(callback.getCreatorPackage())) return;
                registeredAddress = address;
                target = callback;
                scheduleNotify();
            }
        }, new IntentFilter(NativeControlWire.REGISTER), NativeControlWire.PERMISSION, main, Context.RECEIVER_EXPORTED);
        listener = (prefs, key) -> {
            if (key == null || key.startsWith("panel_") || key.startsWith("native_") || "address".equals(key)) scheduleNotify();
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private boolean matches(String address) {
        long age = SystemClock.elapsedRealtime() - preferences.getLong("panel_heartbeat", -1);
        return address != null && age >= 0 && age <= 30000 &&
            preferences.getBoolean("enabled", false) && preferences.getBoolean("native_connected", false) &&
            "A3441".equals(preferences.getString("panel_model", "")) &&
            address.equalsIgnoreCase(preferences.getString("address", ""));
    }

    private String[] state() {
        int charging = preferences.getInt("native_charging", 0);
        return new String[] {
            Boolean.toString(preferences.getInt("panel_left_wearing", -1) == 1), battery("left"),
            Boolean.toString(preferences.getInt("panel_right_wearing", -1) == 1), battery("right"), battery("box"),
            Boolean.toString((charging & 1) != 0), Boolean.toString((charging & 2) != 0),
            Boolean.toString((charging & 4) != 0), TEMPLATE, "true"
        };
    }

    private String battery(String component) {
        int value = preferences.getInt("native_" + component, -1);
        return value >= 0 && value <= 100 ? Integer.toString(value) : "-";
    }

    private Bundle repository(String action, Bundle request) {
        String key = request.getString("extra_key", "");
        Bundle result = new Bundle(request);
        if ("check_feature_support".equals(action)) {
            // In particular ConnectL2cap remains disabled for this app-owned device.
            result.putBoolean("extra_feature_support", "NoiseControl".equals(key) && preferences.getBoolean("panel_anc_available", false));
            return result;
        }
        if ("get_state".equals(action)) {
            String value = read(key);
            if (value == null) return null;
            result.putString("extra_value", value);
            return result;
        }
        if ("send_command".equals(action) && "air_anc".equals(key)) {
            int value;
            try { value = Integer.parseInt(request.getString("extra_value", ""), 16); }
            catch (NumberFormatException error) { value = 0; }
            boolean accepted = value >= 1 && value <= 4 && preferences.getBoolean("panel_anc_available", false) &&
                !preferences.getBoolean("panel_anc_pending", false) && send("anc", value);
            result.putInt("extra_command_state", accepted ? 1 : 0);
            return result;
        }
        if ("send_command".equals(action) && "hyperpods_spatial".equals(key)) {
            String value = request.getString("extra_value", "");
            boolean accepted = ("00".equals(value) || "01".equals(value)) &&
                preferences.getBoolean("panel_spatial_available", false) &&
                !preferences.getBoolean("panel_spatial_pending", false) && send("spatial", "01".equals(value) ? 1 : 0);
            result.putInt("extra_command_state", accepted ? 1 : 0);
            return result;
        }
        // Never persist a client-side guess of an acknowledged headset setting.
        if ("set_state".equals(action) && read(key) != null) return result;
        return null;
    }

    private String read(String key) {
        if ("type".equals(key)) return TEMPLATE;
        if ("hyperpods_spatial".equals(key)) return preferences.getBoolean("panel_spatial_enabled", false) ? "01" : "00";
        if ("air_anc".equals(key)) {
            int value = preferences.getInt("panel_anc", 0);
            return value >= 1 && value <= 4 ? String.format(Locale.ROOT, "%02X", value) : "";
        }
        if ("air_wear_state_format".equals(key)) {
            int count = preferences.getInt("panel_wear_count", -1);
            return count == 2 ? "02" : count == 1 ? "01" : count == 0 ? "00" : "";
        }
        String preference = switch (key) {
            case "air_ear_detection" -> "panel_ear_detection";
            case "air_one_bud_anc_mode" -> "panel_one_bud_anc";
            case "air_off_option_listening_mode" -> "panel_off_mode";
            default -> null;
        };
        if (preference != null) {
            int value = preferences.getInt(preference, 0);
            return value == 1 || value == 2 ? String.format(Locale.ROOT, "%02X", value) : "";
        }
        return null;
    }

    private boolean send(String command, int value) {
        PendingIntent callback = target;
        if (callback == null || !matches(registeredAddress)) return false;
        try {
            callback.send(context, 0, new Intent().putExtra("address", registeredAddress)
                .putExtra("created", SystemClock.elapsedRealtime()).putExtra("command", command).putExtra("value", value));
            Log.i("HyperPods-X", "Forwarded native panel " + command + " request: " + value);
            return true;
        } catch (PendingIntent.CanceledException error) {
            target = null;
            scheduleNotify();
            return false;
        }
    }

    private void scheduleNotify() {
        main.removeCallbacks(notify);
        main.postDelayed(notify, 100);
    }

    private void notifyState() {
        try {
            context.getContentResolver().notifyChange(STATE, null, false);
            String address = registeredAddress;
            if (!matches(address)) return;
            for (String key : new String[] {"air_anc", "air_wear_state_format"}) {
                String value = read(key);
                if (value == null || value.isEmpty()) continue;
                // The platform repository observer consumes the first parameter as
                // its state key, so it must precede the address parameter.
                Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("airpodsRepository")
                    .appendQueryParameter(key, value).appendQueryParameter("address", address).build();
                context.getContentResolver().notifyChange(uri, null, false);
            }
        } catch (RuntimeException error) {
            Log.w("HyperPods-X", "Native headset state notification failed", error);
        }
    }
}
