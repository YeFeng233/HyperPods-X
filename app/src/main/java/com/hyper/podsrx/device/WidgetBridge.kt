package com.hyper.podsrx.device

import android.content.Intent
import android.os.SystemClock
import com.hyper.podsrx.hook.WidgetWire

/**
 * Feeds the security center "device battery" widget. The case rides the camera-grip slot and the
 * headset slot comes from the widget provider, where the module answers for the bound AirPods
 * while the system reports none. Both refresh broadcasts are refused to apps, so the app only
 * publishes the slots; a new sequence asks the Bluetooth host to send them.
 */
object WidgetBridge {
    private const val HOST = "com.xiaomi.bluetooth"
    data class Slots(val headset: Int, val case: Int, val charging: Boolean, val sequence: Long)

    // The widget forgets the grip when the launcher restarts; a slow repeat brings it back.
    private const val RESEND_MS = 60000L
    // Starts apart from the last process's sequence so the host always notices the first update.
    private var slots = Slots(-1, -1, false, SystemClock.elapsedRealtime())
    private var sentAt = 0L
    private var delivered = Long.MIN_VALUE

    fun headsetLevel(state: PodsState): Int? {
        val levels = listOfNotNull(state.batteries[4]?.level, state.batteries[2]?.level).filter { it in 0..100 }
        return if (levels.isEmpty()) null else levels.sum() / levels.size
    }

    @Synchronized fun slots(state: PodsState): Slots {
        val headset = headsetLevel(state)?.takeIf { state.running } ?: -1
        val reading = state.batteries[8]?.takeIf { state.running && it.level in 0..100 }
        val case = reading?.level ?: -1
        val charging = reading?.charging == true
        val now = SystemClock.elapsedRealtime()
        if (headset != slots.headset || case != slots.case || charging != slots.charging || case >= 0 && now - sentAt > RESEND_MS) {
            slots = Slots(headset, case, charging, slots.sequence + 1)
            sentAt = now
        }
        return slots
    }

    /**
     * The ROM freezes the Bluetooth host between lid openings, and preference callbacks wait for it
     * to thaw; a foreground broadcast wakes it for each new sequence.
     */
    @Synchronized fun deliver(slots: Slots) {
        if (slots.sequence == delivered) return
        delivered = slots.sequence
        runCatching {
            DeviceRepository.app.sendBroadcast(Intent(WidgetWire.ACTION).setPackage(HOST)
                .putExtra(WidgetWire.SEQUENCE, slots.sequence)
                .putExtra(WidgetWire.HEADSET, slots.headset)
                .putExtra(WidgetWire.CASE, slots.case)
                .putExtra(WidgetWire.CASE_CHARGING, slots.charging)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
        }
    }
}
