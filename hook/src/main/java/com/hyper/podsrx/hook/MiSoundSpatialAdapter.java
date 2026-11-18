package com.hyper.podsrx.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Spatializer;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Mirrors explicit MiSound toggles to the bound A2DP output preference. */
final class MiSoundSpatialAdapter {
    private static final String TAG = "HyperPods-X";
    private static final String ACTIVITY = HubWire.SOUND_SETTINGS + ".HeadsetSettingsActivity";
    private static final long SUPPORTED_VERSION = 260917;
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).build();

    private final SharedPreferences preferences;
    private final SpatialOutputPreference outputPreference;
    private long hostVersion = -1;

    private MiSoundSpatialAdapter(SharedPreferences preferences) throws ClassNotFoundException {
        this.preferences = preferences;
        outputPreference = new SpatialOutputPreference();
    }

    static void install(XposedModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        MiSoundSpatialAdapter adapter = new MiSoundSpatialAdapter(preferences);
        Class<?> fragment = Class.forName("h.r", false, loader);
        Method setSpatialEffect = fragment.getDeclaredMethod("Q0", boolean.class);
        Method getActivity = fragment.getMethod("getActivity");
        if (setSpatialEffect.getReturnType() != void.class || !Modifier.isPrivate(setSpatialEffect.getModifiers())) {
            throw new NoSuchMethodException("MiSound spatial click signature changed");
        }
        module.hook(setSpatialEffect).intercept(chain -> {
            if (ReloadLifecycle.canStartOperation()) {
                try {
                    if (getActivity.invoke(chain.getThisObject()) instanceof Activity activity &&
                            ACTIVITY.equals(activity.getClass().getName())) {
                        adapter.applyOutputPreference(activity, (boolean) chain.getArg(0));
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    DiagnosticLog.w(TAG, "MiSound AirPods spatial preference unavailable", error);
                }
            }
            return chain.proceed();
        });
    }

    private void applyOutputPreference(Activity activity, boolean enabled) throws ReflectiveOperationException {
        if (hostVersion < 0) {
            try {
                hostVersion = activity.getPackageManager().getPackageInfo(HubWire.SOUND_SETTINGS, 0).getLongVersionCode();
            } catch (PackageManager.NameNotFoundException error) {
                throw new IllegalStateException("MiSound package unavailable", error);
            }
            if (hostVersion != SUPPORTED_VERSION) {
                DiagnosticLog.w(TAG, "MiSound spatial click unsupported on version " + hostVersion);
            }
        }
        if (hostVersion != SUPPORTED_VERSION || !preferences.getBoolean("enabled", false)) return;
        String address = preferences.getString("address", "");
        if (address.isEmpty()) return;

        AudioManager audio = activity.getSystemService(AudioManager.class);
        if (audio == null || !isBoundOutput(audio, address)) {
            DiagnosticLog.i(TAG, "MiSound spatial toggle skipped: bound AirPods are not the sole A2DP media output");
            return;
        }
        Spatializer spatializer = audio.getSpatializer();
        Object device = outputPreference.a2dpDevice(address);
        if (outputPreference.isEnabled(spatializer, device) == enabled || !isBoundOutput(audio, address)) return;
        // Apply the device preference before MiSound's global write so the app's
        // Spatializer callback can read the updated preference.
        outputPreference.setEnabled(spatializer, device, enabled);
        if (outputPreference.isEnabled(spatializer, device) == enabled) {
            DiagnosticLog.i(TAG, "MiSound set the AirPods spatial preference to " + enabled);
        } else {
            DiagnosticLog.w(TAG, "MiSound AirPods spatial preference was not confirmed for " + enabled);
        }
    }

    private static boolean isBoundOutput(AudioManager audio, String address) {
        List<AudioDeviceInfo> outputs = audio.getAudioDevicesForAttributes(MEDIA);
        if (outputs.size() != 1) return false;
        AudioDeviceInfo device = outputs.get(0);
        return device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
            address.equalsIgnoreCase(device.getAddress());
    }
}
