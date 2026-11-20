package com.hyper.podsrx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import com.hyper.podsrx.device.AirPodsBatteryWidget
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
        if (HubWire.isAosp(this)) {
            val widget = ComponentName(this, AirPodsBatteryWidget::class.java)
            if (packageManager.getComponentEnabledSetting(widget) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                packageManager.setComponentEnabledSetting(widget, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
    }

    /**
     * The hub in the Bluetooth host posts the battery alerts as this app, on this channel. The
     * delegate is kept by the system, so the alerts keep coming once the app is gone.
     */
    private fun prepareBatteryAlerts() = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(HubWire.ALERT_CHANNEL, "电量提醒", NotificationManager.IMPORTANCE_HIGH))
        val host = HubWire.bluetoothHost(this)
        if (manager.notificationDelegate != host) manager.notificationDelegate = host
    }
}
