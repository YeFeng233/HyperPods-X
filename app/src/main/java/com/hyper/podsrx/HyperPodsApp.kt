package com.hyper.podsrx

import android.app.Application
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.FrameworkBridge

class HyperPodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceRepository.initialize(this)
        FrameworkBridge.initialize()
    }
}
