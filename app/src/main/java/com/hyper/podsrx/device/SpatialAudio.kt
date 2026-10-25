package com.hyper.podsrx.device

import android.app.Service
import android.content.*
import android.media.*
import android.os.*
import com.hyper.podsrx.hook.SpatialWire
import com.hyper.podsrx.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

enum class SpatialMode { OFF, FIXED, HEAD_TRACKING }

data class SpatialState(
    val capable:Boolean=false,val available:Boolean=false,val enabled:Boolean=false,val can51:Boolean=false,
    val route:Boolean=false,val compatible:Boolean=false,val bridge:Boolean=false,
    val headAvailable:Boolean=false,val actualMode:Int=-2,val desiredMode:Int=-2,val nativeActive:Boolean=false,
    val status:String="等待读取系统空间音频",val motionActive:Boolean=false,val motionStatus:String="姿态采样未启动",
    val packets:Int=0,val poses:Int=0,val rate:Int=0,val vector:List<Double> = emptyList(),
    val calibrated:Boolean=false,val calibrationStep:Int=0,val collecting:Boolean=false,
    val testPlaying:Boolean=false,val testStatus:String="试听为低音量多声道测试音，可随时停止"
    ,val selfTesting:Boolean=false
    ,val acknowledgedService:Int=-1
    ,val acknowledgementError:Long=0,val sensorServices:String="",val ownershipStatus:String="尚未申请"
    ,val trackingRequested:Boolean=false,val trackingStarting:Boolean=false,val stopping:Boolean=false
    ,val calibrationMode:Boolean=false,val calibrationPreparing:Boolean=false,val notice:String=""
    ,val modeChanging:Boolean=false,val requestedMode:SpatialMode?=null
){
    val mode:SpatialMode? get()=when {
        !bridge -> null
        compatible&&nativeActive -> SpatialMode.HEAD_TRACKING
        compatible -> SpatialMode.FIXED
        else -> SpatialMode.OFF
    }
    fun diagnostic()=JSONObject().put("capable",capable).put("available",available).put("enabled",enabled)
        .put("can51",can51).put("route",route).put("compatible",compatible).put("bridge",bridge)
        .put("headAvailable",headAvailable).put("actualMode",actualMode).put("desiredMode",desiredMode).put("nativeActive",nativeActive)
        .put("status",status).put("motionActive",motionActive).put("motionStatus",motionStatus)
        .put("packets",packets).put("poses",poses).put("rate",rate).put("vector",vector.joinToString(","))
        .put("calibrated",calibrated).put("calibrationStep",calibrationStep).put("collecting",collecting)
        .put("testPlaying",testPlaying).put("testStatus",testStatus)
        .put("selfTesting",selfTesting)
        .put("acknowledgedService",acknowledgedService)
        .put("acknowledgementError",acknowledgementError).put("sensorServices",sensorServices).put("ownershipStatus",ownershipStatus)
        .put("trackingRequested",trackingRequested).put("trackingStarting",trackingStarting).put("stopping",stopping)
        .put("calibrationMode",calibrationMode).put("calibrationPreparing",calibrationPreparing).put("notice",notice)
        .put("mode",mode?.name).put("modeChanging",modeChanging).put("requestedMode",requestedMode?.name)
}

/** Runtime-only experiment. Never subscribes or creates a head tracker at boot. */
class SpatialAudio(private val context:Service,private val write:(ByteArray)->Boolean,private val restartControl:()->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val audio=context.getSystemService(AudioManager::class.java)
    private val spatial=audio.spatializer
    private val transactionMutex=Mutex()
    @Volatile private var remote:IBinder?=null
    private val owner=Binder()
    private val poseQueue=Channel<Pair<Long,List<Double>>>(Channel.CONFLATED)
    @Volatile private var profileService=16
    private var requestSequence=1024
    private val services=ConcurrentHashMap<Int,MotionCodec.SensorService>()
    @Volatile private var ownershipTouched=false
    private var ownershipBefore:Int?=null
    @Volatile private var finishing=false
    private var motionJob:Job?=null
    private var operationJob:Job?=null
    private var cleanupJob:Job?=null
    private var operation=0L
    @Volatile private var trackingRequested=false
    private var playbackJob:Job?=null
    private var session=0L
    @Volatile private var active=false
    @Volatile private var forwarding=false
    @Volatile private var selfTesting=false
    private var sequence=0L
    private var calibration=MotionCalibration()
    private var matrix:List<Double>?=null
    private var neutral:Quaternion?=null
    private var latest:Quaternion?=null
    @Volatile private var latestAt=0L
    private var lastUi=0L
    private var rateAt=0L
    private var rateCount=0
    private var rawSaved=0
    @Volatile private var poses=0
    private var packets=0
    private val preferences=context.getSharedPreferences("spatial_calibration",0)
    private fun state(block:(SpatialState)->SpatialState){DeviceRepository.update{it.copy(spatial=block(it.spatial))}}

    fun start(){
        scope.launch {
            for((captured,vector)in poseQueue){
                if(!forwarding||SystemClock.elapsedRealtime()-captured>250)continue
                val generation=session
                val answer=request(SpatialWire.POSE,Bundle().apply{putLong("captured",captured);putLong("sequence",++sequence);putFloatArray("vector",vector.map{it.toFloat()}.toFloatArray())})
                synchronized(this@SpatialAudio){
                    if(forwarding&&session==generation&&!answer.getBoolean("ok"))stopMotion("动态追踪已中断，请重新开启")
                }
            }
        }
        scope.launch {while(isActive){refresh();delay(1500)}}
    }
    private suspend fun bind():IBinder?=withContext(Dispatchers.Main){
        remote?.takeIf{it.isBinderAlive}?.let{return@withContext it}
        FrameworkBridge.publish()
        val deferred=CompletableDeferred<IBinder?>()
        context.sendOrderedBroadcast(Intent(SpatialWire.ACTION).setPackage("com.xiaomi.bluetooth")
            .putExtra("created",SystemClock.elapsedRealtime()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),null,
            object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
                val binder=if(resultCode==1)getResultExtras(false)?.getBinder("bridge")else null
                deferred.complete(binder)
            }},Handler(Looper.getMainLooper()),0,null,null)
        val binder=withTimeoutOrNull(1200){deferred.await()}
        remote=binder;binder
    }
    private suspend fun request(code:Int,extra:Bundle=Bundle()):Bundle=withContext(Dispatchers.IO){
        transactionMutex.withLock {
            try {
                val binder=bind()?:return@withLock Bundle().apply{putString("status","空间音频宿主未就绪，请检查小米蓝牙作用域")}
                val input=Parcel.obtain();val output=Parcel.obtain()
                try {
                    input.writeInterfaceToken(SpatialWire.DESCRIPTOR)
                    extra.putString("address",DeviceRepository.state.value.address);input.writeBundle(extra)
                    if(!binder.transact(code,input,output,0))error("不支持的桥接版本")
                    output.readException();output.readBundle(javaClass.classLoader)?:error("空桥接结果")
                }finally{input.recycle();output.recycle()}
            }catch(error:CancellationException){throw error}
            catch(error:Exception){remote=null;Bundle().apply{putString("status",error.message?:error.javaClass.simpleName)}}
        }
    }
    fun refreshNow(){scope.launch{refresh()}}
    private suspend fun refresh(){
        val current=DeviceRepository.state.value
        if(current.address.isEmpty())return
        if(!active&&!current.spatial.calibrationMode&&current.model.isNotBlank()&&current.firmware.isNotBlank())loadCalibration()
        val queriedSession=session;val wasForwarding=forwarding
        val b=request(SpatialWire.QUERY)
        runCatching {
            val format=AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
            val hasHead=spatial.isHeadTrackerAvailable
            synchronized(this){
            if(session!=queriedSession||forwarding!=wasForwarding)return
            if(forwarding&&!b.getBoolean("nativeActive")){stopMotion("动态追踪已停止，请重新开启");return}
            state{it.copy(capable=spatial.immersiveAudioLevel>0,available=spatial.isAvailable,enabled=spatial.isEnabled,
                can51=spatial.canBeSpatialized(attributes(),format),headAvailable=hasHead,
                route=b.getBoolean("route"),compatible=b.getBoolean("compatible"),bridge=b.getBoolean("ok"),
                actualMode=b.getInt("actualMode",-2),desiredMode=b.getInt("desiredMode",-2),nativeActive=forwarding&&b.getBoolean("nativeActive"),
                status=if(forwarding&&b.getBoolean("nativeActive")&&hasHead)"真实耳机姿态已接入系统"else b.getString("status", "空间音频查询失败"))}
            }
        }.onFailure{error->state{it.copy(status="系统空间能力读取失败：${error.message}")}}
        DeviceRepository.saveDiagnostics()
    }
    @Synchronized fun selectMode(mode:SpatialMode){
        val current=DeviceRepository.state.value.spatial
        if(current.modeChanging||finishing||current.calibrationMode||selfTesting)return
        if(mode==SpatialMode.HEAD_TRACKING){startTracking();return}
        val canStop=mode==SpatialMode.OFF&&(active||trackingRequested||forwarding)
        if((!current.bridge||!current.route)&&!canStop){notice("请连接耳机并等待系统空间音频就绪");return}
        if(current.mode==mode&&(mode==SpatialMode.OFF||current.desiredMode==-1)&&!active&&!trackingRequested&&!forwarding)return
        if(active||trackingRequested||forwarding)stopMotion("正在切换空间音频模式…")
        val cleanup=cleanupJob
        state{it.copy(modeChanging=true,requestedMode=mode,notice="")}
        scope.launch{
            try{
                // An in-flight START must finish cleanup before changing the device preference.
                cleanup?.join()
                ensureActive()
                val enabled=mode==SpatialMode.FIXED
                val answer=request(SpatialWire.FIXED,Bundle().apply{putBoolean("enabled",enabled)})
                check(answer.getBoolean("ok")){answer.getString("status","空间音频模式更新失败")}
                refresh()
                val reported=DeviceRepository.state.value.spatial
                check(reported.bridge&&reported.compatible==enabled&&!reported.nativeActive){"系统尚未确认空间音频模式，请重试"}
            }catch(error:CancellationException){throw error}
            catch(error:Exception){notice(error.message?:"空间音频模式更新失败");refresh()}
            finally{
                state{it.copy(modeChanging=false,requestedMode=null)}
                DeviceRepository.saveDiagnostics()
            }
        }
    }
    @Synchronized private fun loadCalibration(){
        if(DeviceRepository.state.value.spatial.calibrationMode)return
        val device=DeviceRepository.state.value
        matrix=if(preferences.getString("address","")==device.address&&preferences.getString("model","")==device.model&&preferences.getString("firmware","")==device.firmware)
            preferences.getString("matrix",null)?.split(",")?.mapNotNull{it.toDoubleOrNull()}?.takeIf{it.size==9&&it.all{v->v.isFinite()}} else null
        state{it.copy(calibrated=matrix!=null,calibrationStep=if(matrix!=null)3 else calibration.progress)}
    }
    private fun notice(text:String){state{it.copy(notice=text)};DeviceRepository.saveDiagnostics()}
    private suspend fun prepareMotion(){
        check(DeviceRepository.state.value.connected){"请先连接并佩戴双耳"}
        motion(true)
        withTimeout(25000){
            while(active&&SystemClock.elapsedRealtime()-latestAt>250)delay(50)
        }
        check(active&&latest!=null){"无法获取耳机姿态，请重新佩戴后重试"}
    }
    /** Head tracking owns discovery, the spatializer prerequisite and the real pose bridge. */
    @Synchronized private fun startTracking(){
        if(trackingRequested||finishing||DeviceRepository.state.value.spatial.modeChanging||DeviceRepository.state.value.spatial.calibrationMode)return
        loadCalibration()
        if(matrix==null){notice("请先完成三轴校准");return}
        if(!DeviceRepository.state.value.connected){notice("请先连接并佩戴双耳");return}
        trackingRequested=true;val generation=++operation
        state{it.copy(trackingRequested=true,trackingStarting=true,requestedMode=SpatialMode.HEAD_TRACKING,notice="")}
        operationJob=scope.launch{
            try{
                prepareMotion()
                val fixed=request(SpatialWire.FIXED,Bundle().apply{putBoolean("enabled",true)})
                check(fixed.getBoolean("ok")){"无法启用系统空间音频，请检查模块连接"}
                ensureActive()
                synchronized(this@SpatialAudio){
                    check(operation==generation&&active&&trackingRequested)
                    neutral=latest
                }
                val result=request(SpatialWire.START,Bundle().apply{putBinder("client",owner)})
                ensureActive()
                check(result.getBoolean("ok")){"无法开启动态追踪，请检查耳机连接后重试"}
                synchronized(this@SpatialAudio){
                    if(operation==generation&&active&&trackingRequested){
                        forwarding=true
                        state{it.copy(nativeActive=true,trackingStarting=false,requestedMode=null)}
                    }
                }
                refresh()
            }catch(error:TimeoutCancellationException){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion("耳机响应超时，请重新佩戴后重试")}
            }catch(error:CancellationException){throw error}
            catch(error:Exception){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion(error.message?:"开启失败，请重试")}
            }
        }
    }
    @Synchronized fun beginCalibration(){
        if(finishing||trackingRequested||forwarding||DeviceRepository.state.value.spatial.modeChanging||DeviceRepository.state.value.spatial.calibrationMode)return
        if(!DeviceRepository.state.value.connected){notice("请先连接并佩戴双耳");return}
        calibration=MotionCalibration();val generation=++operation
        // Keep the last successful matrix until all three replacement steps pass.
        state{it.copy(calibrationMode=true,calibrationPreparing=true,calibrationStep=0,collecting=false,notice="")}
        operationJob=scope.launch{
            try{
                prepareMotion()
                synchronized(this@SpatialAudio){if(operation==generation)state{it.copy(calibrationPreparing=false)}}
            }catch(error:TimeoutCancellationException){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion("耳机响应超时，请重新佩戴后重试")}
            }catch(error:CancellationException){throw error}
            catch(error:Exception){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion(error.message?:"校准准备失败，请重试")}
            }
        }
    }
    @Synchronized fun cancelCalibration(){
        if(!DeviceRepository.state.value.spatial.calibrationMode)return
        stopMotion("已取消校准，保留上次结果")
        calibration=MotionCalibration();loadCalibration()
    }
    @Synchronized fun motion(enabled:Boolean){
        if(!enabled){stopMotion("姿态采样已停止");return}
        if(active||selfTesting||finishing)return
        DeviceRepository.log("请求姿态订阅 · 工作协程 ${scope.isActive}")
        if(!DeviceRepository.state.value.connected){DeviceRepository.message("请先连接并佩戴双耳");return}
        DeviceRepository.update{it.copy(message="")}
        loadCalibration();active=true;session++;val generation=session
        neutral=null;latest=null;latestAt=0;poses=0;packets=0;rawSaved=0;rateAt=SystemClock.elapsedRealtime();rateCount=0;services.clear()
        File(context.filesDir,"motion-sample.bin").delete()
        state{it.copy(motionActive=true,motionStatus="正在订阅耳机姿态…",packets=0,poses=0,rate=0,vector=emptyList(),collecting=false,acknowledgedService=-1,acknowledgementError=0,sensorServices="",ownershipStatus="尚未申请")}
        motionJob=scope.launch {
            val route=request(SpatialWire.QUERY)
            if(!route.getBoolean("route")||audio.mode!=AudioManager.MODE_NORMAL){stopMotion("请将此耳机设为音频输出，并结束通话");return@launch}
            for(setup in MotionCodec.rtBuddySetup()){
                if(!active||session!=generation)return@launch
                write(setup);delay(150)
            }
            delay(800)
            state{it.copy(motionStatus="正在读取耳机传感器服务…")}
            if(!write(MotionCodec.descriptorRequest(++requestSequence))){stopMotion("传感器发现请求发送失败");return@launch}
            delay(2000)
            ownershipBefore=DeviceRepository.state.value.controls[6]?.firstOrNull()?.takeIf{it in 0..1}
            if(ownershipBefore!=1){
                state{it.copy(motionStatus="正在申请当前耳机控制会话…",ownershipStatus="请求中")}
                synchronized(this@SpatialAudio){
                    if(!active||session!=generation)return@launch
                    ownershipTouched=write(Aacp.control(6,listOf(1)))
                }
                delay(1200)
                val owns=DeviceRepository.state.value.controls[6]?.firstOrNull()==1
                state{it.copy(ownershipStatus=if(owns)"耳机已确认"else"已请求，尚无设备确认")}
                write(MotionCodec.descriptorRequest(++requestSequence));delay(1800)
            }else state{it.copy(ownershipStatus="当前已拥有控制会话")}
            val discovered=services.values.filter{it.name.equals("devmotion6",true)}.map{it.id}
            val candidates=if(discovered.isNotEmpty())discovered else listOf(16,14,6)
            for(candidate in candidates){
                if(!active||session!=generation)return@launch
                profileService=candidate
                state{it.copy(motionStatus="订阅姿态服务 $candidate",acknowledgedService=-1,acknowledgementError=0)}
                if(!write(MotionCodec.subscription(candidate,20000,++requestSequence))){stopMotion("姿态订阅发送失败");return@launch}
                delay(4000)
                if(poses>0)break
                write(MotionCodec.subscription(candidate,0,++requestSequence))
            }
            if(poses==0){stopMotion("尚未收到可解析的四元数姿态，已停止订阅");return@launch}
            while(active&&session==generation){
                delay(1000)
                if(SystemClock.elapsedRealtime()-latestAt>2500){stopMotion("姿态流已中断，已停止订阅");return@launch}
                if(!forwarding&&SystemClock.elapsedRealtime()-rateAt>300000){stopMotion("本次 5 分钟姿态诊断结束");return@launch}
            }
        }
    }
    @Synchronized private fun stopMotion(reason:String){
        if(finishing)return
        val pendingOperation=operationJob;operationJob=null;operation++
        pendingOperation?.cancel();trackingRequested=false
        if(active)write(MotionCodec.subscription(profileService,0,++requestSequence))
        val pendingMotion=motionJob
        active=false;forwarding=false;session++;motionJob?.cancel();motionJob=null
        val restore=ownershipTouched;val previous=ownershipBefore
        ownershipTouched=false;ownershipBefore=null;finishing=true
        state{it.copy(motionActive=false,nativeActive=false,motionStatus=reason,collecting=false,rate=0,
            trackingRequested=false,trackingStarting=false,requestedMode=null,stopping=true,calibrationMode=false,calibrationPreparing=false,notice=reason)}
        cleanupJob=scope.launch{
            try{
                // A cancelled START may already be inside Binder. Join it before STOP and
                // before allowing another user request to create a replacement tracker.
                pendingOperation?.join();pendingMotion?.join()
                while(poseQueue.tryReceive().isSuccess){}
                disableForwarding()
                if(restore&&DeviceRepository.state.value.connected){
                    if(previous==0)write(Aacp.control(6,listOf(0)))
                    else restartControl() // Unknown original session state: recreate the control channel.
                }
            }finally{synchronized(this@SpatialAudio){state{it.copy(ownershipStatus="采样会话已结束",stopping=false)};finishing=false}}
        }
        DeviceRepository.log(reason);DeviceRepository.saveDiagnostics()
    }
    @Synchronized fun packet(packet:ByteArray){
        if(!active)return
        packets++
        if(packets<=8||SystemClock.elapsedRealtime()-lastUi>200)state{it.copy(packets=packets)}
        if(rawSaved<64&&packet.size in 12..4096){
            rawSaved++
            File(context.filesDir,"motion-sample.bin").appendBytes(byteArrayOf((packet.size and 255).toByte(),(packet.size ushr 8).toByte())+packet)
        }
        val descriptors=MotionCodec.services(packet)
        if(descriptors.isNotEmpty()){
            descriptors.forEach{services[it.id]=it}
            state{it.copy(sensorServices=services.values.sortedBy{v->v.id}.joinToString(" · "){v->"${v.id}:${v.name}"})}
        }
        MotionCodec.acknowledgement(packet)?.let{acknowledge(it)}
        MotionCodec.decode(packet,profileService)?.let{onMotion(it)}
    }
    @Synchronized fun onMotion(event:Event.Motion){
        if(!active)return
        val now=SystemClock.elapsedRealtime();poses++;rateCount++
        latest=event.orientation;latestAt=now
        if(neutral==null)neutral=event.orientation
        if(DeviceRepository.state.value.spatial.collecting)calibration.sample(event.orientation)
        val raw=(neutral!!.inverse()*event.orientation).rotationVector()
        val v=matrix?.let{MotionCalibration.apply(it,raw)}?:raw
        if(forwarding)poseQueue.trySend(now to v)
        if(now-lastUi>200){
            lastUi=now
            state{it.copy(packets=packets,poses=poses,rate=(rateCount*1000/(now-rateAt).coerceAtLeast(1)).toInt(),vector=v,
                motionStatus="四元数姿态接收中 · 服务 ${event.service}")}
        }
    }
    fun acknowledge(ack:Event.MotionAcknowledgement){if(active)state{it.copy(acknowledgedService=ack.service,acknowledgementError=ack.error)}}
    @Synchronized fun calibrate(){
        val s=DeviceRepository.state.value.spatial
        if(!s.calibrationMode||s.calibrationPreparing||forwarding)return
        if(!active||SystemClock.elapsedRealtime()-latestAt>250){notice("请保持耳机连接后重试");return}
        notice("")
        if(!s.collecting){
            calibration.begin(latest!!);state{it.copy(collecting=true)}
        }else{
            if(!calibration.finish()){state{it.copy(collecting=false)};notice("本步动作不足或混入其他方向，请回正后重试");return}
            val replacement=calibration.matrix()
            if(replacement!=null){
                matrix=replacement
                val d=DeviceRepository.state.value
                preferences.edit().putString("address",d.address).putString("model",d.model).putString("firmware",d.firmware)
                    .putString("matrix",matrix!!.joinToString(",")).apply()
            }
            state{it.copy(collecting=false,calibrationStep=calibration.progress,calibrated=matrix!=null)}
            if(replacement!=null)stopMotion("校准已保存，可以开启动态头部追踪")
            else if(calibration.progress==3){calibration=MotionCalibration();state{it.copy(calibrationStep=0)};notice("三轴校准不一致，请重新记录")}
        }
        DeviceRepository.saveDiagnostics()
    }
    @Synchronized fun recenter(){
        if(!forwarding||SystemClock.elapsedRealtime()-latestAt>250)return
        neutral=latest
        notice("已回中，请保持手机位置固定")
    }
    fun forwarding(enabled:Boolean){scope.launch{
        if(!enabled){disableForwarding();refresh();return@launch}
        if(selfTesting||!active||matrix==null||SystemClock.elapsedRealtime()-latestAt>250){DeviceRepository.message("请先取得姿态流并完成三轴校准");return@launch}
        val generation=session
        val result=request(SpatialWire.START,Bundle().apply{putBinder("client",owner)})
        if(!active||session!=generation){request(SpatialWire.STOP);return@launch}
        forwarding=result.getBoolean("ok")
        state{it.copy(nativeActive=forwarding,status=result.getString("status","系统追踪启动失败"))}
        if(!forwarding)DeviceRepository.message(result.getString("status","系统追踪启动失败"))
        refresh()
    }}
    private suspend fun disableForwarding(){
        forwarding=false
        request(SpatialWire.STOP)
        state{it.copy(nativeActive=false)}
    }
    @Synchronized fun disconnected(lostControl:Boolean=true){if(lostControl)ownershipTouched=false;if(active||forwarding||trackingRequested||DeviceRepository.state.value.spatial.calibrationMode)stopMotion("耳机断开或已摘下，动态追踪已停止");stopPlayback()}
    private fun attributes()=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO).build()

    fun playTest(anchor:Boolean=false){
        if(playbackJob?.isActive==true)return
        DeviceRepository.log("请求多声道试听 · 工作协程 ${scope.isActive}")
        playbackJob=scope.launch(Dispatchers.IO){
            DeviceRepository.log("开始多声道试听")
            var track:AudioTrack?=null
            try {
                if(audio.mode!=AudioManager.MODE_NORMAL)error("请结束通话后试听")
                val device=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull{it.type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP&&it.address.equals(DeviceRepository.state.value.address,true)}
                    ?:error("此副耳机尚未连接为音频输出")
                val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
                val size=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_5POINT1,AudioFormat.ENCODING_PCM_16BIT)
                require(size>0){"系统未接受 5.1 PCM 格式"}
                track=AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(size,57600)).build()
                check(track.state==AudioTrack.STATE_INITIALIZED&&track.setPreferredDevice(device)){"无法锁定耳机试听输出"}
                track.setVolume(0.35f);track.play()
                track.write(ShortArray(4800*6),0,4800*6,AudioTrack.WRITE_BLOCKING)
                delay(120)
                check(track.routedDevice?.id==device.id){"实际输出未指向此副耳机，已停止试听"}
                state{it.copy(testPlaying=true,testStatus="5.1 测试音 · 当前输出 AirPods")}
                val labels=listOf("左前方","右前方","正前方","低频声道","左后方","右后方")
                val seconds=if(anchor)20 else 2
                val channels=if(anchor)listOf(2)else (0..5).toList()
                for(channel in channels){
                    state{it.copy(testStatus=if(anchor)"正前方定位试听 · 20 秒 · 手机保持固定"else
                        "5.1 试听 · ${labels[channel]} · ${if(spatial.isEnabled)"系统空间渲染已启用"else"系统空间渲染已关闭"}")}
                    for(chunk in 0 until seconds*10){
                        ensureActive()
                        check(audio.mode==AudioManager.MODE_NORMAL&&track.routedDevice?.id==device.id){"音频路由改变，试听已停止"}
                        val pcm=ShortArray(4800*6)
                        for(frame in 0 until 4800){
                            val t=(chunk*4800+frame)/48000.0
                            val fade=min(1.0,min(t/0.05,(seconds-t)/0.05)).coerceAtLeast(0.0)
                            val signal=if(anchor)(sin(2*PI*440*t)+sin(2*PI*733*t)+sin(2*PI*1173*t))/3
                                else sin(2*PI*(if(channel==3)80.0 else 440.0)*t)
                            pcm[frame*6+channel]=(signal*32767*0.1*fade).toInt().toShort()
                        }
                        check(track.write(pcm,0,pcm.size,AudioTrack.WRITE_BLOCKING)==pcm.size){"音频写入中断"}
                    }
                }
                delay(300)
                state{it.copy(testStatus="试听已结束 · 实际空间效果请结合听感与诊断确认")}
            }catch(error:CancellationException){state{it.copy(testStatus="试听已停止")};throw error}
            catch(error:Exception){state{it.copy(testStatus="试听未完成：${error.message}")}}
            finally {runCatching{track?.stop()};track?.release();state{it.copy(testPlaying=false)};DeviceRepository.saveDiagnostics()}
        }
    }
    fun stopPlayback(){playbackJob?.cancel()}
    fun selfTest(){scope.launch{
        if(active||forwarding||selfTesting){DeviceRepository.message("请先停止姿态采样再自检");return@launch}
        selfTesting=true;state{it.copy(selfTesting=true,status="系统桥接自检中 · 使用静止测试姿态")}
        try {
            val result=request(SpatialWire.START,Bundle().apply{putBinder("client",owner)})
            if(!result.getBoolean("ok"))error(result.getString("status","创建失败"))
            repeat(60){
                val reply=request(SpatialWire.POSE,Bundle().apply{putLong("captured",SystemClock.elapsedRealtime());putLong("sequence",++sequence);putFloatArray("vector",floatArrayOf(0f,0f,0f))})
                check(reply.getBoolean("ok")){reply.getString("status","姿态提交失败")}
                delay(50)
            }
            val status=request(SpatialWire.QUERY)
            val available=spatial.isHeadTrackerAvailable
            DeviceRepository.log("系统桥接自检：关联 ${status.getBoolean("hasTracker")}，可用 $available")
            DeviceRepository.message(if(available)"自检通过：系统发现头部传感器；尚不代表耳机动态追踪通过"else"自检尚未通过：虚拟传感器未关联音频输出")
        }catch(error:Exception){DeviceRepository.message("桥接自检失败：${error.message}")}
        finally{withContext(NonCancellable){request(SpatialWire.STOP)};selfTesting=false;state{it.copy(selfTesting=false)};refresh()}
    }}
    fun close(){
        if(active)write(MotionCodec.subscription(profileService,0,++requestSequence))
        if(ownershipTouched&&ownershipBefore==0)write(Aacp.control(6,listOf(0)))
        ownershipTouched=false;active=false;forwarding=false;trackingRequested=false
        playbackJob?.cancel();scope.cancel();poseQueue.close()
        // A separate bounded cleanup survives service coroutine cancellation; host also has a lease/death guard.
        CoroutineScope(Dispatchers.IO).launch{withTimeoutOrNull(2000){request(SpatialWire.STOP)}}
        state{it.copy(motionActive=false,nativeActive=false,testPlaying=false,collecting=false,rate=0,
            trackingRequested=false,trackingStarting=false,stopping=false,calibrationMode=false,calibrationPreparing=false,
            modeChanging=false,requestedMode=null)}
    }
}
