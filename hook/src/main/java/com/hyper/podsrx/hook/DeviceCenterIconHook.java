package com.hyper.podsrx.hook;

import android.content.Context;
import android.graphics.drawable.Drawable;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.Locale;

/** Replaces the camera-grip icon used by the SystemUI Device Center card. */
final class DeviceCenterIconHook {
    private static final String TAG = "HyperPods-X";
    private static final String COMPANION = "miui.systemui.devicecenter.devices.DeviceInfoWrapper$Companion";
    private static final String TARGET_TYPE = "camera";
    private static final String TARGET_HANDLE = "handle";
    private static final String TARGET_CAMERA = "camera";
    private static final java.util.Set<ClassLoader> INSTALLED =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<Class<?>> HOOKED_COMPANIONS =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private DeviceCenterIconHook() { }

    static void reset() {
        synchronized (INSTALLED) { INSTALLED.clear(); }
        synchronized (HOOKED_COMPANIONS) { HOOKED_COMPANIONS.clear(); }
    }

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        if (!INSTALLED.add(loader)) return;
        TypefaceHolder.typeface = SystemUiHandleIconHook.symbolTypeface(module);
        if (TypefaceHolder.typeface == null) throw new IllegalStateException("SF Symbols font is unavailable");
        hookLoadedClass(module, loader);
        hookClassLoading(module);
        DiagnosticLog.i(TAG, "Device Center icon hook installed");
    }

    private static void hookLoadedClass(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> companion = findClass(loader, COMPANION);
        if (companion == null) return;
        synchronized (HOOKED_COMPANIONS) {
            if (!HOOKED_COMPANIONS.add(companion)) return;
        }
        int candidates = 0;
        for (Method method : companion.getDeclaredMethods()) {
            if (!method.getName().equals("getDrawable") || method.getParameterCount() != 2
                || !Drawable.class.isAssignableFrom(method.getReturnType())) continue;
            candidates++;
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                Object info = chain.getArg(0);
                Object context = chain.getArg(1);
                if (!(context instanceof Context host) || !isCameraHandle(info, host)) return result;
                DiagnosticLog.i(TAG, "Replacing Device Center camera handle icon");
                return SystemUiHandleIconHook.caseGlyphDrawable(TypefaceHolder.typeface, host);
            });
        }
        if (candidates == 0) {
            DiagnosticLog.w(TAG, "Device Center drawable hook target unavailable");
        }
    }

    private static void hookClassLoading(XposedModule module) {
        try {
            hookClassLoadingMethod(module, ClassLoader.class.getDeclaredMethod("loadClass", String.class));
            hookClassLoadingMethod(module, ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class));
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w(TAG, "Device Center class-loader hook unavailable", error);
        }
    }

    private static void hookClassLoadingMethod(XposedModule module, Method loadClass) {
        module.hook(loadClass).intercept(chain -> {
            Object requested = chain.getArg(0);
            boolean interesting = requested instanceof String
                && ((String) requested).contains("DeviceInfoWrapper");
            Object result = chain.proceed();
            if (interesting && result instanceof Class<?> loaded && COMPANION.equals(loaded.getName())) {
                try { hookLoadedClass(module, loaded.getClassLoader()); }
                catch (Throwable error) { DiagnosticLog.w(TAG, "Device Center delayed hook skipped", error); }
            }
            return result;
        });
    }

    private static boolean isCameraHandle(Object info, Context host) {
        if (info == null) return false;
        String type = invokeString(info, "getDeviceType");
        String category = invokeString(info, "getCategory");
        String title = invokeString(info, "getTitle");
        String resource = iconResource(info, host);
        boolean handle = containsHandle(type) || containsHandle(category) || containsHandle(title) || containsHandle(resource);
        boolean camera = containsCamera(type) || containsCamera(category) || containsCamera(title) || containsCamera(resource);
        return handle || (camera && containsHandle(resource));
    }

    private static boolean containsHandle(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains(TARGET_HANDLE);
    }

    private static boolean containsCamera(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains(TARGET_CAMERA) || normalized.contains(TARGET_TYPE);
    }

    private static String iconResource(Object info, Context host) {
        Object icon = invoke(info, "getIcon");
        if (icon == null) return null;
        Object data = invoke(icon, "getData");
        if (data == null) return null;
        Object pkg = invoke(data, "getPkg");
        Object id = invoke(data, "getId");
        if (!(pkg instanceof String packageName) || !(id instanceof Integer resId)) return null;
        try {
            return packageName + ":" + host.getPackageManager()
                .getResourcesForApplication(packageName).getResourceEntryName(resId);
        } catch (Exception ignored) {
            return packageName + ":0x" + Integer.toHexString(resId);
        }
    }

    private static String invokeString(Object owner, String name) {
        Object value = invoke(owner, name);
        return value instanceof String ? (String) value : null;
    }

    private static Object invoke(Object owner, String name) {
        if (owner == null) return null;
        try { return owner.getClass().getMethod(name).invoke(owner); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static Class<?> findClass(ClassLoader loader, String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError ignored) { return null; }
    }

    private static final class TypefaceHolder {
        private static android.graphics.Typeface typeface;
        private TypefaceHolder() { }
    }
}
