package com.hyper.podsrx.hook;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** Reuses the ROM's AirPods controller only for a signed request for the bound device. */
final class NativeAirPodsDialogAdapter {
    private static final String APP="com.hyper.podsrx";
    private static final Set<ClassLoader> installed=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<String> reported=Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<Intent> creating=new ThreadLocal<>();
    private static final Handler dismissHandler=new Handler(Looper.getMainLooper());
    private static final Set<Activity> dismissActivities=Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean cleanupRegistered;

    private static void prepareCleanup() {
        if(cleanupRegistered)return;
        cleanupRegistered=true;
        ReloadLifecycle.add(()->{
            dismissHandler.removeCallbacksAndMessages(null);
            // A live popup does not receive onActivityCreated again in the new
            // generation. Finish only signed module popups whose timer we owned.
            for(Activity activity:new ArrayList<>(dismissActivities))finishModulePopup(activity);
            dismissActivities.clear();
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
        Method pair=controller.getDeclaredMethod("startPairAndConnectLocal",long.class,boolean.class,boolean.class);
        for(String setter:new String[]{"setDevice","setDeviceModel"})info.getMethod(setter,String.class);
        for(String setter:new String[]{"setConnectState","setLeftBattery","setRightBattery","setBoxBattery","setChargingState","setWearType"})
            info.getMethod(setter,int.class);
        module.hook(create).intercept(chain->{
            if(!ReloadLifecycle.isActive())return chain.proceed();
            Intent request=request(chain.getThisObject(),preferences);
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
                            request.getStringExtra("headset_addresses"),connected(request,preferences)?"2":"0","AirPods 5"};
                }
            } catch(Exception | LinkageError error) { report("getAirPodsState",error); }
            return result;
        });
        module.hook(parameters).intercept(chain->{
            Object result=chain.proceed();
            if(!ReloadLifecycle.isActive())return result;
            try {
                if(Boolean.TRUE.equals(result)&&request(chain.getThisObject(),preferences)!=null){
                    Object owner=chain.getThisObject();
                    Activity host=(Activity)field(owner,"mActivityContext").get(owner);
                    Context artwork=host.createPackageContext(APP,0);
                    Drawable buds=artwork.getDrawable(artwork.getResources().getIdentifier("airpods5_pair","drawable",APP));
                    Drawable box=artwork.getDrawable(artwork.getResources().getIdentifier("airpods5_case","drawable",APP));
                    Drawable[] icons=(Drawable[])field(owner,"mHeadsetIconDrawable").get(owner);
                    java.util.Arrays.fill(icons,buds);
                    field(owner,"mHeadsetBoxIconDrawable").set(owner,box);
                    DiagnosticLog.i("HyperPods-X","Native AirPods dialog uses AirPods 5 artwork");
                }
            } catch(Exception | LinkageError error) { report("initParameter",error); }
            return result;
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
                        if(label!=null)label.setText(validLevel(amount)?amount+"%"+(history?"（上次）":""):"—");
                    }
                    TextView caption=(TextView)find(root,host,"textCharacters");
                    if(caption!=null)caption.setText(request.getBooleanExtra("hyperpods_preview",false)?"弹窗预览":connected(request,preferences)?"已连接":"充电盒已打开");
                    for(String id:new String[]{"imageViewBoxLayout","textViewBoxBattery","textViewBoxBatteryPercent"}){
                        View box=find(root,host,id);if(box!=null)box.setVisibility(View.VISIBLE);
                    }
                }
            } catch(Exception | LinkageError error) { report("setConnectSuccessLayoutDetail",error); }
            return result;
        });
        // Keep the ROM connection callback intact for real lid events: its
        // CachedBluetoothDevice.connect(true) restores the A2DP/HFP profiles.
        // A preview must remain presentation-only and never connect the device.
        module.hook(pair).intercept(chain->{
            Intent request=request(chain.getThisObject(),preferences);
            return request!=null&&request.getBooleanExtra("hyperpods_preview",false)?null:chain.proceed();
        });
        installed.add(loader);
        DiagnosticLog.i("HyperPods-X","Native AirPods dialog adapter ready");
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
            String address=request.getStringExtra("headset_addresses");
            return token!=null&&APP.equals(token.getCreatorPackage())&&address!=null&&address.equalsIgnoreCase(preferences.getString("address",""))?request:null;
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
    private static boolean validLevel(int level){return level>=0&&level<=100;}
    private static int boxLevel(Intent request,SharedPreferences preferences){
        int current=value(request,preferences,"box",-1);
        int previous=value(request,preferences,"box_history",-1);
        return validLevel(current)?current:validLevel(previous)?previous:-1;
    }
    private static void populate(Object info,Intent request,SharedPreferences preferences)throws Exception{
        Class<?> type=info.getClass();
        type.getMethod("setDevice",String.class).invoke(info,request.getStringExtra("headset_addresses"));
        type.getMethod("setDeviceModel",String.class).invoke(info,"AirPods 5");
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
