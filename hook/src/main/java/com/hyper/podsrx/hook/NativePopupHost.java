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
    private static final String DIALOG = "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity";
    private static boolean registered;

    static void install(Context context) {
        if (registered || !ReloadLifecycle.isActive()) return;
        Handler delivery = new Handler(Looper.getMainLooper());
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent request) {
                if (!ReloadLifecycle.isActive() || !ACTION.equals(request.getAction())) return;
                long age = SystemClock.elapsedRealtime() - request.getLongExtra("created", 0);
                if (age < 0 || age > 1000) return;
                PendingIntent target = request.getParcelableExtra("target", PendingIntent.class);
                if (target == null || !APP.equals(target.getCreatorPackage())) return;
                try {
                    HeadsetModels.Model headset = HeadsetModels.of(request.getStringExtra("model"), request.getIntExtra("product", -1));
                    String address=request.getStringExtra("address");
                    if(!request.getBooleanExtra("preview",false))reconnectProfiles(host,address);
                    boolean home=false;
                    java.util.List<ActivityManager.RunningTaskInfo> tasks=host.getSystemService(ActivityManager.class).getRunningTasks(1);
                    if(!tasks.isEmpty()&&tasks.get(0).topActivity!=null)home="com.miui.home".equals(tasks.get(0).topActivity.getPackageName());
                    // The ROM's lid launch may already be showing this state; do not stack a second popup.
                    if(!request.getBooleanExtra("preview",false)&&!tasks.isEmpty()&&tasks.get(0).topActivity!=null
                            &&DIALOG.equals(tasks.get(0).topActivity.getClassName())){
                        DiagnosticLog.i("HyperPods-X","Headset dialog already showing; skipped a second popup");
                        setResultCode(1);return;
                    }
                    if(!request.getBooleanExtra("island",false)&&(home||request.getBooleanExtra("preview",false))){
                        if(!BluetoothAdapter.checkBluetoothAddress(address))return;
                        Intent dialog=new Intent("com.android.bluetooth.FAST_CONNECT_DEVICE_AIRPODS")
                                .setClassName("com.xiaomi.bluetooth",DIALOG)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                                .putExtra(BluetoothDevice.EXTRA_DEVICE,host.getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(address))
                                .putExtra("headset_addresses",address).putExtra("headset_extra_data",new int[]{-60,1,0,16,0,1})
                                .putExtra("device_name_over_write",(request.getBooleanExtra("preview",false)?"弹窗预览 · ":"")+request.getStringExtra("name"))
                                .putExtra("hyperpods_preview",request.getBooleanExtra("preview",false))
                                .putExtra("hyperpods_model",request.getStringExtra("model"))
                                .putExtra("hyperpods_product",request.getIntExtra("product",-1))
                                .putExtra("hyperpods_target",target);
                        for(String key:new String[]{"left","right","box","box_history","charging"})dialog.putExtra("native_"+key,request.getIntExtra("native_"+key,-1));
                        dialog.putExtra("native_connected",request.getBooleanExtra("native_connected",false));
                        host.startActivity(dialog);
                        DiagnosticLog.i("HyperPods-X","Launched built-in AirPods fast-connect dialog");
                        setResultCode(1);return;
                    }
                    JSONObject params = new JSONObject();
                    params.put("left", area(headset.artwork + "_left", percentage(request.getIntExtra("native_left", -1))));
                    params.put("right", area(headset.artwork + "_right", percentage(request.getIntExtra("native_right", -1))));
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
                    DiagnosticLog.i("HyperPods-X", "Submitted native headset popup to SystemUI");
                    setResultCode(1);
                } catch (Throwable error) {
                    DiagnosticLog.w("HyperPods-X", "Native headset popup unavailable", error);
                    setResultCode(-1);
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION), PERMISSION, delivery, Context.RECEIVER_EXPORTED);
        ReloadLifecycle.add(() -> {
            context.unregisterReceiver(receiver);
            delivery.removeCallbacksAndMessages(null);
        });
        registered = true;
        DiagnosticLog.i("HyperPods-X", "Native headset popup bridge ready");
    }

    /**
     * The ROM's fast-connect service owns the privileged CachedBluetoothDevice path, but it only
     * runs where the ROM enables that feature. Without it the stack connects the profiles itself.
     */
    private static void reconnectProfiles(Context context,String address){
        if(!BluetoothAdapter.checkBluetoothAddress(address))return;
        BluetoothDevice device=context.getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(address);
        try{
            Class<?> serviceType=Class.forName("com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectService",false,context.getClassLoader());
            Object service=serviceType.getMethod("getInstance").invoke(null);
            Object cached=service==null?null:serviceType.getMethod("getCachedDevice",BluetoothDevice.class).invoke(service,device);
            if(cached!=null){
                cached.getClass().getMethod("connect",boolean.class).invoke(cached,true);
                DiagnosticLog.i("HyperPods-X","Requested ROM A2DP/HFP reconnect for AirPods");
                return;
            }
        }catch(ReflectiveOperationException|RuntimeException error){
            DiagnosticLog.w("HyperPods-X","ROM A2DP/HFP reconnect unavailable",error);
        }
        // An ACL the app opened for its control channel never brings the audio profiles with it;
        // on 2509FPN0BC they followed about 30 s later. The host holds BLUETOOTH_PRIVILEGED.
        try{
            Object status=BluetoothDevice.class.getMethod("connect").invoke(device);
            DiagnosticLog.i("HyperPods-X","Requested stack A2DP/HFP connect for AirPods: "+status);
        }catch(ReflectiveOperationException|RuntimeException error){
            DiagnosticLog.w("HyperPods-X","Stack A2DP/HFP connect unavailable",error);
        }
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
