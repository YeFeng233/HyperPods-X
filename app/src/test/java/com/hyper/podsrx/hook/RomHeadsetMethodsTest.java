package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class RomHeadsetMethodsTest {
    private static final String OFFLINE = "com.android.bluetooth.ble.app.offlinefind.OfflineFindProvider";
    private static final String STORAGE = "com.xiaomi.bluetooth.modules.airlibrary.db.AirLocalStorage";

    private RomHeadsetMethods.ClassLookup classes(Map<String, Class<?>> available) {
        return name -> {
            Class<?> found = available.get(name);
            if (found == null) throw new ClassNotFoundException(name);
            return found;
        };
    }

    @Test public void originalLayoutTakesPrecedence() throws Exception {
        var lookup = classes(Map.of(OFFLINE, Original.class, STORAGE, Original.class,
            "w0.j", Obfuscated.class, "l1.a", Obfuscated.class));
        var state = RomHeadsetMethods.offlineState(lookup);
        assertEquals("isAppleHeadset", state.identity().getName());
        assertEquals("getAirPodsState", state.read().getName());
        assertEquals("isFeatureSupport", RomHeadsetMethods.featureSupport(lookup).getName());
    }

    @Test public void verifiedHyperOsLayoutResolvesBothStateMethodsAndFeatureGuard() throws Exception {
        var lookup = classes(Map.of("w0.j", Obfuscated.class, "l1.a", Obfuscated.class));
        var state = RomHeadsetMethods.offlineState(lookup);
        assertEquals("r", state.identity().getName());
        assertEquals("h", state.read().getName());
        assertEquals("j", RomHeadsetMethods.featureSupport(lookup).getName());
    }

    @Test public void incompleteNamedLayoutFallsBackAsAUnit() throws Exception {
        var lookup = classes(Map.of(OFFLINE, Incomplete.class, "w0.j", Obfuscated.class));
        var state = RomHeadsetMethods.offlineState(lookup);
        assertEquals(Obfuscated.class, state.identity().getDeclaringClass());
        assertEquals(Obfuscated.class, state.read().getDeclaringClass());
    }

    @Test public void unrelatedClassNamesAreNotSearched() {
        var lookup = classes(Map.of("arbitrary.State", Obfuscated.class));
        assertThrows(NoSuchMethodException.class, () -> RomHeadsetMethods.offlineState(lookup));
        assertThrows(NoSuchMethodException.class, () -> RomHeadsetMethods.featureSupport(lookup));
    }

    @Test public void wrongReturnTypeInstanceMethodsAndReorderedArgumentsAreRejected() {
        for (Class<?> wrong : new Class<?>[] { WrongReturn.class, Instance.class, WrongArguments.class, Private.class }) {
            var lookup = classes(Map.of("w0.j", wrong, "l1.a", wrong));
            assertThrows(wrong.getSimpleName(), NoSuchMethodException.class, () -> RomHeadsetMethods.offlineState(lookup));
            assertThrows(wrong.getSimpleName(), NoSuchMethodException.class, () -> RomHeadsetMethods.featureSupport(lookup));
        }
    }

    public static class Original {
        public static boolean isAppleHeadset(String address, Context context) { return false; }
        public static String[] getAirPodsState(String address, Context context) { return null; }
        public static boolean isFeatureSupport(Context context, BluetoothDevice device, String feature) { return false; }
    }
    public static class Incomplete {
        public static boolean isAppleHeadset(String address, Context context) { return false; }
    }
    public static class Obfuscated {
        public static boolean r(String address, Context context) { return false; }
        public static String[] h(String address, Context context) { return null; }
        public static boolean j(Context context, BluetoothDevice device, String feature) { return false; }
    }
    public static class WrongReturn {
        public static boolean r(String address, Context context) { return false; }
        public static String h(String address, Context context) { return null; }
        public static Boolean j(Context context, BluetoothDevice device, String feature) { return false; }
    }
    public static class Instance {
        public boolean r(String address, Context context) { return false; }
        public String[] h(String address, Context context) { return null; }
        public boolean j(Context context, BluetoothDevice device, String feature) { return false; }
    }
    public static class WrongArguments {
        public static boolean r(Context context, String address) { return false; }
        public static String[] h(Context context, String address) { return null; }
        public static boolean j(BluetoothDevice device, Context context, String feature) { return false; }
    }
    public static class Private {
        private static boolean r(String address, Context context) { return false; }
        private static String[] h(String address, Context context) { return null; }
        private static boolean j(Context context, BluetoothDevice device, String feature) { return false; }
    }
}
