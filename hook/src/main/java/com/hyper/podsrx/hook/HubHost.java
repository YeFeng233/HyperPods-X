package com.hyper.podsrx.hook;

import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanResult;
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
import com.hyper.podsrx.protocol.Battery;
import com.hyper.podsrx.protocol.BatteryAlert;
import com.hyper.podsrx.protocol.BatteryAlerts;
import com.hyper.podsrx.protocol.BatteryLedger;
import com.hyper.podsrx.protocol.CaseThrottle;
import com.hyper.podsrx.protocol.Proximity;
import com.hyper.podsrx.protocol.Reading;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * The one live state of the bound headset, kept in the persistent Bluetooth host so it outlives
 * the app. It scans, decodes and follows the lid itself and drives the popup, the island and the
 * widget; the app only adds the AACP session. The app reaches it through a versioned Binder; other
 * hosts pull a snapshot through the ROM's headset data provider and hear a dataless broadcast when
 * it changes.
 */
final class HubHost extends Binder implements ScanEngine.Listener, AdvertisementParser.Sink, LidController.Actions {
    private static final String TAG = "HyperPods-X";
    /** Changes inside this window reach consumers as one. */
    private static final long NOTIFY_WINDOW_MS = 250;
    /** Out of range, nothing refreshes the readings; past this age they are hidden. */
    private static final long STALE_READING_MS = 60_000;
    private static final String[] CONSUMERS = {"com.xiaomi.bluetooth", "com.milink.service", "com.miui.securitycenter", "com.android.settings"};
    private static final int PER_USER_RANGE = 100000;

    /** The provider hook and the relay outlive nothing but this generation; null once it retires. */
    private static volatile HubHost instance;

    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int appUid;
    private final int milinkUid;
    private final KeyVault keys;
    private final ScanEngine scan;
    private final AdvertisementParser parser = new AdvertisementParser();
    private final LidController lid = new LidController(this);
    private final WidgetDriver widget;
    private final BatteryNotifier notifier;
    private final CaseThrottle caseThrottle = new CaseThrottle(CaseThrottle.REFRESH_MS);
    private final long started = SystemClock.elapsedRealtime();
    /** Hook results by process, from {@link Compatibility}. */
    private final java.util.concurrent.ConcurrentHashMap<String, Bundle> capabilities = new java.util.concurrent.ConcurrentHashMap<>();
    private BroadcastReceiver capabilityReceiver;
    /** The hub's own view for the adapters in this process. */
    final HubReader reader = HubReader.local(() -> this.state);
    private BroadcastReceiver receiver;

    // Guarded by this.
    private final BatteryLedger ledger = new BatteryLedger();
    private BatteryAlerts alerts;
    private HubBundles.Control control = HubBundles.Control.DISCONNECTED;
    /** Written under the lock, read anywhere. */
    private volatile String address;
    private int product = -1;
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

    static HubHost install(HyperPodsModule module, Context context, SharedPreferences preferences) throws Exception {
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
        context.registerReceiver(host.receiver, new IntentFilter(HubWire.CONNECT), HubWire.PERMISSION, host.main, Context.RECEIVER_EXPORTED);
        host.capabilityReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (host.closed || !ReloadLifecycle.isActive() || !HubWire.CAPABILITIES.equals(intent.getAction())) return;
                // Only the scoped hosts may report; any app could send this broadcast.
                if (!host.mayRead(getSentFromUid())) return;
                String process = intent.getStringExtra("process");
                Bundle results = intent.getBundleExtra("results");
                if (process != null && !process.isEmpty() && results != null) host.capabilities.put(process, results);
            }
        };
        context.registerReceiver(host.capabilityReceiver, new IntentFilter(HubWire.CAPABILITIES), null, host.main, Context.RECEIVER_EXPORTED);
        hookProvider(module, context.getClassLoader());
        try { hookAirCore(module, context.getClassLoader()); Compatibility.installed("ROM AACP channel guard"); }
        catch (Exception | LinkageError error) {
            Compatibility.failed("ROM AACP channel guard", error);
            DiagnosticLog.w(TAG, "ROM AACP channel guard unavailable", error);
        }
        instance = host;
        host.scan.start();
        host.sight();
        host.publish();
        host.announceReady();
        DiagnosticLog.i(TAG, "State hub ready");
        return host;
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
        scan = new ScanEngine(context, this);
        widget = new WidgetDriver(context);
        notifier = new BatteryNotifier(context);
        alerts = notifier.restore(address);
        attachInterface(null, HubWire.DESCRIPTOR);
    }

    /** The current generation's hub, or null. */
    static HubHost current() { return instance; }

    /** Relayed from the ROM's Apple scan path on a binder thread. */
    static void relay(ScanResult result) {
        HubHost hub = instance;
        if (hub != null) hub.main.post(() -> hub.onResult(result, "系统通道"));
    }

    /** Whether advertisements can be attributed: bound, with the identity key. */
    @Override public boolean bound() {
        String bound = address;
        return !bound.isEmpty() && keys.get(bound, KeyVault.IRK) != null;
    }

    @Override public synchronized boolean controlConnected() {
        return callback != null && control.connected;
    }

    @Override public String address() { return address; }

    synchronized PendingIntent controlTarget() { return controlTarget; }

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

    /**
     * The ROM opens its own AACP channel 2 s after HFP connects for headsets its cloud list
     * supports, Pro 3 among them. The headset reports its settings only to the channel opened last,
     * so that channel starved the app's, at once or in the middle of a session. While the app is
     * alive the bound headset is its alone; without the app the ROM keeps its own controls.
     */
    private static void hookAirCore(HyperPodsModule module, ClassLoader loader) throws Exception {
        Class<?> storage = Class.forName("com.xiaomi.bluetooth.modules.airlibrary.db.AirLocalStorage", false, loader);
        Method support = storage.getDeclaredMethod("isFeatureSupport", Context.class, BluetoothDevice.class, String.class);
        module.hook(support).intercept(chain -> {
            if (!ReloadLifecycle.isActive() || !"ConnectL2cap".equals(chain.getArg(2))) return chain.proceed();
            try {
                HubHost hub = instance;
                BluetoothDevice device = (BluetoothDevice) chain.getArg(1);
                if (hub != null && device != null && device.getAddress().equalsIgnoreCase(hub.address) && hub.state.appAlive) {
                    DiagnosticLog.i(TAG, "Kept the ROM's AACP channel off the app's headset");
                    return false;
                }
            } catch (Throwable error) {
                DiagnosticLog.w(TAG, "ROM AACP channel guard skipped", error);
            }
            return chain.proceed();
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
        for (BroadcastReceiver registered : new BroadcastReceiver[] {receiver, capabilityReceiver}) {
            if (registered == null) continue;
            try { context.unregisterReceiver(registered); } catch (IllegalArgumentException ignored) {}
        }
        receiver = null;
        capabilityReceiver = null;
        unlinkCallback();
        scan.close();
        lid.close();
        widget.close();
        main.removeCallbacksAndMessages(null);
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

    // Advertisements and the lid (main thread)

    @Override public void onResult(ScanResult result, String source) {
        if (closed) return;
        String bound = address;
        parser.parse(result, bound, keys.get(bound, KeyVault.IRK), keys.get(bound, KeyVault.ENC), source, this);
        publish();
    }

    @Override public void proximity(Proximity status, long capturedAt, String source) {
        scan.heard();
        synchronized (this) { product = status.getModelId(); }
        if (!lid.observe(status.getLidOpen(), capturedAt, source)) return;
        synchronized (this) { ledger.apply(status.getBatteries(), status.getPrecise(), false, SystemClock.elapsedRealtime()); }
        alert();
    }

    @Override public void caseStatus(List<Battery> values, String source) {
        scan.heard();
        long now = SystemClock.elapsedRealtime();
        if (!caseThrottle.admit(values, now)) return;
        DiagnosticLog.i(TAG, "Case status " + source + ": " + describe(values));
        synchronized (this) { ledger.apply(values, true, false, now); }
        alert();
    }

    /** Such as "L100+ R98 C89", a plus marking a charging part. */
    private static String describe(List<Battery> values) {
        StringBuilder out = new StringBuilder();
        for (Battery value : values) {
            if (out.length() > 0) out.append(' ');
            out.append(value.getComponent() == 4 ? "L" : value.getComponent() == 2 ? "R" : value.getComponent() == 8 ? "C" : "#" + value.getComponent())
                .append(value.getLevel()).append(value.getCharging() ? "+" : "");
        }
        return out.length() == 0 ? "no levels" : out.toString();
    }

    @Override public void opened() {
        boolean shown = false;
        boolean wanted = preferences.getBoolean("popup_enabled", true);
        boolean locked = context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        if (wanted && preferences.getBoolean("native_popup", true) && !locked) {
            try { shown = PopupDriver.show(context, popupRequest(false, false)); }
            catch (Throwable error) { DiagnosticLog.w(TAG, "Native headset popup unavailable", error); }
        } else PopupDriver.reconnectProfiles(context, address);
        // Without a native popup the app shows its own card, if it is running.
        callApp(HubWire.ON_LID, HubBundles.lid(HubBundles.LID_EVENT_OPENED, shown));
        publish();
    }

    @Override public void confirmed() {
        // The app reconnects its control channel if it has none.
        callApp(HubWire.ON_LID, HubBundles.lid(HubBundles.LID_EVENT_CONFIRMED, false));
    }

    @Override public void closed(boolean changed, boolean repeat) {
        if (changed) PopupDriver.close(context);
        if (changed && !repeat) {
            callApp(HubWire.ON_LID, HubBundles.lid(HubBundles.LID_EVENT_CLOSED, false));
            publish();
        }
    }

    // Scan engine (main thread)

    @Override public void onScreen(boolean on) {
        lid.screen(on);
        // Nothing was heard with the scans off; the readings age again from now.
        if (on) synchronized (this) { ledger.touch(SystemClock.elapsedRealtime()); }
        sight();
        publish();
    }

    @Override public void onTick() {
        long now = SystemClock.elapsedRealtime();
        boolean expired;
        synchronized (this) {
            // The control channel reports batteries only when they change, so its readings age only
            // once it drops, counted from that moment.
            long controlFreshAt = controlConnected() ? now : controlLostAt;
            expired = ledger.expire(STALE_READING_MS, controlFreshAt, now);
        }
        if (expired) DiagnosticLog.i(TAG, "Hub hid readings unheard for " + STALE_READING_MS / 1000 + " s; case status heard "
            + parser.caseMessages + ", decoded " + parser.caseDecoded + "; scan " + scan.mode() + ", screen " + (scan.screenOn() ? "on" : "off")
            + ", control " + (controlConnected() ? "up" : "down"));
        publish();
        // The widget forgets the grip on a launcher restart; this repeats it slowly.
        widget.update(state);
    }

    @Override public void onModeChanged(int mode) {
        DiagnosticLog.i(TAG, "Hub live scan " + (mode == HubBundles.SCAN_LIVE ? "live" : mode == HubBundles.SCAN_IDLE ? "idle" : "off"));
        publish();
    }

    @Override public void onLinked() {}

    // Popups

    private PopupDriver.Request popupRequest(boolean preview, boolean island) {
        HubBundles.State s = state;
        PendingIntent target;
        synchronized (this) { target = popupCredential; }
        // A host restart forgets the app's credential; the settings page opens the same way.
        if (target == null) target = PendingIntent.getActivity(context, 0, new Intent()
            .setClassName(HubWire.APP, HubWire.APP + ".DeviceSettingsActivity").putExtra("device_address", s.address),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Map<Integer, Reading> r = s.readings;
        int charging = (charging(r, 4) ? 1 : 0) | (charging(r, 2) ? 2 : 0) | (charging(r, 8) ? 4 : 0);
        return new PopupDriver.Request(s.address, headsetName(s), s.control.model, s.product,
            preview, island, s.controlAlive(), level(r, 4), level(r, 2), level(r, 8), charging, target);
    }

    /** The name the control channel reports, else the system alias. */
    private String headsetName(HubBundles.State s) {
        String name = s.control.name;
        if (name.isEmpty()) {
            try { name = context.getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(s.address).getAlias(); }
            catch (RuntimeException unavailable) { name = null; }
        }
        return name == null || name.isEmpty() ? "AirPods" : name;
    }

    // Battery alerts

    /** Runs the alerts on the merged readings after a report; any thread. */
    private void alert() {
        List<BatteryAlert> fired;
        String bound;
        synchronized (this) {
            if (closed) return;
            boolean low = alerts.getLowArmed(), full = alerts.getFullArmed();
            fired = alerts.observe(ledger.getReadings(), SystemClock.elapsedRealtime());
            bound = address;
            if (low != alerts.getLowArmed() || full != alerts.getFullArmed()) notifier.save(bound, alerts.getLowArmed(), alerts.getFullArmed());
        }
        if (fired.isEmpty()) return;
        main.post(() -> {
            if (closed || !bound.equals(address)) return;
            HubBundles.State s = state;
            notifier.show(fired, headsetName(s), s.control.model, s.product, preferences.getBoolean("battery_alerts", true));
        });
    }

    /** With the screen off and no control channel nothing refreshes the readings. Main thread. */
    private void sight() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::sight); return; }
        boolean live = scan.screenOn() || controlConnected();
        synchronized (this) { alerts.sight(live, SystemClock.elapsedRealtime()); }
    }

    private static int level(Map<Integer, Reading> readings, int component) {
        Reading reading = readings.get(component);
        return reading == null || reading.getLevel() == null ? -1 : reading.getLevel();
    }

    private static boolean charging(Map<Integer, Reading> readings, int component) {
        Reading reading = readings.get(component);
        return reading != null && reading.getLevel() != null && reading.getCharging();
    }

    // State

    private void publish() {
        HubBundles.State next;
        synchronized (this) {
            if (closed) return;
            boolean bound = !address.isEmpty();
            boolean replace = bound && preferences.getBoolean("popup_enabled", true) && preferences.getBoolean("native_popup", true);
            Boolean open = lid.open();
            int lidState = open == null ? HubBundles.LID_UNKNOWN : open ? HubBundles.LID_OPEN : HubBundles.LID_CLOSED;
            int model = product >= 0 ? product : preferences.getInt("panel_product", -1);
            next = new HubBundles.State(address, ledger.getReadings(), lidState, model, control, callback != null, replace,
                scan.mode(), parser.broadcasts, parser.trusted, parser.candidates, parser.matches, started);
            state = next;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) scheduleNotify();
        else main.post(this::scheduleNotify);
    }

    private void scheduleNotify() {
        if (notifyPending || closed) return;
        notifyPending = true;
        main.postDelayed(() -> {
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
        reader.changed();
        widget.update(current);
        callApp(HubWire.ON_STATE, current.toBundle());
    }

    private void callApp(int code, Bundle payload) {
        IBinder target;
        synchronized (this) { target = callback; }
        if (target != null) send(target, code, payload);
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

    private void appDied(IBinder dead) {
        synchronized (this) {
            if (closed || callback != dead) return;
            unlinkCallback();
            loseControl();
        }
        DiagnosticLog.i(TAG, "App left the hub");
        sight();
        publish();
        scan.refresh();
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
        alerts = notifier.restore(next);
        product = -1;
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
                sight();
                alert();
                publish();
                scan.refresh();
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
                scan.refresh();
                return HubWire.OK;
            }
            case HubWire.GET_STATE:
                response.putAll(state.toBundle());
                return HubWire.OK;
            case HubWire.GET_DIAGNOSTICS:
                response.putInt("scanMode", state.scanMode);
                response.putInt("scanStarts", scan.starts());
                Bundle reports = new Bundle();
                for (java.util.Map.Entry<String, Bundle> entry : capabilities.entrySet()) reports.putBundle(entry.getKey(), entry.getValue());
                response.putBundle("capabilities", reports);
                return HubWire.OK;
            case HubWire.REQUEST_POPUP: {
                if (state.address.isEmpty()) return HubWire.FAILED;
                PopupDriver.Request popup = popupRequest(request.getBoolean("preview"), request.getBoolean("island"));
                response.putBoolean("shown", PopupDriver.show(context, popup));
                return HubWire.OK;
            }
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
        sight();
        publish();
        scan.refresh();
        response.putAll(state.toBundle());
        DiagnosticLog.i(TAG, "App joined the hub");
        return HubWire.OK;
    }
}
