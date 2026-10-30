package com.hyper.podsrx.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import com.hyper.podsrx.DeviceSettingsActivity
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.HubBundles
import com.hyper.podsrx.hook.HubWire
import com.hyper.podsrx.hook.NativeControlWire
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.protocol.Battery
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's side of the Bluetooth host's state hub. Pushes the AACP session and the proximity keys
 * as they change; the hub's callbacks are the only thing it listens to. Nothing here is periodic.
 */
class HubClient(context:Context,private val onLid:(event:Int,nativeShown:Boolean)->Unit) {
    private val strings = context.stringResolver()
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    // One transaction at a time, in the order they were asked for.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    private val controlBatteries=MutableStateFlow<List<Battery>>(emptyList())
    @Volatile private var hub:IBinder?=null
    private var hubDeath:IBinder.DeathRecipient?=null
    @Volatile private var closed=false
    private var retry:Job?=null
    private var backoffMs=MIN_BACKOFF_MS
    private val popupCredential=PendingIntent.getActivity(this.context,21,Intent(this.context,DeviceSettingsActivity::class.java)
        .putExtra("device_address",DeviceRepository.state.value.address),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val controlTarget=PendingIntent.getBroadcast(this.context,26,
        Intent(this.context,NativeControlReceiver::class.java).setAction(NativeControlWire.COMMAND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Hub callbacks; only the Bluetooth host may speak here. */
    private val callback=object:Binder() {
        init { attachInterface(null,HubWire.CALLBACK_DESCRIPTOR) }
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            if(code==INTERFACE_TRANSACTION){reply?.writeString(HubWire.CALLBACK_DESCRIPTOR);return true}
            if(Binder.getCallingUid()!=Process.BLUETOOTH_UID)return false
            data.enforceInterface(HubWire.CALLBACK_DESCRIPTOR)
            val payload=data.readBundle(javaClass.classLoader)?:return true
            when(code) {
                HubWire.ON_STATE -> DeviceRepository.hub(HubBundles.State.from(payload))
                HubWire.ON_LID -> onLid(payload.getInt("event"),payload.getBoolean("nativeShown"))
            }
            return true
        }
    }

    private val readyReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action!=HubWire.READY||sentFromUid!=Process.BLUETOOTH_UID)return
            scope.launch{drop();backoffMs=MIN_BACKOFF_MS;connect()}
        }
    }

    init {
        this.context.registerReceiver(readyReceiver,IntentFilter(HubWire.READY),Context.RECEIVER_EXPORTED)
        scope.launch{connect()}
        scope.launch {
            combine(DeviceRepository.state,controlBatteries){s,batteries->controlKey(s,batteries)}
                .distinctUntilChanged().collect{pushControl()}
        }
    }

    /** The control channel's latest battery report, kept raw so the hub merges it by its own rules. */
    fun controlBatteries(values:List<Battery>) { controlBatteries.value=values }

    fun pushKeys() { scope.launch{sendKeys()} }

    /** Which hooks each scoped process installed, as the hub collected them. */
    fun refreshDiagnostics() {
        scope.launch {
            val binder=hub?:return@launch
            val (status,reply)=runCatching{transact(binder,HubWire.GET_DIAGNOSTICS,Bundle())}.getOrNull()?:return@launch
            if(status!=HubWire.OK)return@launch
            val reports=reply.getBundle("capabilities")?:Bundle()
            val parsed=reports.keySet().sorted().associateWith{process->
                val results=reports.getBundle(process)?:Bundle()
                results.keySet().sorted().associateWith{results.getString(it).orEmpty()}
            }
            DeviceRepository.update{it.copy(hostCapabilities=parsed)}
        }
    }

    /** The hub shows the native popup or island; [onResult] runs on the main thread. */
    fun requestPopup(preview:Boolean,island:Boolean,onResult:(Boolean)->Unit) {
        scope.launch {
            val binder=hub
            val shown=binder!=null&&runCatching{
                val (status,reply)=transact(binder,HubWire.REQUEST_POPUP,HubBundles.popupRequest(preview,island))
                status==HubWire.OK&&reply.getBoolean("shown")
            }.getOrDefault(false)
            main.post{onResult(shown)}
        }
    }

    fun close() {
        closed=true
        runCatching{context.unregisterReceiver(readyReceiver)}
        // The hub keeps the control readings but stops trusting the session at once.
        val binder=hub
        scope.cancel()
        if(binder!=null)runCatching{transact(binder,HubWire.PUSH_CONTROL,HubBundles.Control.DISCONNECTED.toBundle())}
        drop()
        DeviceRepository.update{it.copy(hub=null,hubStatus=text(R.string.hub_disconnected))}
    }

    private suspend fun connect() {
        if(closed||hub!=null)return
        retry?.cancel()
        val binder=handshake()
        if(binder==null){wakeHost();scheduleRetry(text(R.string.hub_not_ready));return}
        val (status,reply)=runCatching{transact(binder,HubWire.HELLO,
            HubBundles.Hello(HubWire.VERSION,callback,DeviceRepository.state.value.address,popupCredential,controlTarget).toBundle())}
            .getOrElse{scheduleRetry(text(R.string.hub_handshake_failed, it.javaClass.simpleName));return}
        when(status) {
            HubWire.OK -> Unit
            HubWire.VERSION_MISMATCH -> {
                DeviceRepository.update{it.copy(hubStatus=text(R.string.hub_version_mismatch))}
                DeviceRepository.log(strings(R.string.hub_version_log, reply.getInt("version"), HubWire.VERSION))
                return
            }
            else -> {scheduleRetry(text(R.string.hub_connection_rejected));return}
        }
        val recipient=IBinder.DeathRecipient{scope.launch{if(hub===binder){drop();connect()}}}
        if(runCatching{binder.linkToDeath(recipient,0)}.isFailure){scheduleRetry(text(R.string.hub_exited));return}
        hub=binder;hubDeath=recipient;backoffMs=MIN_BACKOFF_MS
        DeviceRepository.update{it.copy(hubStatus=text(R.string.hub_connected))}
        DeviceRepository.hub(HubBundles.State.from(reply))
        DeviceRepository.log(strings(R.string.bluetooth_hub_connected))
        sendKeys()
        pushControl()
        refreshDiagnostics()
        // A restarted host's processes report their hooks a moment after it comes up.
        scope.launch{delay(DIAGNOSTICS_SETTLE_MS);refreshDiagnostics()}
    }

    /** The host returns its binder in the ordered broadcast's result; it requires NATIVE_POPUP from us. */
    private suspend fun handshake():IBinder? = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
        suspendCancellableCoroutine{continuation->
            runCatching {
                context.sendOrderedBroadcast(Intent(HubWire.CONNECT).setPackage(HubWire.HOST)
                    .putExtra(HubWire.EXTRA_CREATED,SystemClock.elapsedRealtime()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),null,
                    object:BroadcastReceiver(){
                        override fun onReceive(context:Context,intent:Intent){
                            val binder=if(resultCode==1)getResultExtras(false)?.getBinder(HubWire.EXTRA_BINDER)else null
                            if(continuation.isActive)continuation.resume(binder)
                        }
                    },main,0,null,null)
            }.onFailure{if(continuation.isActive)continuation.resume(null)}
        }
    }

    /**
     * A force-stopped host stays down and receives no broadcasts, and with its scans gone the stack
     * has no Apple advertisement to hand its provider. Reaching that provider starts the process; the
     * hub then refuses this caller and announces HUB_READY, which reconnects at once.
     */
    private fun wakeHost() {
        runCatching{context.contentResolver.call(android.net.Uri.parse(HubWire.PROVIDER_URI),HubWire.STATE_METHOD,null,null)}
    }

    private fun scheduleRetry(status: UiText) {
        if(closed)return
        DeviceRepository.update{it.copy(hubStatus=status)}
        val wait=backoffMs
        backoffMs=(backoffMs*2).coerceAtMost(MAX_BACKOFF_MS)
        retry=scope.launch{
            delay(wait)
            // connect() cancels any pending retry; this attempt is already running.
            retry=null
            connect()
        }
    }

    private fun drop() {
        val binder=hub;val recipient=hubDeath
        hub=null;hubDeath=null
        if(binder!=null&&recipient!=null)runCatching{binder.unlinkToDeath(recipient,0)}
    }

    private fun pushControl() {
        val binder=hub?:return
        call(binder,HubWire.PUSH_CONTROL,control(DeviceRepository.state.value,controlBatteries.value).toBundle())
    }

    private fun sendKeys() {
        val binder=hub?:return
        val address=DeviceRepository.state.value.address
        if(address.isBlank())return
        val irk=DeviceRepository.keys.get(address,1);val enc=DeviceRepository.keys.get(address,4)
        if(irk==null&&enc==null)return
        call(binder,HubWire.PUSH_KEYS,HubBundles.Keys(address,irk,enc).toBundle())
    }

    /** A retired hub ran nothing; reconnecting reaches the generation that replaced it. */
    private fun call(binder:IBinder,code:Int,payload:Bundle) {
        val (status,reply)=runCatching{transact(binder,code,payload)}.getOrElse{
            if(hub===binder){drop();scope.launch{connect()}}
            return
        }
        if(status==HubWire.OK)return
        if(reply.getBoolean("retired")||status==HubWire.DENIED){
            if(hub===binder){drop();scope.launch{connect()}}
        } else DeviceRepository.log(strings(R.string.hub_request_failed, code, reply.getString("status")?:status))
    }

    private fun transact(binder:IBinder,code:Int,payload:Bundle):Pair<Int,Bundle> {
        val input=Parcel.obtain();val output=Parcel.obtain()
        try {
            input.writeInterfaceToken(HubWire.DESCRIPTOR)
            input.writeBundle(payload)
            if(!binder.transact(code,input,output,0))error(strings(R.string.hub_version_unsupported))
            output.readException()
            val status=output.readInt()
            return status to (output.readBundle(javaClass.classLoader)?:Bundle())
        } finally {input.recycle();output.recycle()}
    }

    companion object {
        private const val MIN_BACKOFF_MS=1000L
        private const val MAX_BACKOFF_MS=30000L
        private const val HANDSHAKE_TIMEOUT_MS=3000L
        private const val DIAGNOSTICS_SETTLE_MS=3000L

        /** Everything [control] reads, so an unrelated state change pushes nothing. */
        private fun controlKey(s:PodsState,batteries:List<Battery>):List<Any?> = listOf(
            s.address,s.connected,s.model,s.name,s.firmware,batteries,
            HubBundles.Control.PANEL_CONTROLS.map{s.controls[it]},0x0d in s.pending,s.controlRefreshUntil,
            s.spatial.route,s.spatial.mode,s.spatial.bridge,s.spatial.capable,s.spatial.modeChanging,s.wearing?.inEarCount)

        /** The same panel shape FrameworkBridge writes to the panel_* preferences. */
        fun control(s:PodsState,batteries:List<Battery>):HubBundles.Control = HubBundles.Control(
            s.connected,s.model,s.name,s.firmware,batteries,
            HubBundles.Control.PANEL_CONTROLS.asList().mapNotNull{id->s.controls[id]?.let{values->id to values.toIntArray()}}.toMap(),
            0x0d in s.pending,s.controlRefreshUntil,s.spatial.route,
            s.spatial.mode in listOf(SpatialMode.FIXED,SpatialMode.HEAD_TRACKING),
            s.spatial.bridge&&s.spatial.capable&&s.spatial.route,s.spatial.modeChanging,
            s.wearing?.inEarCount?:-1)
    }
}
