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
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SuppressLint("MissingPermission")
class PodsService: Service() {
    companion object {
        private val strings get() = DeviceRepository.app.stringResolver()
        @Volatile private var instance: PodsService?=null
        const val EQ_COMMAND=ControlCommands.EQUALIZER
        // BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED is hidden from the public SDK.
        private const val A2DP_ACTIVE_DEVICE_CHANGED="android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
        fun setControl(id:Int,value:Int) {
            val current=DeviceRepository.state.value.controls[id].orEmpty()
            instance?.control?.enqueue(id,Controls.valuesForWrite(id,value,current)) ?: DeviceRepository.message(R.string.connect_earbuds_first)
        }
        fun pendingControlValue(id:Int):Int? = instance?.control?.pendingValue(id)
        fun setEqualizer(values:List<Int>) {instance?.control?.enqueue(EQ_COMMAND,values) ?: DeviceRepository.message(R.string.connect_earbuds_first)}
        fun previewPopup() {instance?.popup?.show(PopupReason.PREVIEW,preview=true)?:DeviceRepository.message(R.string.start_earbud_service_first)}
        fun previewIsland() {instance?.popup?.show(PopupReason.ISLAND_PREVIEW,preview=true)?:DeviceRepository.message(R.string.start_earbud_service_first)}
        fun spatialRefresh(){instance?.spatial?.refreshNow()}
        fun refreshHubDiagnostics(){instance?.hubClient?.refreshDiagnostics()}
        fun spatialBeginCalibration(){instance?.spatial?.beginCalibration()}
        fun spatialCancelCalibration(){instance?.spatial?.cancelCalibration()}
        fun spatialCalibrate(){instance?.spatial?.calibrate()}
        fun spatialRecenter(){instance?.spatial?.recenter()}
        fun spatialMode(mode:SpatialMode){instance?.spatial?.selectMode(mode)?:DeviceRepository.message(R.string.connect_earbuds_first)}
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
            spatial.refreshNow()
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
                DeviceRepository.log(strings(R.string.lid_verified_open))
                if(nativeShown)DeviceRepository.log(strings(R.string.native_popup_lid_submitted))
                else if(DeviceRepository.state.value.popupEnabled)popup.showFallback()
            }
            HubBundles.LID_EVENT_CONFIRMED -> {
                DeviceRepository.log(strings(R.string.lid_open_stable))
                control.reconnectIfClosed()
            }
            HubBundles.LID_EVENT_CLOSED -> {
                DeviceRepository.update{it.copy(lidOpen=false)}
                DeviceRepository.log(strings(R.string.lid_verified_closed))
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
        if(DeviceRepository.state.value.connectionPopup&&DeviceRepository.state.value.lidOpen!=true)popup.show(PopupReason.CONNECTED)
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
                spatial.refreshNow()
                showConnectionCard()
            } else if(intent.action==A2DP_ACTIVE_DEVICE_CHANGED) {
                // The spatial route follows the active output.
                spatial.refreshNow()
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
        updateNotificationChannels()
        startForeground(10,notification(strings(R.string.connecting_airpods)))
        DeviceRepository.update{it.copy(running=true,message="",messageCode=0,messageControl=null,messageText=text(""))}
        control=ControlSession(this,scope,controlListener,hubClient::openControl)
        spatial=SpatialAudio(this,write={packet->control.write(packet)},restartControl={control.restartForReadback()})
        spatial.start()
        registerReceiver(systemReceiver,IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply{
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED);addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(A2DP_ACTIVE_DEVICE_CHANGED)
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
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
        getSystemService(NotificationManager::class.java).notify(10, notification(DeviceRepository.batterySummary()))
    }
    private fun updateNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection", strings(R.string.notification_channel_connection), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("lid", strings(R.string.notification_channel_lid), NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun dump(fd:java.io.FileDescriptor, writer:java.io.PrintWriter, args:Array<out String>?) {
        // ActivityManager gates service dumps with DUMP; never expose transport bytes or keys.
        writer.println(com.hyper.podsrx.hook.LogRedactor.redact(DeviceRepository.diagnosticSnapshot()
            .put("running", DeviceRepository.state.value.running)
            .put("elapsedRealtime", SystemClock.elapsedRealtime())
            .toString(2)))
    }
    private fun notification(text:String):Notification {
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"connection").setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(DeviceRepository.state.value.name).setContentText(text)
            .setContentIntent(intent).setOngoing(true).setSilent(true).build()
    }
    private fun onEvent(event:Event,session:Long) {
        if(event is Event.Motion){spatial.onMotion(event);return}
        if(event is Event.MotionAcknowledgement){spatial.acknowledge(event);return}
        DeviceRepository.update{it.copy(lastPacket=System.currentTimeMillis(),status=text(R.string.earbud_control_connected))}
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
                if(DeviceRepository.state.value.cardStatus!=event.flags)DeviceRepository.log(strings(R.string.earbud_card_status, event.flags.joinToString(",")))
                DeviceRepository.update{it.copy(cardStatus=event.flags)}
            }
            is Event.Information -> {
                DeviceRepository.update{it.copy(name=event.name,model=event.model,firmware=event.firmware,
                    internalFirmware=event.internalFirmware,firmwareIdentifier=event.firmwareIdentifier)}
                DeviceRepository.log(strings(R.string.model_firmware_log, event.model,
                    event.firmware.ifBlank{strings(R.string.firmware_not_provided)}))
            }
            is Event.Keys -> {
                val address=DeviceRepository.state.value.address
                event.values.forEach{(type,bytes)->DeviceRepository.keys.put(address,type,bytes)}
                val ready=DeviceRepository.keys.get(address,1)!=null
                DeviceRepository.update{it.copy(identityReady=ready)}
                hubClient.pushKeys()
                DeviceRepository.log(strings(R.string.identity_key_status, if(ready) strings(R.string.ready) else strings(R.string.valid_value_missing)))
            }
            is Event.Wearing -> {
                if(event.inEarCount==0)spatial.disconnected(lostControl=false)
                earDetection.wearing(event,DeviceRepository.state.value.address)
                conversation.wearing(event)
            }
            is Event.Conversation -> { conversation.event(event); return }
            is Event.AudioSource -> {
                DeviceRepository.update{it.copy(conversation=it.conversation.copy(audioSource=event.state))}
                DeviceRepository.log(strings(R.string.earbud_audio_source, event.state))
            }
            is Event.Unknown -> {
                if(seenOpcodes.add(event.opcode))DeviceRepository.log(strings(R.string.protocol_received, event.opcode.toString(16), event.length))
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
        DeviceRepository.update{it.copy(running=false,connected=false,status=text(R.string.earbud_service_stopped),batteries=emptyMap(),controls=emptyMap(),equalizer=null,pending=emptySet(),adaptiveTarget=null,lidOpen=null,wearing=null,earDetectionStatus=text(R.string.earbud_service_stopped))}
        FrameworkBridge.publish();DeviceRepository.flushDiagnostics();super.onDestroy()
    }
}

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        if(DeviceRepository.permissionsGranted()&&DeviceRepository.state.value.address.isNotBlank())DeviceRepository.start()
    }
}
