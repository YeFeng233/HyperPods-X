package com.hyper.podsrx.hook;

/** Apple proximity advertisements relayed from the ROM's real-time Apple scan path. */
public final class LidWire {
    private LidWire() {}
    public static final String APP = "com.hyper.podsrx";
    public static final String ACTION = APP + ".APPLE_ADVERTISEMENT";
    public static final String EXTRA_RESULT = "result";
    /** Sent by the app when the lid closes; the headset dialog slides out at once. */
    public static final String CLOSE_POPUP = APP + ".CLOSE_NATIVE_POPUP";
}
