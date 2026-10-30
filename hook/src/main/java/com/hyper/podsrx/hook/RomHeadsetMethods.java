package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Explicit ROM layouts; obfuscated names are never resolved by signature alone. */
final class RomHeadsetMethods {
    interface ClassLookup {
        Class<?> load(String name) throws ClassNotFoundException;
    }

    record OfflineState(Method identity, Method read) {}
    private record OfflineLayout(String owner, String identity, String read) {}
    private record FeatureLayout(String owner, String support) {}

    private static final OfflineLayout[] OFFLINE = {
        new OfflineLayout("com.android.bluetooth.ble.app.offlinefind.OfflineFindProvider", "isAppleHeadset", "getAirPodsState"),
        new OfflineLayout("w0.j", "r", "h")
    };
    private static final FeatureLayout[] FEATURES = {
        new FeatureLayout("com.xiaomi.bluetooth.modules.airlibrary.db.AirLocalStorage", "isFeatureSupport"),
        new FeatureLayout("l1.a", "j")
    };

    static OfflineState offlineState(ClassLookup classes) throws NoSuchMethodException {
        NoSuchMethodException failure = new NoSuchMethodException("Unsupported ROM headset state layout");
        for (OfflineLayout layout : OFFLINE) {
            try {
                Class<?> owner = classes.load(layout.owner());
                Method identity = staticMethod(owner, layout.identity(), boolean.class, String.class, Context.class);
                Method read = staticMethod(owner, layout.read(), String[].class, String.class, Context.class);
                return new OfflineState(identity, read);
            } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
                failure.addSuppressed(unavailable);
            }
        }
        throw failure;
    }

    static Method featureSupport(ClassLookup classes) throws NoSuchMethodException {
        NoSuchMethodException failure = new NoSuchMethodException("Unsupported ROM headset feature layout");
        for (FeatureLayout layout : FEATURES) {
            try {
                return staticMethod(classes.load(layout.owner()), layout.support(), boolean.class,
                    Context.class, BluetoothDevice.class, String.class);
            } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
                failure.addSuppressed(unavailable);
            }
        }
        throw failure;
    }

    private static Method staticMethod(Class<?> owner, String name, Class<?> result, Class<?>... arguments)
        throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != result || !Modifier.isStatic(method.getModifiers()) ||
            !Modifier.isPublic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "." + name + " signature mismatch");
        }
        return method;
    }

    private RomHeadsetMethods() {}
}
