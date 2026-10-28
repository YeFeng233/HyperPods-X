package com.hyper.podsrx

import android.app.Application
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.FrameworkBridge
import com.hyper.podsrx.ui.AppearancePreferences
import com.hyper.podsrx.diagnostics.DiagnosticLogs

class HyperPodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogs.initialize(this)
        AppearancePreferences.initialize(this)
        DeviceRepository.initialize(this)
        FrameworkBridge.initialize()
    }
}
