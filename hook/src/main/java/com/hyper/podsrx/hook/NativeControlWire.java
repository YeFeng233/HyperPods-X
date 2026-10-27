package com.hyper.podsrx.hook;

/** Capability shared by the app and the scoped Bluetooth content provider. */
public final class NativeControlWire {
    private NativeControlWire() {}
    public static final String APP = "com.hyper.podsrx";
    public static final String HOST = "com.xiaomi.bluetooth";
    public static final String REGISTER = APP + ".REGISTER_NATIVE_CONTROLS";
    public static final String COMMAND = APP + ".NATIVE_CONTROL";
    public static final String PERMISSION = APP + ".permission.NATIVE_POPUP";
}
