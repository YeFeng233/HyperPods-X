package com.hyper.podsrx.hook;

import android.bluetooth.*;
import android.content.*;
import android.hardware.SensorManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.*;
import java.lang.reflect.*;
import java.util.List;

/** Owns only the explicitly requested, bound-device virtual head tracker. */
final class SpatialHost extends Binder {
    private final Context context;
    private final android.content.SharedPreferences preferences;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final Object spatializer;
    private final Class<?> attributesClass;
    private volatile BluetoothProfile a2dp;
    private final BluetoothAdapter adapter;
    private BluetoothProfile profileProxy;
    private boolean profilePending;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;
    private volatile boolean closed;
    private boolean cleanupComplete;
    private final int appUid;
    private Object nativeController;
    private String activeAddress="";
    private IBinder client;
    private IBinder.DeathRecipient death;
    private long lastPose,sequence=-1;
    private boolean previousTracker;
    private int previousMode;
    private String lastStatus;

    static void install(Context context,android.content.SharedPreferences preferences)throws Exception {
        SpatialHost host=new SpatialHost(context,preferences);
        ReloadLifecycle.add(host::close);
        ReloadLifecycle.rejectWhile(host::busyForReload);
        host.receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent intent){
                if(host.closed||!ReloadLifecycle.isActive()||!SpatialWire.ACTION.equals(intent.getAction()))return;
                long age=SystemClock.elapsedRealtime()-intent.getLongExtra("created",0);
                if(age<0||age>1000)return;
                Bundle result=new Bundle();result.putBinder("bridge",host);
                setResultExtras(result);setResultCode(1);
            }
        };
        try {
            context.registerReceiver(host.receiver,new IntentFilter(SpatialWire.ACTION),SpatialWire.PERMISSION,host.handler,Context.RECEIVER_EXPORTED);
            host.receiverRegistered=true;
            host.connectProfile();
        } catch (RuntimeException error) {
            host.close();
            throw error;
        }
        DiagnosticLog.i("HyperPods-X","Spatial Binder bridge ready");
    }
    private SpatialHost(Context c,android.content.SharedPreferences p)throws Exception {
        context=c;lastStatus=HookStrings.get(c,R.string.hook_motion_bridge_ready);preferences=p;audio=c.getSystemService(AudioManager.class);
        spatializer=audio.getSpatializer();attributesClass=Class.forName("android.media.AudioDeviceAttributes");
        appUid=c.getPackageManager().getPackageUid(SpatialWire.APP,0);
        attachInterface(null,SpatialWire.DESCRIPTOR);
        adapter=c.getSystemService(BluetoothManager.class).getAdapter();
    }
    private synchronized void connectProfile(){
        profilePending=true;
        if(!adapter.getProfileProxy(context,new BluetoothProfile.ServiceListener(){
            @Override public void onServiceConnected(int profile,BluetoothProfile proxy){
                synchronized(SpatialHost.this){
                    profilePending=false;
                    if(closed){adapter.closeProfileProxy(profile,proxy);return;}
                    profileProxy=proxy;a2dp=proxy;
                }
            }
            @Override public void onServiceDisconnected(int profile){
                synchronized(SpatialHost.this){a2dp=null;if(!closed)stopAsync();}
            }
        },BluetoothProfile.A2DP))profilePending=false;
    }
    private synchronized boolean busyForReload(){
        return !cleanupComplete&&(profilePending||!activeAddress.isEmpty()||nativeController!=null);
    }
    private synchronized void close(){
        if(cleanupComplete)return;
        // Normally rejected by the preflight guard. Catch an intervening Binder START
        // as well, so native tracking is never abandoned by an unloaded generation.
        if(!activeAddress.isEmpty()||nativeController!=null)
            throw new IllegalStateException("Spatial session became active during reload");
        closed=true;
        if(receiverRegistered){context.unregisterReceiver(receiver);receiverRegistered=false;}
        receiver=null;
        stop();
        handler.removeCallbacksAndMessages(null);
        BluetoothProfile proxy=profileProxy;
        if(proxy!=null)adapter.closeProfileProxy(BluetoothProfile.A2DP,proxy);
        profileProxy=null;a2dp=null;
        cleanupComplete=true;
    }
    private void stopAsync(){if(!closed)handler.post(()->{synchronized(this){if(!closed)stop();}});}
    private Object attrs(String address)throws Exception {
        return attributesClass.getConstructor(int.class,int.class,String.class).newInstance(2,AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,address);
    }
    private Object call(String name,Class<?>[] types,Object...values)throws Exception {
        return spatializer.getClass().getMethod(name,types).invoke(spatializer,values);
    }
    private boolean routeMatches(String address){
        try {
            BluetoothProfile profile=a2dp;
            if(profile==null)return false;
            BluetoothDevice active=(BluetoothDevice)profile.getClass().getMethod("getActiveDevice").invoke(profile);
            return active!=null&&address.equalsIgnoreCase(active.getAddress());
        }catch(Exception error){return false;}
    }
    private boolean compatible(Object attr)throws Exception {
        return ((List<?>)call("getCompatibleAudioDevices",new Class<?>[0])).contains(attr);
    }
    private synchronized Bundle execute(int code,Bundle request)throws Exception {
        if(cleanupComplete){
            Bundle retired=new Bundle();
            retired.putBoolean("ok",false);
            retired.putBoolean("bridgeRetired",true);
            retired.putString("status",HookStrings.get(context, R.string.hook_motion_bridge_updated));
            return retired;
        }
        if(closed||!ReloadLifecycle.isActive())throw new IllegalStateException(HookStrings.get(context, R.string.hook_motion_bridge_updated));
        if((code==SpatialWire.START||code==SpatialWire.FIXED)&&!ReloadLifecycle.canStartOperation())
            throw new IllegalStateException(HookStrings.get(context, R.string.hook_module_updating));
        String address=request.getString("address","");
        if(!BluetoothAdapter.checkBluetoothAddress(address)||!address.equalsIgnoreCase(preferences.getString("address","")))
            throw new SecurityException(HookStrings.get(context, R.string.hook_bound_earbuds_only));
        Object attr=attrs(address);
        if(code==SpatialWire.QUERY){
            Bundle b=new Bundle();b.putBoolean("ok",true);
            b.putBoolean("compatible",compatible(attr));b.putBoolean("route",routeMatches(address));
            b.putBoolean("hasTracker",(Boolean)call("hasHeadTracker",new Class<?>[]{attributesClass},attr));
            b.putBoolean("trackerEnabled",(Boolean)call("isHeadTrackerEnabled",new Class<?>[]{attributesClass},attr));
            b.putInt("actualMode",(Integer)call("getHeadTrackingMode",new Class<?>[0]));
            b.putInt("desiredMode",(Integer)call("getDesiredHeadTrackingMode",new Class<?>[0]));
            b.putBoolean("nativeActive",!activeAddress.isEmpty());b.putString("status",lastStatus);return b;
        }
        if(code==SpatialWire.FIXED){
            if(!routeMatches(address))throw new IllegalStateException(HookStrings.get(context, R.string.hook_connect_and_select_output));
            stop();
            boolean enabled=request.getBoolean("enabled");
            call(enabled?"addCompatibleAudioDevice":"removeCompatibleAudioDevice",new Class<?>[]{attributesClass},attr);
            if(enabled)call("setDesiredHeadTrackingMode",new Class<?>[]{int.class},-1);
            lastStatus=enabled?HookStrings.get(context, R.string.hook_fixed_spatial_enabled):HookStrings.get(context, R.string.hook_spatial_disabled);
        }else if(code==SpatialWire.START){
            if(!routeMatches(address))throw new IllegalStateException(HookStrings.get(context, R.string.hook_earbuds_not_current_output));
            if(audio.getMode()!=AudioManager.MODE_NORMAL)throw new IllegalStateException(HookStrings.get(context, R.string.hook_tracking_paused_call));
            if(!compatible(attr))throw new IllegalStateException(HookStrings.get(context, R.string.hook_enable_fixed_spatial_first));
            stop();
            if(!context.getSystemService(SensorManager.class).getDynamicSensorList(37).isEmpty())
                throw new IllegalStateException(HookStrings.get(context, R.string.hook_other_head_sensor_active));
            IBinder token=request.getBinder("client");if(token==null)throw new SecurityException(HookStrings.get(context, R.string.hook_session_owner_missing));
            previousTracker=(Boolean)call("isHeadTrackerEnabled",new Class<?>[]{attributesClass},attr);
            previousMode=(Integer)call("getDesiredHeadTrackingMode",new Class<?>[0]);
            Class<?> nativeClass=Class.forName("com.android.bluetooth.ble.app.headset.Spatialcontroller",true,context.getClassLoader());
            nativeController=nativeClass.getConstructor().newInstance();
            activeAddress=address;lastPose=SystemClock.elapsedRealtime();sequence=-1;
            try {
                // On this exact ROM the JNI boolean is an error flag: false after a full
                // UHID_CREATE write, true on open/write failure (verified in libspatial).
                boolean failed=(Boolean)nativeClass.getMethod("createspatialhid",String.class).invoke(nativeController,hidAddress(address));
                if(failed)throw new IllegalStateException(HookStrings.get(context, R.string.hook_virtual_sensor_creation_failed));
                client=token;death=this::stopAsync;
                token.linkToDeath(death,0);
                call("setHeadTrackerEnabled",new Class<?>[]{boolean.class,attributesClass},true,attr);
                call("setDesiredHeadTrackingMode",new Class<?>[]{int.class},1);
            }catch(Throwable error){stop();throw error;}
            lastStatus=HookStrings.get(context, R.string.hook_virtual_sensor_created);
            handler.postDelayed(watchdog,500);
            DiagnosticLog.i("HyperPods-X","Created native spatial head tracker");
        }else if(code==SpatialWire.POSE){
            if(activeAddress.isEmpty()||!address.equalsIgnoreCase(activeAddress))throw new IllegalStateException(HookStrings.get(context, R.string.hook_tracking_session_ended));
            long age=SystemClock.elapsedRealtime()-request.getLong("captured",0);
            long next=request.getLong("sequence",-1);
            float[] v=request.getFloatArray("vector");
            if(age<0||age>250||next<=sequence||v==null||v.length!=3)throw new IllegalArgumentException(HookStrings.get(context, R.string.hook_pose_stale_invalid));
            double n=0;for(float x:v){if(!Float.isFinite(x))throw new IllegalArgumentException(HookStrings.get(context, R.string.hook_pose_invalid));n+=x*x;}
            if(n>10.0)throw new IllegalArgumentException(HookStrings.get(context, R.string.hook_pose_out_of_range));
            // ROM native code scales integer degrees to signed HID rotation-vector values.
            // It reorders [yaw,pitch,roll] to [X,Y,Z]; provide the rotation vector in that order.
            float degrees=(float)(180.0/Math.PI);
            float[] nativeInput=new float[]{v[2]*degrees,v[0]*degrees,v[1]*degrees};
            nativeController.getClass().getMethod("sendspatialdata",String.class,float[].class).invoke(nativeController,hidAddress(address),nativeInput);
            sequence=next;lastPose=SystemClock.elapsedRealtime();
        }else if(code==SpatialWire.STOP){stop();}
        else throw new IllegalArgumentException(HookStrings.get(context, R.string.hook_unknown_bridge_command));
        Bundle b=new Bundle();b.putBoolean("ok",true);b.putString("status",lastStatus);return b;
    }
    private final Runnable watchdog=new Runnable(){
        @Override public void run(){synchronized(SpatialHost.this){
            if(activeAddress.isEmpty())return;
            if(SystemClock.elapsedRealtime()-lastPose>800||!routeMatches(activeAddress)||audio.getMode()!=AudioManager.MODE_NORMAL){
                stop();lastStatus=HookStrings.get(context, R.string.hook_sensor_cleared_route_changed);return;
            }
            try {
                Object device=attrs(activeAddress);
                if((Boolean)call("hasHeadTracker",new Class<?>[]{attributesClass},device)&&
                        !(Boolean)call("isHeadTrackerEnabled",new Class<?>[]{attributesClass},device))
                    call("setHeadTrackerEnabled",new Class<?>[]{boolean.class,attributesClass},true,device);
            }catch(Exception error){DiagnosticLog.w("HyperPods-X","Head tracker association is pending",error);}
            handler.postDelayed(this,250);
        }}
    };
    private static String hidAddress(String address){return "0x"+address.replace(":","");}
    private void stop(){
        handler.removeCallbacks(watchdog);
        String address=activeAddress;activeAddress="";
        if(client!=null&&death!=null){try{client.unlinkToDeath(death,0);}catch(RuntimeException ignored){}}
        client=null;death=null;
        if(address.isEmpty())return;
        try {nativeController.getClass().getMethod("destroyspatialhid",String.class).invoke(nativeController,hidAddress(address));}
        catch(Throwable e){DiagnosticLog.w("HyperPods-X","Native head tracker cleanup failed",e);}
        finally {nativeController=null;}
        try {
            call("setHeadTrackerEnabled",new Class<?>[]{boolean.class,attributesClass},previousTracker,attrs(address));
            call("setDesiredHeadTrackingMode",new Class<?>[]{int.class},previousMode);
        }catch(Throwable e){DiagnosticLog.w("HyperPods-X","Head tracker preference restore failed",e);}
        lastStatus=HookStrings.get(context, R.string.hook_head_sensor_cleared);
        DiagnosticLog.i("HyperPods-X","Destroyed native spatial head tracker");
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
        if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(SpatialWire.DESCRIPTOR);return true;}
        if(Binder.getCallingUid()!=appUid)throw new SecurityException("Unsupported caller");
        data.enforceInterface(SpatialWire.DESCRIPTOR);
        Bundle request=data.readBundle(getClass().getClassLoader());data.enforceNoDataAvail();
        Bundle response;
        long identity=Binder.clearCallingIdentity();
        try {response=execute(code,request==null?new Bundle():request);}
        catch(Throwable error){
            Throwable reason=error instanceof InvocationTargetException&&error.getCause()!=null?error.getCause():error;
            response=new Bundle();response.putBoolean("ok",false);response.putString("status",reason.getClass().getSimpleName()+": "+reason.getMessage());
            if(code!=SpatialWire.POSE)DiagnosticLog.w("HyperPods-X","Spatial bridge request failed",reason);
        }finally{Binder.restoreCallingIdentity(identity);}
        if(reply!=null){reply.writeNoException();reply.writeBundle(response);}return true;
    }
}
