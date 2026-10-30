package com.hyper.podsrx.device

import android.app.Service
import android.content.*
import android.media.*
import android.os.*
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.SpatialWire
import com.hyper.podsrx.i18n.Strings
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import com.hyper.podsrx.protocol.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class SpatialMode { OFF, FIXED, HEAD_TRACKING }

data class SpatialState(
    val capable:Boolean=false,val available:Boolean=false,val enabled:Boolean=false,val can51:Boolean=false,
    val route:Boolean=false,val compatible:Boolean=false,val bridge:Boolean=false,
    val headAvailable:Boolean=false,val actualMode:Int=-2,val desiredMode:Int=-2,val nativeActive:Boolean=false,
    val status:String="",val motionActive:Boolean=false,val motionStatus:String="",
    val packets:Int=0,val poses:Int=0,val rate:Int=0,val vector:List<Double> = emptyList(),
    val calibrated:Boolean=false,val calibrationStep:Int=0,val collecting:Boolean=false,
    val testPlaying:Boolean=false,val testStatus:String=""
    ,val selfTesting:Boolean=false
    ,val acknowledgedService:Int=-1
    ,val acknowledgementError:Long=0,val sensorServices:String="",val ownershipStatus:String=""
    ,val trackingRequested:Boolean=false,val trackingStarting:Boolean=false,val stopping:Boolean=false
    ,val calibrationMode:Boolean=false,val calibrationPreparing:Boolean=false,val notice: UiText = text("")
    ,val modeChanging:Boolean=false,val requestedMode:SpatialMode?=null
){
    val mode:SpatialMode? get()=when {
        !bridge -> null
        compatible&&nativeActive -> SpatialMode.HEAD_TRACKING
        compatible -> SpatialMode.FIXED
        else -> SpatialMode.OFF
    }
    fun diagnostic(strings: Strings)=JSONObject().put("capable",capable).put("available",available).put("enabled",enabled)
        .put("can51",can51).put("route",route).put("compatible",compatible).put("bridge",bridge)
        .put("headAvailable",headAvailable).put("actualMode",actualMode).put("desiredMode",desiredMode).put("nativeActive",nativeActive)
        .put("status",status.ifBlank { strings(R.string.waiting_system_spatial) }).put("motionActive",motionActive)
        .put("motionStatus",motionStatus.ifBlank { strings(R.string.motion_not_started) })
        .put("packets",packets).put("poses",poses).put("rate",rate).put("vector",vector.joinToString(","))
        .put("calibrated",calibrated).put("calibrationStep",calibrationStep).put("collecting",collecting)
        .put("testPlaying",testPlaying).put("testStatus",testStatus.ifBlank { strings(R.string.spatial_test_description) })
        .put("selfTesting",selfTesting)
        .put("acknowledgedService",acknowledgedService)
        .put("acknowledgementError",acknowledgementError).put("sensorServices",sensorServices)
        .put("ownershipStatus",ownershipStatus.ifBlank { strings(R.string.not_requested) })
        .put("trackingRequested",trackingRequested).put("trackingStarting",trackingStarting).put("stopping",stopping)
        .put("calibrationMode",calibrationMode).put("calibrationPreparing",calibrationPreparing).put("notice",notice.resolve(strings))
        .put("mode",mode?.name).put("modeChanging",modeChanging).put("requestedMode",requestedMode?.name)
}

/** Runtime-only experiment. Never subscribes or creates a head tracker at boot. */
class SpatialAudio(private val context:Service,private val write:(ByteArray)->Boolean,private val restartControl:()->Unit) {
    private val strings = context.stringResolver()
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
                    if(forwarding&&session==generation&&!answer.getBoolean("ok"))stopMotion(text(R.string.tracking_interrupted))
                }
            }
        }
        scope.launch {
            while(isActive){
                refresh()
                // A visible page or a running session follows the host as it moves; otherwise the
                // route, the control channel and the pages ask for a refresh when it can change.
                if(following())withTimeoutOrNull(followMs){wake.receive()} else wake.receive()
            }
        }
        // A session started from a native panel, with no page open, is followed as well.
        scope.launch {
            DeviceRepository.state.map{it.spatial.let{s->s.calibrationMode||s.modeChanging||s.trackingRequested}}
                .distinctUntilChanged().collect{if(it)wake.trySend(Unit)}
        }
    }
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val followMs=1500L
    private fun following():Boolean {
        val s=DeviceRepository.state.value.spatial
        return DeviceRepository.pagesVisible.value>0||active||forwarding||s.calibrationMode||s.modeChanging||s.trackingRequested
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
                repeat(2) { attempt ->
                    val binder=bind()?:return@withLock Bundle().apply{putString("status",strings(R.string.spatial_host_not_ready))}
                    val input=Parcel.obtain();val output=Parcel.obtain()
                    val answer=try {
                        input.writeInterfaceToken(SpatialWire.DESCRIPTOR)
                        extra.putString("address",DeviceRepository.state.value.address);input.writeBundle(extra)
                        if(!binder.transact(code,input,output,0))error(strings(R.string.bridge_version_unsupported))
                        output.readException();output.readBundle(javaClass.classLoader)?:error(strings(R.string.bridge_empty_response))
                    }finally{input.recycle();output.recycle()}
                    // A completed retirement explicitly guarantees no command ran.
                    // Ordinary negative replies are not safe to retry automatically.
                    if(answer.getBoolean("bridgeRetired")){
                        if(remote===binder)remote=null
                        if(attempt==0)return@repeat
                    }
                    return@withLock answer
                }
                error(strings(R.string.spatial_bridge_reconnect_failed))
            }catch(error:CancellationException){throw error}
            catch(error:Exception){remote=null;Bundle().apply{putString("status",error.message?:error.javaClass.simpleName)}}
        }
    }
    fun refreshNow(){wake.trySend(Unit)}
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
            if(forwarding&&!b.getBoolean("nativeActive")){stopMotion(text(R.string.tracking_stopped_restart));return}
            state{it.copy(capable=spatial.immersiveAudioLevel>0,available=spatial.isAvailable,enabled=spatial.isEnabled,
                can51=spatial.canBeSpatialized(attributes(),format),headAvailable=hasHead,
                route=b.getBoolean("route"),compatible=b.getBoolean("compatible"),bridge=b.getBoolean("ok"),
                actualMode=b.getInt("actualMode",-2),desiredMode=b.getInt("desiredMode",-2),nativeActive=forwarding&&b.getBoolean("nativeActive"),
                status=if(forwarding&&b.getBoolean("nativeActive")&&hasHead)strings(R.string.earbud_motion_connected)else b.getString("status", strings(R.string.spatial_query_failed)))}
            }
        }.onFailure{error->state{it.copy(status=strings(R.string.spatial_capability_failed, error.message))}}
        DeviceRepository.saveDiagnostics()
    }
    @Synchronized fun selectMode(mode:SpatialMode){
        val current=DeviceRepository.state.value.spatial
        if(current.modeChanging||finishing||current.calibrationMode||selfTesting)return
        if(mode==SpatialMode.HEAD_TRACKING){startTracking();return}
        val canStop=mode==SpatialMode.OFF&&(active||trackingRequested||forwarding)
        if((!current.bridge||!current.route)&&!canStop){notice(text(R.string.wait_system_spatial_ready));return}
        if(current.mode==mode&&(mode==SpatialMode.OFF||current.desiredMode==-1)&&!active&&!trackingRequested&&!forwarding)return
        if(active||trackingRequested||forwarding)stopMotion(text(R.string.switching_spatial_mode))
        val cleanup=cleanupJob
        state{it.copy(modeChanging=true,requestedMode=mode,notice=text(""))}
        scope.launch{
            try{
                // An in-flight START must finish cleanup before changing the device preference.
                cleanup?.join()
                ensureActive()
                val enabled=mode==SpatialMode.FIXED
                val answer=request(SpatialWire.FIXED,Bundle().apply{putBoolean("enabled",enabled)})
                check(answer.getBoolean("ok")){answer.getString("status",strings(R.string.spatial_mode_update_failed))}
                refresh()
                val reported=DeviceRepository.state.value.spatial
                check(reported.bridge&&reported.compatible==enabled&&!reported.nativeActive){strings(R.string.spatial_mode_unconfirmed)}
            }catch(error:CancellationException){throw error}
            catch(error:Exception){notice(error.message?.let(::text) ?:text(R.string.spatial_mode_update_failed));refresh()}
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
    private fun notice(value: UiText){state{it.copy(notice=value)};DeviceRepository.saveDiagnostics()}
    private suspend fun prepareMotion(){
        check(DeviceRepository.state.value.connected){strings(R.string.connect_and_wear_both)}
        motion(true)
        withTimeout(25000){
            while(active&&SystemClock.elapsedRealtime()-latestAt>250)delay(50)
        }
        check(active&&latest!=null){strings(R.string.motion_unavailable)}
    }
    /** Head tracking owns discovery, the spatializer prerequisite and the real pose bridge. */
    @Synchronized private fun startTracking(){
        if(trackingRequested||finishing||DeviceRepository.state.value.spatial.modeChanging||DeviceRepository.state.value.spatial.calibrationMode)return
        loadCalibration()
        if(matrix==null){notice(text(R.string.calibrate_first));return}
        if(!DeviceRepository.state.value.connected){notice(text(R.string.connect_and_wear_both));return}
        trackingRequested=true;val generation=++operation
        state{it.copy(trackingRequested=true,trackingStarting=true,requestedMode=SpatialMode.HEAD_TRACKING,notice=text(""))}
        operationJob=scope.launch{
            try{
                prepareMotion()
                val fixed=request(SpatialWire.FIXED,Bundle().apply{putBoolean("enabled",true)})
                check(fixed.getBoolean("ok")){strings(R.string.spatial_enable_failed)}
                ensureActive()
                synchronized(this@SpatialAudio){
                    check(operation==generation&&active&&trackingRequested)
                    neutral=latest
                }
                val result=request(SpatialWire.START,Bundle().apply{putBinder("client",owner)})
                ensureActive()
                check(result.getBoolean("ok")){strings(R.string.tracking_enable_failed)}
                synchronized(this@SpatialAudio){
                    if(operation==generation&&active&&trackingRequested){
                        forwarding=true
                        state{it.copy(nativeActive=true,trackingStarting=false,requestedMode=null)}
                    }
                }
                refresh()
            }catch(error:TimeoutCancellationException){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion(text(R.string.earbud_response_timeout))}
            }catch(error:CancellationException){throw error}
            catch(error:Exception){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion(error.message?.let(::text) ?:text(R.string.enable_failed_retry))}
            }
        }
    }
    @Synchronized fun beginCalibration(){
        if(finishing||trackingRequested||forwarding||DeviceRepository.state.value.spatial.modeChanging||DeviceRepository.state.value.spatial.calibrationMode)return
        if(!DeviceRepository.state.value.connected){notice(text(R.string.connect_and_wear_both));return}
        calibration=MotionCalibration();val generation=++operation
        // Keep the last successful matrix until all three replacement steps pass.
        state{it.copy(calibrationMode=true,calibrationPreparing=true,calibrationStep=0,collecting=false,notice=text(""))}
        operationJob=scope.launch{
            try{
                prepareMotion()
                synchronized(this@SpatialAudio){if(operation==generation)state{it.copy(calibrationPreparing=false)}}
            }catch(error:TimeoutCancellationException){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion(text(R.string.earbud_response_timeout))}
            }catch(error:CancellationException){throw error}
            catch(error:Exception){
                synchronized(this@SpatialAudio){if(operation==generation)stopMotion(error.message?.let(::text) ?:text(R.string.calibration_prepare_failed))}
            }
        }
    }
    @Synchronized fun cancelCalibration(){
        if(!DeviceRepository.state.value.spatial.calibrationMode)return
        stopMotion(text(R.string.calibration_cancelled))
        calibration=MotionCalibration();loadCalibration()
    }
    @Synchronized fun motion(enabled:Boolean){
        if(!enabled){stopMotion(text(R.string.motion_stopped));return}
        if(active||selfTesting||finishing)return
        DeviceRepository.log(strings(R.string.motion_subscription_request, scope.isActive))
        if(!DeviceRepository.state.value.connected){DeviceRepository.message(R.string.connect_and_wear_both);return}
        DeviceRepository.update{it.copy(message="",messageCode=0,messageControl=null,messageText=text(""))}
        loadCalibration();active=true;session++;val generation=session
        neutral=null;latest=null;latestAt=0;poses=0;packets=0;rawSaved=0;rateAt=SystemClock.elapsedRealtime();rateCount=0;services.clear()
        File(context.filesDir,"motion-sample.bin").delete()
        state{it.copy(motionActive=true,motionStatus=strings(R.string.subscribing_motion),packets=0,poses=0,rate=0,vector=emptyList(),collecting=false,acknowledgedService=-1,acknowledgementError=0,sensorServices="",ownershipStatus=strings(R.string.not_requested))}
        motionJob=scope.launch {
            val route=request(SpatialWire.QUERY)
            if(!route.getBoolean("route")||audio.mode!=AudioManager.MODE_NORMAL){stopMotion(text(R.string.select_earbuds_and_end_call));return@launch}
            for(setup in MotionCodec.rtBuddySetup()){
                if(!active||session!=generation)return@launch
                write(setup);delay(150)
            }
            delay(800)
            state{it.copy(motionStatus=strings(R.string.reading_sensor_services))}
            if(!write(MotionCodec.descriptorRequest(++requestSequence))){stopMotion(text(R.string.sensor_discovery_failed));return@launch}
            delay(2000)
            ownershipBefore=DeviceRepository.state.value.controls[6]?.firstOrNull()?.takeIf{it in 0..1}
            if(ownershipBefore!=1){
                state{it.copy(motionStatus=strings(R.string.requesting_control_session),ownershipStatus=strings(R.string.requesting))}
                synchronized(this@SpatialAudio){
                    if(!active||session!=generation)return@launch
                    ownershipTouched=write(Aacp.control(6,listOf(1)))
                }
                delay(1200)
                val owns=DeviceRepository.state.value.controls[6]?.firstOrNull()==1
                state{it.copy(ownershipStatus=if(owns)strings(R.string.earbuds_confirmed)else strings(R.string.requested_unconfirmed))}
                write(MotionCodec.descriptorRequest(++requestSequence));delay(1800)
            }else state{it.copy(ownershipStatus=strings(R.string.control_session_owned))}
            val discovered=services.values.filter{it.name.equals("devmotion6",true)}.map{it.id}
            val candidates=if(discovered.isNotEmpty())discovered else listOf(16,14,6)
            for(candidate in candidates){
                if(!active||session!=generation)return@launch
                profileService=candidate
                state{it.copy(motionStatus=strings(R.string.subscribing_motion_service, candidate),acknowledgedService=-1,acknowledgementError=0)}
                if(!write(MotionCodec.subscription(candidate,20000,++requestSequence))){stopMotion(text(R.string.motion_subscription_failed));return@launch}
                delay(4000)
                if(poses>0)break
                write(MotionCodec.subscription(candidate,0,++requestSequence))
            }
            if(poses==0){stopMotion(text(R.string.quaternion_missing));return@launch}
            while(active&&session==generation){
                delay(1000)
                if(SystemClock.elapsedRealtime()-latestAt>2500){stopMotion(text(R.string.motion_stream_interrupted));return@launch}
                if(!forwarding&&SystemClock.elapsedRealtime()-rateAt>300000){stopMotion(text(R.string.motion_diagnostic_finished));return@launch}
            }
        }
    }
    @Synchronized private fun stopMotion(reason: UiText){
        if(finishing)return
        val pendingOperation=operationJob;operationJob=null;operation++
        pendingOperation?.cancel();trackingRequested=false
        if(active)write(MotionCodec.subscription(profileService,0,++requestSequence))
        val pendingMotion=motionJob
        active=false;forwarding=false;session++;motionJob?.cancel();motionJob=null
        val restore=ownershipTouched;val previous=ownershipBefore
        ownershipTouched=false;ownershipBefore=null;finishing=true
        state{it.copy(motionActive=false,nativeActive=false,motionStatus=reason.resolve(strings),collecting=false,rate=0,
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
            }finally{synchronized(this@SpatialAudio){state{it.copy(ownershipStatus=strings(R.string.sampling_session_ended),stopping=false)};finishing=false}}
        }
        DeviceRepository.log(reason.resolve(strings));DeviceRepository.saveDiagnostics()
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
                motionStatus=strings(R.string.receiving_quaternion, event.service))}
        }
    }
    fun acknowledge(ack:Event.MotionAcknowledgement){if(active)state{it.copy(acknowledgedService=ack.service,acknowledgementError=ack.error)}}
    @Synchronized fun calibrate(){
        val s=DeviceRepository.state.value.spatial
        if(!s.calibrationMode||s.calibrationPreparing||forwarding)return
        if(!active||SystemClock.elapsedRealtime()-latestAt>250){notice(text(R.string.keep_earbuds_connected));return}
        notice(text(""))
        if(!s.collecting){
            calibration.begin(latest!!);state{it.copy(collecting=true)}
        }else{
            if(!calibration.finish()){state{it.copy(collecting=false)};notice(text(R.string.calibration_motion_insufficient));return}
            val replacement=calibration.matrix()
            if(replacement!=null){
                matrix=replacement
                val d=DeviceRepository.state.value
                preferences.edit().putString("address",d.address).putString("model",d.model).putString("firmware",d.firmware)
                    .putString("matrix",matrix!!.joinToString(",")).apply()
            }
            state{it.copy(collecting=false,calibrationStep=calibration.progress,calibrated=matrix!=null)}
            if(replacement!=null)stopMotion(text(R.string.calibration_saved))
            else if(calibration.progress==3){calibration=MotionCalibration();state{it.copy(calibrationStep=0)};notice(text(R.string.calibration_inconsistent))}
        }
        DeviceRepository.saveDiagnostics()
    }
    @Synchronized fun recenter(){
        if(!forwarding||SystemClock.elapsedRealtime()-latestAt>250)return
        neutral=latest
        notice(text(R.string.recentered))
    }
    fun forwarding(enabled:Boolean){scope.launch{
        if(!enabled){disableForwarding();refresh();return@launch}
        if(selfTesting||!active||matrix==null||SystemClock.elapsedRealtime()-latestAt>250){DeviceRepository.message(R.string.motion_and_calibration_required);return@launch}
        val generation=session
        val result=request(SpatialWire.START,Bundle().apply{putBinder("client",owner)})
        if(!active||session!=generation){request(SpatialWire.STOP);return@launch}
        forwarding=result.getBoolean("ok")
        state{it.copy(nativeActive=forwarding,status=result.getString("status",strings(R.string.system_tracking_start_failed)))}
        if(!forwarding)DeviceRepository.message(result.getString("status",strings(R.string.system_tracking_start_failed)))
        refresh()
    }}
    private suspend fun disableForwarding(){
        forwarding=false
        request(SpatialWire.STOP)
        state{it.copy(nativeActive=false)}
    }
    @Synchronized fun disconnected(lostControl:Boolean=true){if(lostControl)ownershipTouched=false;if(active||forwarding||trackingRequested||DeviceRepository.state.value.spatial.calibrationMode)stopMotion(text(R.string.tracking_stopped_removed));stopPlayback()}
    private fun attributes()=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO).build()

    fun playTest(anchor:Boolean=false){
        if(playbackJob?.isActive==true)return
        DeviceRepository.log(strings(R.string.multichannel_test_request, scope.isActive))
        playbackJob=scope.launch(Dispatchers.IO){
            DeviceRepository.log(strings(R.string.multichannel_test_start))
            var track:AudioTrack?=null
            try {
                if(audio.mode!=AudioManager.MODE_NORMAL)error(strings(R.string.end_call_before_test))
                val device=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull{it.type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP&&it.address.equals(DeviceRepository.state.value.address,true)}
                    ?:error(strings(R.string.earbuds_not_audio_output))
                val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
                val size=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_5POINT1,AudioFormat.ENCODING_PCM_16BIT)
                require(size>0){strings(R.string.pcm_format_rejected)}
                track=AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(size,57600)).build()
                check(track.state==AudioTrack.STATE_INITIALIZED&&track.setPreferredDevice(device)){strings(R.string.test_output_lock_failed)}
                track.setVolume(0.35f);track.play()
                track.write(ShortArray(4800*6),0,4800*6,AudioTrack.WRITE_BLOCKING)
                delay(120)
                check(track.routedDevice?.id==device.id){strings(R.string.test_wrong_output)}
                state{it.copy(testPlaying=true,testStatus=strings(R.string.test_tone_airpods))}
                val labels=listOf(strings(R.string.channel_front_left),strings(R.string.channel_front_right),strings(R.string.channel_front_center),strings(R.string.channel_lfe),strings(R.string.channel_rear_left),strings(R.string.channel_rear_right))
                val seconds=if(anchor)20 else 2
                val channels=if(anchor)listOf(2)else (0..5).toList()
                for(channel in channels){
                    state{it.copy(testStatus=if(anchor)strings(R.string.front_anchor_test)else
                        strings(R.string.multichannel_test_status, labels[channel], if(spatial.isEnabled)strings(R.string.system_spatial_enabled)else strings(R.string.system_spatial_disabled)))}
                    for(chunk in 0 until seconds*10){
                        ensureActive()
                        check(audio.mode==AudioManager.MODE_NORMAL&&track.routedDevice?.id==device.id){strings(R.string.test_route_changed)}
                        val pcm=ShortArray(4800*6)
                        for(frame in 0 until 4800){
                            val t=(chunk*4800+frame)/48000.0
                            val fade=min(1.0,min(t/0.05,(seconds-t)/0.05)).coerceAtLeast(0.0)
                            val signal=if(anchor)(sin(2*PI*440*t)+sin(2*PI*733*t)+sin(2*PI*1173*t))/3
                                else sin(2*PI*(if(channel==3)80.0 else 440.0)*t)
                            pcm[frame*6+channel]=(signal*32767*0.1*fade).toInt().toShort()
                        }
                        check(track.write(pcm,0,pcm.size,AudioTrack.WRITE_BLOCKING)==pcm.size){strings(R.string.audio_write_interrupted)}
                    }
                }
                delay(300)
                state{it.copy(testStatus=strings(R.string.test_finished))}
            }catch(error:CancellationException){state{it.copy(testStatus=strings(R.string.test_stopped))};throw error}
            catch(error:Exception){state{it.copy(testStatus=strings(R.string.test_incomplete, error.message))}}
            finally {runCatching{track?.stop()};track?.release();state{it.copy(testPlaying=false)};DeviceRepository.saveDiagnostics()}
        }
    }
    fun stopPlayback(){playbackJob?.cancel()}
    fun selfTest(){scope.launch{
        if(active||forwarding||selfTesting){DeviceRepository.message(R.string.stop_motion_before_self_test);return@launch}
        selfTesting=true;state{it.copy(selfTesting=true,status=strings(R.string.bridge_self_test_running))}
        try {
            val result=request(SpatialWire.START,Bundle().apply{putBinder("client",owner)})
            if(!result.getBoolean("ok"))error(result.getString("status",strings(R.string.creation_failed)))
            repeat(60){
                val reply=request(SpatialWire.POSE,Bundle().apply{putLong("captured",SystemClock.elapsedRealtime());putLong("sequence",++sequence);putFloatArray("vector",floatArrayOf(0f,0f,0f))})
                check(reply.getBoolean("ok")){reply.getString("status",strings(R.string.pose_submit_failed))}
                delay(50)
            }
            val status=request(SpatialWire.QUERY)
            val available=spatial.isHeadTrackerAvailable
            DeviceRepository.log(strings(R.string.bridge_self_test_log, status.getBoolean("hasTracker"), available))
            DeviceRepository.message(if(available)strings(R.string.bridge_self_test_passed)else strings(R.string.bridge_self_test_not_passed))
        }catch(error:Exception){DeviceRepository.message(R.string.bridge_self_test_failed, error.message)}
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
