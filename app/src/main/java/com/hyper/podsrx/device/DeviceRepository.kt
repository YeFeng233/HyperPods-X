package com.hyper.podsrx.device

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.DiagnosticLog
import com.hyper.podsrx.hook.HeadsetModels
import com.hyper.podsrx.hook.HubBundles
import com.hyper.podsrx.hook.LogRedactor
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.protocol.Battery
import com.hyper.podsrx.protocol.BatteryLedger
import com.hyper.podsrx.protocol.Event
import com.hyper.podsrx.protocol.Reading
import com.hyper.podsrx.protocol.content
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class PodsState(
    val name: String = "AirPods", val address: String = "", val model: String = "", val firmware: String = "",
    val internalFirmware: String = "", val firmwareIdentifier: String = "",
    val status: UiText = text(R.string.ready_to_connect), val connected: Boolean = false, val running: Boolean = false,
    val framework: UiText = text(R.string.waiting_module_service), val frameworkApi: Int = 0,
    val batteries: Map<Int, Reading> = emptyMap(), val controls: Map<Int, List<Int>> = emptyMap(),
    val pending: Set<Int> = emptySet(), val message: String = "", val identityReady: Boolean = false,
    @param:StringRes val messageCode: Int = 0, val messageControl: Int? = null,
    val messageText: UiText = text(""), val messageRevision: Long = 0,
    val broadcasts: Int = 0, val trustedBroadcasts: Int = 0, val popupEnabled: Boolean = true,
    val earAutomation: Boolean = false, val lastPacket: Long = 0, val log: List<String> = emptyList()
    ,val equalizer: List<Int>? = null, val proximityCandidates: Int = 0, val identityMatches: Int = 0
    ,val cardStatus: List<Int> = emptyList()
    ,val connectionPopup: Boolean = true
    ,val autoConnect: Boolean = true
    ,val lidOpen:Boolean?=null
    ,val nativePopup:Boolean=true
    ,val nativePopupStatus:UiText=text(R.string.waiting_native_popup)
    ,val batteryAlerts:Boolean=true
    ,val spatial:SpatialState=SpatialState()
    ,val localName:String=""
    ,val wearing:Event.Wearing?=null
    ,val earDetectionStatus: UiText = text(R.string.waiting_wearing_report)
    ,val conversation:ConversationState=ConversationState()
    ,val adaptiveTarget:Int?=null
    ,val controlRefreshUntil:Long=0L
    ,val product:Int=-1
    ,val hub:HubBundles.State?=null
    ,val hubStatus: UiText = text(R.string.hub_disconnected)
    /** Process to component to "" when its hook installed, or the failure's class name. */
    ,val hostCapabilities:Map<String,Map<String,String>> = emptyMap()
) {
    val displayName:String get()=localName.ifBlank{name}
    /** Calibration stays tied to the reported build even without a public firmware label. */
    val firmwareIdentity:String get()=internalFirmware.ifBlank{firmware.ifBlank{firmwareIdentifier}}
    val headset:HeadsetModels.Model get()=HeadsetModels.of(model,product)
    /** The hub's link and the scan it runs, for the diagnostics page. */
    fun hubSummary(strings: Strings):String = hub?.let{h->"${hubStatus.resolve(strings)} · "+when(h.scanMode){
        HubBundles.SCAN_LIVE->strings(R.string.scan_live);HubBundles.SCAN_IDLE->strings(R.string.scan_power_saving);else->strings(R.string.scan_stopped)}}?:hubStatus.resolve(strings)
}
object DeviceRepository {
    private val strings get() = app.stringResolver()
    lateinit var app: Application
        private set
    lateinit var keys: SecureKeys
        private set
    private val mutable = MutableStateFlow(PodsState())
    val state = mutable.asStateFlow()
    /** App pages on screen; the spatial bridge follows the host only while one is. */
    val pagesVisible = MutableStateFlow(0)
    fun pageVisible(visible:Boolean) { pagesVisible.update{(it+if(visible)1 else -1).coerceAtLeast(0)};if(visible){PodsService.spatialRefresh();PodsService.refreshHubDiagnostics()} }
    fun initialize(application: Application) {
        app = application
        keys = SecureKeys(app)
        val p = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        update { it.copy(address = p.getString("address", "")!!, name = p.getString("name", "AirPods")!!,
            popupEnabled = p.getBoolean("popup",true), earAutomation = p.getBoolean("earAutomation",false),connectionPopup=p.getBoolean("connectionPopup",true),autoConnect=p.getBoolean("autoConnect",true),nativePopup=p.getBoolean("nativePopup",true),batteryAlerts=p.getBoolean("batteryAlerts",true),product=p.getInt("product",-1)) }
        // The case advertises its status even with the lid closed, so readings are never restored from disk.
        app.deleteSharedPreferences("battery_history")
    }
    fun update(block: (PodsState) -> PodsState) { mutable.update(block) }
    fun message(value: String) = message(text(value))
    fun message(value: UiText) {
        val rendered = value.resolve(strings)
        update { it.copy(message = rendered, messageCode = value.resource, messageControl = null,
            messageText = value, messageRevision = it.messageRevision + 1) }
        log(rendered)
        saveDiagnostics()
    }
    fun message(@StringRes code: Int, vararg arguments: Any?, control: Int? = null) {
        val rendered = text(code, *arguments).resolve(strings)
        update { it.copy(message = rendered, messageCode = code, messageControl = control,
            messageText = text(code, *arguments), messageRevision = it.messageRevision + 1) }
        log(rendered)
        saveDiagnostics()
    }
    fun log(text: String) {
        DiagnosticLog.i("HyperPods-X", text)
        val clean = LogRedactor.limitUtf8(LogRedactor.redact(text), 7500)
        update { it.copy(log = (it.log + clean).takeLast(30)) }
    }
    // The Bluetooth host scans for the app, so only the control channel needs a permission.
    fun permissionsGranted() = ContextCompat.checkSelfPermission(app,Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    @Suppress("MissingPermission")
    fun refreshLocalName() {
        val address=state.value.address
        if(address.isBlank()||ContextCompat.checkSelfPermission(app,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return
        val name=runCatching{app.getSystemService(BluetoothManager::class.java).adapter?.getRemoteDevice(address)?.alias.orEmpty()}.getOrNull()?:return
        update{if(it.address==address)it.copy(localName=name)else it}
    }
    @Suppress("MissingPermission")
    fun rename(name: String): Boolean {
        val cleaned = name.trim()
        val address = state.value.address
        if (cleaned.isEmpty() || cleaned.length > 100 || address.isBlank() || !permissionsGranted()) return false
        return runCatching {
            val device = app.getSystemService(BluetoothManager::class.java).adapter?.getRemoteDevice(address)
                ?: return false
            if (device.setAlias(cleaned) != BluetoothStatusCodes.SUCCESS) {
                message(R.string.rename_failed)
                false
            } else {
                refreshLocalName()
                FrameworkBridge.publish()
                true
            }
        }.getOrElse {
            message(R.string.rename_failed)
            false
        }
    }
    @Suppress("MissingPermission")
    fun candidates(): List<BluetoothDevice> {
        if (!permissionsGranted()) return emptyList()
        return app.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty().filter { d ->
            // Cached SDP can be absent; the name fallback is only a discovery hint.
            val aacp = d.uuids.orEmpty().any {
                it.uuid.toString().equals("74ec2172-0bad-4d01-8f77-997b2be0722a", true)
            }
            val reportedName = d.name.orEmpty()
            val alias = runCatching { d.alias.orEmpty() }.getOrDefault("")
            aacp || reportedName.contains("airpods", ignoreCase = true) ||
                alias.contains("airpods", ignoreCase = true)
        }
    }
    @Suppress("MissingPermission")
    fun bind(device: BluetoothDevice) {
        if (state.value.running) stop()
        app.getSharedPreferences("app",Context.MODE_PRIVATE).edit().putString("address",device.address).putString("name",device.name ?: "AirPods").remove("product").apply()
        update { PodsState(name=device.name ?: "AirPods", address=device.address,framework=it.framework,frameworkApi=it.frameworkApi,popupEnabled=it.popupEnabled,earAutomation=it.earAutomation,connectionPopup=it.connectionPopup,autoConnect=it.autoConnect,nativePopup=it.nativePopup,batteryAlerts=it.batteryAlerts) }
        refreshLocalName()
        FrameworkBridge.publish()
        start()
    }
    fun start() {
        if (!permissionsGranted()) { message(R.string.grant_nearby_permission); return }
        if (state.value.address.isEmpty()) {
            val devices = candidates()
            if (devices.size == 1) { bind(devices.single()); return }
            message(if (devices.isEmpty()) R.string.pair_airpods_first else R.string.choose_airpods_to_manage); return
        }
        runCatching { ContextCompat.startForegroundService(app,Intent(app,PodsService::class.java)) }
            .onFailure { message(R.string.service_start_failed, it.javaClass.simpleName) }
    }
    fun stop() { app.stopService(Intent(app,PodsService::class.java)) }
    fun setPopup(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("popup",enabled).apply(); update{it.copy(popupEnabled=enabled)}; FrameworkBridge.publish()
    }
    fun setConnectionPopup(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("connectionPopup",enabled).apply();update{it.copy(connectionPopup=enabled)}
    }
    fun setAutoConnect(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("autoConnect",enabled).apply();update{it.copy(autoConnect=enabled)};FrameworkBridge.publish()
    }
    fun setNativePopup(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("nativePopup",enabled).apply();update{it.copy(nativePopup=enabled)};FrameworkBridge.publish()
    }
    fun setBatteryAlerts(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("batteryAlerts",enabled).apply();update{it.copy(batteryAlerts=enabled)};FrameworkBridge.publish()
    }
    /** The verified advertisement names the product before the first AACP session of this run. */
    fun product(id:Int) {
        if(state.value.product==id)return
        app.getSharedPreferences("app",0).edit().putInt("product",id).apply();update{it.copy(product=id)};FrameworkBridge.publish()
    }
    fun setEarAutomation(enabled:Boolean) {
        app.getSharedPreferences("app",0).edit().putBoolean("earAutomation",enabled).apply(); update{it.copy(earAutomation=enabled)}
        PodsService.earAutomationChanged()
    }
    /** Merges by the rules shared with the Bluetooth host; see [BatteryLedger.merged]. */
    fun batteries(values:List<Battery>, precise:Boolean, control:Boolean=false) {
        val now=System.currentTimeMillis()
        var changed=false
        update { s ->
            val result=BatteryLedger.merged(s.batteries,values,precise,control,now)
            changed=result.content()!=s.batteries.content()
            s.copy(batteries=result)
        }
        // A repeat only refreshes the times.
        if(changed)saveDiagnostics()
    }
    /**
     * Takes the hub's merged readings, lid and counters; the hub hears the advertisements and ages
     * the readings. Its times are on the host's elapsed clock and become wall times here.
     */
    fun hub(h:HubBundles.State) {
        val offset=System.currentTimeMillis()-android.os.SystemClock.elapsedRealtime()
        val readings=h.readings.mapValues{it.value.copy(updated=it.value.updated+offset)}
        update{it.copy(hub=h,batteries=if(h.address.equals(it.address,true))readings else it.batteries,
            lidOpen=when(h.lid){HubBundles.LID_OPEN->true;HubBundles.LID_CLOSED->false;else->null},
            broadcasts=h.broadcasts,trustedBroadcasts=h.trusted,proximityCandidates=h.candidates,identityMatches=h.matches)}
        if(h.product>=0)product(h.product)
        saveDiagnostics()
    }
    fun batterySummary():String = listOf(4 to strings(R.string.left),2 to strings(R.string.right),8 to strings(R.string.case_short)).joinToString(" · ") { (id,label) ->
        val b=state.value.batteries[id]
        "${label} ${b?.level?.let { "${if(b.precise) "" else strings(R.string.approximately)}${it}%" } ?: "—"}"
    }
    fun diagnosticSnapshot(): JSONObject {
        val s=state.value
        return JSONObject().put("name",s.name).put("model",s.model).put("firmware",s.firmware)
            .put("internalFirmware",s.internalFirmware).put("firmwareIdentifier",s.firmwareIdentifier)
                .put("status",s.status.resolve(strings)).put("connected",s.connected).put("framework",s.framework.resolve(strings)).put("frameworkApi",s.frameworkApi)
                .put("identityReady",s.identityReady).put("broadcasts",s.broadcasts).put("trustedBroadcasts",s.trustedBroadcasts)
                .put("proximityCandidates",s.proximityCandidates).put("identityMatches",s.identityMatches).put("cardStatus",s.cardStatus.joinToString(","))
                .put("lidOpen",s.lidOpen)
                .put("nativePopup",s.nativePopup).put("nativePopupStatus",s.nativePopupStatus.resolve(strings)).put("batteryAlerts",s.batteryAlerts).put("autoConnect",s.autoConnect)
                .put("earAutomation",s.earAutomation).put("earDetectionStatus",s.earDetectionStatus.resolve(strings))
                .put("wearingPrimary",s.wearing?.primary?.name).put("wearingSecondary",s.wearing?.secondary?.name)
                .put("spatial",s.spatial.diagnostic(strings))
                .put("conversation",s.conversation.diagnostic(strings))
                .put("equalizer",s.equalizer?.joinToString(","))
                .put("battery",batterySummary()).put("controls",JSONObject(s.controls.mapKeys{it.key.toString()}.mapValues{it.value.joinToString(",")}))
                .put("pending",s.pending.joinToString(",")).put("message",s.message).put("log",s.log.joinToString("\n"))
                .put("adaptiveTarget",s.adaptiveTarget)
                .put("controlRefreshUntil",s.controlRefreshUntil)
                .put("hubStatus",s.hubStatus.resolve(strings)).put("hub",s.hub?.let{hubDiagnostic(it)})
                .put("hostCapabilities",JSONObject(s.hostCapabilities.mapValues{JSONObject(it.value)}))
    }
    /** What the hub holds, side by side with the app's own readings while both paths run. */
    private fun hubDiagnostic(h:HubBundles.State):JSONObject = JSONObject()
        .put("appAlive",h.appAlive).put("controlConnected",h.control.connected).put("replaceSystemPopup",h.replaceSystemPopup)
        .put("lid",h.lid).put("product",h.product).put("scanMode",h.scanMode)
        .put("batteries",h.readings.entries.joinToString(" "){(id,r)->"${id}:${r.level}${if(r.charging)"+" else ""}${if(r.control)"c" else ""}"})
        .put("broadcasts",h.broadcasts).put("trusted",h.trusted).put("candidates",h.candidates).put("matches",h.matches)
    // A burst of events, such as a reconnect's readback, writes the snapshot once.
    private const val DIAGNOSTICS_DELAY_MS=1500L
    private val diagnosticsWriter=Executors.newSingleThreadScheduledExecutor{Thread(it,"diagnostics").apply{isDaemon=true}}
    private var pendingDiagnostics:ScheduledFuture<*>?=null
    /** Writes the snapshot within [DIAGNOSTICS_DELAY_MS], off the caller's thread. */
    fun saveDiagnostics() = synchronized(diagnosticsWriter) {
        if(pendingDiagnostics?.isDone==false)return@synchronized
        pendingDiagnostics=diagnosticsWriter.schedule({writeDiagnostics()},DIAGNOSTICS_DELAY_MS,TimeUnit.MILLISECONDS)
    }
    /** Writes now on this thread: the service stopping, an export, or a crash. */
    fun flushDiagnostics() {
        synchronized(diagnosticsWriter){pendingDiagnostics?.cancel(false);pendingDiagnostics=null}
        writeDiagnostics()
    }
    @Synchronized private fun writeDiagnostics() {
        runCatching {
            val json=diagnosticSnapshot()
            val file=android.util.AtomicFile(File(app.filesDir,"diagnostics.json"))
            val output=file.startWrite()
            try {output.write(json.toString(2).toByteArray());file.finishWrite(output)}
            catch(error:Exception){file.failWrite(output);throw error}
        }
    }
}
