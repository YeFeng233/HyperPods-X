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
                HostReloadCoordinator.onServiceBound(bound)
            }
            override fun onServiceDied(bound: XposedService) {
                if (service === bound) {
                    service = null
                    HostReloadCoordinator.onServiceDied()
                    DeviceRepository.update { it.copy(framework = "模块服务已断开", frameworkApi = 0) }
                }
            }
        })
    }
    /**
     * Writes configuration only. Live state belongs to the Bluetooth host's hub, which every other
     * host reads; preferences written here reach them late and outlive the app.
     */
    fun publish() {
        val s = DeviceRepository.state.value
        runCatching {
            service?.getRemotePreferences("device_state")?.edit()
                ?.putString("address", s.address)
                ?.putString("name", s.displayName)
                ?.putBoolean("popup_enabled", s.popupEnabled)
                ?.putBoolean("native_popup", s.nativePopup)
                // Known from an advertisement before the first session reports the model.
                ?.putInt("panel_product", s.product)
                ?.putBoolean("enabled", true)?.apply()
        }
    }
    fun requestNativeScope() {
        val bound = service ?: return DeviceRepository.message("请先在 LSPosed 中启用 HyperPods-X")
        bound.requestScope(listOf("com.android.settings","com.xiaomi.bluetooth","com.milink.service","com.miui.securitycenter"),object:XposedService.OnScopeEventListener{
            override fun onScopeRequestApproved(approved:MutableList<String>){
                DeviceRepository.message("作用域已授权，正在检查宿主加载状态")
                HostReloadCoordinator.onScopeApproved(bound, approved)
            }
            override fun onScopeRequestFailed(message:String){DeviceRepository.message(message)}
        })
    }
}
