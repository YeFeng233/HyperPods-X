package com.hyper.podsrx.hook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import com.hyper.podsrx.protocol.BatteryAlert;
import com.hyper.podsrx.protocol.BatteryAlerts;
import java.util.List;

/**
 * Shows the battery alerts from the Bluetooth host, so they come without the app. The low and full
 * alerts are notifications: the app makes this host its notification delegate and they appear as
 * its own, on its channel; a ROM that refuses the delegate gets them on a channel of the host
 * instead. The docking shows as an island like the lid popup. The arm flags are kept in
 * device-protected storage, so a host restart does not repeat an alert. Main thread only.
 */
final class BatteryNotifier {
    private static final String TAG = "HyperPods-Battery";
    private static final String NOTIFICATION_TAG = "hyperpods_battery";
    private static final String FALLBACK_CHANNEL = "hyperpods_battery_alerts";
    private static final int LOW = 1, FULL = 2;
    private final Context context;
    private final NotificationManager manager;
    private final SharedPreferences store;
    private boolean fallbackChannel;

    BatteryNotifier(Context context) {
        this.context = context;
        manager = context.getSystemService(NotificationManager.class);
        store = context.createDeviceProtectedStorageContext().getSharedPreferences("hyperpods_hub_alerts", Context.MODE_PRIVATE);
    }

    /** The kept flags of [address]; another headset starts armed for low only. */
    BatteryAlerts restore(String address) {
        if (address == null || address.isEmpty() || !address.equalsIgnoreCase(store.getString("address", ""))) return new BatteryAlerts(true, false);
        return new BatteryAlerts(store.getBoolean("lowArmed", true), store.getBoolean("fullArmed", false));
    }

    void save(String address, boolean lowArmed, boolean fullArmed) {
        store.edit().putString("address", address).putBoolean("lowArmed", lowArmed).putBoolean("fullArmed", fullArmed).apply();
    }

    /** Withdrawals always run; new alerts only while [enabled]. */
    void show(List<BatteryAlert> alerts, String name, String model, int product, boolean enabled) {
        for (BatteryAlert alert : alerts) {
            try {
                if (alert == BatteryAlert.LowCleared.INSTANCE) { cancel(LOW); continue; }
                if (alert instanceof BatteryAlert.DockSkipped) {
                    DiagnosticLog.i(TAG, "Docking skipped: case " + ((BatteryAlert.DockSkipped) alert).getCaseLevel() + "%");
                    continue;
                }
                if (!enabled) { DiagnosticLog.i(TAG, "Alert off: " + alert); continue; }
                if (alert == BatteryAlert.Low.INSTANCE) post(LOW, "AirPods电量低", "请为“" + name + "”充电");
                else if (alert == BatteryAlert.Full.INSTANCE) { cancel(LOW); post(FULL, "Airpods电池", "“" + name + "”已充满电"); }
                else if (alert instanceof BatteryAlert.Docked) {
                    BatteryAlert.Docked docked = (BatteryAlert.Docked) alert;
                    // The island is gone within seconds; a later, more precise level is not worth another.
                    if (docked.getUpdate()) continue;
                    PopupDriver.caseIsland(context, model, product, docked.getCaseLevel(), launch());
                }
                DiagnosticLog.i(TAG, "Alert " + alert);
            } catch (Exception error) {
                DiagnosticLog.w(TAG, "Alert failed: " + alert, error);
            }
        }
    }

    private void post(int id, String title, String text) {
        try {
            if (manager.canNotifyAsPackage(HubWire.APP)) {
                manager.notifyAsPackage(HubWire.APP, NOTIFICATION_TAG, id, build(HubWire.ALERT_CHANNEL, title, text));
                return;
            }
            DiagnosticLog.w(TAG, "Not the app's notification delegate; posting as the host");
        } catch (RuntimeException error) {
            DiagnosticLog.w(TAG, "Delegated alert refused; posting as the host", error);
        }
        if (!fallbackChannel) {
            manager.createNotificationChannel(new NotificationChannel(FALLBACK_CHANNEL, "AirPods 电量提醒", NotificationManager.IMPORTANCE_HIGH));
            fallbackChannel = true;
        }
        manager.notify(NOTIFICATION_TAG, id, build(FALLBACK_CHANNEL, title, text));
    }

    private void cancel(int id) {
        try { manager.cancelAsPackage(HubWire.APP, NOTIFICATION_TAG, id); }
        catch (RuntimeException ignored) {}
        manager.cancel(NOTIFICATION_TAG, id);
    }

    private Notification build(String channel, String title, String text) {
        Notification.Builder builder = new Notification.Builder(context, channel)
            .setSmallIcon(icon()).setContentTitle(title).setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true);
        PendingIntent launch = launch();
        if (launch != null) builder.setContentIntent(launch);
        return builder.build();
    }

    private PendingIntent launch() {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(HubWire.APP);
        return intent == null ? null : PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    /** The app's headphones icon, found by name since this module does not see the app's R. */
    private Icon icon() {
        try {
            int id = context.getPackageManager().getResourcesForApplication(HubWire.APP).getIdentifier("ic_headphones", "drawable", HubWire.APP);
            if (id != 0) return Icon.createWithResource(HubWire.APP, id);
        } catch (Exception unavailable) {}
        return Icon.createWithResource("android", android.R.drawable.stat_sys_data_bluetooth);
    }
}
