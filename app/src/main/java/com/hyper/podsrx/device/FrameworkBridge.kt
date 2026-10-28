package com.hyper.podsrx.device

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
        runCatching {
            service?.getRemotePreferences("device_state")?.edit()
                ?.putString("address", s.address)
                ?.putString("summary", DeviceRepository.batterySummary())
                ?.putLong("updated",System.currentTimeMillis())
                ?.putBoolean("native_connected",s.connected)
                ?.putInt("native_left",s.batteries[4]?.takeIf{it.current}?.level?:-1)
                ?.putInt("native_right",s.batteries[2]?.takeIf{it.current}?.level?:-1)
                ?.putInt("native_box",s.batteries[8]?.takeIf{it.current}?.level?:-1)
                ?.putInt("native_box_history",s.batteries[8]?.level?:-1)
                ?.putInt("native_charging",listOf(4 to 1,2 to 2,8 to 4).sumOf{(id,mask)->if(s.batteries[id]?.let{it.current&&it.charging}==true)mask else 0})
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
        service?.requestScope(listOf("com.xiaomi.bluetooth"),object:XposedService.OnScopeEventListener{
            override fun onScopeRequestApproved(approved:MutableList<String>){DeviceRepository.message("小米蓝牙作用域已授权，重新启动小米蓝牙扩展后生效")}
            override fun onScopeRequestFailed(message:String){DeviceRepository.message(message)}
        })?:DeviceRepository.message("请在 LSPosed 中为 HyperPods-X 勾选“小米蓝牙”")
    }
}
