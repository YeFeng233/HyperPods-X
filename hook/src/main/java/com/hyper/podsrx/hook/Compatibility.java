package com.hyper.podsrx.hook;

import android.app.Application;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * What each scoped process could hook, reported to the hub so the app shows it per process instead
 * of a lone log line. A component maps to "" when installed, or to the failure's class name.
 */
final class Compatibility {
    /** Results arriving together are sent once. */
    private static final long BATCH_MS = 1000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Bundle results = new Bundle();
    private static String process = "";
    private static long hubStarted = Long.MIN_VALUE;
    private static final Runnable send = Compatibility::send;

    private Compatibility() {}

    static synchronized void process(String name) { process = name == null ? "" : name; }

    static synchronized void installed(String component) { record(component, ""); }

    static synchronized void failed(String component, Throwable error) {
        record(component, error.getClass().getSimpleName());
    }

    /** A new hub generation has forgotten every report; this process sends its own again. */
    static synchronized void hubStarted(long started) {
        if (started == hubStarted) return;
        hubStarted = started;
        schedule();
    }

    private static void record(String component, String result) {
        if (result.equals(results.getString(component))) return;
        results.putString(component, result);
        schedule();
    }

    private static void schedule() {
        main.removeCallbacks(send);
        main.postDelayed(send, BATCH_MS);
    }

    private static void send() {
        if (!ReloadLifecycle.isActive()) return;
        Bundle copy;
        String name;
        synchronized (Compatibility.class) {
            if (results.isEmpty()) return;
            copy = new Bundle(results);
            name = process;
        }
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app == null) { schedule(); return; }
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            app.sendBroadcast(new Intent(HubWire.CAPABILITIES).setPackage(HubWire.HOST)
                .putExtra("process", name).putExtra("results", copy), null, options.toBundle());
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "Compatibility report skipped", error);
        }
    }
}
