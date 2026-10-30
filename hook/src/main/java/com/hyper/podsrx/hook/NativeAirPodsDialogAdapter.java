package com.hyper.podsrx.hook;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Reuses the ROM's AirPods controller only for a signed request for the bound device. */
final class NativeAirPodsDialogAdapter {
    private static final String APP="com.hyper.podsrx";
    private static final Set<ClassLoader> installed=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<String> reported=Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<Intent> creating=new ThreadLocal<>();
    private static final String ADOPTED="hyperpods_adopted";
    private static final String AIRPODS_CONTROLLER="com.android.bluetooth.ble.app.fastconnect.airpods.MiuiAirPodsFastConnectController";
    private static final int DISMISSING=1,WAITING=2,FINISH=3,EXIT_MS=320;
    private static final int CONNECT_FAIL_SHOW_ISLAND=10007; // AirpodsConstants.MSG_CONNECT_FAIL_SHOW_ISLAND
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Map<Activity,Integer> closing=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Activity,BroadcastReceiver> lidWatchers=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Handler dismissHandler=new Handler(Looper.getMainLooper());
    private static final Set<Activity> dismissActivities=Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean cleanupRegistered;

    private static void prepareCleanup() {
        if(cleanupRegistered)return;
        cleanupRegistered=true;
        ReloadLifecycle.add(()->{
            dismissHandler.removeCallbacksAndMessages(null);
            main.removeCallbacksAndMessages(null);
            // A live popup does not receive onActivityCreated again in the new
            // generation. Finish only signed module popups whose timer we owned.
            for(Activity activity:new ArrayList<>(dismissActivities))finishModulePopup(activity);
            dismissActivities.clear();
            for(Activity activity:new ArrayList<>(lidWatchers.keySet()))stopFollowingLid(activity);
            closing.clear();
            installed.clear();
            reported.clear();
            creating.remove();
        });
    }

    static void install(HyperPodsModule module, Activity activity, SharedPreferences preferences) throws Exception {
        if(!ReloadLifecycle.isActive())return;
        prepareCleanup();
        if(!activity.getClass().getName().equals("com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"))return;
        ClassLoader loader=activity.getClass().getClassLoader();
        if(installed.contains(loader))return;
        Class<?> controller=Class.forName("com.android.bluetooth.ble.app.fastconnect.airpods.MiuiAirPodsFastConnectController",false,loader);
        Class<?> info=Class.forName("com.android.bluetooth.ble.app.fastconnect.airpods.AirPodsInfo",false,loader);
        // Resolve every hook target before installing the first interceptor. A missing
        // method on another ROM must not leave a partly installed set of adapters.
        Method create=controller.getDeclaredMethod("onActivityCreate");
        Method status=info.getDeclaredMethod("buildPodsStatus",String.class);
        Method state=controller.getDeclaredMethod("getAirPodsState",String.class);
        Method parameters=controller.getDeclaredMethod("initParameter");
        Method detail=controller.getDeclaredMethod("setConnectSuccessLayoutDetail",View.class);
        Method connecting=controller.getDeclaredMethod("setConnectLessLayout",View.class,int.class,int.class);
        Method pair=controller.getDeclaredMethod("startPairAndConnectLocal",long.class,boolean.class,boolean.class);
        Method finish=Activity.class.getDeclaredMethod("finish");
        Method destroy=null;
        Field baseRunning=null;
        try { destroy=activity.getClass().getDeclaredMethod("onDestroy"); baseRunning=field(activity,"mBaseRunning"); }
        catch(NoSuchMethodException | NoSuchFieldException error) { report("keepWarm",error); }
        for(String setter:new String[]{"setDevice","setDeviceModel"})info.getMethod(setter,String.class);
        for(String setter:new String[]{"setConnectState","setLeftBattery","setRightBattery","setBoxBattery","setChargingState","setWearType"})
            info.getMethod(setter,int.class);
        module.hook(create).intercept(chain->{
            if(!ReloadLifecycle.isActive())return chain.proceed();
            Intent request=request(chain.getThisObject(),preferences);
            if(request==null)request=adopt(chain.getThisObject(),preferences);
            if(request==null)return chain.proceed();
            creating.set(request);
            try {
                Object result=chain.proceed();
                if(!ReloadLifecycle.isActive())return result;
                try {
                    Object owner=chain.getThisObject();
                    // The native layout also represents BLE battery data while audio is disconnected.
                    field(owner,"mDialogConnectState").setInt(owner,3);
                    field(owner,"mHasRunSuccessAnimation").setBoolean(owner,true);
                    field(owner,"mCanRunSuccessAnimation").setBoolean(owner,true);
                    Object animation=field(owner,"mLocalResource").get(owner);
                    animation.getClass().getMethod("setAlphaAnimationState",int.class,ViewGroup.class).invoke(animation,2,null);
                    View root=(View)field(owner,"mView").get(owner);
                    Method modify=controller.getDeclaredMethod("modifyView",View.class,int.class,int.class,String.class);
                    modify.setAccessible(true);
                    modify.invoke(owner,root,3,0,request.getStringExtra("device_name_over_write"));
                } catch(Exception | LinkageError error) { report("onActivityCreate",error); }
                return result;
            } finally { creating.remove(); }
        });
        module.hook(status).intercept(chain->{
            Object result=chain.proceed();
            if(!ReloadLifecycle.isActive())return result;
            Intent request=creating.get();
            try { if(request!=null)populate(result,request,preferences); }
            catch(Exception | LinkageError error) { report("buildPodsStatus",error); }
            return result;
        });
        module.hook(state).intercept(chain->{
            Object result=chain.proceed();
            if(!ReloadLifecycle.isActive())return result;
            try {
                Intent request=request(chain.getThisObject(),preferences);
                if(request!=null){
                    int mask=value(request,preferences,"charging",0);
                    return new String[]{level(request,preferences,"left"),level(request,preferences,"right"),level(request,preferences,"box"),
                            "false","false",String.valueOf((mask&1)!=0),String.valueOf((mask&2)!=0),String.valueOf((mask&4)!=0),
                            request.getStringExtra("headset_addresses"),connected(request,preferences)?"2":"0",headset(request,preferences).name};
                }
            } catch(Exception | LinkageError error) { report("getAirPodsState",error); }
            return result;
        });
        module.hook(parameters).intercept(chain->{
            Object result=chain.proceed();
            if(!ReloadLifecycle.isActive())return result;
            try {
                Intent request=Boolean.TRUE.equals(result)?request(chain.getThisObject(),preferences):null;
                if(request!=null){
                    Object owner=chain.getThisObject();
                    Activity host=(Activity)field(owner,"mActivityContext").get(owner);
                    HeadsetModels.Model headset=headset(request,preferences);
                    Context artwork=host.createPackageContext(APP,0);
                    Drawable buds=artwork.getDrawable(artwork.getResources().getIdentifier(headset.artwork+"_pair","drawable",APP));
                    Drawable box=artwork.getDrawable(artwork.getResources().getIdentifier(headset.artwork+"_case","drawable",APP));
                    Drawable[] icons=(Drawable[])field(owner,"mHeadsetIconDrawable").get(owner);
                    java.util.Arrays.fill(icons,buds);
                    field(owner,"mHeadsetBoxIconDrawable").set(owner,box);
                    DiagnosticLog.i("HyperPods-X","Native AirPods dialog uses "+headset.name+" artwork");
                }
            } catch(Exception | LinkageError error) { report("initParameter",error); }
            return result;
        });
        // The ROM's service reports the headset's own HFP progress when the dialog binds, and while the
        // earbuds reconnect after leaving the case it redraws "connecting" over the module's battery
        // layout. The module's dialog shows the module's state; failure prompts still pass.
        module.hook(connecting).intercept(chain->{
            if(!ReloadLifecycle.isActive())return chain.proceed();
            try {
                if((int)chain.getArg(1)==2&&request(chain.getThisObject(),preferences)!=null)return false;
            } catch(Exception | LinkageError error) { report("setConnectLessLayout",error); }
            return chain.proceed();
        });
        module.hook(detail).intercept(chain->{
            Object result=chain.proceed();
            if(!ReloadLifecycle.isActive())return result;
            try {
                Object owner=chain.getThisObject();
                Intent request=request(owner,preferences);
                if(request!=null){
                    View root=(View)field(owner,"mView").get(owner);
                    Context host=root.getContext();
                    String[] keys={"left","right","box"};
                    String[] ids={"textViewHeadsetLBatteryPercent","textViewHeadsetRBatteryPercent","textViewBoxBatteryPercent"};
                    for(int i=0;i<keys.length;i++){
                        TextView label=(TextView)find(root,host,ids[i]);
                        int amount=i==2?boxLevel(request,preferences):value(request,preferences,keys[i],-1);
                        boolean history=i==2&&!validLevel(value(request,preferences,"box",-1))&&validLevel(amount);
                        if(label!=null)label.setText(validLevel(amount)?amount+"%"+(history?HookStrings.get(host, R.string.hook_previous_reading):""):"—");
                    }
                    TextView caption=(TextView)find(root,host,"textCharacters");
                    if(caption!=null)caption.setText(request.getBooleanExtra("hyperpods_preview",false)?HookStrings.get(host, R.string.hook_popup_preview):connected(request,preferences)?HookStrings.get(host, R.string.hook_connected):HookStrings.get(host, R.string.hook_case_opened));
                    for(String id:new String[]{"imageViewBoxLayout","textViewBoxBattery","textViewBoxBatteryPercent"}){
                        View box=find(root,host,id);if(box!=null)box.setVisibility(View.VISIBLE);
                    }
                }
            } catch(Exception | LinkageError error) { report("setConnectSuccessLayoutDetail",error); }
            return result;
        });
        // Keep the ROM connection callback only when automatic connection is enabled.
        // A preview and an explicitly disabled auto-connect setting remain presentation-only.
        module.hook(pair).intercept(chain->{
            Intent request=request(chain.getThisObject(),preferences);
            return request!=null&&(request.getBooleanExtra("hyperpods_preview",false)
                    || !preferences.getBoolean("auto_connect",true))?null:chain.proceed();
        });
        // The ROM's timers and disconnect handling call finish() on the host Activity, which cuts the
        // dialog window's exit animation. Dismiss the dialog first so it slides out like a manual close.
        module.hook(finish).intercept(chain->{
            if(!ReloadLifecycle.isActive())return chain.proceed();
            Object owner=chain.getThisObject();
            if(owner instanceof Activity&&owner.getClass().getName().equals("com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity")
                    &&slideOut((Activity)owner))return null;
            return chain.proceed();
        });
        // The ROM exits this process whenever its dialog closes, so every lid open paid a cold start.
        // Its duplicate-dialog flag skips that exit; the bound device's dialog keeps the process warm.
        if(destroy!=null){
            Field warm=baseRunning;
            module.hook(destroy).intercept(chain->{
                if(!ReloadLifecycle.isActive())return chain.proceed();
                try {
                    Activity owner=(Activity)chain.getThisObject();
                    String address=owner.getIntent().getStringExtra("headset_addresses");
                    if(address!=null&&address.equalsIgnoreCase(preferences.getString("address","")))warm.setBoolean(owner,true);
                } catch(Exception | LinkageError error) { report("keepWarm",error); }
                return chain.proceed();
            });
        }
        installed.add(loader);
        DiagnosticLog.i("HyperPods-X","Native AirPods dialog adapter ready");
    }
    /** The ROM's "connecting"/"connection failed" island shares SystemUI's headset_wear_notification slot with ours. */
    static void suppressConnectToast(HyperPodsModule module,ClassLoader loader,SharedPreferences preferences) throws Exception {
        Class<?> notification=Class.forName("com.android.bluetooth.ble.app.MiuiBluetoothNotification",false,loader);
        Method toast=notification.getDeclaredMethod("showConnectToastForAirPods",int.class,BluetoothDevice.class,int.class);
        module.hook(toast).intercept(chain->{
            if(!ReloadLifecycle.isActive())return chain.proceed();
            try {
                Object device=chain.getArg(1);
                if(device instanceof BluetoothDevice&&replacing(preferences)
                        &&((BluetoothDevice)device).getAddress().equalsIgnoreCase(preferences.getString("address",""))){
                    DiagnosticLog.i("HyperPods-X","Skipped the ROM's AirPods connection island for the bound device");
                    // The island queues a 10 s "connection failed" check right after this call; if the
                    // lid has closed by then it relaunches the dialog, so it goes with the island.
                    main.postDelayed(()->dropConnectFailCheck(loader),200);
                    return null;
                }
            } catch(Exception | LinkageError error) { report("showConnectToastForAirPods",error); }
            return chain.proceed();
        });
        DiagnosticLog.i("HyperPods-X","ROM AirPods connection island filter ready");
    }
    private static void dropConnectFailCheck(ClassLoader loader) {
        try {
            Class<?> helper=Class.forName("com.android.bluetooth.ble.app.fastconnect.airpods.MiuiAirPodsFastConnectHelper",false,loader);
            Field instance=helper.getDeclaredField("sInstance");
            instance.setAccessible(true);
            Object owner=instance.get(null);
            Object handler=owner==null?null:field(owner,"mHandler").get(owner);
            if(handler instanceof Handler)((Handler)handler).removeMessages(CONNECT_FAIL_SHOW_ISLAND);
        } catch(Exception | LinkageError error) { report("dropConnectFailCheck",error); }
    }
    /** The bound device keeps one popup: the ROM's own launch for it shows the module's content. */
    private static Intent adopt(Object controller,SharedPreferences preferences) {
        try {
            Activity activity=(Activity)field(controller,"mActivityContext").get(controller);
            Intent request=activity.getIntent();
            String address=request.getStringExtra("headset_addresses");
            if(address==null||!address.equalsIgnoreCase(preferences.getString("address",""))||!replacing(preferences))return null;
            // The ROM launches when the headset reconnects, sometimes before the module's popup;
            // that launch keeps its timing and shows the module's last published state.
            for(String key:new String[]{"left","right","box","box_history","charging"})
                request.putExtra("native_"+key,preferences.getInt("native_"+key,key.equals("charging")?0:-1));
            request.putExtra("native_connected",preferences.getBoolean("native_connected",false));
            String name=null;
            BluetoothDevice device=request.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
            try { if(device!=null)name=device.getAlias(); } catch(SecurityException ignored) { }
            request.putExtra("device_name_over_write",name!=null?name:"AirPods");
            request.putExtra(ADOPTED,true);
            DiagnosticLog.i("HyperPods-X","Showing module state in the ROM's own AirPods dialog for the bound device");
            return request;
        } catch(Exception | LinkageError error) { report("adopt",error); return null; }
    }
    /**
     * The module only takes over while its service is running. A reboot or a killed process leaves the
     * last published flag behind, so state written before this boot does not count.
     */
    private static boolean replacing(SharedPreferences preferences) {
        long boot=System.currentTimeMillis()-SystemClock.elapsedRealtime();
        return preferences.getBoolean("replace_system_popup",false)&&preferences.getLong("updated",0)>=boot;
    }
    /** Returns true when finish() was replaced by a dialog dismissal that will finish the Activity itself. */
    private static boolean slideOut(Activity activity) {
        Integer state=closing.get(activity);
        if(state!=null) {
            // dismiss() reaches finish() through the dialog's dismiss listener; finish after the exit animation.
            if(state==DISMISSING){closing.put(activity,WAITING);main.postDelayed(()->release(activity),EXIT_MS);}
            return state!=FINISH;
        }
        try {
            Object controller=field(activity,"mFastConnectController").get(activity);
            if(controller==null||!AIRPODS_CONTROLLER.equals(controller.getClass().getName()))return false;
            Object alert=field(controller,"mAlert").get(controller);
            if(!(alert instanceof Dialog)||!((Dialog)alert).isShowing()||activity.isDestroyed())return false;
            closing.put(activity,DISMISSING);
            // Finish anyway if the dismissal never reaches finish().
            main.postDelayed(()->release(activity),EXIT_MS*3);
            main.post(()->{
                try { ((Dialog)alert).dismiss(); }
                catch(RuntimeException error){ release(activity); }
            });
            return true;
        } catch(Exception | LinkageError error) { report("slideOut",error); return false; }
    }
    private static void release(Activity activity) {
        Integer state=closing.get(activity);
        if(state!=null&&state==FINISH)return;
        closing.put(activity,FINISH);
        if(activity.isFinishing()||activity.isDestroyed())return;
        activity.overridePendingTransition(0,0);
        activity.finish();
    }
    /** The app reports a closed lid as it happens; the bound device's dialog leaves with it instead of on a timer. */
    static void followLid(Activity activity,SharedPreferences preferences) {
        if(!ReloadLifecycle.isActive())return;
        prepareCleanup();
        if(!activity.getClass().getName().equals("com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"))return;
        try {
            String address=activity.getIntent().getStringExtra("headset_addresses");
            if(address==null||!address.equalsIgnoreCase(preferences.getString("address","")))return;
            BroadcastReceiver receiver=new BroadcastReceiver(){
                @Override public void onReceive(Context context,Intent intent){
                    if(LidWire.CLOSE_POPUP.equals(intent.getAction())&&!activity.isFinishing())activity.finish();
                }
            };
            // Sent by the hub in this package's main process, which shares this uid.
            activity.registerReceiver(receiver,new IntentFilter(LidWire.CLOSE_POPUP),null,main,Context.RECEIVER_NOT_EXPORTED);
            lidWatchers.put(activity,receiver);
        } catch(RuntimeException | LinkageError error) { report("followLid",error); }
    }
    static void stopFollowingLid(Activity activity) {
        BroadcastReceiver receiver=lidWatchers.remove(activity);
        if(receiver!=null)try{activity.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}
    }
    static void autoDismiss(Activity activity) {
        if(!ReloadLifecycle.isActive())return;
        prepareCleanup();
        try {
            if(isModulePopup(activity)&&dismissActivities.add(activity))dismissHandler.postDelayed(()->{
                dismissActivities.remove(activity);
                if(ReloadLifecycle.isActive())finishModulePopup(activity);
            },8000);
        } catch(RuntimeException | LinkageError error) { report("autoDismiss",error); }
    }
    private static boolean isModulePopup(Activity activity) {
        if(!activity.getClass().getName().equals("com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"))return false;
        PendingIntent token=activity.getIntent().getParcelableExtra("hyperpods_target",PendingIntent.class);
        return token!=null&&APP.equals(token.getCreatorPackage());
    }
    private static void finishModulePopup(Activity activity) {
        try {
            if(!activity.isFinishing()&&!activity.isDestroyed()&&isModulePopup(activity))activity.finish();
        } catch(RuntimeException | LinkageError error) { report("autoDismiss",error); }
    }
    private static Intent request(Object controller,SharedPreferences preferences) {
        if(!ReloadLifecycle.isActive())return null;
        try {
            Activity activity=(Activity)field(controller,"mActivityContext").get(controller);
            Intent request=activity.getIntent();
            PendingIntent token=request.getParcelableExtra("hyperpods_target",PendingIntent.class);
            boolean module=token!=null&&APP.equals(token.getCreatorPackage())||request.getBooleanExtra(ADOPTED,false);
            String address=request.getStringExtra("headset_addresses");
            return module&&address!=null&&address.equalsIgnoreCase(preferences.getString("address",""))?request:null;
        } catch(Exception | LinkageError error) { report("request",error); return null; }
    }
    private static void report(String operation,Throwable error) {
        if(reported.add(operation))DiagnosticLog.w("HyperPods-X",
                "Native AirPods adapter skipped incompatible interface: "+operation,error);
    }
    private static int value(Intent request,SharedPreferences preferences,String key,int fallback){
        return fresh(preferences)&&preferences.contains("native_"+key)?preferences.getInt("native_"+key,fallback):request.getIntExtra("native_"+key,fallback);
    }
    private static boolean fresh(SharedPreferences preferences){
        long age=System.currentTimeMillis()-preferences.getLong("updated",0);
        return age>=0&&age<15000;
    }
    private static boolean connected(Intent request,SharedPreferences preferences){
        return fresh(preferences)?preferences.getBoolean("native_connected",false):request.getBooleanExtra("native_connected",false);
    }
    private static String level(Intent request,SharedPreferences preferences,String key){
        int value="box".equals(key)?boxLevel(request,preferences):value(request,preferences,key,-1);
        return validLevel(value)?String.valueOf(value):"-";
    }
    /** The module's launch names the product; the ROM's own launch for the bound device uses the published state. */
    private static HeadsetModels.Model headset(Intent request,SharedPreferences preferences){
        String model=request.hasExtra("hyperpods_model")?request.getStringExtra("hyperpods_model"):preferences.getString("panel_model","");
        int product=request.hasExtra("hyperpods_product")?request.getIntExtra("hyperpods_product",-1):preferences.getInt("panel_product",-1);
        return HeadsetModels.of(model,product);
    }
    private static boolean validLevel(int level){return level>=0&&level<=100;}
    private static int boxLevel(Intent request,SharedPreferences preferences){
        int current=value(request,preferences,"box",-1);
        int previous=value(request,preferences,"box_history",-1);
        return validLevel(current)?current:validLevel(previous)?previous:-1;
    }
    private static void populate(Object info,Intent request,SharedPreferences preferences)throws Exception{
        Class<?> type=info.getClass();
        type.getMethod("setDevice",String.class).invoke(info,request.getStringExtra("headset_addresses"));
        type.getMethod("setDeviceModel",String.class).invoke(info,headset(request,preferences).name);
        type.getMethod("setConnectState",int.class).invoke(info,connected(request,preferences)?2:0);
        type.getMethod("setLeftBattery",int.class).invoke(info,value(request,preferences,"left",-1));
        type.getMethod("setRightBattery",int.class).invoke(info,value(request,preferences,"right",-1));
        type.getMethod("setBoxBattery",int.class).invoke(info,boxLevel(request,preferences));
        type.getMethod("setChargingState",int.class).invoke(info,value(request,preferences,"charging",0));
        type.getMethod("setWearType",int.class).invoke(info,1);
    }
    private static Field field(Object owner,String name)throws Exception{
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{
            Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
        }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static View find(View root,Context context,String name){
        int id=context.getResources().getIdentifier(name,"id","com.xiaomi.bluetooth.sample.preloadedFeature");
        return id==0?null:root.findViewById(id);
    }
}
