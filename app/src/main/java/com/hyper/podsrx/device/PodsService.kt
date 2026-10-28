package com.hyper.podsrx.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.hyper.podsrx.MainActivity
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.HubBundles
import com.hyper.podsrx.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SuppressLint("MissingPermission")
class PodsService: Service() {
    companion object {
        @Volatile private var instance: PodsService?=null
        const val EQ_COMMAND=ControlCommands.EQUALIZER
        fun setControl(id:Int,value:Int) {
            val current=DeviceRepository.state.value.controls[id].orEmpty()
            instance?.control?.enqueue(id,Controls.valuesForWrite(id,value,current)) ?: DeviceRepository.message("请先连接耳机")
        }
        fun pendingControlValue(id:Int):Int? = instance?.control?.pendingValue(id)
        fun setEqualizer(values:List<Int>) {instance?.control?.enqueue(EQ_COMMAND,values) ?: DeviceRepository.message("请先连接耳机")}
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
        }
        override fun onAccepted(command:ControlCommands.Command) {
            if(command.id==0x28)conversation.requested(command.values.first())
        }
    }
    private val lidHandler=Handler(Looper.getMainLooper())
    /** The hub follows the lid; the app adds its card where no native popup showed, and its channel. */
    private fun lid(event:Int,nativeShown:Boolean) { lidHandler.post {
        when(event) {
            HubBundles.LID_EVENT_OPENED -> {
                // The hub's state notice may still be on its way; the card checks this.
                DeviceRepository.update{it.copy(lidOpen=true)}
                DeviceRepository.log("已验证盒盖：打开")
                if(nativeShown)DeviceRepository.log("已提交 HyperOS 原生耳机提示：充电盒已打开")
                else if(DeviceRepository.state.value.popupEnabled)popup.showFallback()
            }
            HubBundles.LID_EVENT_CONFIRMED -> {
                DeviceRepository.log("开盖状态已稳定")
                control.reconnectIfClosed()
            }
            HubBundles.LID_EVENT_CLOSED -> {
                DeviceRepository.update{it.copy(lidOpen=false)}
                DeviceRepository.log("已验证盒盖：关闭")
                popup.close(animated=true)
            }
        }
        DeviceRepository.saveDiagnostics()
    } }
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
    private var receiverRegistered=false
    private lateinit var popup:PopupController
    private lateinit var spatial:SpatialAudio
    private lateinit var earDetection:EarDetectionController
    private lateinit var conversation:ConversationController
    private lateinit var hubClient:HubClient
    private val systemReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==BluetoothAdapter.ACTION_STATE_CHANGED) {
                if(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,0)==BluetoothAdapter.STATE_ON)control.requestReconnect()
                else control.closeSocket()
            } else if(intent.action==BluetoothDevice.ACTION_ACL_CONNECTED||intent.action==BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                val device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                if(!device?.address.equals(DeviceRepository.state.value.address,true))return
                control.linkChanged(intent.action==BluetoothDevice.ACTION_ACL_CONNECTED)
            } else if(intent.action==BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                if(!device?.address.equals(DeviceRepository.state.value.address,true))return
                audioReady=intent.getIntExtra(BluetoothProfile.EXTRA_STATE,0)==BluetoothProfile.STATE_CONNECTED
                if(audioReady)control.audioConnected()
                showConnectionCard()
            } else if(intent.action==BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                if(!device?.address.equals(DeviceRepository.state.value.address,true))return
                if(intent.getIntExtra(BluetoothProfile.EXTRA_STATE,0)==BluetoothProfile.STATE_CONNECTED)control.audioConnected()
            }
        }
    }
    override fun onCreate() {
        super.onCreate(); instance=this
        hubClient=HubClient(this,::lid)
        popup=PopupController(this){preview,island,onResult->hubClient.requestPopup(preview,island,onResult)}
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
        registerReceiver(systemReceiver,IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply{
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED);addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        },RECEIVER_EXPORTED)
        receiverRegistered=true
        control.start()
        // The hub's readings arrive without an AACP event; the notification follows them as well.
        scope.launch {
            DeviceRepository.state.map{it.batteries.content()}.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(10,notification(DeviceRepository.batterySummary()))
            }
        }
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
    override fun onDestroy() {
        if(instance===this)instance=null
        if (::hubClient.isInitialized) hubClient.close()
        lidHandler.removeCallbacksAndMessages(null)
        spatial.close()
        conversation.close()
        earDetection.close()
        scope.cancel();control.close()
        popup.close();if(receiverRegistered)unregisterReceiver(systemReceiver)
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
