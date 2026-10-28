package com.hyper.podsrx.hook;

import android.app.PendingIntent;
import android.os.Bundle;
import android.os.IBinder;
import com.hyper.podsrx.protocol.Battery;
import com.hyper.podsrx.protocol.BatteryLedgerKt;
import com.hyper.podsrx.protocol.Reading;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only encoder and decoder of {@link HubWire} payloads, shared by the app and the hub. Readers
 * tolerate missing keys, so a field added later reads as its default from an older peer.
 */
public final class HubBundles {
    private HubBundles() {}

    /** Absent level, which a Bundle int cannot hold as null. */
    private static final int NONE = -1;

    // Batteries

    public static Bundle readings(Map<Integer, Reading> readings) {
        Bundle out = new Bundle();
        for (Map.Entry<Integer, Reading> entry : readings.entrySet()) {
            Reading r = entry.getValue();
            Bundle one = new Bundle();
            one.putInt("level", r.getLevel() == null ? NONE : r.getLevel());
            one.putBoolean("charging", r.getCharging());
            one.putBoolean("precise", r.getPrecise());
            one.putLong("updated", r.getUpdated());
            one.putBoolean("control", r.getControl());
            out.putBundle(String.valueOf(entry.getKey()), one);
        }
        return out;
    }

    public static Map<Integer, Reading> readings(Bundle in) {
        if (in == null) return Collections.emptyMap();
        Map<Integer, Reading> out = new LinkedHashMap<>();
        for (String key : in.keySet()) {
            Bundle one = in.getBundle(key);
            Integer component = parseComponent(key);
            if (one == null || component == null) continue;
            int level = one.getInt("level", NONE);
            out.put(component, new Reading(level == NONE ? null : level, one.getBoolean("charging"),
                one.getBoolean("precise"), one.getLong("updated"), one.getBoolean("control")));
        }
        return out;
    }

    public static Bundle batteries(List<Battery> batteries) {
        int size = batteries.size();
        int[] component = new int[size], level = new int[size];
        boolean[] charging = new boolean[size], connected = new boolean[size];
        for (int i = 0; i < size; i++) {
            Battery b = batteries.get(i);
            component[i] = b.getComponent();
            level[i] = b.getLevel() == null ? NONE : b.getLevel();
            charging[i] = b.getCharging();
            connected[i] = b.getConnected();
        }
        Bundle out = new Bundle();
        out.putIntArray("component", component);
        out.putIntArray("level", level);
        out.putBooleanArray("charging", charging);
        out.putBooleanArray("connected", connected);
        return out;
    }

    public static List<Battery> batteries(Bundle in) {
        if (in == null) return Collections.emptyList();
        int[] component = in.getIntArray("component"), level = in.getIntArray("level");
        boolean[] charging = in.getBooleanArray("charging"), connected = in.getBooleanArray("connected");
        if (component == null || level == null || charging == null || connected == null) return Collections.emptyList();
        int size = Math.min(Math.min(component.length, level.length), Math.min(charging.length, connected.length));
        List<Battery> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            out.add(new Battery(component[i], level[i] == NONE ? null : level[i], charging[i], connected[i]));
        return out;
    }

    private static Integer parseComponent(String key) {
        try { return Integer.valueOf(key); } catch (NumberFormatException e) { return null; }
    }

    // app -> hub

    /** HELLO: the app's callback binder doubles as its liveness; the intents outlive its process. */
    public static final class Hello {
        public final int version;
        public final IBinder callback;
        public final String address;
        /** Proves the popup request came from the app; FLAG_IMMUTABLE. */
        public final PendingIntent popupCredential;
        /** Where native panel commands go. */
        public final PendingIntent controlTarget;

        public Hello(int version, IBinder callback, String address, PendingIntent popupCredential, PendingIntent controlTarget) {
            this.version = version;
            this.callback = callback;
            this.address = address == null ? "" : address;
            this.popupCredential = popupCredential;
            this.controlTarget = controlTarget;
        }

        public Bundle toBundle() {
            Bundle out = new Bundle();
            out.putInt("version", version);
            out.putBinder("callback", callback);
            out.putString("address", address);
            out.putParcelable("popupCredential", popupCredential);
            out.putParcelable("controlTarget", controlTarget);
            return out;
        }

        public static Hello from(Bundle in) {
            return new Hello(in.getInt("version", 0), in.getBinder("callback"), in.getString("address"),
                in.getParcelable("popupCredential", PendingIntent.class),
                in.getParcelable("controlTarget", PendingIntent.class));
        }
    }

    /**
     * PUSH_CONTROL: what the AACP session knows, already in the shape the native panels show.
     * It replaces every {@code panel_*} preference and {@code native_connected}.
     */
    public static final class Control {
        public final boolean connected;
        public final String model, name, firmware;
        /** The control channel's battery report, including components that dropped off. */
        public final List<Battery> batteries;
        /** Raw values of the panel controls 0x0d, 0x0a, 0x1b and 0x34 that the headset reported. */
        public final Map<Integer, int[]> controls;
        public final boolean ancPending;
        /** elapsedRealtime until which a reconnect reads the settings back; 0 when none. */
        public final long refreshUntil;
        public final boolean audioRoute, spatialEnabled, spatialAvailable, spatialPending;
        /** Earbuds in ear, or -1 before the headset reports it. */
        public final int wearCount;

        public static final int[] PANEL_CONTROLS = {0x0d, 0x0a, 0x1b, 0x34};
        public static final Control DISCONNECTED = new Control(false, "", "", "", Collections.emptyList(),
            Collections.emptyMap(), false, 0, false, false, false, false, -1);

        public Control(boolean connected, String model, String name, String firmware, List<Battery> batteries,
                Map<Integer, int[]> controls, boolean ancPending, long refreshUntil, boolean audioRoute,
                boolean spatialEnabled, boolean spatialAvailable, boolean spatialPending, int wearCount) {
            this.connected = connected;
            this.model = model == null ? "" : model;
            this.name = name == null ? "" : name;
            this.firmware = firmware == null ? "" : firmware;
            this.batteries = Collections.unmodifiableList(new ArrayList<>(batteries));
            this.controls = Collections.unmodifiableMap(new LinkedHashMap<>(controls));
            this.ancPending = ancPending;
            this.refreshUntil = refreshUntil;
            this.audioRoute = audioRoute;
            this.spatialEnabled = spatialEnabled;
            this.spatialAvailable = spatialAvailable;
            this.spatialPending = spatialPending;
            this.wearCount = wearCount;
        }

        public boolean sameContent(Control other) {
            if (other == this) return true;
            if (other == null || connected != other.connected || ancPending != other.ancPending
                    || refreshUntil != other.refreshUntil || audioRoute != other.audioRoute
                    || spatialEnabled != other.spatialEnabled || spatialAvailable != other.spatialAvailable
                    || spatialPending != other.spatialPending || wearCount != other.wearCount
                    || !model.equals(other.model) || !name.equals(other.name) || !firmware.equals(other.firmware)
                    || !batteries.equals(other.batteries) || !controls.keySet().equals(other.controls.keySet())) return false;
            for (Map.Entry<Integer, int[]> entry : controls.entrySet())
                if (!Arrays.equals(entry.getValue(), other.controls.get(entry.getKey()))) return false;
            return true;
        }

        /** First value of a panel control, or [fallback] when the headset has not reported it. */
        public int control(int id, int fallback) {
            int[] values = controls.get(id);
            return values == null || values.length == 0 ? fallback : values[0];
        }

        public Bundle toBundle() {
            Bundle out = new Bundle();
            out.putBoolean("connected", connected);
            out.putString("model", model);
            out.putString("name", name);
            out.putString("firmware", firmware);
            out.putBundle("batteries", HubBundles.batteries(batteries));
            Bundle values = new Bundle();
            for (Map.Entry<Integer, int[]> entry : controls.entrySet())
                values.putIntArray(String.valueOf(entry.getKey()), entry.getValue());
            out.putBundle("controls", values);
            out.putBoolean("ancPending", ancPending);
            out.putLong("refreshUntil", refreshUntil);
            out.putBoolean("audioRoute", audioRoute);
            out.putBoolean("spatialEnabled", spatialEnabled);
            out.putBoolean("spatialAvailable", spatialAvailable);
            out.putBoolean("spatialPending", spatialPending);
            out.putInt("wearCount", wearCount);
            return out;
        }

        public static Control from(Bundle in) {
            if (in == null) return DISCONNECTED;
            Map<Integer, int[]> controls = new LinkedHashMap<>();
            Bundle values = in.getBundle("controls");
            if (values != null) for (String key : values.keySet()) {
                Integer id = parseComponent(key);
                int[] value = values.getIntArray(key);
                if (id != null && value != null) controls.put(id, value);
            }
            return new Control(in.getBoolean("connected"), in.getString("model"), in.getString("name"),
                in.getString("firmware"), HubBundles.batteries(in.getBundle("batteries")), controls,
                in.getBoolean("ancPending"), in.getLong("refreshUntil"), in.getBoolean("audioRoute"),
                in.getBoolean("spatialEnabled"), in.getBoolean("spatialAvailable"),
                in.getBoolean("spatialPending"), in.getInt("wearCount", -1));
        }
    }

    /** PUSH_KEYS: the proximity keys for [address]; never logged, never put in preferences. */
    public static final class Keys {
        public final String address;
        /** Identity resolving key, type 1. */
        public final byte[] irk;
        /** Proximity encryption key, type 4. */
        public final byte[] enc;

        public Keys(String address, byte[] irk, byte[] enc) {
            this.address = address == null ? "" : address;
            this.irk = irk;
            this.enc = enc;
        }

        public Bundle toBundle() {
            Bundle out = new Bundle();
            out.putString("address", address);
            out.putByteArray("irk", irk);
            out.putByteArray("enc", enc);
            return out;
        }

        public static Keys from(Bundle in) {
            return new Keys(in.getString("address"), in.getByteArray("irk"), in.getByteArray("enc"));
        }
    }

    /** REQUEST_POPUP, from the preview buttons. */
    public static Bundle popupRequest(boolean preview, boolean island) {
        Bundle out = new Bundle();
        out.putBoolean("preview", preview);
        out.putBoolean("island", island);
        return out;
    }

    // hub -> app and other hosts

    public static final int LID_UNKNOWN = -1, LID_CLOSED = 0, LID_OPEN = 1;
    public static final int SCAN_OFF = 0, SCAN_LIVE = 1, SCAN_IDLE = 2;

    /**
     * The hub's one live state: ON_STATE, GET_STATE and the provider snapshot. Built once per change
     * and never mutated, so the provider's binder threads can read it without a lock.
     */
    public static final class State {
        public final String address;
        public final Map<Integer, Reading> readings;
        public final int lid;
        public final int product;
        /** The latest PUSH_CONTROL; {@link Control#DISCONNECTED} while the app is dead. */
        public final Control control;
        /** Whether the app's callback binder is alive. */
        public final boolean appAlive;
        /** The hub owns the bound headset's popup in place of the ROM's. */
        public final boolean replaceSystemPopup;
        public final int scanMode;
        public final int broadcasts, trusted, candidates, matches;
        /** elapsedRealtime the hub generation started; a new value means it forgot every report. */
        public final long started;

        public State(String address, Map<Integer, Reading> readings, int lid, int product, Control control,
                boolean appAlive, boolean replaceSystemPopup, int scanMode,
                int broadcasts, int trusted, int candidates, int matches, long started) {
            this.address = address == null ? "" : address;
            this.readings = Collections.unmodifiableMap(new LinkedHashMap<>(readings));
            this.lid = lid;
            this.product = product;
            this.control = control == null ? Control.DISCONNECTED : control;
            this.appAlive = appAlive;
            this.replaceSystemPopup = replaceSystemPopup;
            this.scanMode = scanMode;
            this.broadcasts = broadcasts;
            this.trusted = trusted;
            this.candidates = candidates;
            this.matches = matches;
            this.started = started;
        }

        public static final State EMPTY = new State("", Collections.emptyMap(), LID_UNKNOWN, -1,
            Control.DISCONNECTED, false, false, SCAN_OFF, 0, 0, 0, 0, 0);

        /** Equal but for reading refresh times, which consumers need not hear about. */
        public boolean sameContent(State other) {
            if (other == this) return true;
            return other != null && address.equals(other.address) && lid == other.lid && product == other.product
                && appAlive == other.appAlive && replaceSystemPopup == other.replaceSystemPopup
                && scanMode == other.scanMode && started == other.started && control.sameContent(other.control)
                && BatteryLedgerKt.content(readings).equals(BatteryLedgerKt.content(other.readings));
        }

        /** Whether native panels may drive the session: the app is alive and its channel connected. */
        public boolean controlAlive() {
            return appAlive && control.connected;
        }

        public Bundle toBundle() {
            Bundle out = new Bundle();
            out.putInt("version", HubWire.VERSION);
            out.putString("address", address);
            out.putBundle("readings", HubBundles.readings(readings));
            out.putInt("lid", lid);
            out.putInt("product", product);
            out.putBundle("control", control.toBundle());
            out.putBoolean("appAlive", appAlive);
            out.putBoolean("replaceSystemPopup", replaceSystemPopup);
            out.putInt("scanMode", scanMode);
            out.putInt("broadcasts", broadcasts);
            out.putInt("trusted", trusted);
            out.putInt("candidates", candidates);
            out.putInt("matches", matches);
            out.putLong("started", started);
            return out;
        }

        public static State from(Bundle in) {
            if (in == null) return EMPTY;
            return new State(in.getString("address"), HubBundles.readings(in.getBundle("readings")),
                in.getInt("lid", LID_UNKNOWN), in.getInt("product", -1), Control.from(in.getBundle("control")),
                in.getBoolean("appAlive"), in.getBoolean("replaceSystemPopup"), in.getInt("scanMode", SCAN_OFF),
                in.getInt("broadcasts"), in.getInt("trusted"), in.getInt("candidates"), in.getInt("matches"),
                in.getLong("started"));
        }
    }

    public static final int LID_EVENT_OPENED = 1, LID_EVENT_CONFIRMED = 2, LID_EVENT_CLOSED = 3;

    /** ON_LID. [nativeShown] false on an open asks the app for its fallback card. */
    public static Bundle lid(int event, boolean nativeShown) {
        Bundle out = new Bundle();
        out.putInt("event", event);
        out.putBoolean("nativeShown", nativeShown);
        return out;
    }
}
