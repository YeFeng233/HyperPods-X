package com.hyper.podsrx.widget

import android.content.Context
import android.content.Intent
import com.hyper.podsrx.hook.HubWire

/**
 * Asks the Bluetooth host's hub for the current snapshot. The ROM's headset provider answers
 * scoped system hosts only, so the state travels over the hub's own notice instead.
 */
internal object WidgetHub {
    fun requestSnapshot(context: Context) {
        runCatching { context.sendBroadcast(Intent(HubWire.SNAPSHOT).setPackage(HubWire.HOST)) }
    }
}
