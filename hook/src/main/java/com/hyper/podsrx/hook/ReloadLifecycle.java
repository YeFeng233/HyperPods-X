package com.hyper.podsrx.hook;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** One registry per module classloader, never shared with the next generation. */
final class ReloadLifecycle {
    private static final ReloadResources resources = new ReloadResources();
    private ReloadLifecycle() {}
    static boolean isActive() { return resources.isActive(); }
    static boolean canStartOperation() { return resources.canStartOperation(); }
    static void add(Runnable cleanup) { resources.add(cleanup); }
    static void rejectWhile(BooleanSupplier busy) { resources.rejectWhile(busy); }
    static boolean retire() { return resources.retire(); }

    /** A timed-out queued task must never retire the old generation later. */
    static <T> T onMain(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicInteger state = new AtomicInteger(); // queued, started, cancelled
        CountDownLatch done = new CountDownLatch(1);
        Object[] result = new Object[2];
        Runnable task = () -> {
            if (!state.compareAndSet(0, 1)) return;
            try { result[0] = action.call(); }
            catch (Throwable error) { result[1] = error; }
            finally { done.countDown(); }
        };
        if (!main.post(task)) throw new IllegalStateException("Host main looper unavailable");
        boolean interrupted = false;
        try {
            try {
                if (!done.await(5, TimeUnit.SECONDS) && state.compareAndSet(0, 2)) {
                    main.removeCallbacks(task);
                    throw new IllegalStateException("Host main thread busy; retry later");
                }
            } catch (InterruptedException error) {
                interrupted = true;
                if (state.compareAndSet(0, 2)) {
                    main.removeCallbacks(task);
                    throw error;
                }
            }
            // Once cleanup starts, await its bounded cleanup actions rather than
            // report failure while a queued action is still mutating the host.
            for (;;) {
                try { done.await(); break; }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (result[1] != null) throw new IllegalStateException("Host lifecycle operation failed", (Throwable) result[1]);
            @SuppressWarnings("unchecked") T value = (T) result[0];
            return value;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
