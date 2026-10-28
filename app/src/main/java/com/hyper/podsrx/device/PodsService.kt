package com.hyper.podsrx.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.hyper.podsrx.MainActivity
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.LidWire
import com.hyper.podsrx.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class PodsService: Service() {
    companion object {
        @Volatile private var instance: PodsService?=null
        const val EQ_COMMAND=256
        private const val SILENT_HANDSHAKE_MS=5000L
        private const val SILENT_RETRIES=3
        fun setControl(id:Int,value:Int) {
            val current=DeviceRepository.state.value.controls[id].orEmpty()
            instance?.enqueue(id,Controls.valuesForWrite(id,value,current)) ?: DeviceRepository.message("请先连接耳机")
        }
        // Read alongside observable state.pending; desired survives a readback reconnect.
        fun pendingControlValue(id:Int):Int? = instance?.desired?.get(id)?.values?.firstOrNull()
        fun setEqualizer(values:List<Int>) {instance?.enqueue(EQ_COMMAND,values) ?: DeviceRepository.message("请先连接耳机")}
        fun diagnoseScan(isolated:Boolean=false) {instance?.let{
            if(isolated){it.scanOnlyUntil=SystemClock.elapsedRealtime()+120000;runCatching{it.socket?.close()};DeviceRepository.log("广播诊断：暂缓控制连接 2 分钟")}
            it.stopScan();it.startScan(diagnostic=true)
        }}
        fun previewPopup() {instance?.popup?.show("弹窗预览",preview=true)?:DeviceRepository.message("请先启动耳机服务")}
        fun previewIsland() {instance?.popup?.show("超级岛预览",preview=true)?:DeviceRepository.message("请先启动耳机服务")}
        fun spatialRefresh(){instance?.spatial?.refreshNow()}
        fun spatialBeginCalibration(){instance?.spatial?.beginCalibration()}
        fun spatialCancelCalibration(){instance?.spatial?.cancelCalibration()}
        fun spatialCalibrate(){instance?.spatial?.calibrate()}
        fun spatialRecenter(){instance?.spatial?.recenter()}
        fun spatialMode(mode:SpatialMode){instance?.spatial?.selectMode(mode)?:DeviceRepository.message("请先连接耳机")}
        fun earAutomationChanged(){instance?.earDetection?.preferenceChanged()}
        fun reconnect() {instance?.let{runCatching{it.socket?.close()};it.reconnect.trySend(Unit)}?:DeviceRepository.start()}
        fun setCallControl(flipped:Boolean) {
            val current=DeviceRepository.state.value.controls[0x24]?:return
            if(current.size<2)return
            instance?.enqueue(0x24,current.toMutableList().also{it[1]=if(flipped)2 else 3})
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var socket: BluetoothSocket?=null
    private data class Command(val id:Int,val values:List<Int>,val token:Long)
    private val commandSequence=AtomicLong()
    private val controlSessionSequence=AtomicLong()
    private val controlSessionIds=ConcurrentHashMap<BluetoothSocket,Long>()
    private val commands=Channel<Command>(Channel.UNLIMITED)
    private val reconnect=Channel<Unit>(Channel.CONFLATED)
    private val desired=ConcurrentHashMap<Int,Command>()
    @Volatile private var readingBack=false
    @Volatile private var readbackStartedInSession=0L
    private val readbackScheduled=AtomicBoolean()
    @Volatile private var scanOnlyUntil=0L
    private var scanner:BluetoothLeScanner?=null
    private var scanCallback:ScanCallback?=null
    private var liveCallback:ScanCallback?=null
    private var receiverRegistered=false
    private var relayed=false
    private val relayReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            // Only the Bluetooth host may speak for the stack's Apple scan path.
            if(intent.action!=LidWire.ACTION||sentFromUid!=Process.BLUETOOTH_UID)return
            val result=intent.getParcelableExtra(LidWire.EXTRA_RESULT,ScanResult::class.java)?:return
            if(!relayed){relayed=true;DeviceRepository.log("已接入系统实时 Apple 广播通道")}
            advertisement(result,source="系统通道")
        }
    }
    // Relayed advertisements arrive tens of milliseconds apart and closes are no longer missed, so
    // every observed reopen is real and a short settle still rides out a wobbling lid.
    private val lid=LidSession(cooldownMs=0,settleMs=300)
    private val lidHandler=Handler(Looper.getMainLooper())
    // The popup starts with the first open sample so its process launch overlaps the settle;
    // a lid that wobbles shut inside it closes the popup again.
    private val confirmLid=Runnable {
        if(lid.confirm(SystemClock.elapsedRealtime())){
            DeviceRepository.log("开盖状态已稳定")
            if(socket==null)reconnect.trySend(Unit)
            DeviceRepository.saveDiagnostics()
        }
    }
    // A close can reach the dialog's process before the dialog listens for it; repeat it briefly.
    private val closeToken=Any()
    private var screenOnAt=Long.MIN_VALUE/2
    // Main thread only. The control channel connects seconds before audio can play, so the
    // connection card waits for both the earbuds' batteries and an A2DP connection.
    private var connectionCardShown=false
    private var batteriesReady=false
    private var audioReady=false
    private fun showConnectionCard() {
        if(connectionCardShown||!batteriesReady||!audioReady)return
        connectionCardShown=true
        if(DeviceRepository.state.value.connectionPopup&&DeviceRepository.state.value.lidOpen!=true)popup.show("耳机已连接")
    }
    private val seenOpcodes=mutableSetOf<Int>()
    private val seenAdvertisements=mutableSetOf<String>()
    private var lastScanSnapshot=0L
    private var diagnosticRecords=0
    private val diagnosticShapes=mutableSetOf<String>()
    private lateinit var popup:PopupController
    private lateinit var spatial:SpatialAudio
    private lateinit var earDetection:EarDetectionController
    private lateinit var conversation:ConversationController
    private lateinit var nativeControls:NativeControlBridge
    private val systemReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==BluetoothAdapter.ACTION_STATE_CHANGED) {
                if(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,0)==BluetoothAdapter.STATE_ON) {startScan();reconnect.trySend(Unit)}
                else { runCatching{socket?.close()}; stopScan() }
            } else if(intent.action==BluetoothDevice.ACTION_ACL_CONNECTED) {
                val device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                if(device?.address.equals(DeviceRepository.state.value.address,true)&&socket==null)reconnect.trySend(Unit)
            } else if(intent.action==BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                if(!device?.address.equals(DeviceRepository.state.value.address,true))return
                audioReady=intent.getIntExtra(BluetoothProfile.EXTRA_STATE,0)==BluetoothProfile.STATE_CONNECTED
                showConnectionCard()
            } else if(intent.action==Intent.ACTION_SCREEN_OFF) {
                // A popup cannot be seen with the screen off, so the scans stop. The lid may move
                // unseen meanwhile; forget it so the next open is a fresh transition.
                stopScan()
                lidHandler.removeCallbacks(confirmLid);lidHandler.removeCallbacksAndMessages(closeToken)
                lid.reset();DeviceRepository.update{it.copy(lidOpen=null)}
            } else if(intent.action==Intent.ACTION_SCREEN_ON) {
                screenOnAt=SystemClock.elapsedRealtime()
                startScan()
            }
        }
    }
    override fun onCreate() {
        super.onCreate(); instance=this; popup=PopupController(this)
        earDetection=EarDetectionController(this)
        conversation=ConversationController(this)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection","耳机连接",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("lid","开盖提示",NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(10,notification("正在连接 AirPods"))
        DeviceRepository.update{it.copy(running=true,message="")}
        spatial=SpatialAudio(this,write={packet->
            val channel=socket
            if(channel==null)false else runCatching{synchronized(channel){channel.outputStream.write(packet);channel.outputStream.flush()};true}.getOrDefault(false)
        },restartControl={
            prepareReadback()
            runCatching{socket?.close()};reconnect.trySend(Unit)
        })
        spatial.start()
        nativeControls=NativeControlBridge(this)
        registerReceiver(systemReceiver,IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply{
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED);addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON)
        },RECEIVER_EXPORTED)
        registerReceiver(relayReceiver,IntentFilter(LidWire.ACTION),RECEIVER_EXPORTED); receiverRegistered=true
        scope.launch { for(command in commands) {
            val (id,values)=command
            if(desired[id]!==command)continue
            if(id==0x2e && DeviceRepository.state.value.controls[0x0d]?.firstOrNull()!=4) {
                complete(command)
                DeviceRepository.message("请在自适应模式下调整降噪幅度"); continue
            }
            try {
                val s=socket ?: throw IOException("控制通道未连接")
                DeviceRepository.update{it.copy(message="")}
                DeviceRepository.saveDiagnostics()
                val packet=if(id==EQ_COMMAND)Aacp.equalizer(values)else Aacp.control(id,values)
                val writtenSession=controlSessionIds[s] ?: 0L
                val writtenAt=synchronized(s) {s.outputStream.write(packet);s.outputStream.flush();SystemClock.elapsedRealtime()}
                traceControl(id,"write",command.token,writtenSession,writtenAt) {
                    "packet=${packet.joinToString(" "){(it.toInt() and 255).toString(16).padStart(2,'0')}}"
                }
                if(id==0x2e){
                    // LibrePods streams these writes without waiting for a matching reply.
                    // Keep the sent target separate from the earbud's actual control report.
                    synchronized(desired){
                        if(desired[id]===command)DeviceRepository.update{it.copy(pending=it.pending-id)}
                    }
                    DeviceRepository.saveDiagnostics()
                    continue
                }
                scope.launch {
                    delay(1200)
                    // Keep the control session alive while the adaptive slider is moving.
                    if(id!=0x2e&&socket===s&&desired[id]===command&&readbackScheduled.compareAndSet(false,true)){
                        scope.launch {
                            try {
                                prepareReadback()
                                DeviceRepository.log("主动重连控制通道以回读设置")
                                runCatching{s.close()};reconnect.trySend(Unit)
                                delay(1000)
                            } finally {readbackScheduled.set(false)}
                        }
                    }
                }
                scope.launch {
                    delay(12000)
                    if(complete(command)) {
                        traceControl(id,"timeout",command.token) {
                            "target=${command.values} actual=${DeviceRepository.state.value.controls[id]}"
                        }
                        DeviceRepository.message("${Controls.all.find{it.id==id}?.title ?: if(id==EQ_COMMAND)"均衡器" else "设置"}未确认目标值，已保留设备回读结果")
                    }
                }
            } catch(e:Exception) {
                complete(command)
                DeviceRepository.message("设置发送失败：${e.message}")
            }
        } }
        scope.launch { connectLoop() }
        startScan()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    private fun notification(text:String):Notification {
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"connection").setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("HyperPods-X · ${DeviceRepository.state.value.name}").setContentText(text)
            .setContentIntent(intent).setOngoing(true).setSilent(true).build()
    }
    private fun enqueue(id:Int,values:List<Int>) {
        val state=DeviceRepository.state.value
        if(!state.connected){DeviceRepository.message("耳机控制尚未连接");return}
        if(state.controlRefreshUntil!=0L){DeviceRepository.message("正在回读耳机设置，请稍候");return}
        if(id==0x2e && !Controls.canAdjustAdaptive(state.connected,state.controls,state.pending)) {
            DeviceRepository.message("请等待自适应模式与降噪幅度读取完成"); return
        }
        if(id==0x0d && 0x2e in state.pending) {
            DeviceRepository.message("请等待降噪指令发送完成后再切换模式"); return
        }
        val allowed=if(id==0x24)values.size==4&&values.all{it in 0..255}&&values[1] in 2..3&&state.controls[0x24]?.getOrNull(1) in 2..3
            else if(id==EQ_COMMAND)state.equalizer!=null&&values.size==4&&values[0] in 1..2&&values.drop(1).all{it in 0..100}
            else Controls.allowed(id,values)&&state.controls.containsKey(id)
        if(!allowed) {
            DeviceRepository.message("当前设置或取值尚未开放写入"); return
        }
        val command=Command(id,values.toList(),commandSequence.incrementAndGet())
        synchronized(desired){
            if(id==0x2e){
                if(!Controls.shouldSendAdaptive(values.first(),Controls.adaptiveStrength(DeviceRepository.state.value.controls),desired[id]?.values?.firstOrNull()))return
                desired[id]=command
            }else if(desired.putIfAbsent(id,command)!=null)return
            traceControl(id,"accepted",command.token) { "target=${command.values} actual=${state.controls[id]}" }
            DeviceRepository.update{it.copy(pending=it.pending+id,adaptiveTarget=if(id==0x2e)values.first()else it.adaptiveTarget)}
            if(id==0x28)conversation.requested(values.first())
            if(commands.trySend(command).isFailure)complete(command)
        }
    }
    private fun complete(command:Command):Boolean=synchronized(desired){
        if(!desired.remove(command.id,command))false else{
            DeviceRepository.update{it.copy(pending=it.pending-command.id,adaptiveTarget=if(command.id==0x2e)null else it.adaptiveTarget)}
            true
        }
    }
    // Only these setting values are logged; never include a device address or other AACP payloads.
    private fun traceControl(id:Int,stage:String,token:Long?=null,
        session:Long=controlSessionSequence.get(),elapsedMs:Long=SystemClock.elapsedRealtime(),details:()->String) {
        if(id!=0x0d&&id!=0x1f&&id!=0x23&&id!=0x25&&id!=0x2e)return
        DeviceRepository.log("设置诊断 id=0x${id.toString(16)} stage=$stage elapsedMs=$elapsedMs session=$session token=${token ?: "none"} ${details()}")
    }
    private fun prepareReadback() {
        readingBack=true
        readbackStartedInSession=controlSessionSequence.get()
        val until=SystemClock.elapsedRealtime()+5000
        DeviceRepository.update { it.copy(controlRefreshUntil=until) }
        scope.launch {
            delay(5000)
            DeviceRepository.update {
                if(it.controlRefreshUntil!=until)it else it.copy(controlRefreshUntil=0,
                    controls=emptyMap(),equalizer=null,batteries=it.batteries.mapValues{(_,b)->b.copy(current=false)})
            }
        }
    }
    private suspend fun connectLoop() {
        var attempts=0
        var silentRetries=0
        while(scope.isActive) {
            val pause=scanOnlyUntil-SystemClock.elapsedRealtime()
            if(pause>0){
                DeviceRepository.update{it.copy(status="广播诊断中 · 暂缓控制连接")}
                withTimeoutOrNull(pause.coerceAtMost(30000)){reconnect.receive()}
                continue
            }
            var current:BluetoothSocket?=null
            var watchdog:Job?=null
            val silent=AtomicBoolean(false)
            try {
                val adapter=getSystemService(BluetoothManager::class.java).adapter ?: throw IOException("蓝牙不可用")
                if(!adapter.isEnabled)throw IOException("蓝牙已关闭")
                val target=adapter.getRemoteDevice(DeviceRepository.state.value.address)
                if(adapter.isDiscovering)adapter.cancelDiscovery()
                DeviceRepository.update{
                    val refresh=it.controlRefreshUntil>SystemClock.elapsedRealtime()
                    it.copy(status="建立耳机控制连接…",connected=false,
                        controls=if(refresh)it.controls else emptyMap(),equalizer=if(refresh)it.equalizer else null,
                        controlRefreshUntil=if(refresh)it.controlRefreshUntil else 0)
                }
                val opened=ClassicTransport.create(adapter,target)
                val session=controlSessionSequence.incrementAndGet()
                controlSessionIds[opened]=session
                current=opened; socket=opened
                val timeout=scope.launch{delay(12000);if(!opened.isConnected)runCatching{opened.close()}}
                try{opened.connect()}finally{timeout.cancel()}
                val wasReadback=readingBack
                readingBack=false
                DeviceRepository.log("经典 L2CAP 通道已连接")
                lidHandler.post{connectionCardShown=wasReadback;batteriesReady=false}
                DeviceRepository.update{it.copy(status="正在读取耳机状态…",connected=true)}
                conversation.connected()
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
                watchdog=scope.launch{
                    delay(SILENT_HANDSHAKE_MS)
                    if(!received.get()){
                        silent.set(true)
                        DeviceRepository.log("握手后 ${SILENT_HANDSHAKE_MS/1000} 秒无响应，重建控制通道")
                        runCatching{opened.close()}
                    }
                }
                val framer=AacpFramer(); val buffer=ByteArray(65536)
                while(scope.isActive) {
                    val count=opened.inputStream.read(buffer)
                    if(count<=0)throw IOException("耳机控制通道已关闭")
                    if(!received.getAndSet(true)){watchdog?.cancel();silentRetries=0}
                    if(count>=8 && buffer.copyOfRange(0,4).contentEquals(Aacp.header) && buffer[4]==0x4b.toByte() && buffer[5]==0.toByte()) {
                        DeviceRepository.update{it.copy(conversation=it.conversation.copy(wirePackets=it.conversation.wirePackets+1))}
                        DeviceRepository.log("对话原始报文：读取 $count 字节，载荷长度 ${(buffer[6].toInt() and 255) or ((buffer[7].toInt() and 255) shl 8)}")
                    }
                    for(packet in framer.feed(buffer.copyOf(count))){
                        if(packet.size>=6&&packet[4].toInt()==0x17){spatial.packet(packet);continue}
                        Aacp.decode(packet)?.let{onEvent(it,session)}
                    }
                }
            } catch(e:Exception) {
                if(!scope.isActive)break
                conversation.disconnected()
                earDetection.disconnected()
                spatial.disconnected()
                DeviceRepository.log("控制连接：${e.message ?: e.javaClass.simpleName}")
                val preserve=readingBack
                synchronized(desired){
                    desired.remove(0x2e)
                    if(!preserve)desired.clear()
                    DeviceRepository.update{
                        val refresh=preserve&&it.controlRefreshUntil>SystemClock.elapsedRealtime()
                        it.copy(connected=false,status=if(preserve)"正在回读耳机设置…"else"等待重连 · ${e.message ?: "连接失败"}",
                            controls=if(refresh)it.controls else emptyMap(),equalizer=if(refresh)it.equalizer else null,
                            controlRefreshUntil=if(refresh)it.controlRefreshUntil else 0,
                            pending=if(preserve)it.pending-0x2e else emptySet(),adaptiveTarget=null,
                            batteries=if(refresh)it.batteries else it.batteries.mapValues{(_,b)->b.copy(current=false)})
                    }
                }
                FrameworkBridge.publish(); DeviceRepository.saveDiagnostics()
            } finally {watchdog?.cancel();runCatching{current?.close()};if(socket===current)socket=null;current?.let{controlSessionIds.remove(it)}}
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
    private fun onEvent(event:Event,session:Long) {
        if(event is Event.Motion){spatial.onMotion(event);return}
        if(event is Event.MotionAcknowledgement){spatial.acknowledge(event);return}
        DeviceRepository.update{it.copy(lastPacket=System.currentTimeMillis(),status="耳机控制已连接")}
        when(event) {
            is Event.Batteries -> {
                DeviceRepository.batteries(event.values,true)
                if(event.values.any{it.component in setOf(2,4)&&it.level!=null&&it.connected})lidHandler.post{batteriesReady=true;showConnectionCard()}
            }
            is Event.Control -> {
                DeviceRepository.update{it.copy(controls=it.controls+(event.id to event.value),
                    controlRefreshUntil=if(event.id==0x0d&&session>readbackStartedInSession)0 else it.controlRefreshUntil)}
                val expected=desired[event.id]
                val matched=expected!=null&&event.value.take(expected.values.size)==expected.values
                traceControl(event.id,"readback",expected?.token,session) {
                    "actual=${event.value} target=${expected?.values} matched=${if(expected==null)"n/a" else matched.toString()}"
                }
                if(expected!=null&&matched)complete(expected)
                if(event.id==0x0d&&event.value.firstOrNull()!=4){
                    synchronized(desired){
                        desired.remove(0x2e)
                        DeviceRepository.update{it.copy(adaptiveTarget=null,pending=it.pending-0x2e)}
                    }
                }
                if(event.id==0x28)conversation.configured()
            }
            is Event.Equalizer -> {
                val values=listOf(event.state,event.low,event.mid,event.high)
                DeviceRepository.update{it.copy(equalizer=values)}
                desired[EQ_COMMAND]?.let{expected->if(expected.values==values)complete(expected)}
            }
            is Event.CardStatus -> {
                if(DeviceRepository.state.value.cardStatus!=event.flags)DeviceRepository.log("耳机卡片状态 ${event.flags.joinToString(",")}")
                DeviceRepository.update{it.copy(cardStatus=event.flags)}
            }
            is Event.Information -> {
                DeviceRepository.update{it.copy(name=event.name,model=event.model,firmware=event.firmware)}
                DeviceRepository.log("型号 ${event.model} · 固件 ${event.firmware}")
            }
            is Event.Keys -> {
                val address=DeviceRepository.state.value.address
                event.values.forEach{(type,bytes)->DeviceRepository.keys.put(address,type,bytes)}
                val ready=DeviceRepository.keys.get(address,1)!=null
                DeviceRepository.update{it.copy(identityReady=ready)}
                DeviceRepository.log("绑定身份密钥状态：${if(ready) "就绪" else "未取得有效值"}")
            }
            is Event.Wearing -> {
                if(event.inEarCount==0)spatial.disconnected(lostControl=false)
                earDetection.wearing(event,DeviceRepository.state.value.address)
                conversation.wearing(event)
            }
            is Event.Conversation -> { conversation.event(event); return }
            is Event.AudioSource -> {
                DeviceRepository.update{it.copy(conversation=it.conversation.copy(audioSource=event.state))}
                DeviceRepository.log("耳机音频来源状态：${event.state}")
            }
            is Event.Unknown -> {
                if(seenOpcodes.add(event.opcode))DeviceRepository.log("接收协议 0x${event.opcode.toString(16)} · ${event.length} 字节")
                return
            }
            is Event.Motion -> return
            is Event.MotionAcknowledgement -> return
        }
        getSystemService(NotificationManager::class.java).notify(10,notification(DeviceRepository.batterySummary()))
        DeviceRepository.saveDiagnostics()
    }
    private fun startScan(diagnostic:Boolean=false) {
        if(scanCallback!=null||!DeviceRepository.permissionsGranted())return
        if(!diagnostic&&!getSystemService(PowerManager::class.java).isInteractive)return
        val adapter=getSystemService(BluetoothManager::class.java).adapter ?: return
        if(!adapter.isEnabled)return
        scanner=adapter.bluetoothLeScanner
        val callback=object:ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){if(scanCallback===this)advertisement(result,diagnostic)}
            override fun onBatchScanResults(results:MutableList<ScanResult>){
                if(scanCallback===this)results.sortedBy{it.timestampNanos}.forEach{advertisement(it,diagnostic)}
            }
            override fun onScanFailed(errorCode:Int){DeviceRepository.log("BLE 扫描错误 $errorCode");scanCallback=null}
        }
        scanCallback=callback
        DeviceRepository.update{it.copy(diagnosticScanning=diagnostic)}
        val pattern=ByteArray(27).apply{this[0]=7;this[1]=25}
        val mask=ByteArray(27).apply{this[0]=-1;this[1]=-1}
        val filter=ScanFilter.Builder().setManufacturerData(0x004c,pattern,mask).build()
        if(diagnostic){diagnosticRecords=0;diagnosticShapes.clear();DeviceRepository.log("开始 2 分钟广播诊断，不记录地址或广播内容")}
        // A short lid advertisement was missed with LOW_POWER on this phone. Keep the
        // proven scan mode, with a manufacturer filter during normal operation.
        runCatching{scanner?.startScan(if(diagnostic)emptyList()else listOf(filter),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(true).setPhy(BluetoothDevice.PHY_LE_1M).setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).setReportDelay(500).build(),callback)}
            .onFailure{scanCallback=null;DeviceRepository.log("扫描启动失败：${it.javaClass.simpleName}")}
        // The stack hands Apple advertisements from regular scans to the ROM provider, which the
        // module relays at once; this scan keeps the radio listening while the ROM pauses its own.
        if(!diagnostic){
            val live=object:ScanCallback(){
                override fun onScanResult(type:Int,result:ScanResult){if(liveCallback===this)advertisement(result,source="实时扫描")}
                override fun onScanFailed(errorCode:Int){DeviceRepository.log("实时扫描错误 $errorCode");liveCallback=null}
            }
            liveCallback=live
            runCatching{scanner?.startScan(listOf(filter),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(true).setReportDelay(0).build(),live)}
                .onFailure{liveCallback=null;DeviceRepository.log("实时扫描启动失败：${it.javaClass.simpleName}")}
        }
        if(diagnostic)scope.launch{delay(120000);if(scanCallback===callback){
            DeviceRepository.log("广播诊断结束：$diagnosticRecords 条；厂商/长度 ${diagnosticShapes.joinToString(" ")}")
            DeviceRepository.saveDiagnostics()
            stopScan();startScan()
        }}
    }
    private fun advertisement(result:ScanResult,diagnostic:Boolean=false,source:String="批量扫描") {
        val record=result.scanRecord?:return
        val address=DeviceRepository.state.value.address
        val irk=DeviceRepository.keys.get(address,1)
        val matches=address.equals(result.device.address,true)||(irk!=null&&ProximityCodec.matchesIdentity(result.device.address,irk))
        if(diagnostic){
            diagnosticRecords++
            val manufacturers=record.manufacturerSpecificData
            for(i in 0 until manufacturers.size())if(diagnosticShapes.size<48){
                val payload=manufacturers.valueAt(i)
                diagnosticShapes.add("${manufacturers.keyAt(i).toString(16)}/${payload.firstOrNull()?.toInt()?.and(255)?.toString(16)}:${payload.size}")
            }
        }
        if(matches){DeviceRepository.update{it.copy(identityMatches=it.identityMatches+1)}}
        val data=record.getManufacturerSpecificData(0x004c)?:return
        DeviceRepository.update{it.copy(broadcasts=it.broadcasts+1)}
        val messages=AppleAdvertisement.proximityMessages(data)
        if(messages.isNotEmpty())DeviceRepository.update{it.copy(proximityCandidates=it.proximityCandidates+messages.size)}
        if(data.isNotEmpty()) {
            val shape="${data[0].toInt() and 255}:${data.size}"
            if(seenAdvertisements.add(shape))DeviceRepository.log("Apple 广播类型/长度 $shape")
        }
        if(SystemClock.elapsedRealtime()-lastScanSnapshot>5000){lastScanSnapshot=SystemClock.elapsedRealtime();DeviceRepository.saveDiagnostics()}
        if(!matches)return
        val shape="trusted:${data.firstOrNull()?.toInt()?.and(255)}:${data.size}"
        if(seenAdvertisements.add(shape))DeviceRepository.log("已验证的广播 $shape")
        for(message in messages){
        val status=ProximityCodec.decode(message,DeviceRepository.keys.get(address,4))?:continue
        DeviceRepository.update{it.copy(trustedBroadcasts=it.trustedBroadcasts+1,identityReady=true)}
        DeviceRepository.product(status.modelId)
        val now=SystemClock.elapsedRealtime()
        val capturedAt=result.timestampNanos/1000000
        if(!lid.observe(status.lidOpen,capturedAt,now))continue
        val previous=DeviceRepository.state.value.lidOpen
        val changed=previous!=status.lidOpen
        // The first state heard right after the screen turns on was not seen opening; it is a baseline.
        val baseline=previous==null&&now-screenOnAt<1500
        if(changed)DeviceRepository.log("已验证盒盖：${if(status.lidOpen)"打开"else"关闭"} · 广播延迟 ${now-capturedAt}ms · $source")
        DeviceRepository.update{it.copy(lidOpen=status.lidOpen)}
        DeviceRepository.batteries(status.batteries,status.precise)
        if(!status.lidOpen){
            popup.close(animated=true)
            if(changed){
                NativePopupBridge.close(this)
                for(delay in longArrayOf(700,1500))lidHandler.postDelayed({
                    if(DeviceRepository.state.value.lidOpen==false)NativePopupBridge.close(this)
                },closeToken,delay)
            }
        }
        lidHandler.removeCallbacks(confirmLid)
        val settle=lid.confirmationDelay(now)
        if(status.lidOpen&&changed){
            lidHandler.removeCallbacksAndMessages(closeToken)
            if(settle!=null&&!baseline&&DeviceRepository.state.value.popupEnabled)popup.show()
        }
        settle?.let{lidHandler.postDelayed(confirmLid,it)}
        }
    }
    private fun stopScan(){runCatching{scanCallback?.let{scanner?.stopScan(it)}};runCatching{liveCallback?.let{scanner?.stopScan(it)}};scanCallback=null;liveCallback=null;scanner=null;DeviceRepository.update{it.copy(diagnosticScanning=false)}}
    override fun onDestroy() {
        if(instance===this)instance=null
        if (::nativeControls.isInitialized) nativeControls.close()
        lidHandler.removeCallbacks(confirmLid);lidHandler.removeCallbacksAndMessages(closeToken)
        spatial.close()
        conversation.close()
        earDetection.close()
        scope.cancel();commands.close();runCatching{socket?.close()};socket=null
        stopScan();popup.close();if(receiverRegistered){unregisterReceiver(systemReceiver);unregisterReceiver(relayReceiver)}
        DeviceRepository.update{it.copy(running=false,connected=false,status="耳机服务已停止",controls=emptyMap(),equalizer=null,pending=emptySet(),adaptiveTarget=null,lidOpen=null,wearing=null,earDetectionStatus="耳机服务已停止",batteries=it.batteries.mapValues{(_,b)->b.copy(current=false)})}
        FrameworkBridge.publish();DeviceRepository.saveDiagnostics();super.onDestroy()
    }
}

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        if(DeviceRepository.permissionsGranted()&&DeviceRepository.state.value.address.isNotBlank())DeviceRepository.start()
    }
}
