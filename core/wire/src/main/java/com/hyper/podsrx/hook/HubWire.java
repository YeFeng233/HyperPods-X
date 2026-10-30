package com.hyper.podsrx.hook;

/**
 * Versioned Binder protocol between the app and the Bluetooth host's state hub. Payloads are
 * Bundles built and read by {@link HubBundles}; transactions carry one Bundle each way.
 */
public final class HubWire {
    private HubWire() {}
    public static final String APP = "com.hyper.podsrx";
    public static final String HOST = "com.xiaomi.bluetooth";
    /** Ordered broadcast from the app; the hub answers with its binder in the result extras. */
    public static final String CONNECT = APP + ".HUB_CONNECT";
    /** Sent to the app once a hub is installed, so a running app reconnects at once. */
    public static final String READY = APP + ".HUB_READY";
    /** Sent to each consuming host on a content change; carries no data. */
    public static final String CHANGED = APP + ".HUB_CHANGED";
    /** Each scoped process reports which of its hooks installed; see GET_DIAGNOSTICS. */
    public static final String CAPABILITIES = APP + ".HUB_CAPABILITIES";
    public static final String PERMISSION = NativeControlWire.PERMISSION;
    public static final String DESCRIPTOR = APP + ".Hub.v1";
    public static final String CALLBACK_DESCRIPTOR = APP + ".HubCallback.v1";
    public static final int VERSION = 1;

    public static final String EXTRA_CREATED = "created";
    public static final String EXTRA_BINDER = "hub";
    /** A connect request older than this is ignored. */
    public static final long CONNECT_MAX_AGE_MS = 1000;

    /** Other hosts pull the snapshot through the ROM's headset data provider. */
    public static final String PROVIDER_URI = "content://com.android.bluetooth.ble.app.headsetdata.provider/AirpodsModel";
    public static final String STATE_METHOD = "hyperpods_state";

    /**
     * The app's channel for the battery alerts. The app makes {@link #HOST} its notification
     * delegate, so the hub posts them as the app even while it is not running.
     */
    public static final String ALERT_CHANNEL = "battery_alerts";

    // app -> hub
    public static final int HELLO = 1, PUSH_CONTROL = 2, PUSH_KEYS = 3, REQUEST_POPUP = 4,
        GET_STATE = 5, GET_DIAGNOSTICS = 6, OPEN_CONTROL = 7;
    // REQUEST_POPUP replies with "shown" once the popup is submitted.
    // hub -> app, oneway
    public static final int ON_STATE = 101, ON_LID = 102;

    // Reply status, written before the reply Bundle.
    public static final int OK = 0, VERSION_MISMATCH = 1, DENIED = 2, FAILED = 3;
}
