package com.hyper.podsrx.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import com.hyper.podsrx.protocol.Aacp
import com.hyper.podsrx.protocol.AacpFramer
import com.hyper.podsrx.protocol.ControlCommands
import com.hyper.podsrx.protocol.ControlCommands.Command
import com.hyper.podsrx.protocol.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The AACP control channel: one L2CAP socket at a time, its handshake and reconnects, and the
 * setting writes queued on it. The rules for those writes live in [ControlCommands].
 */
@SuppressLint("MissingPermission")
class ControlSession(private val context:Context,private val scope:CoroutineScope,private val listener:Listener) {
    interface Listener {
        fun onOpened(wasReadback:Boolean)
        fun onEvent(event:Event,session:Long)
        fun onSpatialPacket(packet:ByteArray)
        /** The channel dropped; [ControlCommands] and the repository are settled after this returns. */
        fun onLost()
        fun onAccepted(command:Command)
    }

    @Volatile private var socket:BluetoothSocket?=null
    private val sessionSequence=AtomicLong()
    private val sessionIds=ConcurrentHashMap<BluetoothSocket,Long>()
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
                ControlCommands.Rejection.NOT_CONNECTED -> "耳机控制尚未连接"
                ControlCommands.Rejection.READING_BACK -> "正在回读耳机设置，请稍候"
                ControlCommands.Rejection.ADAPTIVE_NOT_READY -> "请等待自适应模式与降噪幅度读取完成"
                ControlCommands.Rejection.MODE_WHILE_ADAPTIVE -> "请等待降噪指令发送完成后再切换模式"
                ControlCommands.Rejection.NOT_ALLOWED -> "当前设置或取值尚未开放写入"
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
        return runCatching{synchronized(channel){channel.outputStream.write(packet);channel.outputStream.flush()};true}.getOrDefault(false)
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
                ControlCommands.Send.NOT_ADAPTIVE -> {DeviceRepository.message("请在自适应模式下调整降噪幅度");continue}
                ControlCommands.Send.SEND -> Unit
            }
            try {
                val s=socket ?: throw IOException("控制通道未连接")
                DeviceRepository.update{it.copy(message="")}
                DeviceRepository.saveDiagnostics()
                val packet=if(id==ControlCommands.EQUALIZER)Aacp.equalizer(command.values)else Aacp.control(id,command.values)
                val writtenSession=sessionIds[s] ?: 0L
                val writtenAt=synchronized(s) {s.outputStream.write(packet);s.outputStream.flush();SystemClock.elapsedRealtime()}
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
                                DeviceRepository.log("主动重连控制通道以回读设置")
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
                        DeviceRepository.message("${Controls.all.find{it.id==id}?.title ?: if(id==ControlCommands.EQUALIZER)"均衡器" else "设置"}未确认目标值，已保留设备回读结果")
                    }
                }
            } catch(e:Exception) {
                commands.complete(command)
                DeviceRepository.message("设置发送失败：${e.message}")
            }
        }
    }

    // Only these setting values are logged; never include a device address or other AACP payloads.
    private fun traceControl(id:Int,stage:String,token:Long?=null,
        session:Long=sessionSequence.get(),elapsedMs:Long=SystemClock.elapsedRealtime(),details:()->String) {
        if(id!=0x0d&&id!=0x1f&&id!=0x23&&id!=0x25&&id!=0x2e)return
        DeviceRepository.log("设置诊断 id=0x${id.toString(16)} stage=$stage elapsedMs=$elapsedMs session=$session token=${token ?: "none"} ${details()}")
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
            var current:BluetoothSocket?=null
            var watchdog:Job?=null
            val silent=AtomicBoolean(false)
            try {
                val adapter=context.getSystemService(BluetoothManager::class.java).adapter ?: throw IOException("蓝牙不可用")
                if(!adapter.isEnabled)throw IOException("蓝牙已关闭")
                val target=adapter.getRemoteDevice(DeviceRepository.state.value.address)
                if(!linkUp(target)){
                    DeviceRepository.update{it.copy(status="等待耳机连接")}
                    reconnect.receive()
                    continue
                }
                // A channel opened before the audio profiles heard no settings for 5-20 s. A link
                // whose audio never comes, such as one serving another phone, opens it anyway.
                val audioWait=linkedAt+AUDIO_WAIT_MS-SystemClock.elapsedRealtime()
                if(audio==false&&audioWait>0){
                    DeviceRepository.update{it.copy(status="等待耳机音频连接")}
                    withTimeoutOrNull(audioWait){reconnect.receive()}
                    continue
                }
                // Needs BLUETOOTH_SCAN, which the app no longer asks for.
                runCatching{if(adapter.isDiscovering)adapter.cancelDiscovery()}
                DeviceRepository.update{
                    val refresh=it.controlRefreshUntil>SystemClock.elapsedRealtime()
                    it.copy(status="建立耳机控制连接…",connected=false,
                        controls=if(refresh)it.controls else emptyMap(),equalizer=if(refresh)it.equalizer else null,
                        controlRefreshUntil=if(refresh)it.controlRefreshUntil else 0)
                }
                val opened=ClassicTransport.create(adapter,target)
                val session=sessionSequence.incrementAndGet()
                sessionIds[opened]=session
                current=opened; socket=opened
                val timeout=scope.launch{delay(12000);if(!opened.isConnected)runCatching{opened.close()}}
                try{opened.connect()}finally{timeout.cancel()}
                val wasReadback=commands.opened()
                DeviceRepository.log("经典 L2CAP 通道已连接")
                DeviceRepository.update{it.copy(status="正在读取耳机状态…",connected=true)}
                listener.onOpened(wasReadback)
                val output=opened.outputStream
                synchronized(opened){output.write(Aacp.handshake);output.flush()}
                delay(200)
                synchronized(opened){output.write(Aacp.features());output.flush()}
                delay(200)
                synchronized(opened){output.write(Aacp.notifications());output.flush()}
                delay(200)
                synchronized(opened){output.write(Aacp.keyRequest(1));output.write(Aacp.keyRequest(4));output.flush()}
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
                        DeviceRepository.log("握手后 ${SILENT_HANDSHAKE_MS/1000} 秒${if(heard)"未收到耳机设置" else "无响应"}，重建控制通道")
                        runCatching{opened.close()}
                    }
                }
                val framer=AacpFramer(); val buffer=ByteArray(65536)
                while(scope.isActive) {
                    val count=opened.inputStream.read(buffer)
                    if(count<=0)throw IOException("耳机控制通道已关闭")
                    received.set(true)
                    if(count>=8 && buffer.copyOfRange(0,4).contentEquals(Aacp.header) && buffer[4]==0x4b.toByte() && buffer[5]==0.toByte()) {
                        DeviceRepository.update{it.copy(conversation=it.conversation.copy(wirePackets=it.conversation.wirePackets+1))}
                        DeviceRepository.log("对话原始报文：读取 $count 字节，载荷长度 ${(buffer[6].toInt() and 255) or ((buffer[7].toInt() and 255) shl 8)}")
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
                DeviceRepository.log("控制连接：${e.message ?: e.javaClass.simpleName}")
                commands.disconnected { preserve ->
                    DeviceRepository.update{
                        val refresh=preserve&&it.controlRefreshUntil>SystemClock.elapsedRealtime()
                        it.copy(connected=false,status=if(preserve)"正在回读耳机设置…"else"等待重连 · ${e.message ?: "连接失败"}",
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
                DeviceRepository.update{it.copy(status="等待开盖或蓝牙连接")}
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
