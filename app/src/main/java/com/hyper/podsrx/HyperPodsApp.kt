package com.hyper.podsrx

import android.app.Application
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.FrameworkBridge
import com.hyper.podsrx.ui.AppearancePreferences

class HyperPodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppearancePreferences.initialize(this)
        DeviceRepository.initialize(this)
        FrameworkBridge.initialize()
    }
}
