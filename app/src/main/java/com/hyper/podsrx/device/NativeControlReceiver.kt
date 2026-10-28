package com.hyper.podsrx.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.hyper.podsrx.hook.HeadsetModels
import com.hyper.podsrx.hook.NativeControlWire

/** Only the app-issued mutable PendingIntent can reach this private endpoint. */
class NativeControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NativeControlWire.COMMAND) return
        val age = SystemClock.elapsedRealtime() - intent.getLongExtra("created", -1)
        if (age !in 0..2_000) return
        val state = DeviceRepository.state.value
        if (!state.connected || state.controlRefreshUntil != 0L || !HeadsetModels.nativeControls(state.model) ||
            !state.address.equals(intent.getStringExtra("address"), true)) return
        val value = intent.getIntExtra("value", -1)
        when (intent.getStringExtra("command")) {
            "anc" -> if (value in 1..4 && 0x0d in state.controls && 0x0d !in state.pending) {
                DeviceRepository.log("Native panel ANC request: $value")
                PodsService.setControl(0x0d, value)
            }
            "spatial" -> if (value in 0..1 && !state.spatial.modeChanging) {
                DeviceRepository.log("Native panel spatial request: $value")
                PodsService.spatialMode(if (value == 0) SpatialMode.OFF else SpatialMode.FIXED)
            }
        }
    }
}
