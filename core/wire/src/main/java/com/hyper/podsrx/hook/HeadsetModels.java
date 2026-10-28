package com.hyper.podsrx.hook;

/** Product identity shared by the app and the scoped system hosts. */
public final class HeadsetModels {
    private HeadsetModels() {}

    public static final class Model {
        public final String name;
        /** Prefix of the module's {@code _pair}, {@code _case}, {@code _left} and {@code _right} drawables. */
        public final String artwork;
        /** Whether the app-owned session may drive HyperOS's native headset controls. */
        public final boolean nativeControls;

        private Model(String name, String artwork, boolean nativeControls) {
            this.name = name;
            this.artwork = artwork;
            this.nativeControls = nativeControls;
        }
    }

    public static final Model AIRPODS_5 = new Model("AirPods 5", "airpods5", true);
    public static final Model AIRPODS_PRO_3 = new Model("AirPods Pro 3", "airpodspro3", true);
    /** An unknown headset keeps the original presentation and stays on the ROM's own controls. */
    public static final Model FALLBACK = new Model("AirPods 5", "airpods5", false);

    /** Proximity pairing product id advertised by AirPods Pro 3. */
    public static final int PRO_3_PRODUCT = 0x2720;

    /**
     * Prefers the model number reported by the AACP session. A lid opening before the first session
     * of this app run only has the product id from the verified advertisement.
     */
    public static Model of(String model, int product) {
        if (model != null) switch (model) {
            case "A3439", "A3440", "A3441" -> { return AIRPODS_5; }
            case "A3063", "A3064", "A3065" -> { return AIRPODS_PRO_3; }
            default -> { }
        }
        return product == PRO_3_PRODUCT ? AIRPODS_PRO_3 : FALLBACK;
    }

    public static boolean nativeControls(String model) {
        return of(model, -1).nativeControls;
    }
}
