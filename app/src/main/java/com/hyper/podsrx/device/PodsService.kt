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

@SuppressLint("MissingPermission")
class PodsService: Service() {
    companion object {
        @Volatile private var instance: PodsService?=null
        const val EQ_COMMAND=ControlCommands.EQUALIZER
        // Out of range, nothing refreshes the readings; past this age they are hidden.
        private const val STALE_READING_MS=60000L
        private const val EXPIRY_CHECK_MS=10000L
        // Must stay well under STALE_READING_MS so an unchanged case status keeps its readings fresh.
        private const val CASE_REFRESH_MS=20000L
        // Unheard and unconnected this long, the headset is treated as away and the live scan idles.
        private const val IDLE_SCAN_MS=180000L
        // Android silently drops a sixth scan start within 30 s; the slack covers clock skew.
        private const val SCAN_STARTS_PER_WINDOW=5
        private const val SCAN_WINDOW_MS=30000L
        private const val SCAN_WINDOW_SLACK_MS=1000L
        fun setControl(id:Int,value:Int) {
            val current=DeviceRepository.state.value.controls[id].orEmpty()
            instance?.control?.enqueue(id,Controls.valuesForWrite(id,value,current)) ?: DeviceRepository.message("请先连接耳机")
        }
        fun pendingControlValue(id:Int):Int? = instance?.control?.pendingValue(id)
        fun setEqualizer(values:List<Int>) {instance?.control?.enqueue(EQ_COMMAND,values) ?: DeviceRepository.message("请先连接耳机")}
        fun diagnoseScan(isolated:Boolean=false) {instance?.let{
            if(isolated){it.scanOnlyUntil=SystemClock.elapsedRealtime()+120000;it.control.closeSocket();DeviceRepository.log("广播诊断：暂缓控制连接 2 分钟")}
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
        fun reconnect() {instance?.control?.reconnectNow()?:DeviceRepository.start()}
        fun setCallControl(flipped:Boolean) {
            val current=DeviceRepository.state.value.controls[0x24]?:return
            if(current.size<2)return
            instance?.control?.enqueue(0x24,current.toMutableList().also{it[1]=if(flipped)2 else 3})
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var control:ControlSession
    private val controlListener=object:ControlSession.Listener {
        override fun pausedUntil()=scanOnlyUntil
        override fun onOpened(wasReadback:Boolean) {
            lidHandler.post{connectionCardShown=wasReadback;batteriesReady=false}
            conversation.connected()
        }
        override fun onEvent(event:Event,session:Long)=this@PodsService.onEvent(event,session)
        override fun onSpatialPacket(packet:ByteArray)=spatial.packet(packet)
        override fun onLost() {
            conversation.disconnected()
            earDetection.disconnected()
            spatial.disconnected()
            if(DeviceRepository.state.value.connected)controlLostAt=System.currentTimeMillis()
        }
        override fun onAccepted(command:ControlCommands.Command) {
            if(command.id==0x28)conversation.requested(command.values.first())
        }
    }
    @Volatile private var scanOnlyUntil=0L
    private var scanner:BluetoothLeScanner?=null
    private var scanCallback:ScanCallback?=null
    private var liveCallback:ScanCallback?=null
    private var caseCallback:ScanCallback?=null
    // Main thread only. A new service starts as if the headset were just heard.
    private var liveIdle=false
    private var lastHeardAt=SystemClock.elapsedRealtime()
    // Main thread only. A dropped start reports nothing, so starts are counted here and a start
    // that would exceed the budget waits for it instead.
    private val scanStarts=ArrayDeque<Long>()
    private val retryScan=Runnable{startScan()}
    private var lastCase:List<Battery>?=null
    private var lastCaseAt=0L
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
            control.reconnectIfClosed()
            DeviceRepository.saveDiagnostics()
        }
    }
    // The control channel reports batteries only when they change, so its readings age only once it
    // drops, counted from that moment; broadcast readings always age.
    @Volatile private var controlLostAt=0L
    private val expireReadings=object:Runnable {
        override fun run() {
            val connected=DeviceRepository.state.value.connected
            if(connected)heard()
            else if(liveCallback!=null&&!liveIdle&&SystemClock.elapsedRealtime()-lastHeardAt>=IDLE_SCAN_MS)setLiveIdle(true)
            val controlFreshAt=if(connected)System.currentTimeMillis()else controlLostAt
            if(DeviceRepository.expireBatteries(STALE_READING_MS,controlFreshAt)){
                DeviceRepository.log("${STALE_READING_MS/1000} 秒未收到耳机广播，隐藏过期电量")
                getSystemService(NotificationManager::class.java).notify(10,notification(DeviceRepository.batterySummary()))
            }
            lidHandler.postDelayed(this,EXPIRY_CHECK_MS)
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
    private lateinit var hubClient:HubClient
    private val systemReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==BluetoothAdapter.ACTION_STATE_CHANGED) {
                if(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,0)==BluetoothAdapter.STATE_ON) {startScan();control.requestReconnect()}
                else { control.closeSocket(); stopScan() }
            } else if(intent.action==BluetoothDevice.ACTION_ACL_CONNECTED) {
                val device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                if(!device?.address.equals(DeviceRepository.state.value.address,true))return
                heard()
                control.reconnectIfClosed()
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
        control=ControlSession(this,scope,controlListener)
        spatial=SpatialAudio(this,write={packet->control.write(packet)},restartControl={control.restartForReadback()})
        spatial.start()
        lidHandler.postDelayed(expireReadings,EXPIRY_CHECK_MS)
        nativeControls=NativeControlBridge(this)
        hubClient=HubClient(this)
        registerReceiver(systemReceiver,IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply{
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED);addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON)
        },RECEIVER_EXPORTED)
        registerReceiver(relayReceiver,IntentFilter(LidWire.ACTION),RECEIVER_EXPORTED); receiverRegistered=true
        control.start()
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
    private fun onEvent(event:Event,session:Long) {
        if(event is Event.Motion){spatial.onMotion(event);return}
        if(event is Event.MotionAcknowledgement){spatial.acknowledge(event);return}
        DeviceRepository.update{it.copy(lastPacket=System.currentTimeMillis(),status="耳机控制已连接")}
        when(event) {
            is Event.Batteries -> {
                DeviceRepository.batteries(event.values,true,control=true)
                hubClient.controlBatteries(event.values)
                if(event.values.any{it.component in setOf(2,4)&&it.level!=null&&it.connected})lidHandler.post{batteriesReady=true;showConnectionCard()}
            }
            is Event.Control -> {
                control.onControl(event,session)
                if(event.id==0x28)conversation.configured()
            }
            is Event.Equalizer -> {
                val values=listOf(event.state,event.low,event.mid,event.high)
                DeviceRepository.update{it.copy(equalizer=values)}
                control.onEqualizer(values)
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
                hubClient.pushKeys()
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
        if(scanCallback!=null||liveCallback!=null||caseCallback!=null||!DeviceRepository.permissionsGranted())return
        if(!diagnostic&&!getSystemService(PowerManager::class.java).isInteractive)return
        val adapter=getSystemService(BluetoothManager::class.java).adapter ?: return
        if(!adapter.isEnabled)return
        // A diagnostic request has already stopped the regular scans, which the retry brings back.
        if(!reserveScanStarts(if(diagnostic)1 else 2,retry=true)){
            if(diagnostic)DeviceRepository.message("扫描启动过于频繁，请稍后再开始广播诊断")
            return
        }
        scanner=adapter.bluetoothLeScanner
        DeviceRepository.update{it.copy(diagnosticScanning=diagnostic)}
        if(diagnostic){
            // The only unfiltered scan; it lists what the radio hears and nothing else runs meanwhile.
            val callback=object:ScanCallback(){
                override fun onScanResult(type:Int,result:ScanResult){if(scanCallback===this)advertisement(result,true)}
                override fun onBatchScanResults(results:MutableList<ScanResult>){
                    if(scanCallback===this)results.sortedBy{it.timestampNanos}.forEach{advertisement(it,true)}
                }
                override fun onScanFailed(errorCode:Int){DeviceRepository.log("BLE 扫描错误 $errorCode");scanCallback=null}
            }
            scanCallback=callback
            diagnosticRecords=0;diagnosticShapes.clear();DeviceRepository.log("开始 2 分钟广播诊断，不记录地址或广播内容")
            runCatching{scanner?.startScan(emptyList(),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(true).setPhy(BluetoothDevice.PHY_LE_1M).setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT).setReportDelay(500).build(),callback)}
                .onFailure{scanCallback=null;DeviceRepository.log("扫描启动失败：${it.javaClass.simpleName}")}
            scope.launch{delay(120000);lidHandler.post{if(scanCallback===callback){
                DeviceRepository.log("广播诊断结束：$diagnosticRecords 条；厂商/长度 ${diagnosticShapes.joinToString(" ")}")
                DeviceRepository.saveDiagnostics()
                stopScan();startScan()
            }}}
            return
        }
        // After a long silence the headset is likely away; the ROM keeps scanning while it is
        // disconnected, and the relay hears it come back.
        startLiveScan(idle=!DeviceRepository.state.value.connected&&SystemClock.elapsedRealtime()-lastHeardAt>=IDLE_SCAN_MS)
        // The case status is connectable, so the stack leaves it on the regular scan path. The
        // offloaded filter only matched once the pattern spanned the whole payload.
        val case=object:ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){if(caseCallback===this)advertisement(result,source="盒子状态")}
            override fun onScanFailed(errorCode:Int){DeviceRepository.log("盒子状态扫描错误 $errorCode");caseCallback=null}
        }
        caseCallback=case
        val casePattern=ByteArray(CaseStatusCodec.LENGTH+2).apply{this[0]=7;this[1]=CaseStatusCodec.LENGTH.toByte()}
        val caseMask=ByteArray(CaseStatusCodec.LENGTH+2).apply{this[0]=-1;this[1]=-1}
        runCatching{scanner?.startScan(listOf(ScanFilter.Builder().setManufacturerData(0x004c,casePattern,caseMask).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setLegacy(true).setReportDelay(0).build(),case)}
            .onFailure{caseCallback=null;DeviceRepository.log("盒子状态扫描启动失败：${it.javaClass.simpleName}")}
    }
    /**
     * The stack hands Apple advertisements from regular scans to the ROM provider, which the module
     * relays at once; this scan keeps the radio listening while the ROM pauses its own. Third-party
     * batch scans arrive 5-20 s late, so nothing else watches the proximity messages. A short lid
     * advertisement was missed with LOW_POWER, which is only used while the headset is away.
     */
    private fun startLiveScan(idle:Boolean) {
        val scanner=scanner ?: return
        val pattern=ByteArray(27).apply{this[0]=7;this[1]=25}
        val mask=ByteArray(27).apply{this[0]=-1;this[1]=-1}
        val live=object:ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){if(liveCallback===this)advertisement(result,source="实时扫描")}
            override fun onScanFailed(errorCode:Int){DeviceRepository.log("实时扫描错误 $errorCode");liveCallback=null}
        }
        liveCallback=live;liveIdle=idle
        runCatching{scanner.startScan(listOf(ScanFilter.Builder().setManufacturerData(0x004c,pattern,mask).build()),
            ScanSettings.Builder().setScanMode(if(idle)ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(true).setReportDelay(0).build(),live)}
            .onFailure{liveCallback=null;DeviceRepository.log("实时扫描启动失败：${it.javaClass.simpleName}")}
    }
    /** Main thread only. Restarts the live scan in the other mode; a stopped scan stays stopped. */
    private fun setLiveIdle(idle:Boolean) {
        val current=liveCallback ?: return
        // Without a start to spare the current scan keeps running; the next tick or sighting retries.
        if(liveIdle==idle||!reserveScanStarts(1,retry=false))return
        runCatching{scanner?.stopScan(current)}
        liveCallback=null
        startLiveScan(idle)
        DeviceRepository.log(if(idle)"${IDLE_SCAN_MS/60000} 分钟未听到耳机，实时扫描降为省电模式" else "已听到耳机，实时扫描恢复高频")
    }
    /**
     * Main thread only. Records [count] scan starts if they fit the system's budget; otherwise
     * nothing is recorded and, with [retry], the scans start once the oldest starts age out.
     */
    private fun reserveScanStarts(count:Int,retry:Boolean):Boolean {
        val now=SystemClock.elapsedRealtime()
        while(scanStarts.isNotEmpty()&&now-scanStarts.first()>=SCAN_WINDOW_MS)scanStarts.removeFirst()
        val excess=scanStarts.size+count-SCAN_STARTS_PER_WINDOW
        if(excess<=0){repeat(count){scanStarts.addLast(now)};return true}
        if(retry){
            val wait=SCAN_WINDOW_MS-(now-scanStarts.elementAt(excess-1))+SCAN_WINDOW_SLACK_MS
            lidHandler.removeCallbacks(retryScan);lidHandler.postDelayed(retryScan,wait)
            DeviceRepository.log("扫描启动过于频繁，${(wait+999)/1000} 秒后启动")
        }
        return false
    }
    /** Main thread only. The bound headset was heard or connected. */
    private fun heard() {
        lastHeardAt=SystemClock.elapsedRealtime()
        if(liveIdle)setLiveIdle(false)
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
        val (caseMessages,proximity)=messages.partition{it[1].toInt()==CaseStatusCodec.LENGTH}
        caseMessages.forEach{caseStatus(it,address)}
        if(!matches)return
        val shape="trusted:${data.firstOrNull()?.toInt()?.and(255)}:${data.size}"
        if(seenAdvertisements.add(shape))DeviceRepository.log("已验证的广播 $shape")
        for(message in proximity){
        val status=ProximityCodec.decode(message,DeviceRepository.keys.get(address,4))?:continue
        DeviceRepository.update{it.copy(trustedBroadcasts=it.trustedBroadcasts+1,identityReady=true)}
        heard()
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
    /** The case repeats its status every couple of seconds; only changes and a slow refresh reach the repository. */
    private fun caseStatus(message:ByteArray,address:String) {
        val values=CaseStatusCodec.decode(message,DeviceRepository.keys.get(address,4))?.filter{it.level!=null}?:return
        heard()
        val now=SystemClock.elapsedRealtime()
        if(values==lastCase&&now-lastCaseAt<CASE_REFRESH_MS)return
        if(lastCase==null)DeviceRepository.log("已接收盒子状态广播")
        lastCase=values;lastCaseAt=now
        DeviceRepository.batteries(values,precise=true)
    }
    private fun stopScan(){lidHandler.removeCallbacks(retryScan);runCatching{scanCallback?.let{scanner?.stopScan(it)}};runCatching{liveCallback?.let{scanner?.stopScan(it)}};runCatching{caseCallback?.let{scanner?.stopScan(it)}};scanCallback=null;liveCallback=null;caseCallback=null;lastCase=null;scanner=null;DeviceRepository.update{it.copy(diagnosticScanning=false)}}
    override fun onDestroy() {
        if(instance===this)instance=null
        if (::nativeControls.isInitialized) nativeControls.close()
        if (::hubClient.isInitialized) hubClient.close()
        lidHandler.removeCallbacks(confirmLid);lidHandler.removeCallbacksAndMessages(closeToken);lidHandler.removeCallbacks(expireReadings)
        spatial.close()
        conversation.close()
        earDetection.close()
        scope.cancel();control.close()
        stopScan();popup.close();if(receiverRegistered){unregisterReceiver(systemReceiver);unregisterReceiver(relayReceiver)}
        DeviceRepository.update{it.copy(running=false,connected=false,status="耳机服务已停止",batteries=emptyMap(),controls=emptyMap(),equalizer=null,pending=emptySet(),adaptiveTarget=null,lidOpen=null,wearing=null,earDetectionStatus="耳机服务已停止")}
        FrameworkBridge.publish();DeviceRepository.saveDiagnostics();super.onDestroy()
    }
}

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        if(DeviceRepository.permissionsGranted()&&DeviceRepository.state.value.address.isNotBlank())DeviceRepository.start()
    }
}
