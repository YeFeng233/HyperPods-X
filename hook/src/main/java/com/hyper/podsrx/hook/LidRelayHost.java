package com.hyper.podsrx.hook;

import android.app.BroadcastOptions;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Bluetooth stack diverts Apple advertisements away from regular app scans and hands them to
 * this ROM provider as they arrive. Forwarding the proximity ones gives the app the lid state
 * without the batch-scan report delay, while the module's own scan keeps the radio listening.
 */
final class LidRelayHost {
    static final String PROVIDER ="com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider";
    private static boolean relayed;

    static void install(HyperPodsModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        Class<?> provider = Class.forName(PROVIDER, false, loader);
        Method call = provider.getDeclaredMethod("call", String.class, String.class, Bundle.class);
        module.hook(call).intercept(chain -> {
            if (!ReloadLifecycle.isActive()) return chain.proceed();
            try {
                if ("onAppleScanResult".equals(chain.getArg(0)) && Binder.getCallingUid() == Process.BLUETOOTH_UID
                        && bound(preferences)) relay(((ContentProvider) chain.getThisObject()).getContext(), (Bundle) chain.getArg(2));
            } catch (Exception | LinkageError error) {
                DiagnosticLog.w("HyperPods-X", "Apple advertisement relay skipped", error);
            }
            return chain.proceed();
        });
        DiagnosticLog.i("HyperPods-X", "Apple advertisement relay ready");
    }

    private static void relay(Context context, Bundle extras) {
        if (context == null || extras == null) return;
        ScanResult result = extras.getParcelable(LidWire.EXTRA_RESULT, ScanResult.class);
        ScanRecord record = result == null ? null : result.getScanRecord();
        byte[] data = record == null ? null : record.getManufacturerSpecificData(0x004c);
        // Only proximity-pairing messages carry the lid state.
        if (data == null || data.length < 2 || data[0] != 0x07 || data[1] != 0x19) return;
        if (!changed(result.getDevice().getAddress(), data)) return;
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        // A normal-priority broadcast to a non-foreground process is deferred by about 500 ms.
        Intent intent = new Intent(LidWire.ACTION).setPackage(LidWire.APP).putExtra(LidWire.EXTRA_RESULT, result)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent, null, options.toBundle());
        if (!relayed) {
            relayed = true;
            DiagnosticLog.i("HyperPods-X", "Relaying Apple proximity advertisements to the app");
        }
    }

    /** Headsets repeat the same payload many times a second; forward changes at once and repeats once a second. */
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

    private static boolean bound(SharedPreferences preferences) {
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return !preferences.getString("address", "").isEmpty() && preferences.getLong("updated", 0) >= boot;
    }
}
