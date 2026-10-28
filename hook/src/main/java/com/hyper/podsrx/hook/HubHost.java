package com.hyper.podsrx.hook;

import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import com.hyper.podsrx.protocol.BatteryLedger;
import java.lang.reflect.Method;

/**
 * The one live state of the bound headset, kept in the persistent Bluetooth host so it outlives
 * the app. The app reaches it through a versioned Binder; other hosts pull a snapshot through the
 * ROM's headset data provider and hear a dataless broadcast when it changes.
 */
final class HubHost extends Binder {
    private static final String TAG = "HyperPods-X";
    /** Changes inside this window reach consumers as one. */
    private static final long NOTIFY_WINDOW_MS = 250;
    private static final String[] CONSUMERS = {"com.xiaomi.bluetooth", "com.milink.service", "com.miui.securitycenter", "com.android.settings"};
    private static final int PER_USER_RANGE = 100000;

    /** The provider hook outlives nothing but this generation; null once it retires. */
    private static volatile HubHost instance;

    private final Context context;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int appUid;
    private final int milinkUid;
    private final KeyVault keys;
    private BroadcastReceiver receiver;

    // Guarded by this.
    private final BatteryLedger ledger = new BatteryLedger();
    private HubBundles.Control control = HubBundles.Control.DISCONNECTED;
    private String address;
    private IBinder callback;
    private IBinder.DeathRecipient death;
    private PendingIntent popupCredential, controlTarget;
    private long controlLostAt;
    private boolean closed;

    /** Rebuilt on every change and never mutated; binder threads read it without a lock. */
    private volatile HubBundles.State state = HubBundles.State.EMPTY;
    // Main thread only.
    private HubBundles.State announced = HubBundles.State.EMPTY;
    private boolean notifyPending;

    static void install(HyperPodsModule module, Context context, SharedPreferences preferences) throws Exception {
        HubHost host = new HubHost(context, preferences);
        ReloadLifecycle.add(host::close);
        host.receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (host.closed || !ReloadLifecycle.isActive() || !HubWire.CONNECT.equals(intent.getAction())) return;
                long age = SystemClock.elapsedRealtime() - intent.getLongExtra(HubWire.EXTRA_CREATED, 0);
                if (age < 0 || age > HubWire.CONNECT_MAX_AGE_MS) return;
                Bundle result = new Bundle();
                result.putBinder(HubWire.EXTRA_BINDER, host);
                setResultExtras(result);
                setResultCode(1);
            }
        };
        context.registerReceiver(host.receiver, new IntentFilter(HubWire.CONNECT), HubWire.PERMISSION, host.handler, Context.RECEIVER_EXPORTED);
        try {
            hookProvider(module, context.getClassLoader());
        } catch (Exception | LinkageError error) {
            host.close();
            throw error;
        }
        instance = host;
        host.publish();
        host.announceReady();
        DiagnosticLog.i(TAG, "State hub ready");
    }

    private HubHost(Context context, SharedPreferences preferences) throws PackageManager.NameNotFoundException {
        this.context = context;
        this.preferences = preferences;
        appUid = context.getPackageManager().getPackageUid(HubWire.APP, 0);
        int milink;
        try { milink = context.getPackageManager().getPackageUid("com.milink.service", 0); }
        catch (PackageManager.NameNotFoundException absent) { milink = -1; }
        milinkUid = milink;
        keys = new KeyVault(context);
        address = preferences.getString("address", "");
        if (address.isEmpty()) address = keys.address();
        attachInterface(null, HubWire.DESCRIPTOR);
    }

    /** Other hosts pull the snapshot here; it reads one volatile reference and never blocks. */
    private static void hookProvider(HyperPodsModule module, ClassLoader loader) throws Exception {
        Class<?> provider = Class.forName(LidRelayHost.PROVIDER, false, loader);
        Method call = provider.getDeclaredMethod("call", String.class, String.class, Bundle.class);
        module.hook(call).intercept(chain -> {
            if (!ReloadLifecycle.isActive() || !HubWire.STATE_METHOD.equals(chain.getArg(0))) return chain.proceed();
            try {
                HubHost hub = instance;
                if (hub != null && hub.mayRead(Binder.getCallingUid())) return hub.state.toBundle();
            } catch (Throwable error) {
                DiagnosticLog.w(TAG, "Hub snapshot unavailable", error);
            }
            return null;
        });
    }

    private boolean mayRead(int uid) {
        int app = uid % PER_USER_RANGE;
        return app == Process.SYSTEM_UID || app == Process.BLUETOOTH_UID || (milinkUid >= 0 && uid == milinkUid);
    }

    private synchronized void close() {
        if (closed) return;
        closed = true;
        if (instance == this) instance = null;
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
            receiver = null;
        }
        unlinkCallback();
        handler.removeCallbacksAndMessages(null);
    }

    /** A running app reconnects at once instead of waiting out its backoff. */
    private void announceReady() {
        try {
            Intent ready = new Intent(HubWire.READY).setPackage(HubWire.APP).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(ready, null, shareIdentity());
        } catch (RuntimeException error) {
            DiagnosticLog.w(TAG, "Hub ready announcement failed", error);
        }
    }

    private static Bundle shareIdentity() {
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        return options.toBundle();
    }

    // State

    private synchronized void publish() {
        if (closed) return;
        boolean bound = !address.isEmpty();
        boolean replace = bound && preferences.getBoolean("popup_enabled", true) && preferences.getBoolean("native_popup", true);
        HubBundles.State previous = state;
        state = new HubBundles.State(address, ledger.getReadings(), previous.lid, preferences.getInt("panel_product", -1),
            control, callback != null, replace, previous.scanMode,
            previous.broadcasts, previous.trusted, previous.candidates, previous.matches);
        handler.post(this::scheduleNotify);
    }

    private void scheduleNotify() {
        if (notifyPending || closed) return;
        notifyPending = true;
        handler.postDelayed(() -> {
            notifyPending = false;
            HubBundles.State current = state;
            if (closed || current.sameContent(announced)) return;
            announced = current;
            notifyConsumers(current);
        }, NOTIFY_WINDOW_MS);
    }

    private void notifyConsumers(HubBundles.State current) {
        Bundle options = shareIdentity();
        for (String consumer : CONSUMERS) {
            try {
                context.sendBroadcast(new Intent(HubWire.CHANGED).setPackage(consumer).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null, options);
            } catch (RuntimeException error) {
                DiagnosticLog.w(TAG, "Hub change notice failed: " + consumer, error);
            }
        }
        IBinder target;
        synchronized (this) { target = callback; }
        if (target != null) send(target, HubWire.ON_STATE, current.toBundle());
    }

    /** Oneway, so a stuck app never holds the host's main thread. */
    private static void send(IBinder target, int code, Bundle payload) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(HubWire.CALLBACK_DESCRIPTOR);
            data.writeBundle(payload);
            target.transact(code, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | RuntimeException error) {
            DiagnosticLog.w(TAG, "Hub callback failed", error);
        } finally {
            data.recycle();
        }
    }

    // App link

    private void unlinkCallback() {
        if (callback != null && death != null) {
            try { callback.unlinkToDeath(death, 0); } catch (RuntimeException ignored) {}
        }
        callback = null;
        death = null;
    }

    private synchronized void appDied(IBinder dead) {
        if (closed || callback != dead) return;
        unlinkCallback();
        loseControl();
        DiagnosticLog.i(TAG, "App left the hub");
        publish();
    }

    /** The control channel's readings start to age from now; its live fields no longer hold. */
    private void loseControl() {
        if (control.connected) controlLostAt = SystemClock.elapsedRealtime();
        control = HubBundles.Control.DISCONNECTED;
    }

    private void bind(String next) {
        if (next == null || next.isEmpty() || next.equalsIgnoreCase(address)) return;
        address = next;
        keys.forgetUnless(next);
        ledger.clear();
        DiagnosticLog.i(TAG, "Hub bound to a new headset");
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(HubWire.DESCRIPTOR); return true; }
        if (Binder.getCallingUid() != appUid) throw new SecurityException("Unsupported caller");
        data.enforceInterface(HubWire.DESCRIPTOR);
        Bundle request = data.readBundle(getClass().getClassLoader());
        Bundle response = new Bundle();
        int status;
        long identity = Binder.clearCallingIdentity();
        try {
            status = execute(code, request == null ? new Bundle() : request, response);
        } catch (Throwable error) {
            status = HubWire.FAILED;
            response.putString("status", error.getClass().getSimpleName());
            DiagnosticLog.w(TAG, "Hub request " + code + " failed", error);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (reply != null) { reply.writeNoException(); reply.writeInt(status); reply.writeBundle(response); }
        return true;
    }

    private int execute(int code, Bundle request, Bundle response) throws Exception {
        synchronized (this) {
            // A retired generation guarantees nothing ran; the app reconnects to the new one.
            if (closed || !ReloadLifecycle.isActive()) { response.putBoolean("retired", true); return HubWire.FAILED; }
        }
        switch (code) {
            case HubWire.HELLO: return hello(HubBundles.Hello.from(request), response);
            case HubWire.PUSH_CONTROL: {
                HubBundles.Control next = HubBundles.Control.from(request);
                synchronized (this) {
                    if (callback == null) return HubWire.DENIED;
                    if (control.connected && !next.connected) controlLostAt = SystemClock.elapsedRealtime();
                    control = next;
                    ledger.apply(next.batteries, true, true, SystemClock.elapsedRealtime());
                }
                publish();
                return HubWire.OK;
            }
            case HubWire.PUSH_KEYS: {
                HubBundles.Keys pushed = HubBundles.Keys.from(request);
                synchronized (this) {
                    if (callback == null) return HubWire.DENIED;
                    bind(pushed.address);
                    if (pushed.irk != null) keys.put(address, KeyVault.IRK, pushed.irk);
                    if (pushed.enc != null) keys.put(address, KeyVault.ENC, pushed.enc);
                }
                publish();
                return HubWire.OK;
            }
            case HubWire.GET_STATE:
                response.putAll(state.toBundle());
                return HubWire.OK;
            case HubWire.GET_DIAGNOSTICS:
                response.putInt("scanMode", state.scanMode);
                response.putBundle("capabilities", new Bundle());
                return HubWire.OK;
            case HubWire.REQUEST_POPUP:
                // The popup driver joins once the hub owns the lid.
                response.putString("status", "unsupported");
                return HubWire.FAILED;
            default:
                return HubWire.FAILED;
        }
    }

    private int hello(HubBundles.Hello hello, Bundle response) throws RemoteException {
        response.putInt("version", HubWire.VERSION);
        if (hello.version != HubWire.VERSION) {
            DiagnosticLog.w(TAG, "Hub version mismatch: app " + hello.version + ", host " + HubWire.VERSION);
            return HubWire.VERSION_MISMATCH;
        }
        if (hello.callback == null) return HubWire.FAILED;
        synchronized (this) {
            unlinkCallback();
            IBinder link = hello.callback;
            IBinder.DeathRecipient recipient = () -> appDied(link);
            link.linkToDeath(recipient, 0);
            callback = link;
            death = recipient;
            popupCredential = hello.popupCredential;
            controlTarget = hello.controlTarget;
            // A fresh app has not reported its session yet.
            loseControl();
            bind(hello.address);
        }
        publish();
        response.putAll(state.toBundle());
        DiagnosticLog.i(TAG, "App joined the hub");
        return HubWire.OK;
    }
}
