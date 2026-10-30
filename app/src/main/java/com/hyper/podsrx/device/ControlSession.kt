package com.hyper.podsrx.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Build
import android.content.Context
import android.os.SystemClock
import com.hyper.podsrx.R
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.protocol.Aacp
import com.hyper.podsrx.protocol.AacpInitialization
import com.hyper.podsrx.hook.ControlSocketWire
import com.hyper.podsrx.protocol.AacpFramer
import com.hyper.podsrx.protocol.ControlCommands
import com.hyper.podsrx.protocol.ControlCommands.Command
import com.hyper.podsrx.protocol.Event
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The AACP control channel: one L2CAP socket at a time, its handshake and reconnects, and the
 * setting writes queued on it. The rules for those writes live in [ControlCommands].
 */
@SuppressLint("MissingPermission")
class ControlSession(private val context:Context,private val scope:CoroutineScope,private val listener:Listener,
    private val hostTransport:suspend (String)->ControlSocketWire.Channel) {
    private val strings = context.stringResolver()
    interface Listener {
        fun onOpened(wasReadback:Boolean)
        fun onEvent(event:Event,session:Long)
        fun onSpatialPacket(packet:ByteArray)
        /** The channel dropped; [ControlCommands] and the repository are settled after this returns. */
        fun onLost()
        fun onAccepted(command:Command)
    }

    @Volatile private var socket:ControlSocketWire.Channel?=null
    private val sessionSequence=AtomicLong()
    private val sessionIds=ConcurrentHashMap<ControlSocketWire.Channel,Long>()
    private val outbox=Channel<Command>(Channel.UNLIMITED)
    private val reconnect=Channel<Unit>(Channel.CONFLATED)
    private val commands=ControlCommands(Controls::allowed,object:ControlCommands.Effects {
        override fun accepted(command:Command) {
            traceControl(command.id,"accepted",command.token) { "target=${command.values} actual=${DeviceRepository.state.value.controls[command.id]}" }
            DeviceRepository.update{it.copy(pending=it.pending+command.id,
                adaptiveTarget=if(command.id==ControlCommands.ADAPTIVE)command.values.first()else it.adaptiveTarget)}
            listener.onAccepted(command)
        }
        override fun completed(command:Command) {
            DeviceRepository.update{it.copy(pending=it.pending-command.id,
                adaptiveTarget=if(command.id==ControlCommands.ADAPTIVE)null else it.adaptiveTarget)}
        }
        override fun adaptiveSent() {
            DeviceRepository.update{it.copy(pending=it.pending-ControlCommands.ADAPTIVE)}
        }
        override fun adaptiveRevoked() {
            DeviceRepository.update{it.copy(adaptiveTarget=null,pending=it.pending-ControlCommands.ADAPTIVE)}
        }
    })

    val isOpen:Boolean get()=socket!=null
    /** Follows the ACL broadcasts; used where the stack cannot be asked. */
    @Volatile private var linked=false

    /** Whether HFP or A2DP is up on the current link; null until a link is seen coming up. */
    @Volatile private var audio:Boolean?=null
    @Volatile private var linkedAt=0L

    /** The bound headset's ACL came up or went down. */
    fun linkChanged(up:Boolean) {
        linked=up
        audio=false
        if(!up)return
        linkedAt=SystemClock.elapsedRealtime()
        reconnect.trySend(Unit)
        scope.launch{delay(AUDIO_WAIT_MS);reconnect.trySend(Unit)}
    }

    /** HFP or A2DP connected to the bound headset. */
    fun audioConnected() { audio=true;reconnect.trySend(Unit) }

    /**
     * Opening the control channel without an ACL makes the phone page the headset. On a lid opening
     * that page held up the headset's own connection and the audio profiles by about 10 s, and with
     * the lid shut it paged for nothing; so the channel opens only on a link the headset made.
     */
    private fun linkUp(device:BluetoothDevice):Boolean =
        runCatching{HiddenApiBypass.invoke(BluetoothDevice::class.java,device,"isConnected") as Boolean}.getOrDefault(linked)

    fun start() {
        scope.launch{sendLoop()}
        scope.launch{connectLoop()}
    }

    fun close() {
        outbox.close()
        runCatching{socket?.close()};socket=null
    }

    fun enqueue(id:Int,values:List<Int>) {
        val s=DeviceRepository.state.value
        val view=ControlCommands.View(s.connected,s.controlRefreshUntil!=0L,s.controls,s.pending,s.equalizer)
        when(val admission=commands.enqueue(id,values,view)) {
            is ControlCommands.Admission.Rejected -> DeviceRepository.message(when(admission.reason) {
                ControlCommands.Rejection.NOT_CONNECTED -> R.string.control_not_connected
                ControlCommands.Rejection.READING_BACK -> R.string.reading_settings_wait
                ControlCommands.Rejection.ADAPTIVE_NOT_READY -> R.string.wait_adaptive_readback
                ControlCommands.Rejection.MODE_WHILE_ADAPTIVE -> R.string.wait_noise_command
                ControlCommands.Rejection.NOT_ALLOWED -> R.string.setting_not_writable
            })
            is ControlCommands.Admission.Accepted -> if(outbox.trySend(admission.command).isFailure)commands.complete(admission.command)
            ControlCommands.Admission.Unchanged -> Unit
        }
    }

    /** Read alongside observable state.pending; the wish survives a readback reconnect. */
    fun pendingValue(id:Int):Int? = commands.desiredValue(id)

    fun requestReconnect() { reconnect.trySend(Unit) }
    fun reconnectIfClosed() { if(socket==null)reconnect.trySend(Unit) }
    fun closeSocket() { runCatching{socket?.close()} }
    fun reconnectNow() { closeSocket();reconnect.trySend(Unit) }

    /** Reopens the channel so the headset reports every setting again. */
    fun restartForReadback() { prepareReadback();reconnectNow() }

    fun write(packet:ByteArray):Boolean {
        val channel=socket ?: return false
        return runCatching{synchronized(channel){channel.write(packet)};true}.getOrDefault(false)
    }

    fun onControl(event:Event.Control,session:Long) {
        DeviceRepository.update{it.copy(controls=it.controls+(event.id to event.value),
            controlRefreshUntil=if(commands.endsReadback(event.id,session))0 else it.controlRefreshUntil)}
        val report=commands.onControl(event.id,event.value)
        traceControl(event.id,"readback",report.expected?.token,session) {
            "actual=${event.value} target=${report.expected?.values} matched=${if(report.expected==null)"n/a" else report.matched.toString()}"
        }
    }

    fun onEqualizer(values:List<Int>) = commands.onEqualizer(values)

    private suspend fun sendLoop() {
        for(command in outbox) {
            val id=command.id
            when(commands.beforeSend(command,DeviceRepository.state.value.controls)) {
                ControlCommands.Send.STALE -> continue
                ControlCommands.Send.NOT_ADAPTIVE -> {DeviceRepository.message(R.string.select_adaptive_before_adjusting);continue}
                ControlCommands.Send.SEND -> Unit
            }
            try {
                val s=socket ?: throw IOException(strings(R.string.control_channel_disconnected))
                DeviceRepository.update{it.copy(message="",messageCode=0,messageControl=null,messageText=text(""))}
                DeviceRepository.saveDiagnostics()
                val packet=if(id==ControlCommands.EQUALIZER)Aacp.equalizer(command.values)else Aacp.control(id,command.values)
                val writtenSession=sessionIds[s] ?: 0L
                val writtenAt=synchronized(s) {s.write(packet);SystemClock.elapsedRealtime()}
                traceControl(id,"write",command.token,writtenSession,writtenAt) {
                    "packet=${packet.joinToString(" "){(it.toInt() and 255).toString(16).padStart(2,'0')}}"
                }
                if(!commands.sent(command)){DeviceRepository.saveDiagnostics();continue}
                scope.launch {
                    delay(ControlCommands.READBACK_AFTER_MS)
                    if(socket===s&&commands.claimReadback(command)){
                        scope.launch {
                            try {
                                prepareReadback()
                                DeviceRepository.log(strings(R.string.reconnecting_for_readback))
                                runCatching{s.close()};reconnect.trySend(Unit)
                                delay(1000)
                            } finally {commands.releaseReadback()}
                        }
                    }
                }
                scope.launch {
                    delay(ControlCommands.CONFIRM_TIMEOUT_MS)
                    if(commands.complete(command)) {
                        traceControl(id,"timeout",command.token) {
                            "target=${command.values} actual=${DeviceRepository.state.value.controls[id]}"
                        }
                        val title = Controls.all.find { it.id == id }?.titleRes
                            ?: if (id == ControlCommands.EQUALIZER) R.string.equalizer else R.string.settings
                        DeviceRepository.message(R.string.setting_unconfirmed, text(title), control = id)
                    }
                }
            } catch(e:Exception) {
                commands.complete(command)
                DeviceRepository.message(R.string.setting_send_failed, e.message ?: e.javaClass.simpleName, control = id)
            }
        }
    }

    // Only these setting values are logged; never include a device address or other AACP payloads.
    private fun traceControl(id:Int,stage:String,token:Long?=null,
        session:Long=sessionSequence.get(),elapsedMs:Long=SystemClock.elapsedRealtime(),details:()->String) {
        if(id!=0x0d&&id!=0x1f&&id!=0x23&&id!=0x25&&id!=0x2e)return
        DeviceRepository.log(strings(R.string.setting_diagnostic, id.toString(16), stage, elapsedMs, session, token ?: "none", details()))
    }

    private fun prepareReadback() {
        commands.prepareReadback(sessionSequence.get())
        val until=SystemClock.elapsedRealtime()+ControlCommands.READBACK_WINDOW_MS
        DeviceRepository.update { it.copy(controlRefreshUntil=until) }
        scope.launch {
            delay(ControlCommands.READBACK_WINDOW_MS)
            DeviceRepository.update {
                if(it.controlRefreshUntil!=until)it else it.copy(controlRefreshUntil=0,
                    controls=emptyMap(),equalizer=null)
            }
        }
    }

    private suspend fun connectLoop() {
        var attempts=0
        var silentRetries=0
        while(scope.isActive) {
            var current:ControlSocketWire.Channel?=null
            var watchdog:Job?=null
            val silent=AtomicBoolean(false)
            try {
                val adapter=context.getSystemService(BluetoothManager::class.java).adapter ?: throw IOException(strings(R.string.bluetooth_unavailable))
                if(!adapter.isEnabled)throw IOException(strings(R.string.bluetooth_disabled))
                val target=adapter.getRemoteDevice(DeviceRepository.state.value.address)
                if(!linkUp(target)){
                    DeviceRepository.update{it.copy(status=text(R.string.waiting_earbuds))}
                    reconnect.receive()
                    continue
                }
                // A channel opened before the audio profiles heard no settings for 5-20 s. A link
                // whose audio never comes, such as one serving another phone, opens it anyway.
                val audioWait=linkedAt+AUDIO_WAIT_MS-SystemClock.elapsedRealtime()
                if(audio==false&&audioWait>0){
                    DeviceRepository.update{it.copy(status=text(R.string.waiting_audio_connection))}
                    withTimeoutOrNull(audioWait){reconnect.receive()}
                    continue
                }
                // Needs BLUETOOTH_SCAN, which the app no longer asks for.
                runCatching{if(adapter.isDiscovering)adapter.cancelDiscovery()}
                DeviceRepository.update{
                    val refresh=it.controlRefreshUntil>SystemClock.elapsedRealtime()
                    it.copy(status=text(R.string.opening_control_channel),connected=false,
                        controls=if(refresh)it.controls else emptyMap(),equalizer=if(refresh)it.equalizer else null,
                        controlRefreshUntil=if(refresh)it.controlRefreshUntil else 0)
                }
                val useHost=Build.VERSION.SDK_INT==36
                val opened=if(useHost)hostTransport(target.address)else LocalControlChannel(ClassicTransport.create(adapter,target))
                val session=sessionSequence.incrementAndGet()
                sessionIds[opened]=session
                current=opened; socket=opened
                if(!scope.isActive)break
                val timeout=scope.launch{delay(12000);if(!opened.isConnected)runCatching{opened.close()}}
                try{opened.connect()}finally{timeout.cancel()}
                val wasReadback=commands.opened()
                DeviceRepository.log(strings(R.string.l2cap_connected))
                DeviceRepository.update{it.copy(status=text(R.string.reading_earbud_state),connected=true)}
                listener.onOpened(wasReadback)
                DeviceRepository.log("AACP transport="+if(useHost)"bluetooth-host, handshake=1.2" else "app, handshake=1.3")
                if(useHost) {
                    for(step in AacpInitialization.host()) {
                        delay(step.delayMs)
                        synchronized(opened){opened.write(step.packet)}
                    }
                } else {
                    synchronized(opened){opened.write(Aacp.handshake)}
                    delay(200)
                    synchronized(opened){opened.write(Aacp.features())}
                    delay(200)
                    synchronized(opened){opened.write(Aacp.notifications())}
                    delay(200)
                    synchronized(opened){opened.write(Aacp.keyRequest(1));opened.write(Aacp.keyRequest(4))}
                }
                // An accepted channel can stay silent after the handshake. A blocking read never
                // returns then, so closing the socket is what lets the loop reconnect.
                val received=AtomicBoolean(false)
                // The ROM's host opens its own AACP channel once the audio profiles connect, and
                // the headset reports settings only to the channel opened last. A channel that hears
                // no settings is reopened while quick retries remain; past them it is kept as it is.
                val settled=AtomicBoolean(false)
                watchdog=scope.launch{
                    delay(SILENT_HANDSHAKE_MS)
                    val heard=received.get()
                    if(!heard||(!settled.get()&&silentRetries<SILENT_RETRIES)){
                        silent.set(true)
                        DeviceRepository.log(strings(R.string.handshake_retry, SILENT_HANDSHAKE_MS/1000, if(heard)strings(R.string.settings_not_received) else strings(R.string.no_response)))
                        runCatching{opened.close()}
                    }
                }
                val framer=AacpFramer(); val buffer=ByteArray(65536)
                while(scope.isActive) {
                    val count=opened.read(buffer)
                    if(count<=0)throw IOException(strings(R.string.control_channel_closed))
                    received.set(true)
                    if(count>=8 && buffer.copyOfRange(0,4).contentEquals(Aacp.header) && buffer[4]==0x4b.toByte() && buffer[5]==0.toByte()) {
                        DeviceRepository.update{it.copy(conversation=it.conversation.copy(wirePackets=it.conversation.wirePackets+1))}
                        DeviceRepository.log(strings(R.string.conversation_raw_packet, count, (buffer[6].toInt() and 255) or ((buffer[7].toInt() and 255) shl 8)))
                    }
                    for(packet in framer.feed(buffer.copyOf(count))){
                        if(packet.size>=6&&packet[4].toInt()==0x17){listener.onSpatialPacket(packet);continue}
                        val event=Aacp.decode(packet)?:continue
                        if((event is Event.Information||event is Event.Control)&&!settled.getAndSet(true)){watchdog?.cancel();silentRetries=0}
                        listener.onEvent(event,session)
                    }
                }
            } catch(e:Exception) {
                if(!scope.isActive)break
                listener.onLost()
                DeviceRepository.log(strings(R.string.control_connection_log, e.message ?: e.javaClass.simpleName))
                commands.disconnected { preserve ->
                    DeviceRepository.update{
                        val refresh=preserve&&it.controlRefreshUntil>SystemClock.elapsedRealtime()
                        it.copy(connected=false,status=if(preserve)text(R.string.reading_settings)else text(R.string.waiting_reconnect, e.message ?: text(R.string.connection_failed)),
                            controls=if(refresh)it.controls else emptyMap(),equalizer=if(refresh)it.equalizer else null,
                            controlRefreshUntil=if(refresh)it.controlRefreshUntil else 0,
                            pending=if(preserve)it.pending-ControlCommands.ADAPTIVE else emptySet(),adaptiveTarget=null)
                    }
                }
                FrameworkBridge.publish(); DeviceRepository.saveDiagnostics()
            } finally {watchdog?.cancel();runCatching{current?.close()};if(socket===current)socket=null;current?.let{sessionIds.remove(it)}}
            if(silent.get()&&silentRetries<SILENT_RETRIES){
                // 2, 4 and 8 seconds; a lid open or an ACL connection retries at once.
                withTimeoutOrNull(2000L shl silentRetries++){reconnect.receive()}
                continue
            }
            attempts++
            val requested=if(attempts<2)withTimeoutOrNull(2000){reconnect.receive()} else {
                DeviceRepository.update{it.copy(status=text(R.string.waiting_lid_or_bluetooth))}
                DeviceRepository.saveDiagnostics()
                reconnect.receive()
            }
            if(requested!=null)attempts=0
        }
    }

    private companion object {
        const val SILENT_HANDSHAKE_MS=5000L
        const val SILENT_RETRIES=3
        const val AUDIO_WAIT_MS=3000L
    }
}
