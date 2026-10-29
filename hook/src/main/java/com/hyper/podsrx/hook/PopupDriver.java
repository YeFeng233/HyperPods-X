package com.hyper.podsrx.hook;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.List;
import org.json.JSONObject;

/** Shows the bound headset through the same SystemUI endpoints as this ROM's AirPods popup. */
final class PopupDriver {
    private static final String APP = "com.hyper.podsrx";
    private static final String DIALOG = "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity";

    static final class Request {
        final String address, name, model;
        final int product;
        final boolean preview, island, connected;
        /** -1 when unknown. */
        final int left, right, box;
        /** Bit 1 left, 2 right, 4 case. */
        final int charging;
        final PendingIntent target;

        Request(String address, String name, String model, int product, boolean preview, boolean island, boolean connected,
                int left, int right, int box, int charging, PendingIntent target) {
            this.address = address; this.name = name; this.model = model; this.product = product;
            this.preview = preview; this.island = island; this.connected = connected;
            this.left = left; this.right = right; this.box = box; this.charging = charging; this.target = target;
        }
    }

    private PopupDriver() {}

    /** Returns whether a popup is showing for the request, including the ROM's own. */
    static boolean show(Context host, Request request) throws Exception {
        HeadsetModels.Model headset = HeadsetModels.of(request.model, request.product);
        if (!request.preview) reconnectProfiles(host, request.address);
        boolean home = false;
        List<ActivityManager.RunningTaskInfo> tasks = host.getSystemService(ActivityManager.class).getRunningTasks(1);
        if (!tasks.isEmpty() && tasks.get(0).topActivity != null) home = "com.miui.home".equals(tasks.get(0).topActivity.getPackageName());
        // The ROM's lid launch may already be showing this state; do not stack a second popup.
        if (!request.preview && !tasks.isEmpty() && tasks.get(0).topActivity != null
                && DIALOG.equals(tasks.get(0).topActivity.getClassName())) {
            DiagnosticLog.i("HyperPods-X", "Headset dialog already showing; skipped a second popup");
            return true;
        }
        if (!request.island && (home || request.preview)) {
            if (!BluetoothAdapter.checkBluetoothAddress(request.address)) return false;
            Intent dialog = new Intent("com.android.bluetooth.FAST_CONNECT_DEVICE_AIRPODS")
                .setClassName("com.xiaomi.bluetooth", DIALOG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, host.getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(request.address))
                .putExtra("headset_addresses", request.address).putExtra("headset_extra_data", new int[] {-60, 1, 0, 16, 0, 1})
                .putExtra("device_name_over_write", (request.preview ? "弹窗预览 · " : "") + request.name)
                .putExtra("hyperpods_preview", request.preview)
                .putExtra("hyperpods_model", request.model)
                .putExtra("hyperpods_product", request.product)
                .putExtra("hyperpods_target", request.target)
                .putExtra("native_left", request.left).putExtra("native_right", request.right)
                .putExtra("native_box", request.box).putExtra("native_box_history", request.box)
                .putExtra("native_charging", request.charging)
                .putExtra("native_connected", request.connected);
            host.startActivity(dialog);
            DiagnosticLog.i("HyperPods-X", "Launched built-in AirPods fast-connect dialog");
            return true;
        }
        JSONObject params = new JSONObject();
        params.put("left", area(headset.artwork + "_left", percentage(request.left)));
        params.put("right", area(headset.artwork + "_right", percentage(request.right)));
        island(host, "CATEGORY_VIDEO_TEXT_TEXT_VIDEO", params, request.target);
        DiagnosticLog.i("HyperPods-X", "Submitted native headset popup to SystemUI");
        return true;
    }

    /** The case and its level in the one-area template the ROM uses for its own headset notices. */
    static void caseIsland(Context host, String model, int product, int level, PendingIntent target) throws Exception {
        HeadsetModels.Model headset = HeadsetModels.of(model, product);
        JSONObject params = new JSONObject();
        params.put("left", new JSONObject().put("iconParams", icon(headset.artwork + "_case")));
        params.put("right", new JSONObject().put("textParams", text(percentage(level))));
        island(host, "CATEGORY_VIDEO_TEXT", params, target);
    }

    private static void island(Context host, String template, JSONObject params, PendingIntent target) throws Exception {
        Class<?> controller = Class.forName("com.xiaomi.dist.statusbar.StatusBarController", false, host.getClassLoader());
        String category = (String) controller.getField(template).get(null);
        Bundle bundle = new Bundle();
        // SystemUI routes this native AirPods template by its built-in id.
        bundle.putString("notifyId", "headset_wear_notification");
        bundle.putString("strong_toast_category", category);
        bundle.putParcelable("target", target);
        bundle.putLong("duration", 5000);
        bundle.putString("status_bar_strong_toast", "show_custom_strong_toast");
        bundle.putString("package_name", APP);
        bundle.putString("island_param", params.toString());
        Object statusBar = host.getSystemService("statusbar");
        statusBar.getClass().getMethod("setStatus", int.class, String.class, Bundle.class)
            .invoke(statusBar, 1, "strong_toast_action", bundle);
    }

    /** Slides out the bound device's headset dialog in the ROM's dialog process. */
    static void close(Context host) {
        try {
            host.sendBroadcast(new Intent(LidWire.CLOSE_POPUP).setPackage("com.xiaomi.bluetooth").addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        } catch (RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "Headset dialog close skipped", error);
        }
    }

    /**
     * The ROM's fast-connect service owns the privileged CachedBluetoothDevice path, but it only
     * runs where the ROM enables that feature. Without it the stack connects the profiles itself.
     */
    static void reconnectProfiles(Context context, String address) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return;
        BluetoothDevice device = context.getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(address);
        try {
            Class<?> serviceType = Class.forName("com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectService", false, context.getClassLoader());
            Object service = serviceType.getMethod("getInstance").invoke(null);
            Object cached = service == null ? null : serviceType.getMethod("getCachedDevice", BluetoothDevice.class).invoke(service, device);
            if (cached != null) {
                cached.getClass().getMethod("connect", boolean.class).invoke(cached, true);
                DiagnosticLog.i("HyperPods-X", "Requested ROM A2DP/HFP reconnect for AirPods");
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "ROM A2DP/HFP reconnect unavailable", error);
        }
        // An ACL the app opened for its control channel never brings the audio profiles with it;
        // on 2509FPN0BC they followed about 30 s later. The host holds BLUETOOTH_PRIVILEGED.
        try {
            Object status = BluetoothDevice.class.getMethod("connect").invoke(device);
            DiagnosticLog.i("HyperPods-X", "Requested stack A2DP/HFP connect for AirPods: " + status);
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "Stack A2DP/HFP connect unavailable", error);
        }
    }

    private static String percentage(int level) {
        return level >= 0 && level <= 100 ? level + "%" : "—";
    }

    private static JSONObject area(String resource, String text) throws Exception {
        return new JSONObject().put("iconParams", icon(resource)).put("textParams", text(text));
    }

    private static JSONObject icon(String resource) throws Exception {
        return new JSONObject().put("iconResName", resource).put("iconType", 0)
            .put("iconFormat", "png").put("category", "drawable");
    }

    private static JSONObject text(String text) throws Exception {
        return new JSONObject().put("text", text == null ? "—" : text).put("textColor", -1).put("turnAnim", true);
    }
}
