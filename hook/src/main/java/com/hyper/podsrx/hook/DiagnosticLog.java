package com.hyper.podsrx.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import java.time.Instant;
import java.util.function.Consumer;

/** Captures only this module's records; never reads another app's logcat. */
public final class DiagnosticLog {
    public static final String ACTION = "com.hyper.podsrx.EXPORT_LOGS";
    public static final String PERMISSION = "com.hyper.podsrx.permission.DIAGNOSTICS";
    public static final int MAX_BYTES = 64 * 1024;
    private static final DiagnosticLogBuffer BUFFER = new DiagnosticLogBuffer(MAX_BYTES, 1000);
    private static volatile Consumer<String> sink;
    private static boolean installed;

    private DiagnosticLog() { }

    public static void setSink(Consumer<String> value) { sink = value; }
    public static String snapshot() { return BUFFER.snapshot(); }
    public static void i(String tag, String text) { write(Log.INFO, tag, text, null); }
    public static void w(String tag, String text) { write(Log.WARN, tag, text, null); }
    public static void w(String tag, String text, Throwable error) { write(Log.WARN, tag, text, error); }
    public static void e(String tag, String text, Throwable error) { write(Log.ERROR, tag, text, error); }

    public static void write(int priority, String tag, String text, Throwable error) {
        String detail = text + (error == null ? "" : "\n" + Log.getStackTraceString(error));
        String clean = LogRedactor.limitUtf8(LogRedactor.redact(detail), 7500);
        String record = BUFFER.add(Instant.now() + " " + priority + " [" + Process.myPid() + ":"
                + Process.myTid() + "] " + tag + ": " + clean);
        Log.println(priority, tag, clean);
        Consumer<String> current = sink;
        if (current != null) {
            try { current.accept(record); }
            catch (RuntimeException ignored) { /* Diagnostics must not interrupt Bluetooth callbacks. */ }
        }
    }

    public static synchronized void install(Context context) {
        if (installed || !ReloadLifecycle.isActive()) return;
        Handler delivery = new Handler(Looper.getMainLooper());
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context host, Intent intent) {
                if (!ReloadLifecycle.isActive() || !ACTION.equals(intent.getAction())) return;
                long age = SystemClock.elapsedRealtime() - intent.getLongExtra("created", 0);
                if (age < 0 || age > 3000) return;
                ResultReceiver target = intent.getParcelableExtra("result", ResultReceiver.class);
                if (target == null) return;
                Bundle result = new Bundle();
                result.putString("process", Application.getProcessName());
                result.putString("log", snapshot());
                try { target.send(1, result); }
                catch (RuntimeException ignored) { /* The exporting app may have exited. */ }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION), PERMISSION, delivery, Context.RECEIVER_EXPORTED);
        ReloadLifecycle.add(() -> {
            context.unregisterReceiver(receiver);
            delivery.removeCallbacksAndMessages(null);
        });
        installed = true;
        i("HyperPods-X", "Diagnostic log bridge ready in " + Application.getProcessName());
    }
}
