package com.hyper.podsrx.hook;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Application;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
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
    private boolean bluetoothInstalled;
    private boolean hotReload;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        main = new Handler(Looper.getMainLooper());
        processName = param.getProcessName();
        Compatibility.process(processName);
        ReloadLifecycle.add(() -> {
            main.removeCallbacksAndMessages(null);
            if (preferences != null && listener != null) preferences.unregisterOnSharedPreferenceChangeListener(listener);
            listener = null;
            lastPreference.clear();
        });
        moduleLog(Log.INFO, "HyperPods-X", "Loaded in " + param.getProcessName() + "; API=" + getApiVersion());
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        installPackage(param.getPackageName(), param.getClassLoader(), null);
    }

    @Override public boolean onHotReloading(HotReloadingParam param) {
        try {
            boolean retired = ReloadLifecycle.onMain(ReloadLifecycle::retire);
            moduleLog(Log.INFO, "HyperPods-Reload", "Retire " + processName + ": " + retired);
            return retired;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to retire module generation", error);
        }
    }

    @Override public void onHotReloaded(HotReloadedParam param) {
        try {
            ReloadLifecycle.onMain(() -> {
                Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
                if (app == null) throw new IllegalStateException("Host Application unavailable");
                for (var handle : param.getOldHookHandles()) handle.unhook();
                hotReload = true;
                onModuleLoaded(param);
                installPackage(app.getPackageName(), app.getClassLoader(), app);
                NativeControlPanelAdapter.refreshExistingWindows();
                moduleLog(Log.INFO, "HyperPods-Reload", "Reload complete in " + processName);
                return true;
            });
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize reloaded module", error);
        }
    }

    private void installPackage(String pkg, ClassLoader loader, Application runningApp) {
        if (!ReloadLifecycle.isActive()) return;
        if (pkg.equals("com.android.settings") || pkg.equals("com.xiaomi.bluetooth") || pkg.equals("com.milink.service")) {
            installDiagnosticBridge();
        }
        if (pkg.equals("com.milink.service")) {
            if (!processName.equals(pkg) && !processName.equals(pkg+":core") && !processName.equals(pkg+":ui")) return;
            preferences=HubReader.remote();
            if (processName.equals(pkg+":ui")) {
                try { NativeControlPanelAdapter.install(this,loader,preferences); installed("Native adaptive control row"); }
                catch (Throwable error) { installationFailure("Native adaptive control row", error); }
            }
            try { NativeSpatialControlAdapter.install(this,loader,preferences); installed("Native spatial control"); }
            catch (Throwable error) { installationFailure("Native spatial control", error); }
            try { NativeSettingsAdapter.install(this,loader,preferences); installed("Native headset settings entry"); }
            catch (Throwable error) { installationFailure("Native headset settings entry", error); }
            return;
        }
        if (pkg.equals("com.miui.securitycenter")) {
            // The battery widget's provider lives in its own process.
            if (!processName.equals(pkg + ".remote")) return;
            installDiagnosticBridge();
            preferences = HubReader.remote();
            try { WidgetBatteryHost.install(this, loader, (HubReader) preferences); installed("Widget headset battery"); }
            catch (Throwable error) { installationFailure("Widget headset battery", error); }
            return;
        }
        if (!pkg.equals("com.android.settings") && !pkg.equals("com.xiaomi.bluetooth")) return;
        if (pkg.equals("com.xiaomi.bluetooth")) {
            if (!pkg.equals(processName) && !processName.equals(pkg+":ui")) return;
            try {
                // The hub reads the app's configuration; everything else reads the hub.
                preferences=pkg.equals(processName)?getRemotePreferences("device_state"):HubReader.remote();
                if (runningApp != null) installBluetooth(runningApp, pkg);
                else {
                    Class<?> app = Class.forName("com.android.bluetooth.ble.MiuiApplication", false, loader);
                    hook(app.getDeclaredMethod("onCreate")).intercept(chain -> {
                        Object result = chain.proceed();
                        if (ReloadLifecycle.isActive()) installBluetooth((Application) chain.getThisObject(), pkg);
                        return result;
                    });
                }
            } catch (Throwable error) { installationFailure("Native popup host", error); }
            return;
        }
        try {
            preferences = HubReader.remote();
            listener = (p, key) -> { if (ReloadLifecycle.isActive()) main.post(this::refreshSummary); };
            preferences.registerOnSharedPreferenceChangeListener(listener);
            try {
                Class<?> devicePreference=Class.forName("com.android.settings.bluetooth.BluetoothDevicePreference",false,loader);
                Method click=devicePreference.getDeclaredMethod("onClick",View.class);
                hook(click).intercept(chain->{
                    try {
                        Object view=chain.getArg(0);
                        if(view instanceof View&&openDeviceFromArrow(chain.getThisObject(),(View)view))return null;
                    } catch(Throwable error) {moduleLog(Log.WARN,"HyperPods-X","Direct headset settings unavailable",error);}
                    return chain.proceed();
                });
                moduleLog(Log.INFO,"HyperPods-X","Bluetooth device arrow hook installed");
                installed("Bluetooth device arrow");
            } catch(ReflectiveOperationException error) {installationFailure("Bluetooth device arrow", error);}
            int detailHooks = 0;
            for (String name : new String[]{"com.android.settings.bluetooth.DeviceProfilesSettings", "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment"}) {
                try {
                Class<?> page = Class.forName(name, false, loader);
                Method resume = page.getDeclaredMethod("onResume");
                hook(resume).intercept(chain -> {
                    Object result = chain.proceed();
                    try { if (ReloadLifecycle.isActive()) inject(chain.getThisObject(), loader); }
                    catch (Throwable error) { moduleLog(Log.WARN, "HyperPods-X", "Detail entry unavailable", error); }
                    return result;
                });
                detailHooks++;
                } catch (ReflectiveOperationException error) { moduleLog(Log.WARN, "HyperPods-X", "Settings page unavailable: " + name, error); }
            }
            if (detailHooks == 0) throw new IllegalStateException("No supported Bluetooth details page");
            moduleLog(Log.INFO, "HyperPods-X", "Bluetooth details hook installed: " + detailHooks);
            installed("Settings adapter");
        } catch (Throwable error) { installationFailure("Settings adapter", error); }
    }

    private void installBluetooth(Application host, String pkg) {
        if (bluetoothInstalled || !ReloadLifecycle.isActive()) return;
        bluetoothInstalled = true;
        DiagnosticLog.install(host);
        if (pkg.equals(processName)) {
            SharedPreferences config = preferences;
            try { preferences = HubHost.install(this, host, config).reader; installed("State hub"); }
            catch (Throwable error) { installationFailure("State hub", error); }
            try { NativeAirPodsDialogAdapter.suppressConnectToast(this, host.getClassLoader(), preferences); installed("ROM AirPods connection island filter"); }
            catch (Throwable error) { installationFailure("ROM AirPods connection island filter", error); }
            try { LidRelayHost.install(this, host.getClassLoader()); installed("Apple advertisement relay"); }
            catch (Throwable error) { installationFailure("Apple advertisement relay", error); }
            try { NativeControlHost.install(this, host, preferences); installed("Native control bridge"); }
            catch (Throwable error) { installationFailure("Native control bridge", error); }
            // The bound address is configuration; the spatial and conversation bridges need nothing live.
            try { SpatialHost.install(host, config); installed("Spatial bridge"); }
            catch (Throwable error) { installationFailure("Spatial bridge", error); }
            try { ConversationAudioHost.install(host, config); installed("Conversation bridge"); }
            catch (Throwable error) { installationFailure("Conversation bridge", error); }
        } else {
            Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityPreCreated(Activity activity, Bundle saved) {
                    if (!ReloadLifecycle.isActive()) return;
                    try { NativeAirPodsDialogAdapter.install(HyperPodsModule.this, activity, preferences); installed("Native AirPods dialog adapter"); }
                    catch (Throwable error) {
                        Compatibility.failed("Native AirPods dialog adapter", error);
                        moduleLog(Log.WARN, "HyperPods-X", "Native AirPods dialog adapter unavailable", error);
                    }
                }
                @Override public void onActivityCreated(Activity activity, Bundle saved) {
                    if (!ReloadLifecycle.isActive()) return;
                    NativeAirPodsDialogAdapter.autoDismiss(activity);
                    NativeAirPodsDialogAdapter.followLid(activity, preferences);
                }
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) { onActivityPreCreated(activity, null); }
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
                @Override public void onActivityDestroyed(Activity activity) { NativeAirPodsDialogAdapter.stopFollowingLid(activity); }
            };
            host.registerActivityLifecycleCallbacks(callbacks);
            ReloadLifecycle.add(() -> host.unregisterActivityLifecycleCallbacks(callbacks));
        }
    }

    private void installed(String component) { Compatibility.installed(component); }

    private void installationFailure(String component, Throwable error) {
        Compatibility.failed(component, error);
        moduleLog(Log.WARN, "HyperPods-X", component + " unavailable", error);
        if (hotReload) throw new IllegalStateException(component + " reload failed", error);
    }

    private void moduleLog(int priority, String tag, String message) {
        moduleLog(priority, tag, message, null);
    }

    private void moduleLog(int priority, String tag, String message, Throwable error) {
        DiagnosticLog.write(priority, tag, message, error);
        // Retain the framework's own module log as well as the exportable buffer.
        String detail = message + (error == null ? "" : "\n" + Log.getStackTraceString(error));
        log(priority, tag, LogRedactor.limitUtf8(LogRedactor.redact(detail), 7500));
    }

    private void installDiagnosticBridge() {
        // Application.attach runs before LoadedApk assigns ContextImpl.outerContext.
        // Registering there uses a different receiver-dispatcher key from unregister
        // after startup. Wait until Application creation has completely returned.
        main.post(() -> {
            if (!ReloadLifecycle.isActive()) return;
            try {
                Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
                if (app != null) DiagnosticLog.install(app);
            } catch (Throwable error) {
                moduleLog(Log.WARN, "HyperPods-X", "Diagnostic bridge attachment unavailable", error);
            }
        });
    }

    private boolean openDeviceFromArrow(Object preference,View arrow)throws Exception {
        if(!ReloadLifecycle.isActive()||preferences==null||!preferences.getBoolean("enabled",false))return false;
        int detail=arrow.getResources().getIdentifier("preference_detail","id","com.android.settings");
        if(detail==0||arrow.getId()!=detail)return false;
        String target=preferences.getString("address","");
        if(!BluetoothAdapter.checkBluetoothAddress(target))return false;
        Object cached=call(preference,"getCachedDevice");
        if(cached==null)return false;
        Object device=call(cached,"getDevice");
        if(!(device instanceof BluetoothDevice)||!target.equalsIgnoreCase(((BluetoothDevice)device).getAddress()))return false;
        Context context=arrow.getContext();
        Intent intent=new Intent().setClassName("com.hyper.podsrx","com.hyper.podsrx.DeviceSettingsActivity")
                .putExtra("device_address",target);
        if(intent.resolveActivity(context.getPackageManager())==null)return false;
        while(context instanceof ContextWrapper&&!(context instanceof Activity)){
            Context base=((ContextWrapper)context).getBaseContext();
            if(base==null||base==context)break;
            context=base;
        }
        if(!(context instanceof Activity))intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        moduleLog(Log.INFO,"HyperPods-X","Opened bound headset settings from Bluetooth device arrow");
        return true;
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
            preferenceClass.getMethod("setTitle", CharSequence.class).invoke(preference, HookStrings.get(context, R.string.hook_advanced_settings));
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
        if (!ReloadLifecycle.isActive()) return;
        Object preference = lastPreference.get();
        if (preference == null || preferences == null) return;
        try {
            Context context = (Context) call(preference, "getContext");
            String summary = preferences.getString("summary", HookStrings.get(context, R.string.hook_settings_summary));
            preference.getClass().getMethod("setSummary", CharSequence.class).invoke(preference, summary);
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
