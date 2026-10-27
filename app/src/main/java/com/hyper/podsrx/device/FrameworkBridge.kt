package com.hyper.podsrx.device

import android.os.SystemClock
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object FrameworkBridge {
    @Volatile var service: XposedService? = null
        private set
    fun initialize() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                DeviceRepository.update { it.copy(framework = "${bound.frameworkName} · API ${bound.apiVersion}", frameworkApi = bound.apiVersion) }
                publish()
            }
            override fun onServiceDied(bound: XposedService) {
                if (service === bound) service = null
                DeviceRepository.update { it.copy(framework = "模块服务已断开", frameworkApi = 0) }
            }
        })
    }
    fun publish() {
        val s = DeviceRepository.state.value
        // AACP reports primary/secondary ears, so a single in-ear event does not
        // establish which physical side is wearing the earbud.
        val wearCount = s.wearing?.inEarCount ?: -1
        val sideWearing = when (wearCount) { 0 -> 0; 2 -> 1; else -> -1 }
        runCatching {
            service?.getRemotePreferences("device_state")?.edit()
                ?.putString("address", s.address)
                ?.putString("summary", DeviceRepository.batterySummary())
                ?.putLong("updated",System.currentTimeMillis())
                ?.putBoolean("native_connected",s.connected)
                ?.putInt("native_left",s.batteries[4]?.takeIf{it.current}?.level?:-1)
                ?.putInt("native_right",s.batteries[2]?.takeIf{it.current}?.level?:-1)
                ?.putInt("native_box",s.batteries[8]?.takeIf{it.current}?.level?:-1)
                ?.putInt("native_charging",listOf(4 to 1,2 to 2,8 to 4).sumOf{(id,mask)->if(s.batteries[id]?.let{it.current&&it.charging}==true)mask else 0})
                ?.putString("panel_model", s.model)
                ?.putInt("panel_anc", s.controls[0x0d]?.firstOrNull() ?: 0)
                ?.putBoolean("panel_anc_available", 0x0d in s.controls)
                ?.putBoolean("panel_anc_pending", 0x0d in s.pending)
                ?.putInt("panel_ear_detection", s.controls[0x0a]?.firstOrNull() ?: 0)
                ?.putInt("panel_one_bud_anc", s.controls[0x1b]?.firstOrNull() ?: 0)
                ?.putInt("panel_off_mode", s.controls[0x34]?.firstOrNull() ?: 0)
                ?.putBoolean("panel_spatial_enabled", s.spatial.mode in listOf(SpatialMode.FIXED, SpatialMode.HEAD_TRACKING))
                ?.putBoolean("panel_spatial_available", s.spatial.bridge && s.spatial.capable && s.spatial.route)
                ?.putBoolean("panel_spatial_pending", s.spatial.modeChanging)
                ?.putInt("panel_wear_count", wearCount)
                ?.putInt("panel_left_wearing", sideWearing)
                ?.putInt("panel_right_wearing", sideWearing)
                ?.putLong("panel_heartbeat", SystemClock.elapsedRealtime())
                ?.putBoolean("enabled", true)?.apply()
        }
    }
    fun requestSettingsScope() {
        service?.requestScope(listOf("com.android.settings"), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: MutableList<String>) { DeviceRepository.message("系统设置作用域已授权，请重新打开系统设置") }
            override fun onScopeRequestFailed(message: String) { DeviceRepository.message(message) }
        }) ?: DeviceRepository.message("请先在 LSPosed 中启用 HyperPods-X")
    }
    fun requestNativeScope() {
        service?.requestScope(listOf("com.xiaomi.bluetooth","com.milink.service"),object:XposedService.OnScopeEventListener{
            override fun onScopeRequestApproved(approved:MutableList<String>){DeviceRepository.message("系统原生适配已授权，重新启动小米蓝牙与设备互联后生效")}
            override fun onScopeRequestFailed(message:String){DeviceRepository.message(message)}
        })?:DeviceRepository.message("请在 LSPosed 中勾选“小米蓝牙”和“设备互联”")
    }
}
