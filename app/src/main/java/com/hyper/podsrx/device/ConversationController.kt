package com.hyper.podsrx.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hyper.podsrx.R
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.protocol.ConversationAction
import com.hyper.podsrx.protocol.ConversationPolicy
import com.hyper.podsrx.protocol.Event
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

data class ConversationState(
    val description: UiText = text(R.string.conversation_experimental_wait),
    val mode: String = "tracks",
    val active: Boolean = false,
    val ducking: Boolean = false,
    val events: Long = 0,
    val wirePackets: Long = 0,
    val audioSource: Int = -1,
    val starts: Long = 0,
    val ends: Long = 0,
    val subtype: Int = -1,
    val rawStatus: Int = -1,
    val eventTime: Long = 0,
    val session: Long = 0,
    val mediaRequests: Long = 0,
    val mediaReleases: Long = 0,
    val players: Int = 0,
    val mediaPhase: String = "idle",
    val mediaStatus: String = "",
    val mediaSession: Long = 0,
    val playbackStopsWhileHeld: Long = 0,
    val eventReceivedAt: Long = 0,
    val eventQueueLatencyMs: Long = -1,
    val startSubmitLatencyMs: Long = -1,
    val startAckLatencyMs: Long = -1,
    val stopSubmitLatencyMs: Long = -1,
    val stopAckLatencyMs: Long = -1,
    val stopTimingSource: String = ""
) {
    fun diagnostic(strings: Strings) = JSONObject().put("description", description.resolve(strings)).put("mode", mode)
        .put("active", active).put("ducking", ducking).put("events", events)
        .put("wirePackets", wirePackets).put("audioSource", audioSource)
        .put("starts", starts).put("ends", ends).put("subtype", subtype).put("rawStatus", rawStatus)
        .put("eventTime", eventTime).put("session", session)
        .put("mediaRequests", mediaRequests).put("mediaReleases", mediaReleases).put("players", players)
        .put("mediaPhase", mediaPhase).put("mediaStatus", mediaStatus).put("mediaSession", mediaSession)
        .put("playbackStopsWhileHeld", playbackStopsWhileHeld)
        .put("eventReceivedAt", eventReceivedAt).put("eventQueueLatencyMs", eventQueueLatencyMs)
        .put("startSubmitLatencyMs", startSubmitLatencyMs).put("startAckLatencyMs", startAckLatencyMs)
        .put("stopSubmitLatencyMs", stopSubmitLatencyMs).put("stopAckLatencyMs", stopAckLatencyMs)
        .put("stopTimingSource", stopTimingSource)
}

/** Earbud reports only: no microphone, audio focus, stream-volume writes, or playback keys. */
class ConversationController(context: Context) {
    private val strings = context.stringResolver()
    private enum class MediaPhase { IDLE, STARTING, ACTIVE, STOPPING, UNCONFIRMED }

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val preferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val bridge = ConversationBridge(context)
    private val policy = ConversationPolicy()
    private val epoch = AtomicLong()
    @Volatile private var live = false
    private var closed = false
    private var wear: Event.Wearing? = null
    private var localOffRequested = preferences.getBoolean("conversationOffRequested", false)
    private var blockedUntilEnd = false
    // All media state and its generation are confined to Main, including bridge callbacks.
    private var mediaPhase = MediaPhase.IDLE
    private var mediaGeneration = 0L
    private var mediaSession = 0L
    private var mediaStatus = ""
    private var hostActive = false
    private var players = 0
    private var releaseDescription = text("")
    private var playbackStopObserved = false
    private var playbackRegistered = false
    private var devicesRegistered = false
    private var lastSave = 0L
    private val check = Runnable { refresh() }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = refresh()
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        runCatching { audio.registerAudioPlaybackCallback(playback, handler); playbackRegistered = true }
        runCatching { audio.registerAudioDeviceCallback(devices, handler); devicesRegistered = true }
    }

    fun connected() {
        val session = epoch.incrementAndGet()
        live = true
        onMain {
            if (session != epoch.get()) return@onMain
            release(text(R.string.waiting_earbud_status))
            wear = null
            blockedUntilEnd = false
            update { it.copy(session = session, mode = "tracks") }
            refresh()
        }
    }

    fun disconnected() {
        live = false
        val session = epoch.incrementAndGet()
        onMain {
            if (session != epoch.get()) return@onMain
            wear = null
            blockedUntilEnd = false
            release(text(R.string.waiting_control_connection))
        }
    }

    fun requested(value: Int) = onMain {
        localOffRequested = value != 1
        preferences.edit().putBoolean("conversationOffRequested", localOffRequested).apply()
        blockedUntilEnd = false
        release(if (localOffRequested) text(R.string.phone_link_stopped_pending) else text(R.string.waiting_earbud_confirmation))
    }

    fun configured() = forSession { refresh() }

    fun wearing(event: Event.Wearing) = forSession {
        val removed = event.inEarCount < (wear?.inEarCount ?: event.inEarCount)
        wear = event
        if (removed && policy.active) {
            blockedUntilEnd = true
            release(text(R.string.conversation_stopped_ear_removed))
        }
        refresh()
    }

    fun event(event: Event.Conversation) {
        val receivedAt = SystemClock.elapsedRealtime()
        forSession {
        val now = SystemClock.elapsedRealtime()
        val previous = DeviceRepository.state.value.conversation
        update { it.copy(events = it.events + 1, subtype = event.subtype, rawStatus = event.status, eventTime = now,
            eventReceivedAt = receivedAt, eventQueueLatencyMs = now - receivedAt) }
        if (previous.subtype != event.subtype || previous.rawStatus != event.status) {
            DeviceRepository.log(strings(R.string.conversation_event_log, event.subtype, event.status, epoch.get()))
        }
        // Unknown subtypes neither start a lease nor renew the fixed policy deadline.
        if (event.subtype != 1) {
            if (mediaPhase == MediaPhase.IDLE) publish(text(R.string.conversation_event_unsupported))
            save(now, force = true)
            return@forSession
        }
        val ended = event.status in setOf(6, 7, 8, 9)
        if (ended) blockedUntilEnd = false
        val allowed = unavailableReason() == null && !blockedUntilEnd
        val action = policy.status(event.status, allowed, now)
        when (action) {
            ConversationAction.START -> {
                update { it.copy(starts = it.starts + 1) }
                acquire(receivedAt)
            }
            ConversationAction.RELEASE -> {
                if (ended) update { it.copy(ends = it.ends + 1) } else blockedUntilEnd = true
                release(if (ended) text(R.string.conversation_ended) else text(R.string.conversation_round_stopped), receivedAt)
            }
            ConversationAction.NONE -> if (!policy.active) publish(idleDescription())
        }
        if (policy.active) publish(activeDescription())
        schedule()
        save(now, force = action != ConversationAction.NONE || ended)
        }
    }

    private fun acquire(receivedAt: Long) {
        if (mediaPhase != MediaPhase.IDLE) {
            policy.cancel()
            blockedUntilEnd = true
            publish(idleDescription())
            return
        }
        unavailableReason()?.let {
            blockedUntilEnd = true
            release(it)
            return
        }
        val generation = ++mediaGeneration
        val controlSession = epoch.get()
        mediaPhase = MediaPhase.STARTING
        mediaSession = 0L
        mediaStatus = ""
        hostActive = false
        players = 0
        playbackStopObserved = false
        update { it.copy(mediaRequests = it.mediaRequests + 1, startSubmitLatencyMs = -1, startAckLatencyMs = -1) }
        publish(activeDescription())
        runCatching {
            bridge.start(DeviceRepository.state.value.address) { result ->
                onMain {
                    if (generation != mediaGeneration ||
                        mediaPhase !in setOf(MediaPhase.STARTING, MediaPhase.ACTIVE)) return@onMain
                    val replySession = result.getLong("session", 0L)
                    val validSession = replySession > 0 && (mediaSession == 0L || replySession == mediaSession)
                    if (mediaSession == 0L && validSession) mediaSession = replySession
                    mediaStatus = result.getString("status").orEmpty().take(240)
                    if (!validSession || !result.getBoolean("ok")) {
                        if (!validSession) mediaStatus = strings(R.string.conversation_ack_mismatch, replySession, mediaSession, mediaStatus)
                        // A failed RPC is not evidence that an earlier shaper has recovered.
                        blockedUntilEnd = true
                        DeviceRepository.log(strings(R.string.conversation_start_unconfirmed, mediaStatus))
                        release(text(R.string.media_interrupted_wait_conversation))
                        return@onMain
                    }
                    hostActive = result.getBoolean("active")
                    players = if (hostActive) result.getInt("players", 0).coerceAtLeast(0) else 0
                    if (!hostActive) {
                        blockedUntilEnd = true
                        release(text(R.string.conversation_media_session_ended))
                        return@onMain
                    }
                    if (mediaPhase == MediaPhase.STARTING) {
                        val ackAt = SystemClock.elapsedRealtime()
                        val submittedAt = result.getLong("hostCompletedAt", -1L)
                        val submittedMs = if (submittedAt in receivedAt..ackAt) submittedAt - receivedAt else -1L
                        update { it.copy(startSubmitLatencyMs = submittedMs, startAckLatencyMs = ackAt - receivedAt) }
                        DeviceRepository.log(strings(R.string.conversation_start_timing, submittedMs, ackAt - receivedAt))
                    }
                    mediaPhase = MediaPhase.ACTIVE
                    val reason = unavailableReason()
                    if (controlSession != epoch.get() || !live || !policy.active || reason != null) {
                        blockedUntilEnd = true
                        release(reason ?: text(R.string.control_session_changed))
                    } else if (policy.check(true, SystemClock.elapsedRealtime()) == ConversationAction.RELEASE) {
                        blockedUntilEnd = true
                        release(text(R.string.conversation_safety_timeout))
                    } else {
                        // Host/player confirmation is operational evidence, not a listening verdict.
                        publish(activeDescription())
                        save(SystemClock.elapsedRealtime())
                    }
                }
            }
        }.onFailure {
            if (generation == mediaGeneration) {
                mediaStatus = it.message.orEmpty().take(240)
                blockedUntilEnd = true
                release(text(R.string.media_start_unconfirmed))
            }
        }
    }

    private fun unavailableReason(): UiText? {
        val state = DeviceRepository.state.value
        return when {
            mediaPhase == MediaPhase.STOPPING -> text(R.string.conversation_ending_wait_restore)
            mediaPhase == MediaPhase.UNCONFIRMED -> text(R.string.media_restore_unconfirmed)
            !live || !state.connected -> text(R.string.waiting_control_connection)
            localOffRequested -> text(R.string.phone_link_stopped)
            0x28 in state.pending -> text(R.string.waiting_earbud_confirmation)
            state.controls[0x28]?.firstOrNull() == null -> text(R.string.waiting_conversation_setting)
            state.controls[0x28]?.firstOrNull() != 1 -> text(R.string.conversation_disabled)
            (wear?.inEarCount ?: 0) == 0 -> text(R.string.wear_earbuds_first)
            audio.mode != AudioManager.MODE_NORMAL -> text(R.string.conversation_paused_call)
            !routeMatches(state.address) -> text(R.string.switch_media_to_airpods)
            // Track shaping never pauses/resumes a player, so stopped playback can safely release.
            !audio.isMusicActive -> text(R.string.waiting_media_playback)
            else -> null
        }
    }

    private fun routeMatches(address: String) = address.isNotBlank() && runCatching {
        audio.getAudioDevicesForAttributes(attributes).any {
            it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                it.address.equals(address, ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun idleDescription() = unavailableReason()
        ?: if (blockedUntilEnd) text(R.string.conversation_processing_stopped)
        else text(R.string.conversation_wait_real_event)

    private fun activeDescription() = when {
        mediaPhase == MediaPhase.STARTING -> text(R.string.speaking_wait_media)
        hostActive && players > 0 -> text(R.string.speaking)
        else -> text(R.string.speaking_wait_controllable_media)
    }

    private fun refresh() {
        if (closed) return
        if (policy.active && !playbackStopObserved && !audio.isMusicActive) {
            playbackStopObserved = true
            update { it.copy(playbackStopsWhileHeld = it.playbackStopsWhileHeld + 1) }
            DeviceRepository.log(strings(R.string.conversation_playback_stopped))
        }
        val reason = unavailableReason()
        if (policy.check(reason == null, SystemClock.elapsedRealtime()) == ConversationAction.RELEASE) {
            blockedUntilEnd = true
            release(reason ?: text(R.string.conversation_safety_timeout))
        } else if (!policy.active) publish(idleDescription())
        schedule()
    }

    private fun release(reason: UiText, eventReceivedAt: Long? = null) {
        policy.cancel()
        handler.removeCallbacks(check)
        releaseDescription = reason
        when (mediaPhase) {
            MediaPhase.IDLE -> publish(reason)
            MediaPhase.STOPPING, MediaPhase.UNCONFIRMED -> publish(idleDescription())
            MediaPhase.STARTING, MediaPhase.ACTIVE -> {
                val requestedAt = eventReceivedAt ?: SystemClock.elapsedRealtime()
                update { it.copy(stopSubmitLatencyMs = -1, stopAckLatencyMs = -1,
                    stopTimingSource = if (eventReceivedAt != null) "earbud-event" else "cleanup") }
                val generation = ++mediaGeneration
                mediaPhase = MediaPhase.STOPPING
                // Keep the last acknowledged host state until this exact lease is stopped.
                publish(idleDescription())
                runCatching {
                    bridge.stop { result ->
                        onMain {
                            if (generation != mediaGeneration || mediaPhase != MediaPhase.STOPPING) return@onMain
                            val replySession = result.getLong("session", 0L)
                            val sameSession = replySession > 0 && (mediaSession == 0L || replySession == mediaSession)
                            mediaStatus = result.getString("status").orEmpty().take(240)
                            if (sameSession && result.getBoolean("ok") && result.getBoolean("stopConfirmed") &&
                                !result.getBoolean("cleanupPending", true) && result.getBoolean("activeKnown") &&
                                !result.getBoolean("active", true) &&
                                result.getInt("players", 0) == 0) {
                                mediaSession = replySession
                                mediaPhase = MediaPhase.IDLE
                                hostActive = false
                                players = 0
                                val ackAt = SystemClock.elapsedRealtime()
                                val submittedAt = result.getLong("hostCompletedAt", -1L)
                                // A cached STOP or an unsubmitted START has no new host timing.
                                val submittedMs = if (submittedAt in requestedAt..ackAt) submittedAt - requestedAt else -1L
                                update { it.copy(mediaReleases = it.mediaReleases + 1,
                                    stopSubmitLatencyMs = submittedMs, stopAckLatencyMs = ackAt - requestedAt) }
                                DeviceRepository.log(strings(R.string.conversation_stop_timing, if (eventReceivedAt != null) strings(R.string.event) else strings(R.string.playback), submittedMs, ackAt - requestedAt))
                                DeviceRepository.log(strings(R.string.conversation_clear_unconfirmed))
                                publish(releaseDescription)
                            } else {
                                if (!sameSession) mediaStatus = strings(R.string.conversation_stop_ack_mismatch, replySession, mediaSession, mediaStatus)
                                markUnconfirmed()
                            }
                            save(SystemClock.elapsedRealtime(), force = true)
                        }
                    }
                }.onFailure {
                    if (generation == mediaGeneration) {
                        mediaStatus = it.message.orEmpty().take(240)
                        markUnconfirmed()
                    }
                }
            }
        }
        save(SystemClock.elapsedRealtime(), force = true)
    }

    private fun markUnconfirmed() {
        mediaPhase = MediaPhase.UNCONFIRMED
        blockedUntilEnd = true
        // No automatic START or speculative recovery after a lost/failed STOP acknowledgement.
        DeviceRepository.log(strings(R.string.conversation_restore_unconfirmed, mediaStatus))
        publish(idleDescription())
    }

    private fun publish(description: UiText) = update {
        it.copy(description = description, active = policy.active, ducking = hostActive && players > 0,
            mode = "tracks", players = players, mediaPhase = mediaPhase.name.lowercase(),
            mediaStatus = mediaStatus, mediaSession = mediaSession)
    }

    private fun update(change: (ConversationState) -> ConversationState) {
        // An old service's asynchronous callback must not overwrite its replacement controller.
        if (!closed) DeviceRepository.update { it.copy(conversation = change(it.conversation)) }
    }

    private fun save(now: Long, force: Boolean = false) {
        if (!closed && (force || now - lastSave >= 1000)) { lastSave = now; DeviceRepository.saveDiagnostics() }
    }

    private fun schedule() {
        handler.removeCallbacks(check)
        if (!closed && policy.active) handler.postDelayed(check, 250)
    }

    private fun forSession(block: () -> Unit) {
        val session = epoch.get()
        onMain { if (live && session == epoch.get()) block() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) { if (!closed) block() }
        else handler.post { if (!closed) block() }
    }

    fun close() {
        live = false
        epoch.incrementAndGet()
        onMain {
            release(text(R.string.earbud_service_stopped))
            closed = true
            ++mediaGeneration
            handler.removeCallbacks(check)
            // The bridge owns pending Binder work and must STOP even after our callbacks are invalidated.
            runCatching { bridge.close() }.onFailure { DeviceRepository.log(strings(R.string.conversation_close_failed, it.message)) }
            if (playbackRegistered) runCatching { audio.unregisterAudioPlaybackCallback(playback) }
            if (devicesRegistered) runCatching { audio.unregisterAudioDeviceCallback(devices) }
        }
    }
}
