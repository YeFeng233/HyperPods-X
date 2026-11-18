package com.hyper.podsrx.hook;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Spatializer;
import java.util.List;

/** Hidden Spatializer calls for the A2DP device preference. */
final class SpatialOutputPreference {
    private static final int ROLE_OUTPUT = 2;
    static final AudioAttributes MEDIA = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).build();
    final Class<?> attributesClass;

    SpatialOutputPreference() throws ClassNotFoundException {
        attributesClass = Class.forName("android.media.AudioDeviceAttributes");
    }

    Object a2dpDevice(String address) throws ReflectiveOperationException {
        return attributesClass.getConstructor(int.class, int.class, String.class)
            .newInstance(ROLE_OUTPUT, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, address);
    }

    boolean isEnabled(Spatializer spatializer, Object device) throws ReflectiveOperationException {
        List<?> devices = (List<?>) Spatializer.class.getMethod("getCompatibleAudioDevices").invoke(spatializer);
        return devices.contains(device);
    }

    void setEnabled(Spatializer spatializer, Object device, boolean enabled) throws ReflectiveOperationException {
        String method = enabled ? "addCompatibleAudioDevice" : "removeCompatibleAudioDevice";
        Spatializer.class.getMethod(method, attributesClass).invoke(spatializer, device);
    }

    static boolean isBoundMediaOutput(AudioManager audio, String address) {
        List<AudioDeviceInfo> outputs = audio.getAudioDevicesForAttributes(MEDIA);
        if (outputs.size() != 1) return false;
        AudioDeviceInfo device = outputs.get(0);
        return device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
            address.equalsIgnoreCase(device.getAddress());
    }
}
