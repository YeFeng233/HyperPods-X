package com.hyper.podsrx.hook;

import android.app.PendingIntent;
import android.app.ActivityManager;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** Uses the same SystemUI endpoint as this ROM's AirPods notification renderer. */
final class NativePopupHost {
    private static final String APP = "com.hyper.podsrx";
    static final String ACTION = APP + ".SHOW_NATIVE_POPUP";
    static final String PERMISSION = APP + ".permission.NATIVE_POPUP";
    private static boolean registered;

    static void install(Context context) {
        if (registered) return;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent request) {
                if (!ACTION.equals(request.getAction())) return;
                long age = SystemClock.elapsedRealtime() - request.getLongExtra("created", 0);
                if (age < 0 || age > 1000) return;
                PendingIntent target = request.getParcelableExtra("target", PendingIntent.class);
                if (target == null || !APP.equals(target.getCreatorPackage())) return;
                try {
                    boolean home=false;
                    java.util.List<ActivityManager.RunningTaskInfo> tasks=host.getSystemService(ActivityManager.class).getRunningTasks(1);
                    if(!tasks.isEmpty()&&tasks.get(0).topActivity!=null)home="com.miui.home".equals(tasks.get(0).topActivity.getPackageName());
                    if(!request.getBooleanExtra("island",false)&&(home||request.getBooleanExtra("preview",false))){
                        String address=request.getStringExtra("address");
                        if(!BluetoothAdapter.checkBluetoothAddress(address))return;
                        Intent dialog=new Intent("com.android.bluetooth.FAST_CONNECT_DEVICE_AIRPODS")
                                .setClassName("com.xiaomi.bluetooth","com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                                .putExtra(BluetoothDevice.EXTRA_DEVICE,host.getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(address))
                                .putExtra("headset_addresses",address).putExtra("headset_extra_data",new int[]{-60,1,0,16,0,1})
                                .putExtra("device_name_over_write",(request.getBooleanExtra("preview",false)?"弹窗预览 · ":"")+request.getStringExtra("name"))
                                .putExtra("hyperpods_preview",request.getBooleanExtra("preview",false))
                                .putExtra("hyperpods_target",target);
                        for(String key:new String[]{"left","right","box","box_history","charging"})dialog.putExtra("native_"+key,request.getIntExtra("native_"+key,-1));
                        dialog.putExtra("native_connected",request.getBooleanExtra("native_connected",false));
                        host.startActivity(dialog);
                        android.util.Log.i("HyperPods-X","Launched built-in AirPods fast-connect dialog");
                        setResultCode(1);return;
                    }
                    JSONObject params = new JSONObject();
                    params.put("left", area("airpods5_left", percentage(request.getIntExtra("native_left", -1))));
                    params.put("right", area("airpods5_right", percentage(request.getIntExtra("native_right", -1))));
                    Class<?> controller = Class.forName("com.xiaomi.dist.statusbar.StatusBarController", false, host.getClassLoader());
                    String category = (String) controller.getField("CATEGORY_VIDEO_TEXT_TEXT_VIDEO").get(null);
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
                    android.util.Log.i("HyperPods-X", "Submitted native headset popup to SystemUI");
                    setResultCode(1);
                } catch (Throwable error) {
                    android.util.Log.w("HyperPods-X", "Native headset popup unavailable", error);
                    setResultCode(-1);
                }
            }
        }, new IntentFilter(ACTION), PERMISSION, new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        registered = true;
        android.util.Log.i("HyperPods-X", "Native headset popup bridge ready");
    }

    private static String percentage(int level) {
        return level >= 0 && level <= 100 ? level + "%" : "—";
    }

    private static JSONObject area(String resource, String text) throws Exception {
        JSONObject icon = new JSONObject().put("iconResName", resource).put("iconType", 0)
                .put("iconFormat", "png").put("category", "drawable");
        JSONObject label = new JSONObject().put("text", text == null ? "—" : text)
                .put("textColor", -1).put("turnAnim", true);
        return new JSONObject().put("iconParams", icon).put("textParams", label);
    }
}
