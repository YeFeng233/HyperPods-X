package com.hyper.podsrx.device

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hyper.podsrx.protocol.Battery
import com.hyper.podsrx.protocol.Event
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

data class Reading(val level: Int?, val charging: Boolean, val precise: Boolean, val updated: Long, val current: Boolean)
data class PodsState(
    val name: String = "AirPods", val address: String = "", val model: String = "", val firmware: String = "",
    val status: String = "准备连接耳机", val connected: Boolean = false, val running: Boolean = false,
    val framework: String = "等待模块服务", val frameworkApi: Int = 0,
    val batteries: Map<Int, Reading> = emptyMap(), val controls: Map<Int, List<Int>> = emptyMap(),
    val pending: Set<Int> = emptySet(), val message: String = "", val identityReady: Boolean = false,
    val broadcasts: Int = 0, val trustedBroadcasts: Int = 0, val popupEnabled: Boolean = true,
    val earAutomation: Boolean = false, val lastPacket: Long = 0, val log: List<String> = emptyList()
    ,val equalizer: List<Int>? = null, val proximityCandidates: Int = 0, val identityMatches: Int = 0
    ,val cardStatus: List<Int> = emptyList()
    ,val connectionPopup: Boolean = true
    ,val diagnosticScanning:Boolean=false
    ,val lidOpen:Boolean?=null
    ,val nativePopup:Boolean=true
    ,val nativePopupStatus:String="等待原生弹窗连接"
    ,val spatial:SpatialState=SpatialState()
    ,val localName:String=""
    ,val wearing:Event.Wearing?=null
    ,val earDetectionStatus:String="等待耳机上报佩戴状态"
    ,val conversation:ConversationState=ConversationState()
) {
    val displayName:String get()=localName.ifBlank{name}
}
object DeviceRepository {
    lateinit var app: Application
        private set
    lateinit var keys: SecureKeys
        private set
    private val mutable = MutableStateFlow(PodsState())
    val state = mutable.asStateFlow()
    fun initialize(application: Application) {
        app = application
        keys = SecureKeys(app)
        val p = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        update { it.copy(address = p.getString("address", "")!!, name = p.getString("name", "AirPods")!!,
            popupEnabled = p.getBoolean("popup",true), earAutomation = p.getBoolean("earAutomation",false),connectionPopup=p.getBoolean("connectionPopup",true),nativePopup=p.getBoolean("nativePopup",true)) }
        val history=app.getSharedPreferences("battery_history",0)
        if(history.getString("address",null)==state.value.address){
            val last=mutableMapOf<Int,Reading>()
            for(id in listOf(2,4,8))if(history.contains("level_$id"))last[id]=Reading(history.getInt("level_$id",0),false,
                history.getBoolean("precise_$id",false),history.getLong("updated_$id",0),false)
            update{it.copy(batteries=last)}
        }
    }
    fun update(block: (PodsState) -> PodsState) { mutable.update(block) }
    fun message(text: String) { update { it.copy(message = text) }; log(text);saveDiagnostics() }
    fun log(text: String) {
        Log.i("HyperPods-X", text)
        update { it.copy(log = (it.log + text).takeLast(30)) }
    }
    fun permissionsGranted() = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.ACCESS_FINE_LOCATION)
        .all { ContextCompat.checkSelfPermission(app,it) == PackageManager.PERMISSION_GRANTED }
    @Suppress("MissingPermission")
    fun refreshLocalName() {
        val address=state.value.address
        if(address.isBlank()||ContextCompat.checkSelfPermission(app,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return
        val name=runCatching{app.getSystemService(BluetoothManager::class.java).adapter?.getRemoteDevice(address)?.alias.orEmpty()}.getOrNull()?:return
        update{if(it.address==address)it.copy(localName=name)else it}
    }
    @Suppress("MissingPermission")
    fun candidates(): List<BluetoothDevice> {
        if (!permissionsGranted()) return emptyList()
        return app.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty().filter { d ->
            d.uuids.orEmpty().any { it.uuid.toString().equals("74ec2172-0bad-4d01-8f77-997b2be0722a",true) }
        }
    }
    @Suppress("MissingPermission")
    fun bind(device: BluetoothDevice) {
        if (state.value.running) stop()
        app.getSharedPreferences("app",Context.MODE_PRIVATE).edit().putString("address",device.address).putString("name",device.name ?: "AirPods").apply()
        update { PodsState(name=device.name ?: "AirPods", address=device.address,framework=it.framework,frameworkApi=it.frameworkApi,popupEnabled=it.popupEnabled,earAutomation=it.earAutomation,connectionPopup=it.connectionPopup,nativePopup=it.nativePopup) }
        refreshLocalName()
        FrameworkBridge.publish()
        start()
    }
    fun start() {
        if (!permissionsGranted()) { message("请授权附近设备权限"); return }
        if (state.value.address.isEmpty()) {
            val devices = candidates()
            if (devices.size == 1) { bind(devices.single()); return }
            message(if (devices.isEmpty()) "请先在系统蓝牙中配对 AirPods" else "请选择要管理的 AirPods"); return
        }
        runCatching { ContextCompat.startForegroundService(app,Intent(app,PodsService::class.java)) }
            .onFailure { message("服务启动失败：${it.javaClass.simpleName}") }
    }
    fun stop() { app.stopService(Intent(app,PodsService::class.java)) }
    fun setPopup(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("popup",enabled).apply(); update{it.copy(popupEnabled=enabled)}
    }
    fun setConnectionPopup(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("connectionPopup",enabled).apply();update{it.copy(connectionPopup=enabled)}
    }
    fun setNativePopup(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("nativePopup",enabled).apply();update{it.copy(nativePopup=enabled)}
    }
    fun setEarAutomation(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("earAutomation",enabled).apply(); update{it.copy(earAutomation=enabled)}
        PodsService.earAutomationChanged()
    }
    fun batteries(values:List<Battery>, precise:Boolean, current:Boolean=true) {
        val now=System.currentTimeMillis()
        update { s ->
            val result=s.batteries.toMutableMap()
            for(b in values) {
                val old=result[b.component]
                if(b.level!=null) {
                    // A recent precise control report wins over a coarse advertisement.
                    if (!precise && old?.precise==true && now-old.updated<30000) continue
                    result[b.component]=Reading(b.level,b.charging,precise,now,current && b.connected)
                } else if(old!=null) result[b.component]=old.copy(current=false)
            }
            s.copy(batteries=result)
        }
        val editor=app.getSharedPreferences("battery_history",0).edit().putString("address",state.value.address)
        for((id,b)in state.value.batteries)if(b.level!=null){editor.putInt("level_$id",b.level).putBoolean("precise_$id",b.precise).putLong("updated_$id",b.updated)}
        editor.apply()
        FrameworkBridge.publish(); saveDiagnostics()
    }
    fun batterySummary():String = listOf(4 to "左",2 to "右",8 to "盒").joinToString(" · ") { (id,label) ->
        val b=state.value.batteries[id]
        "$label ${b?.level?.let { "${if(b.precise) "" else "约"}$it%${if(b.current) "" else "（上次）"}" } ?: "—"}"
    }
    @Synchronized fun saveDiagnostics() {
        runCatching {
            val s=state.value
            val json=JSONObject().put("name",s.name).put("model",s.model).put("firmware",s.firmware)
                .put("status",s.status).put("connected",s.connected).put("framework",s.framework)
                .put("identityReady",s.identityReady).put("broadcasts",s.broadcasts).put("trustedBroadcasts",s.trustedBroadcasts)
                .put("proximityCandidates",s.proximityCandidates).put("identityMatches",s.identityMatches).put("cardStatus",s.cardStatus.joinToString(","))
                .put("lidOpen",s.lidOpen)
                .put("nativePopup",s.nativePopup).put("nativePopupStatus",s.nativePopupStatus)
                .put("earAutomation",s.earAutomation).put("earDetectionStatus",s.earDetectionStatus)
                .put("wearingPrimary",s.wearing?.primary?.name).put("wearingSecondary",s.wearing?.secondary?.name)
                .put("spatial",s.spatial.diagnostic())
                .put("conversation",s.conversation.diagnostic())
                .put("equalizer",s.equalizer?.joinToString(","))
                .put("battery",batterySummary()).put("controls",JSONObject(s.controls.mapKeys{it.key.toString()}.mapValues{it.value.joinToString(",")}))
                .put("pending",s.pending.joinToString(",")).put("message",s.message).put("log",s.log.joinToString("\n"))
            val file=android.util.AtomicFile(File(app.filesDir,"diagnostics.json"))
            val output=file.startWrite()
            try {output.write(json.toString(2).toByteArray());file.finishWrite(output)}
            catch(error:Exception){file.failWrite(output);throw error}
        }
    }
}
