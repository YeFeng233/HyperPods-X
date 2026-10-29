package com.hyper.podsrx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.FrameworkBridge
import com.hyper.podsrx.hook.HubWire
import com.hyper.podsrx.ui.AppearancePreferences
import com.hyper.podsrx.diagnostics.DiagnosticLogs

class HyperPodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogs.initialize(this)
        AppearancePreferences.initialize(this)
        DeviceRepository.initialize(this)
        FrameworkBridge.initialize()
        prepareBatteryAlerts()
    }

    /**
     * The hub in the Bluetooth host posts the battery alerts as this app, on this channel. The
     * delegate is kept by the system, so the alerts keep coming once the app is gone.
     */
    private fun prepareBatteryAlerts() = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(HubWire.ALERT_CHANNEL, "电量提醒", NotificationManager.IMPORTANCE_HIGH))
        if (manager.notificationDelegate != HubWire.HOST) manager.notificationDelegate = HubWire.HOST
    }
}
