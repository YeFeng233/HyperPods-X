/* SPDX-License-Identifier: GPL-3.0-or-later
 * Media-key playback follows LibrePods' presence-transition approach.
 * Copyright (C) 2025 LibrePods contributors; adapted for HyperPods-X, 2026.
 * See THIRD_PARTY.md. No Bluetooth profile or global audio policy is modified.
 */
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
import android.view.KeyEvent
import com.hyper.podsrx.protocol.EarPlaybackAction
import com.hyper.podsrx.protocol.EarPlaybackPolicy
import com.hyper.podsrx.protocol.Event

/** Serialize sensor reports, playback callbacks and preference changes on the main thread. */
class EarDetectionController(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val policy = EarPlaybackPolicy()
    private var latest: Event.Wearing? = null
    private var address = ""
    private var closed = false
    private var playbackRegistered = false
    private var devicesRegistered = false
    private var routeErrorReported = false
    private val check = Runnable { checkPlayback() }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = checkPlayback()
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkPlayback()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkPlayback()
    }

    init {
        runCatching { audio.registerAudioPlaybackCallback(playback, handler); playbackRegistered = true }
            .onFailure { DeviceRepository.log("佩戴检测：播放状态监听不可用") }
        runCatching { audio.registerAudioDeviceCallback(devices, handler); devicesRegistered = true }
            .onFailure { DeviceRepository.log("佩戴检测：音频输出监听不可用") }
    }

    fun wearing(event: Event.Wearing, deviceAddress: String) = onMain {
        if (deviceAddress != DeviceRepository.state.value.address || !DeviceRepository.state.value.connected) return@onMain
        if (address != deviceAddress) { policy.reset(); address = deviceAddress }
        if (latest != event) DeviceRepository.log("佩戴状态：${event.primary.name} / ${event.secondary.name}（主 / 副耳）")
        latest = event
        DeviceRepository.update { it.copy(wearing = event) }
        val eligible = eligible()
        val action = policy.wearing(event.inEarCount, audio.isMusicActive, eligible, SystemClock.elapsedRealtime())
        dispatch(action)
        scheduleCheck()
        DeviceRepository.saveDiagnostics()
    }

    fun preferenceChanged() = onMain {
        policy.reset()
        handler.removeCallbacks(check)
        checkPlayback()
    }

    fun disconnected() = onMain {
        policy.reset()
        latest = null
        address = ""
        handler.removeCallbacks(check)
        DeviceRepository.update { it.copy(wearing = null, earDetectionStatus = "等待耳机控制连接") }
    }

    private fun eligible(): Boolean {
        val state = DeviceRepository.state.value
        val connected = state.connected && address == state.address
        val normalMode = audio.mode == AudioManager.MODE_NORMAL
        val routed = state.earAutomation && connected && latest != null && normalMode && routeMatches(state.address)
        val status = when {
            !state.earAutomation -> "自动入耳检测已关闭"
            !connected -> "等待耳机控制连接"
            latest == null -> "等待耳机上报佩戴状态"
            !normalMode -> "通话期间不控制媒体播放"
            !routed -> "请将媒体输出切换到这副 AirPods"
            else -> "检测已就绪 · 摘下一只暂停，戴回继续"
        }
        DeviceRepository.update { if (it.earDetectionStatus == status) it else it.copy(earDetectionStatus = status) }
        return routed
    }

    private fun routeMatches(target: String): Boolean = runCatching {
        // A connected output is not necessarily the selected media route.
        audio.getAudioDevicesForAttributes(attributes).any {
            it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                it.address.equals(target, ignoreCase = true)
        }
    }.onFailure {
        if (!routeErrorReported) {
            routeErrorReported = true
            DeviceRepository.log("佩戴检测：无法查询媒体输出 ${it.javaClass.simpleName}")
        }
    }.getOrDefault(false)

    private fun checkPlayback() {
        if (closed) return
        val eligible = eligible()
        val action = if (policy.inEarCount == null && latest != null) {
            policy.wearing(latest!!.inEarCount, audio.isMusicActive, eligible, SystemClock.elapsedRealtime())
        } else policy.playback(audio.isMusicActive, eligible, SystemClock.elapsedRealtime())
        dispatch(action)
        scheduleCheck()
    }

    private fun dispatch(action: EarPlaybackAction) {
        if (action == EarPlaybackAction.NONE) return
        val key = if (action == EarPlaybackAction.PAUSE) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
        runCatching {
            val time = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0))
            DeviceRepository.log(if (action == EarPlaybackAction.PAUSE) "佩戴检测：已发送暂停" else "佩戴检测：已发送恢复播放")
        }.onFailure {
            policy.reset()
            DeviceRepository.log("佩戴检测：媒体指令失败 ${it.javaClass.simpleName}")
        }
        DeviceRepository.saveDiagnostics()
    }

    private fun scheduleCheck() {
        handler.removeCallbacks(check)
        // A short poll catches asynchronous pause acknowledgement and route changes.
        if (policy.ownsPause) handler.postDelayed(check, 200)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) { if (!closed) block() }
        else handler.post { if (!closed) block() }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (playbackRegistered) audio.unregisterAudioPlaybackCallback(playback)
        if (devicesRegistered) audio.unregisterAudioDeviceCallback(devices)
        policy.reset()
    }
}
