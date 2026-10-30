package com.hyper.podsrx.device

import com.hyper.podsrx.R
import com.hyper.podsrx.hook.HubWire
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.widget.WidgetPresence
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object FrameworkBridge {
    @Volatile var service: XposedService? = null
        private set
    fun initialize() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                DeviceRepository.update { it.copy(framework = text("${bound.frameworkName} · API ${bound.apiVersion}"), frameworkApi = bound.apiVersion) }
                publish()
                HostReloadCoordinator.onServiceBound(bound)
            }
            override fun onServiceDied(bound: XposedService) {
                if (service === bound) {
                    service = null
                    HostReloadCoordinator.onServiceDied()
                    DeviceRepository.update { it.copy(framework = text(R.string.module_service_disconnected), frameworkApi = 0) }
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
                ?.putBoolean("battery_alerts", s.batteryAlerts)
                ?.putBoolean("auto_connect", s.autoConnect)
                // Known from an advertisement before the first session reports the model.
                ?.putInt("panel_product", s.product)
                // The hub wakes this app for battery changes only while a widget is bound.
                ?.putBoolean(HubWire.WIDGET_PREF, WidgetPresence.installed)
                ?.putBoolean("enabled", true)?.apply()
        }
    }
    fun requestNativeScope() {
        val bound = service ?: return DeviceRepository.message(R.string.enable_module_first)
        bound.requestScope(listOf("com.android.settings","com.xiaomi.bluetooth","com.milink.service","com.miui.securitycenter"),object:XposedService.OnScopeEventListener{
            override fun onScopeRequestApproved(approved:MutableList<String>){
                DeviceRepository.message(R.string.scope_authorized_checking)
                HostReloadCoordinator.onScopeApproved(bound, approved)
            }
            override fun onScopeRequestFailed(message:String){DeviceRepository.message(message)}
        })
    }
}
