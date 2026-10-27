package com.hyper.podsrx.hook;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Application;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/** Scoped Settings and Bluetooth adapters; reflected interfaces determine availability. */
public final class HyperPodsModule extends XposedModule {
    private static final String KEY = "hyperpods_x_settings";
    private Handler main;
    private SharedPreferences preferences;
    private WeakReference<Object> lastPreference = new WeakReference<>(null);
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private String processName;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        main = new Handler(Looper.getMainLooper());
        processName = param.getProcessName();
        log(Log.INFO, "HyperPods-X", "Loaded in " + param.getProcessName() + "; API=" + getApiVersion());
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if (pkg.equals("com.milink.service")) {
            if (!processName.equals(pkg) && !processName.equals(pkg+":core") && !processName.equals(pkg+":ui")) return;
            preferences=getRemotePreferences("device_state");
            if (processName.equals(pkg+":ui")) {
                try { NativeControlPanelAdapter.install(this,param.getClassLoader(),preferences); }
                catch (Throwable error) { log(Log.WARN,"HyperPods-X","Native adaptive control row unavailable",error); }
            }
            try { NativeSpatialControlAdapter.install(this,param.getClassLoader(),preferences); }
            catch (Throwable error) { log(Log.WARN,"HyperPods-X","Native spatial control unavailable",error); }
            return;
        }
        if (!pkg.equals("com.android.settings") && !pkg.equals("com.xiaomi.bluetooth")) return;
        if (pkg.equals("com.xiaomi.bluetooth")) {
            if (!pkg.equals(processName) && !processName.equals(pkg+":ui")) return;
            try {
                preferences=getRemotePreferences("device_state");
                Class<?> app = Class.forName("com.android.bluetooth.ble.MiuiApplication", false, param.getClassLoader());
                hook(app.getDeclaredMethod("onCreate")).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Application host=(Application)chain.getThisObject();
                        if(pkg.equals(processName)){
                            NativePopupHost.install(host);
                            try{NativeControlHost.install(HyperPodsModule.this,host,preferences);}catch(Throwable error){log(Log.WARN,"HyperPods-X","Native control bridge unavailable",error);}
                            try{SpatialHost.install(host,preferences);}catch(Throwable error){log(Log.WARN,"HyperPods-X","Spatial bridge unavailable",error);}
                            try{ConversationAudioHost.install(host,preferences);}catch(Throwable error){log(Log.WARN,"HyperPods-X","Conversation bridge unavailable",error);}
                        }
                        else host.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
                            @Override public void onActivityPreCreated(Activity activity,Bundle saved){
                                try{NativeAirPodsDialogAdapter.install(HyperPodsModule.this,activity,preferences);}
                                catch(Throwable error){log(Log.WARN,"HyperPods-X","Native AirPods dialog adapter unavailable",error);}
                            }
                            @Override public void onActivityCreated(Activity activity,Bundle saved){NativeAirPodsDialogAdapter.autoDismiss(activity);}
                            @Override public void onActivityStarted(Activity activity){}
                            @Override public void onActivityResumed(Activity activity){}
                            @Override public void onActivityPaused(Activity activity){}
                            @Override public void onActivityStopped(Activity activity){}
                            @Override public void onActivitySaveInstanceState(Activity activity,Bundle out){}
                            @Override public void onActivityDestroyed(Activity activity){}
                        });
                    }
                    catch (Throwable error) { log(Log.WARN, "HyperPods-X", "Native popup bridge unavailable", error); }
                    return result;
                });
            } catch (Throwable error) { log(Log.WARN, "HyperPods-X", "Native popup host unavailable", error); }
            return;
        }
        try {
            preferences = getRemotePreferences("device_state");
            listener = (p, key) -> main.post(this::refreshSummary);
            preferences.registerOnSharedPreferenceChangeListener(listener);
            for (String name : new String[]{"com.android.settings.bluetooth.DeviceProfilesSettings", "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment"}) {
                try {
                Class<?> page = Class.forName(name, false, param.getClassLoader());
                Method resume = page.getDeclaredMethod("onResume");
                hook(resume).intercept(chain -> {
                    Object result = chain.proceed();
                    try { inject(chain.getThisObject(), param.getClassLoader()); }
                    catch (Throwable error) { log(Log.WARN, "HyperPods-X", "Detail entry unavailable", error); }
                    return result;
                });
                } catch (ReflectiveOperationException error) { log(Log.WARN, "HyperPods-X", "Settings page unavailable: " + name, error); }
            }
            log(Log.INFO, "HyperPods-X", "Bluetooth details hook installed");
        } catch (Throwable error) { log(Log.ERROR, "HyperPods-X", "Settings adapter could not load", error); }
    }

    private void inject(Object fragment, ClassLoader loader) throws Exception {
        String target = preferences.getString("address", "");
        if (target == null || target.isEmpty()) return;
        Object address = field(fragment, "deviceAddress");
        if (address == null) address = field(fragment, "mDeviceMacAddress");
        if (address == null || !target.equalsIgnoreCase(address.toString())) return;
        Object screen = call(fragment, "getPreferenceScreen");
        if (screen == null) return;
        Object preference = screen.getClass().getMethod("findPreference", CharSequence.class).invoke(screen, KEY);
        Class<?> preferenceClass = Class.forName("androidx.preference.Preference", false, loader);
        if (preference == null) {
            Context context = (Context) call(fragment, "getContext");
            if (context == null) return;
            preference = preferenceClass.getConstructor(Context.class).newInstance(context);
            preferenceClass.getMethod("setKey", String.class).invoke(preference, KEY);
            preferenceClass.getMethod("setTitle", CharSequence.class).invoke(preference, "HyperPods-X 高级设置");
            preferenceClass.getMethod("setOrder", int.class).invoke(preference, 2);
            preferenceClass.getMethod("setPersistent", boolean.class).invoke(preference, false);
            Intent intent = new Intent().setClassName("com.hyper.podsrx", "com.hyper.podsrx.DeviceSettingsActivity");
            intent.putExtra("device_address", target);
            preferenceClass.getMethod("setIntent", Intent.class).invoke(preference, intent);
            screen.getClass().getMethod("addPreference", preferenceClass).invoke(screen, preference);
        }
        lastPreference = new WeakReference<>(preference);
        refreshSummary();
    }
    private void refreshSummary() {
        Object preference = lastPreference.get();
        if (preference == null || preferences == null) return;
        try {
            String summary = preferences.getString("summary", "查看耳机电量与高级设置");
            if (System.currentTimeMillis() - preferences.getLong("updated", 0) > 60000) summary = "上次读取 · " + summary;
            preference.getClass().getMethod("setSummary", CharSequence.class).invoke(preference,
                    summary);
        } catch (Throwable ignored) { /* a destroyed settings page is harmless */ }
    }
    private static Object call(Object target, String name) throws Exception { return target.getClass().getMethod(name).invoke(target); }
    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
