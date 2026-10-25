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
import com.hyper.podsrx.protocol.ConversationAction
import com.hyper.podsrx.protocol.ConversationPolicy
import com.hyper.podsrx.protocol.Event
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

data class ConversationState(
    val description: String = "试验功能 · 等待耳机连接",
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
    fun diagnostic() = JSONObject().put("description", description).put("mode", mode)
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
    private var releaseDescription = ""
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
            release("等待耳机状态")
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
            release("等待耳机控制连接")
        }
    }

    fun requested(value: Int) = onMain {
        localOffRequested = value != 1
        preferences.edit().putBoolean("conversationOffRequested", localOffRequested).apply()
        blockedUntilEnd = false
        release(if (localOffRequested) "手机联动已停止 · 等待耳机确认" else "等待耳机确认")
    }

    fun configured() = forSession { refresh() }

    fun wearing(event: Event.Wearing) = forSession {
        val removed = event.inEarCount < (wear?.inEarCount ?: event.inEarCount)
        wear = event
        if (removed && policy.active) {
            blockedUntilEnd = true
            release("摘耳时停止对话处理")
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
            DeviceRepository.log("对话事件：子类型 ${event.subtype}，状态 ${event.status}，会话 ${epoch.get()}")
        }
        // Unknown subtypes neither start a lease nor renew the fixed policy deadline.
        if (event.subtype != 1) {
            if (mediaPhase == MediaPhase.IDLE) publish("收到尚未适配的对话事件")
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
                release(if (ended) "对话已结束" else "本轮对话处理已停止", receivedAt)
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
                        if (!validSession) mediaStatus = "会话回执不匹配 $replySession/$mediaSession：$mediaStatus"
                        // A failed RPC is not evidence that an earlier shaper has recovered.
                        blockedUntilEnd = true
                        DeviceRepository.log("对话音轨：控制未确认，$mediaStatus")
                        release("媒体控制中断 · 等待下一次对话")
                        return@onMain
                    }
                    hostActive = result.getBoolean("active")
                    players = if (hostActive) result.getInt("players", 0).coerceAtLeast(0) else 0
                    if (!hostActive) {
                        blockedUntilEnd = true
                        release("本轮媒体控制已结束")
                        return@onMain
                    }
                    if (mediaPhase == MediaPhase.STARTING) {
                        val ackAt = SystemClock.elapsedRealtime()
                        val submittedAt = result.getLong("hostCompletedAt", -1L)
                        val submittedMs = if (submittedAt in receivedAt..ackAt) submittedAt - receivedAt else -1L
                        update { it.copy(startSubmitLatencyMs = submittedMs, startAckLatencyMs = ackAt - receivedAt) }
                        DeviceRepository.log("对话计时：开始，事件接收到提交 ${submittedMs}ms，回执 ${ackAt - receivedAt}ms")
                    }
                    mediaPhase = MediaPhase.ACTIVE
                    val reason = unavailableReason()
                    if (controlSession != epoch.get() || !live || !policy.active || reason != null) {
                        blockedUntilEnd = true
                        release(reason ?: "耳机控制会话已变化")
                    } else if (policy.check(true, SystemClock.elapsedRealtime()) == ConversationAction.RELEASE) {
                        blockedUntilEnd = true
                        release("已达到本轮保护时限")
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
                release("媒体控制申请未确认")
            }
        }
    }

    private fun unavailableReason(): String? {
        val state = DeviceRepository.state.value
        return when {
            mediaPhase == MediaPhase.STOPPING -> "正在结束对话 · 等待媒体恢复确认"
            mediaPhase == MediaPhase.UNCONFIRMED -> "媒体恢复尚未确认 · 已暂停本功能"
            !live || !state.connected -> "等待耳机控制连接"
            localOffRequested -> "手机联动已停止"
            0x28 in state.pending -> "等待耳机确认"
            state.controls[0x28]?.firstOrNull() == null -> "等待耳机报告对话配置"
            state.controls[0x28]?.firstOrNull() != 1 -> "对话感知已关闭"
            (wear?.inEarCount ?: 0) == 0 -> "请佩戴耳机"
            audio.mode != AudioManager.MODE_NORMAL -> "通话期间暂停对话联动"
            !routeMatches(state.address) -> "请将媒体输出切换到这副 AirPods"
            // Track shaping never pauses/resumes a player, so stopped playback can safely release.
            !audio.isMusicActive -> "等待媒体播放"
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
        ?: if (blockedUntilEnd) "本轮处理已停止 · 等待下一次对话"
        else "试验功能 · 等待真实讲话事件"

    private fun activeDescription() = when {
        mediaPhase == MediaPhase.STARTING -> "正在交谈 · 等待媒体音轨确认"
        hostActive && players > 0 -> "正在交谈"
        else -> "正在交谈 · 等待可控制的媒体音轨"
    }

    private fun refresh() {
        if (closed) return
        if (policy.active && !playbackStopObserved && !audio.isMusicActive) {
            playbackStopObserved = true
            update { it.copy(playbackStopsWhileHeld = it.playbackStopsWhileHeld + 1) }
            DeviceRepository.log("对话音轨：媒体播放停止，解除本轮控制")
        }
        val reason = unavailableReason()
        if (policy.check(reason == null, SystemClock.elapsedRealtime()) == ConversationAction.RELEASE) {
            blockedUntilEnd = true
            release(reason ?: "已达到本轮保护时限")
        } else if (!policy.active) publish(idleDescription())
        schedule()
    }

    private fun release(reason: String, eventReceivedAt: Long? = null) {
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
                                DeviceRepository.log("对话计时：结束（${if (eventReceivedAt != null) "事件" else "清理"}），请求到提交 ${submittedMs}ms，回执 ${ackAt - requestedAt}ms")
                                DeviceRepository.log("对话音轨：宿主确认本轮控制已停止")
                                publish(releaseDescription)
                            } else {
                                if (!sameSession) mediaStatus = "停止回执会话不匹配 $replySession/$mediaSession：$mediaStatus"
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
        DeviceRepository.log("对话音轨：恢复待确认，$mediaStatus")
        publish(idleDescription())
    }

    private fun publish(description: String) = update {
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
            release("耳机服务已停止")
            closed = true
            ++mediaGeneration
            handler.removeCallbacks(check)
            // The bridge owns pending Binder work and must STOP even after our callbacks are invalidated.
            runCatching { bridge.close() }.onFailure { DeviceRepository.log("对话桥接关闭失败：${it.message}") }
            if (playbackRegistered) runCatching { audio.unregisterAudioPlaybackCallback(playback) }
            if (devicesRegistered) runCatching { audio.unregisterAudioDeviceCallback(devices) }
        }
    }
}
