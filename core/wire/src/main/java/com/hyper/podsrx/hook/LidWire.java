package com.hyper.podsrx.hook;

/** The lid closing, sent by the hub to the headset dialog in the same package's dialog process. */
public final class LidWire {
    private LidWire() {}
    public static final String APP = "com.hyper.podsrx";
    /** The headset dialog slides out at once. */
    public static final String CLOSE_POPUP = APP + ".CLOSE_NATIVE_POPUP";
}
