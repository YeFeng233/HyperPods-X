package com.hyper.podsrx.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Owns a single generation's resources; retirement is terminal even if cleanup fails. */
final class ReloadResources {
    private volatile boolean active = true;
    private volatile boolean admitting = true;
    private final List<Runnable> cleanups = new ArrayList<>();
    private final List<BooleanSupplier> busyChecks = new ArrayList<>();

    boolean isActive() { return active; }
    boolean canStartOperation() { return active && admitting; }
    synchronized void add(Runnable cleanup) {
        if (!active) throw new IllegalStateException("Module generation retired");
        cleanups.add(cleanup);
    }
    synchronized void rejectWhile(BooleanSupplier busy) {
        if (!active) throw new IllegalStateException("Module generation retired");
        busyChecks.add(busy);
    }
    synchronized boolean retire() {
        if (!active) throw new IllegalStateException("Generation already retired; restart this host");
        // Stop admitting new operations, but let existing sessions keep receiving
        // heartbeat, pose and STOP requests while their busy guards are inspected.
        admitting = false;
        try {
            for (BooleanSupplier busy : busyChecks) {
                if (busy.getAsBoolean()) { admitting = true; return false; }
            }
        } catch (Throwable error) {
            admitting = true;
            throw error;
        }
        active = false;
        Throwable failure = null;
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try { cleanups.get(i).run(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        cleanups.clear(); busyChecks.clear();
        if (failure != null) throw new IllegalStateException("Incomplete cleanup; restart this host", failure);
        return true;
    }
}
