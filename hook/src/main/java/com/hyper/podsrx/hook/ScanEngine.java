package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.hyper.podsrx.protocol.CaseStatusCodec;
import com.hyper.podsrx.protocol.PresenceTracker;
import java.util.Collections;

/**
 * The hub's two Apple scans, run as uid 1002 so the app's start quota and freezes do not apply.
 * Main thread only. The scans stop with the screen off: nothing shows the readings then, and the
 * stack suspends every scan but ours anyway, so there is nothing to keep up with.
 */
final class ScanEngine {
    interface Listener {
        /** Main thread. */
        void onResult(ScanResult result, String source);
        void onScreen(boolean on);
        void onTick();
        void onModeChanged(int mode);
        /** The bound headset's link came up. */
        void onLinked();
        boolean bound();
        boolean controlConnected();
        String address();
    }

    private static final String TAG = "HyperPods-X";
    /** Two starts of one scan are never closer than this. */
    private static final long RESTART_GAP_MS = 2000;
    /** The stack forces a filtered scan down to LOW_POWER after scan_timeout_millis (300 s here). */
    private static final long REFRESH_MS = 270_000;
    private static final long RETRY_MS = 5000;
    private static final long TICK_MS = 10_000;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PresenceTracker presence = new PresenceTracker(SystemClock.elapsedRealtime(), PresenceTracker.IDLE_AFTER_MS);
    private BroadcastReceiver receiver;
    private boolean screenOn;
    private boolean closed;

    private ScanCallback live, box;
    private boolean liveIdle;
    private long liveStartedAt = Long.MIN_VALUE / 2, boxStartedAt = Long.MIN_VALUE / 2;
    private int starts;
    private final Runnable evaluate = this::evaluate;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed || !screenOn) return;
            try {
                listener.onTick();
                long now = SystemClock.elapsedRealtime();
                if (live != null && now - liveStartedAt >= REFRESH_MS) stopLive();
                if (box != null && now - boxStartedAt >= REFRESH_MS) stopBox();
                evaluate();
            } catch (Throwable error) {
                DiagnosticLog.w(TAG, "Hub scan tick failed", error);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    ScanEngine(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void start() {
        screenOn = context.getSystemService(PowerManager.class).isInteractive();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (closed || !ReloadLifecycle.isActive()) return;
                try { handle(intent); }
                catch (Throwable error) { DiagnosticLog.w(TAG, "Hub scan event skipped", error); }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        context.registerReceiver(receiver, filter, null, main, Context.RECEIVER_NOT_EXPORTED);
        if (screenOn) main.post(tick);
        main.post(evaluate);
    }

    void close() {
        closed = true;
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
            receiver = null;
        }
        main.removeCallbacksAndMessages(null);
        stopLive();
        stopBox();
    }

    boolean screenOn() { return screenOn; }

    int starts() { return starts; }

    int mode() {
        return live == null ? HubBundles.SCAN_OFF : liveIdle ? HubBundles.SCAN_IDLE : HubBundles.SCAN_LIVE;
    }

    /** The bound headset was heard; a quiet scan goes back to full duty. */
    void heard() {
        presence.heard(SystemClock.elapsedRealtime());
        if (live != null && liveIdle) evaluate();
    }

    /** Re-checks whether the scans should run, e.g. once the hub is bound or gets its keys. */
    void refresh() {
        main.post(evaluate);
    }

    private void handle(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            screenOn = true;
            listener.onScreen(true);
            main.removeCallbacks(tick);
            main.postDelayed(tick, TICK_MS);
            evaluate();
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            screenOn = false;
            listener.onScreen(false);
            main.removeCallbacks(tick);
            evaluate();
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            evaluate();
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device == null || !device.getAddress().equalsIgnoreCase(listener.address())) return;
            heard();
            listener.onLinked();
        }
    }

    private void evaluate() {
        if (closed) return;
        main.removeCallbacks(evaluate);
        BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        boolean run = screenOn && listener.bound() && adapter != null && adapter.isEnabled();
        int before = mode();
        if (!run) {
            stopLive();
            stopBox();
        } else {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            boolean idle = presence.shouldIdle(SystemClock.elapsedRealtime(), listener.controlConnected());
            if (live != null && liveIdle != idle) stopLive();
            if (scanner != null) {
                if (live == null) startLive(scanner, idle);
                if (box == null) startBox(scanner);
            }
        }
        if (mode() != before) listener.onModeChanged(mode());
    }

    /** Waits out the restart gap instead of starting now; returns whether it may start. */
    private boolean due(long startedAt) {
        long wait = startedAt + RESTART_GAP_MS - SystemClock.elapsedRealtime();
        if (wait <= 0) return true;
        main.postDelayed(evaluate, wait);
        return false;
    }

    /**
     * The proximity message carries the lid; LOW_POWER missed short lid advertisements, so it is
     * only used while the headset is away. Batch scans would report 5-20 s late.
     */
    private void startLive(BluetoothLeScanner scanner, boolean idle) {
        if (!due(liveStartedAt)) return;
        byte[] pattern = new byte[27], mask = new byte[27];
        pattern[0] = 7; pattern[1] = 25; mask[0] = -1; mask[1] = -1;
        ScanCallback callback = new Callback("实时扫描") {
            @Override boolean current() { return live == this; }
            @Override void failed() { live = null; }
        };
        live = callback;
        liveIdle = idle;
        liveStartedAt = SystemClock.elapsedRealtime();
        try {
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setManufacturerData(0x004c, pattern, mask).build()),
                new ScanSettings.Builder().setScanMode(idle ? ScanSettings.SCAN_MODE_LOW_POWER : ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setLegacy(true).setReportDelay(0).build(), callback);
            starts++;
        } catch (RuntimeException error) {
            live = null;
            DiagnosticLog.w(TAG, "Hub live scan did not start", error);
            main.postDelayed(evaluate, RETRY_MS);
        }
    }

    /**
     * The case status is connectable, so the stack leaves it on the regular scan path. The
     * offloaded filter only matched once the pattern spanned the whole payload.
     */
    private void startBox(BluetoothLeScanner scanner) {
        if (!due(boxStartedAt)) return;
        byte[] pattern = new byte[CaseStatusCodec.LENGTH + 2], mask = new byte[CaseStatusCodec.LENGTH + 2];
        pattern[0] = 7; pattern[1] = (byte) CaseStatusCodec.LENGTH; mask[0] = -1; mask[1] = -1;
        ScanCallback callback = new Callback("盒子状态") {
            @Override boolean current() { return box == this; }
            @Override void failed() { box = null; }
        };
        box = callback;
        boxStartedAt = SystemClock.elapsedRealtime();
        try {
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setManufacturerData(0x004c, pattern, mask).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setLegacy(true).setReportDelay(0).build(), callback);
            starts++;
        } catch (RuntimeException error) {
            box = null;
            DiagnosticLog.w(TAG, "Hub case scan did not start", error);
            main.postDelayed(evaluate, RETRY_MS);
        }
    }

    private void stopLive() {
        ScanCallback callback = live;
        live = null;
        stop(callback);
    }

    private void stopBox() {
        ScanCallback callback = box;
        box = null;
        stop(callback);
    }

    private void stop(ScanCallback callback) {
        if (callback == null) return;
        try {
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
            BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner != null) scanner.stopScan(callback);
        } catch (RuntimeException error) {
            DiagnosticLog.w(TAG, "Hub scan did not stop", error);
        }
    }

    private abstract class Callback extends ScanCallback {
        private final String source;
        Callback(String source) { this.source = source; }
        abstract boolean current();
        abstract void failed();

        @Override public void onScanResult(int type, ScanResult result) {
            if (Looper.myLooper() == Looper.getMainLooper()) deliver(result);
            else main.post(() -> deliver(result));
        }

        private void deliver(ScanResult result) {
            if (closed || !current() || !ReloadLifecycle.isActive()) return;
            try { listener.onResult(result, source); }
            catch (Throwable error) { DiagnosticLog.w(TAG, "Hub advertisement skipped", error); }
        }

        @Override public void onScanFailed(int errorCode) {
            main.post(() -> {
                if (closed || !current()) return;
                DiagnosticLog.w(TAG, source + " failed: " + errorCode);
                failed();
                listener.onModeChanged(mode());
                main.postDelayed(evaluate, RETRY_MS);
            });
        }
    }
}
