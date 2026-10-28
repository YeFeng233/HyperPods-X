package com.hyper.podsrx.hook;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Bluetooth stack hands the Apple advertisements its running scans hear to this ROM provider
 * as they arrive. The proximity ones go straight to the hub in this process, without the scan
 * callback's own delivery path, so the lid is followed as fast as the stack reports it.
 */
final class LidRelayHost {
    static final String PROVIDER = "com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider";
    private static final String EXTRA_RESULT = "result";
    private static boolean relayed;

    static void install(HyperPodsModule module, ClassLoader loader) throws Exception {
        Class<?> provider = Class.forName(PROVIDER, false, loader);
        Method call = provider.getDeclaredMethod("call", String.class, String.class, Bundle.class);
        module.hook(call).intercept(chain -> {
            if (!ReloadLifecycle.isActive()) return chain.proceed();
            try {
                HubHost hub = HubHost.current();
                if ("onAppleScanResult".equals(chain.getArg(0)) && Binder.getCallingUid() == Process.BLUETOOTH_UID
                        && hub != null && hub.bound()) relay((Bundle) chain.getArg(2));
            } catch (Exception | LinkageError error) {
                DiagnosticLog.w("HyperPods-X", "Apple advertisement relay skipped", error);
            }
            return chain.proceed();
        });
        DiagnosticLog.i("HyperPods-X", "Apple advertisement relay ready");
    }

    private static void relay(Bundle extras) {
        if (extras == null) return;
        ScanResult result = extras.getParcelable(EXTRA_RESULT, ScanResult.class);
        ScanRecord record = result == null ? null : result.getScanRecord();
        byte[] data = record == null ? null : record.getManufacturerSpecificData(0x004c);
        // Only proximity-pairing messages carry the lid state.
        if (data == null || data.length < 2 || data[0] != 0x07 || data[1] != 0x19) return;
        if (!changed(result.getDevice().getAddress(), data)) return;
        HubHost.relay(result);
        if (!relayed) {
            relayed = true;
            DiagnosticLog.i("HyperPods-X", "Relaying Apple proximity advertisements to the hub");
        }
    }

    /** Headsets repeat the same payload many times a second; pass changes at once and repeats once a second. */
    private static synchronized boolean changed(String address, byte[] data) {
        long now = SystemClock.elapsedRealtime();
        Sent last = sent.get(address);
        if (last != null && Arrays.equals(last.data, data) && now - last.at < 1000) return false;
        sent.put(address, new Sent(data, now));
        return true;
    }

    private static final Map<String, Sent> sent = new LinkedHashMap<String, Sent>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Sent> eldest) { return size() > 32; }
    };

    private static final class Sent {
        final byte[] data;
        final long at;
        Sent(byte[] data, long at) { this.data = data; this.at = at; }
    }
}
