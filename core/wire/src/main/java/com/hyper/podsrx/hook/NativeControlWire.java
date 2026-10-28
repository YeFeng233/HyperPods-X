package com.hyper.podsrx.hook;

/** Capability shared by the app and the scoped Bluetooth content provider. */
public final class NativeControlWire {
    private NativeControlWire() {}
    public static final String APP = "com.hyper.podsrx";
    public static final String HOST = "com.xiaomi.bluetooth";
    public static final String COMMAND = APP + ".NATIVE_CONTROL";
    public static final String PERMISSION = APP + ".permission.NATIVE_POPUP";

    public static boolean isCurrentTarget(String target, String bound, String model,
            boolean enabled, boolean connected, long heartbeat, long now) {
        return target != null && bound != null && !bound.isEmpty() &&
            target.equalsIgnoreCase(bound) && HeadsetModels.nativeControls(model) && enabled && connected &&
            heartbeat >= 0 && now >= heartbeat && now - heartbeat <= 30_000;
    }

    /** A bounded, read-only presentation lease while a known session reads back settings. */
    public static boolean isPresentationTarget(String target, String bound, String model,
            boolean enabled, boolean connected, boolean audioRoute, long readbackUntil, long heartbeat, long now) {
        boolean refreshing = audioRoute && now < readbackUntil && readbackUntil - now <= 5_000;
        return isCurrentTarget(target, bound, model, enabled, connected || refreshing, heartbeat, now);
    }
}
