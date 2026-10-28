package com.hyper.podsrx.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.hyper.podsrx.protocol.LidSession;

/**
 * Follows the case lid from verified proximity advertisements. Relayed advertisements arrive tens
 * of milliseconds apart and closes are no longer missed, so every observed reopen is real and a
 * short settle still rides out a wobbling lid. Main thread only.
 */
final class LidController {
    interface Actions {
        /** A real open, not the first state heard after the screen came on. */
        void opened();
        /** The lid stayed open through the settle. */
        void confirmed();
        /** [repeat] is true for the follow-up closes of one closing. */
        void closed(boolean changed, boolean repeat);
    }

    /** The first state heard this soon after the screen came on was not seen opening. */
    private static final long BASELINE_MS = 1500;
    /** A close can reach the dialog's process before the dialog listens for it; repeat it briefly. */
    private static final long[] CLOSE_REPEATS_MS = {700, 1500};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LidSession lid = new LidSession(0, 300, 15000);
    private final Actions actions;
    private final Object closeToken = new Object();
    private Boolean open;
    /** A host that starts with the lid already open did not see it opening either. */
    private long screenOnAt = SystemClock.elapsedRealtime();
    // The popup starts with the first open sample so its process launch overlaps the settle; a lid
    // that wobbles shut inside it closes the popup again.
    private final Runnable confirm;

    LidController(Actions actions) {
        this.actions = actions;
        confirm = () -> {
            if (lid.confirm(SystemClock.elapsedRealtime())) actions.confirmed();
        };
    }

    /** Null until a state is heard this screen session. */
    Boolean open() { return open; }

    /** The lid may move unseen with the screen off; forget it so the next open is a fresh transition. */
    void screen(boolean on) {
        if (on) { screenOnAt = SystemClock.elapsedRealtime(); return; }
        main.removeCallbacks(confirm);
        main.removeCallbacksAndMessages(closeToken);
        lid.reset();
        open = null;
    }

    void close() {
        main.removeCallbacksAndMessages(null);
    }

    /** Returns whether the sample was new enough to update the readings. */
    boolean observe(boolean lidOpen, long capturedAt, String source) {
        long now = SystemClock.elapsedRealtime();
        if (!lid.observe(lidOpen, capturedAt, now)) return false;
        Boolean previous = open;
        boolean changed = previous == null || previous != lidOpen;
        boolean baseline = previous == null && now - screenOnAt < BASELINE_MS;
        if (changed) DiagnosticLog.i("HyperPods-X", "Hub lid " + (lidOpen ? "open" : "closed") + " · " + (now - capturedAt) + " ms · " + source);
        open = lidOpen;
        if (!lidOpen) {
            actions.closed(changed, false);
            if (changed) for (long delay : CLOSE_REPEATS_MS) main.postAtTime(() -> {
                if (Boolean.FALSE.equals(open)) actions.closed(true, true);
            }, closeToken, SystemClock.uptimeMillis() + delay);
        }
        main.removeCallbacks(confirm);
        Long settle = lid.confirmationDelay(now);
        if (lidOpen && changed) {
            main.removeCallbacksAndMessages(closeToken);
            if (settle != null && !baseline) actions.opened();
        }
        if (settle != null) main.postDelayed(confirm, settle);
        return true;
    }
}
