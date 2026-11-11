package com.hyper.podsrx.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Prevents the control channel from paging a headset away from another active audio route. */
object BluetoothRouteGuard {
    fun hasCompetingOutput(context: Context, boundAddress: String): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        if (audio.mode != AudioManager.MODE_NORMAL) return true
        return runCatching {
            val devices = buildList {
                addAll(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList())
                audio.communicationDevice?.let(::add)
            }
            devices.any { device ->
                if (!isBluetoothOutput(device.type)) return@any false
                val address = device.address.orEmpty()
                address.isBlank() || !address.equals(boundAddress, ignoreCase = true)
            }
        }.getOrElse { true }
    }

    private fun isBluetoothOutput(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        type == AudioDeviceInfo.TYPE_HEARING_AID
}
