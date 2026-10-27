package com.hyper.podsrx.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class MediaVolumeState(val level: Int = 0, val maximum: Int = 1) {
    val fraction: Float get() = level.toFloat() / maximum.coerceAtLeast(1)
}

/** Uses the current media route; earbud chime volume is a separate AACP setting. */
class MediaVolumeController(context: Context) {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        this.context.registerReceiver(receiver, IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED")
        }, Context.RECEIVER_EXPORTED)
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
    }

    private fun read(): MediaVolumeState {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return MediaVolumeState(audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maximum), maximum)
    }

    fun refresh() {
        if (!closed) mutable.value = read()
    }

    fun setFraction(value: Float) {
        if (closed || !value.isFinite()) return
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val level = (value.coerceIn(0f, 1f) * maximum).roundToInt()
        if (level != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
                .onFailure { DeviceRepository.message("无法调节媒体音量") }
        }
        refresh()
    }

    fun close() {
        if (closed) return
        closed = true
        context.unregisterReceiver(receiver)
        audio.unregisterAudioDeviceCallback(devices)
    }
}
