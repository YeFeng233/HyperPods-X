package com.hyper.podsrx.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import com.hyper.podsrx.hook.HubWire

/** The hub's content notice. The state travels with it; only the Bluetooth host may send it. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HubWire.CHANGED) return
        if (sentFromUid != Process.BLUETOOTH_UID) return
        val snapshot = WidgetStore.of(intent.getBundleExtra(HubWire.EXTRA_STATE)) ?: return
        WidgetStore.write(context, snapshot)
        WidgetRegistry.update(context)
    }
}
