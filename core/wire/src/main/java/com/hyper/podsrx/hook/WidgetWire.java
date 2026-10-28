package com.hyper.podsrx.hook;

/**
 * Widget slots sent by the app straight to the Bluetooth host. A foreground broadcast wakes a host
 * the ROM has frozen, where module preference callbacks would wait until something else woke it.
 */
public final class WidgetWire {
    private WidgetWire() {}
    public static final String ACTION = LidWire.APP + ".REFRESH_WIDGET";
    public static final String SEQUENCE = "widget_seq";
    public static final String HEADSET = "widget_headset";
    public static final String CASE = "widget_case";
    public static final String CASE_CHARGING = "widget_case_charging";
}
