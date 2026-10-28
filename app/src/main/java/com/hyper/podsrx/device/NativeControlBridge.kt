package com.hyper.podsrx.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.hyper.podsrx.hook.HeadsetModels
import com.hyper.podsrx.hook.NativeControlWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Publishes the current AACP session without giving the system a second socket. */
class NativeControlBridge(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleLock = Any()
    private var closed = false
    private val target = PendingIntent.getBroadcast(
        this.context, 25,
        Intent(this.context, NativeControlReceiver::class.java).setAction(NativeControlWire.COMMAND),
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    init {
        scope.launch {
            DeviceRepository.state.map {
                listOf(it.address, it.model, it.connected, it.running, it.batteries,
                    it.controls[0x0d], 0x0d in it.pending, it.controls[0x0a],
                    it.controls[0x1b], it.controls[0x34], it.spatial.mode, it.spatial.modeChanging,
                    it.spatial.bridge, it.spatial.capable, it.spatial.route, it.wearing, it.controlRefreshUntil)
            }.distinctUntilChanged().collect {
                publishAndRegister()
            }
        }
        scope.launch {
            while (isActive) {
                delay(10_000)
                publishAndRegister()
            }
        }
    }

    private fun publishAndRegister() = synchronized(lifecycleLock) {
        if (closed) return@synchronized
        FrameworkBridge.publish()
        val state = DeviceRepository.state.value
        if (state.address.isBlank()) return@synchronized
        runCatching {
            // The host requires NATIVE_POPUP from the sender. The callback itself
            // targets an unexported receiver and can only be used by its holder.
            context.sendBroadcast(Intent(NativeControlWire.REGISTER)
                .setPackage(NativeControlWire.HOST)
                .putExtra("created", SystemClock.elapsedRealtime())
                .putExtra("address", state.address)
                .putExtra("target", target)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            closed = true
            scope.cancel()
            target.cancel()
        }
    }
}

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
