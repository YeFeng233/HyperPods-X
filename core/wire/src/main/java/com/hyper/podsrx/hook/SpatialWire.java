package com.hyper.podsrx.hook;

/** Small versioned Binder protocol shared by the app and the scoped Bluetooth host. */
public final class SpatialWire {
    private SpatialWire() {}
    public static final String APP="com.hyper.podsrx";
    public static final String ACTION=APP+".SPATIAL_BRIDGE";
    public static final String PERMISSION=APP+".permission.SPATIAL";
    public static final String DESCRIPTOR=APP+".Spatial.v1";
    public static final int QUERY=1, FIXED=2, START=3, POSE=4, STOP=5;
}
