package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns only its own bounded volume envelopes on the bound earbud's media players. */
final class ConversationAudioHost extends Binder {
    private static final String TAG = "HyperPods-Conversation";
    // Separate from framework duck/fade/mute IDs 1..4 and normal player-allocated IDs.
    private static final int SHAPER_ID = 0x48505843;
    private static final long MAX_SESSION_MS = 60_000, HEARTBEAT_MS = 1_500;
    private static final long SCAN_MS = 250, ATTACK_MS = 200, RELEASE_MS = 300;
    private static final float GAIN = 0.25f;
    private static ConversationAudioHost installed;

    private final SharedPreferences preferences;
    private final Context context;
    private final HandlerThread thread;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;
    private volatile boolean closed;
    private volatile boolean cleanupComplete;
    private final AudioManager audio;
    private final Handler handler;
    private final int appUid;
    private final Api api;
    private final String unavailable;
    // All session and track state is confined to handler, including delayed cleanup.
    private final Map<Integer, Track> tracks = new LinkedHashMap<>();
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private long session, generation, deadline, lastHeartbeat, lastCaptured, lastStartCaptured;
    private String address = "";
    private String status;
    private Runnable watchdog;

    static synchronized void install(Context context, SharedPreferences preferences) throws Exception {
        if (installed != null) return;
        ConversationAudioHost host = new ConversationAudioHost(context, preferences);
        ReloadLifecycle.add(host::close);
        ReloadLifecycle.rejectWhile(host::busyForReload);
        host.receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (host.closed || !ReloadLifecycle.isActive()
                            || !ConversationWire.ACTION.equals(intent.getAction())) return;
                    long age = SystemClock.elapsedRealtime() - intent.getLongExtra("created", 0);
                    if (age < 0 || age > 1_000) return;
                    Bundle result = new Bundle();
                    result.putBinder("bridge", host);
                    setResultExtras(result);
                    setResultCode(1);
                }
        };
        try {
            context.registerReceiver(host.receiver, new IntentFilter(ConversationWire.ACTION), ConversationWire.PERMISSION,
                    host.handler, Context.RECEIVER_EXPORTED);
            host.receiverRegistered = true;
        } catch (RuntimeException error) {
            host.close();
            throw error;
        }
        installed = host;
        DiagnosticLog.i(TAG, host.unavailable == null ? "Conversation Binder bridge ready" : host.unavailable);
    }

    private ConversationAudioHost(Context context, SharedPreferences preferences) throws Exception {
        this.preferences = preferences;
        this.context = context;
        status = HookStrings.get(context, R.string.hook_media_bridge_ready);
        audio = context.getSystemService(AudioManager.class);
        appUid = context.getPackageManager().getPackageUid(SpatialWire.APP, 0);
        Api resolved = null;
        String failure = null;
        try {
            if (audio == null || context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING")
                    != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException(HookStrings.get(context, R.string.hook_audio_route_permission_missing));
            }
            resolved = new Api();
        } catch (Exception | LinkageError error) {
            failure = HookStrings.get(context, R.string.hook_player_envelope_unavailable) + reason(error).getClass().getSimpleName();
            DiagnosticLog.w(TAG, failure, error);
        }
        api = resolved;
        unavailable = failure;
        thread = new HandlerThread("HyperPods-Conversation");
        thread.start();
        handler = new Handler(thread.getLooper());
        attachInterface(null, ConversationWire.DESCRIPTOR);
    }

    private boolean active() { return owner != null; }

    private boolean pendingAudio() {
        if (active()) return true;
        long now = SystemClock.elapsedRealtime();
        for (Track track : tracks.values()) if (track.expiresAt > now) return true;
        return false;
    }

    private boolean busyForReload() {
        if (cleanupComplete) return false;
        AtomicBoolean busy = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);
        if (!handler.post(() -> {
            try { busy.set(pendingAudio()); }
            finally { done.countDown(); }
        })) return true;
        try { return !done.await(1_000, TimeUnit.MILLISECONDS) || busy.get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return true; }
    }

    private void close() {
        if (cleanupComplete) return;
        // Gate incoming/queued Binder requests before draining the worker.
        closed = true;
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        if (!handler.post(() -> {
            try {
                // The preflight guard normally handles this. Refuse retirement if
                // a START raced that check; keep the worker alive to finish recovery.
                if (pendingAudio()) throw new IllegalStateException("Conversation audio became active during reload");
                if (owner != null && death != null) owner.unlinkToDeath(death, 0);
                owner = null; death = null; watchdog = null;
                tracks.clear();
                handler.removeCallbacksAndMessages(null);
            } catch (RuntimeException error) { failure.set(error); }
            finally { done.countDown(); }
        })) failure.set(new IllegalStateException("Conversation worker is unavailable"));
        try {
            if (failure.get() == null && !done.await(1_000, TimeUnit.MILLISECONDS))
                throw new IllegalStateException("Conversation worker cleanup timed out");
            if (failure.get() != null) throw failure.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Conversation cleanup interrupted", error);
        } catch (RuntimeException error) {
            // A timed-out worker may still finish this cleanup. Keep old Binder
            // requests gated; the framework must not retire a failed generation.
            throw error;
        }
        try {
            if (receiverRegistered) { context.unregisterReceiver(receiver); receiverRegistered = false; }
            receiver = null;
        } finally {
            thread.quitSafely();
            try { thread.join(1_000); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Conversation worker shutdown interrupted", error);
            }
            if (thread.isAlive()) throw new IllegalStateException("Conversation worker did not stop");
            if (installed == this) installed = null;
        }
        cleanupComplete = true;
    }

    private boolean matches(IBinder token, long requestedSession) {
        return active() && owner.equals(token) && session == requestedSession;
    }

    private void checkAddress(String requested) {
        if (!BluetoothAdapter.checkBluetoothAddress(requested)
                || !requested.equalsIgnoreCase(preferences.getString("address", ""))) {
            throw new SecurityException(HookStrings.get(context, R.string.hook_bound_earbuds_only));
        }
    }

    private Bundle execute(int code, Bundle request) throws Exception {
        if (closed || !ReloadLifecycle.isActive()) return result(false, HookStrings.get(context, R.string.hook_media_bridge_updated));
        if (code == ConversationWire.START && !ReloadLifecycle.canStartOperation())
            return result(false, HookStrings.get(context, R.string.hook_module_updating));
        IBinder token = request.getBinder("client");
        long requestedSession = request.getLong("session", 0);
        // Release must remain available after a route, binding, or permission change.
        if (code == ConversationWire.STOP) {
            if (!active()) return result(true, HookStrings.get(context, R.string.hook_session_ended));
            if (!matches(token, requestedSession)) return result(false, HookStrings.get(context, R.string.hook_old_stop_ignored));
            stop(HookStrings.get(context, R.string.hook_media_restore_submitted));
            return result(true, status);
        }
        if (unavailable != null) return result(false, unavailable);
        if (code == ConversationWire.QUERY) return result(true, status);
        if (code != ConversationWire.START && code != ConversationWire.KEEPALIVE) {
            return result(false, HookStrings.get(context, R.string.hook_unknown_bridge_command));
        }
        long now = SystemClock.elapsedRealtime();
        long captured = request.getLong("captured", -1);
        if (captured < 0 || now < captured || now - captured > 1_000) {
            return result(false, HookStrings.get(context, R.string.hook_expired_session_rejected));
        }
        if (token == null || requestedSession <= 0 || !token.isBinderAlive()) {
            return result(false, HookStrings.get(context, R.string.hook_valid_owner_missing));
        }
        if (code == ConversationWire.KEEPALIVE) {
            if (!matches(token, requestedSession)) return result(false, HookStrings.get(context, R.string.hook_attenuation_session_ended));
            if (captured > lastCaptured) {
                lastCaptured = captured;
                lastHeartbeat = now;
            }
            scan(generation);
            return result(active(), status);
        }
        String requestedAddress = request.getString("address", "");
        checkAddress(requestedAddress);
        if (matches(token, requestedSession)) {
            // A repeated START is idempotent and never extends the absolute deadline.
            scan(generation);
            return result(active(), status);
        }
        if (captured <= lastStartCaptured) return result(false, HookStrings.get(context, R.string.hook_old_start_rejected));
        if (audio.getMode() != AudioManager.MODE_NORMAL) return result(false, HookStrings.get(context, R.string.hook_attenuation_paused_call));
        List<Candidate> candidates = candidates(requestedAddress);
        if (candidates.isEmpty()) return result(false, HookStrings.get(context, R.string.hook_no_controllable_tracks));
        stop(HookStrings.get(context, R.string.hook_previous_session_ended));
        generation++;
        long currentGeneration = generation;
        session = requestedSession;
        address = requestedAddress;
        owner = token;
        lastStartCaptured = lastCaptured = captured;
        lastHeartbeat = now;
        deadline = now + MAX_SESSION_MS;
        death = () -> handler.post(() -> {
            if (generation == currentGeneration && matches(token, requestedSession)) {
                stop(HookStrings.get(context, R.string.hook_client_exited_restore_submitted));
            }
        });
        try {
            token.linkToDeath(death, 0);
            reconcile(candidates);
            if (submittedCount() == 0) throw new IllegalStateException(HookStrings.get(context, R.string.hook_no_envelope_tracks));
        } catch (Exception | LinkageError error) {
            stop(HookStrings.get(context, R.string.hook_envelope_failed_restore_requested));
            throw error;
        }
        status = HookStrings.get(context, R.string.hook_envelope_submitted);
        watchdog = new Runnable() {
            @Override public void run() {
                if (!active() || generation != currentGeneration) return;
                scan(currentGeneration);
                if (active() && generation == currentGeneration) handler.postDelayed(this, SCAN_MS);
            }
        };
        handler.postDelayed(watchdog, SCAN_MS);
        return result(true, status);
    }

    private void scan(long expectedGeneration) {
        if (!active() || expectedGeneration != generation) return;
        long now = SystemClock.elapsedRealtime();
        try {
            checkAddress(address);
            if (now - lastHeartbeat > HEARTBEAT_MS || now >= deadline
                    || audio.getMode() != AudioManager.MODE_NORMAL || !owner.isBinderAlive()) {
                stop(HookStrings.get(context, R.string.hook_session_timeout_restore));
                return;
            }
            List<Candidate> current = candidates(address);
            if (current.isEmpty()) {
                stop(HookStrings.get(context, R.string.hook_route_lost_restore));
                return;
            }
            reconcile(current);
        } catch (Exception | LinkageError error) {
            DiagnosticLog.w(TAG, "Conversation player scan failed", reason(error));
            stop(HookStrings.get(context, R.string.hook_player_error_restore));
        }
    }

    private List<Candidate> candidates(String target) throws Exception {
        List<Candidate> result = new ArrayList<>();
        for (AudioPlaybackConfiguration config : audio.getActivePlaybackConfigurations()) {
            if (!(Boolean) api.isActive.invoke(config)
                    || config.getAudioAttributes().getUsage() != AudioAttributes.USAGE_MEDIA) continue;
            int type = (Integer) api.playerType.invoke(config);
            // Confirmed ROM types: AudioTrack=1, MediaPlayer=2. Others may silently ignore IPlayer.
            if (type != 1 && type != 2) continue;
            int uid = (Integer) api.clientUid.invoke(config);
            if (uid < 0 || uid == appUid || uid == android.os.Process.myUid()) continue;
            List<?> devices = (List<?>) api.devices.invoke(config);
            int[] deviceIds = (int[]) api.deviceIds.get(config);
            // getAudioDeviceInfos omits unresolved port IDs; do not treat a partial list as safe.
            if (devices.isEmpty() || deviceIds == null || devices.size() != deviceIds.length) continue;
            boolean allMatch = true;
            for (Object value : devices) {
                AudioDeviceInfo device = (AudioDeviceInfo) value;
                if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || !target.equalsIgnoreCase(device.getAddress())) { allMatch = false; break; }
            }
            if (!allMatch) continue;
            Object proxy = api.playerProxy.invoke(config);
            if (proxy != null) result.add(new Candidate((Integer) api.playerId.invoke(config), uid, proxy));
        }
        return result;
    }

    private void reconcile(List<Candidate> candidates) throws Exception {
        Map<Integer, Candidate> selected = new LinkedHashMap<>();
        for (Candidate candidate : candidates) selected.put(candidate.id, candidate);
        for (Track track : new ArrayList<>(tracks.values())) {
            Candidate candidate = selected.get(track.id);
            if (!track.releasing && (candidate == null || candidate.uid != track.uid)) release(track);
        }
        for (Candidate candidate : selected.values()) {
            Track previous = tracks.get(candidate.id);
            if (previous != null && !previous.releasing && previous.generation == generation
                    && previous.uid == candidate.uid) continue;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= ATTACK_MS + RELEASE_MS + SCAN_MS) continue;
            VolumeShaper.Configuration shape = api.envelope(remaining,
                    new float[]{0f, (float) ATTACK_MS / remaining, 1f - (float) RELEASE_MS / remaining, 1f},
                    new float[]{1f, GAIN, GAIN, 1f});
            // JOIN rescales a non-monotonic 1 -> gain -> 1 curve and can deepen its duck.
            // A new session replaces that same ID without JOIN; recovery below is a monotonic JOIN.
            VolumeShaper.Operation operation = previous == null ? VolumeShaper.Operation.PLAY : api.replace(false);
            api.apply.invoke(candidate.proxy, shape, operation);
            // IPlayer is one-way. This records submission, not a confirmed acoustic result.
            tracks.put(candidate.id, new Track(candidate, generation, deadline));
        }
    }

    private void stop(String message) {
        if (watchdog != null) handler.removeCallbacks(watchdog);
        watchdog = null;
        IBinder previousOwner = owner;
        IBinder.DeathRecipient previousDeath = death;
        owner = null;
        death = null;
        address = "";
        if (previousOwner != null && previousDeath != null) {
            try { previousOwner.unlinkToDeath(previousDeath, 0); }
            catch (RuntimeException ignored) { /* owner already died */ }
        }
        for (Track track : new ArrayList<>(tracks.values())) if (!track.releasing) release(track);
        status = message;
    }

    private void release(Track track) {
        if (track.releasing) return;
        track.releasing = true;
        try {
            // Joining starts from the current envelope gain and leaves player/stream volume intact.
            api.apply.invoke(track.proxy, api.envelope(RELEASE_MS, new float[]{0f, 1f}, new float[]{GAIN, 1f}),
                    api.replace());
        } catch (Exception | LinkageError error) {
            DiagnosticLog.w(TAG, "Release envelope submission failed; trying own-envelope termination", reason(error));
            terminate(track);
            return;
        }
        handler.postDelayed(() -> terminate(track), RELEASE_MS + 50);
    }

    private void terminate(Track track) {
        // An older release callback must never terminate a new session's shape on the same player.
        if (tracks.get(track.id) != track) return;
        try {
            api.apply.invoke(track.proxy, api.id.newInstance(SHAPER_ID), api.terminate());
            tracks.remove(track.id);
        } catch (Exception | LinkageError error) {
            if (!track.recoveryFailed) {
                DiagnosticLog.w(TAG, "Own-envelope cleanup unavailable; finite envelope ends at unity", reason(error));
            }
            track.recoveryFailed = true;
            status = HookStrings.get(context, R.string.hook_restore_unconfirmed_deadline);
            // Retain the proxy and retry only termination. Never resend an attenuation curve.
            handler.postDelayed(() -> {
                if (tracks.get(track.id) != track) return;
                if (SystemClock.elapsedRealtime() >= track.expiresAt) {
                    // Expiry is the independent fail-safe, not a positive recovery acknowledgement.
                    // Keep the failed submission visible; a later same-ID replacement cannot stack it.
                    if (!active()) status = HookStrings.get(context, R.string.hook_restore_deadline_reached);
                } else {
                    terminate(track);
                }
            }, 1_000);
        }
    }

    private int submittedCount() {
        int count = 0;
        for (Track track : tracks.values()) if (!track.releasing && track.generation == generation) count++;
        return count;
    }

    private Bundle result(boolean ok, String message) {
        int failures = 0;
        for (Track track : tracks.values()) if (track.recoveryFailed) failures++;
        Bundle result = new Bundle();
        result.putBoolean("ok", ok && failures == 0);
        result.putBoolean("active", active());
        result.putInt("players", submittedCount());
        result.putString("status", failures == 0 ? message
                : HookStrings.get(context, R.string.hook_restore_unconfirmed_prefix) + failures + HookStrings.get(context, R.string.hook_restore_unconfirmed_tracks));
        result.putInt("recoveryFailures", failures);
        result.putLong("session", session);
        result.putLong("attackMs", ATTACK_MS);
        result.putLong("releaseMs", RELEASE_MS);
        return result;
    }

    private static Throwable reason(Throwable error) {
        return error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(ConversationWire.DESCRIPTOR);
            return true;
        }
        long hostReceivedAt = SystemClock.elapsedRealtime();
        if (Binder.getCallingUid() != appUid) throw new SecurityException("Unsupported caller");
        data.enforceInterface(ConversationWire.DESCRIPTOR);
        Bundle incoming = data.readBundle(getClass().getClassLoader());
        data.enforceNoDataAvail();
        if (closed || !ReloadLifecycle.isActive()) {
            Bundle retired = new Bundle();
            retired.putBoolean("ok", false);
            retired.putBoolean("active", false);
            // Only completed cleanup proves this old endpoint cannot own a lease.
            // A partial/failed cleanup must never authorize rediscovery as recovery.
            retired.putBoolean("bridgeRetired", cleanupComplete);
            retired.putString("status", HookStrings.get(context, R.string.hook_media_bridge_updated));
            if (reply != null) { reply.writeNoException(); reply.writeBundle(retired); }
            return true;
        }
        Bundle request = incoming == null ? new Bundle() : incoming;
        AtomicReference<Bundle> response = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch completed = new CountDownLatch(1);
        handler.post(() -> {
            if (cancelled.get()) { completed.countDown(); return; }
            try { response.set(execute(code, request)); }
            catch (Exception | LinkageError error) {
                DiagnosticLog.w(TAG, "Conversation bridge request failed", reason(error));
                response.set(result(false, HookStrings.get(context, R.string.hook_media_bridge_request_failed) + reason(error).getClass().getSimpleName()));
            } finally {
                Bundle result = response.get();
                if (result != null) {
                    result.putLong("hostReceivedAt", hostReceivedAt);
                    // One-way IPlayer submission has returned; this is not an audible completion.
                    result.putLong("hostCompletedAt", SystemClock.elapsedRealtime());
                }
                completed.countDown();
            }
        });
        boolean done;
        try { done = completed.await(1_000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); done = false; }
        Bundle outgoing;
        if (!done) {
            cancelled.set(true);
            IBinder token = request.getBinder("client");
            long requestedSession = request.getLong("session", 0);
            if (code != ConversationWire.QUERY) handler.post(() -> {
                if (matches(token, requestedSession)) stop(HookStrings.get(context, R.string.hook_bridge_timeout_restore));
            });
            Bundle failed = new Bundle();
            failed.putBoolean("ok", false);
            failed.putBoolean("active", false);
            failed.putInt("players", 0);
            failed.putLong("session", requestedSession);
            failed.putString("status", HookStrings.get(context, R.string.hook_media_bridge_response_timeout));
            failed.putLong("hostReceivedAt", hostReceivedAt);
            failed.putLong("hostCompletedAt", -1); // Handler completion is unknown on timeout.
            failed.putLong("attackMs", ATTACK_MS);
            failed.putLong("releaseMs", RELEASE_MS);
            outgoing = failed;
        } else {
            outgoing = response.get();
        }
        if (reply != null) { reply.writeNoException(); reply.writeBundle(outgoing); }
        return true;
    }

    private static class Candidate {
        final int id, uid;
        final Object proxy;
        Candidate(int id, int uid, Object proxy) { this.id = id; this.uid = uid; this.proxy = proxy; }
    }

    private static final class Track extends Candidate {
        final long generation;
        final long expiresAt;
        boolean releasing, recoveryFailed;
        Track(Candidate candidate, long generation, long expiresAt) {
            super(candidate.id, candidate.uid, candidate.proxy);
            this.generation = generation;
            this.expiresAt = expiresAt;
        }
    }

    /** Resolve the ROM's hidden system APIs before publishing a usable capability. */
    private static final class Api {
        final Method playerId = AudioPlaybackConfiguration.class.getMethod("getPlayerInterfaceId");
        final Method isActive = AudioPlaybackConfiguration.class.getMethod("isActive");
        final Method playerType = AudioPlaybackConfiguration.class.getMethod("getPlayerType");
        final Method clientUid = AudioPlaybackConfiguration.class.getMethod("getClientUid");
        final Method devices = AudioPlaybackConfiguration.class.getMethod("getAudioDeviceInfos");
        final Method playerProxy = AudioPlaybackConfiguration.class.getMethod("getPlayerProxy");
        final Field deviceIds = AudioPlaybackConfiguration.class.getDeclaredField("mDeviceIds");
        final Method apply = Class.forName("android.media.PlayerProxy").getMethod("applyVolumeShaper",
                VolumeShaper.Configuration.class, VolumeShaper.Operation.class);
        final Method setId = VolumeShaper.Configuration.Builder.class.getMethod("setId", int.class);
        final Method options = VolumeShaper.Configuration.Builder.class.getMethod("setOptionFlags", int.class);
        final Constructor<VolumeShaper.Configuration> id = VolumeShaper.Configuration.class.getConstructor(int.class);
        final Class<?> operationBuilder = Class.forName("android.media.VolumeShaper$Operation$Builder");
        final Constructor<?> newOperation = operationBuilder.getConstructor();
        final Constructor<?> copyOperation = operationBuilder.getConstructor(VolumeShaper.Operation.class);
        final Method replace = operationBuilder.getMethod("replace", int.class, boolean.class);
        final Method terminate = operationBuilder.getMethod("terminate");
        final Method buildOperation = operationBuilder.getMethod("build");

        Api() throws Exception {
            deviceIds.setAccessible(true);
            envelope(1_000, new float[]{0f, 1f}, new float[]{GAIN, 1f});
            id.newInstance(SHAPER_ID);
            replace();
            terminate();
        }

        VolumeShaper.Configuration envelope(long duration, float[] times, float[] volumes) throws Exception {
            VolumeShaper.Configuration.Builder builder = new VolumeShaper.Configuration.Builder();
            setId.invoke(builder, SHAPER_ID);
            options.invoke(builder, 2); // OPTION_FLAG_CLOCK_TIME, confirmed in this ROM.
            return builder.setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                    .setDuration(duration).setCurve(times, volumes).build();
        }

        VolumeShaper.Operation replace() throws Exception {
            return replace(true);
        }

        VolumeShaper.Operation replace(boolean join) throws Exception {
            Object builder = copyOperation.newInstance(VolumeShaper.Operation.PLAY);
            replace.invoke(builder, SHAPER_ID, join);
            return (VolumeShaper.Operation) buildOperation.invoke(builder);
        }

        VolumeShaper.Operation terminate() throws Exception {
            Object builder = newOperation.newInstance();
            terminate.invoke(builder);
            return (VolumeShaper.Operation) buildOperation.invoke(builder);
        }
    }
}
