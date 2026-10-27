package com.hyper.podsrx.device

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hyper.podsrx.DeviceSettingsActivity
import com.hyper.podsrx.R
import com.hyper.podsrx.ui.BatteryGlyph
import com.hyper.podsrx.ui.GlasmioAppTheme
import com.hyper.podsrx.ui.batteryPresentation
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlasmioBackdropProvider
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

/** Compatibility popup with a window-owned Compose lifecycle. */
class PopupController(private val context:Context) {
    private val handler=Handler(Looper.getMainLooper())
    private val windows=context.getSystemService(WindowManager::class.java)
    private var view:ComposeView?=null
    private var overlayOwner:OverlayOwner?=null
    private var lastAutomatic=Long.MIN_VALUE/2
    private var lastReason=""
    private var generation=0L
    fun show(reason:String="充电盒已打开",preview:Boolean=false) { handler.post {
        // A closed advertisement may arrive before this queued UI operation executes.
        if(!preview&&reason=="充电盒已打开"&&DeviceRepository.state.value.lidOpen!=true)return@post
        val now=android.os.SystemClock.elapsedRealtime()
        val upgrade=reason=="充电盒已打开"&&lastReason=="耳机已连接"
        if(!preview&&now-lastAutomatic<10000&&!upgrade)return@post
        if(!preview){lastAutomatic=now;lastReason=reason}
        if(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){notifyStatus(reason);return@post}
        close()
        val request=generation
        if(DeviceRepository.state.value.nativePopup)NativePopupBridge.show(context,preview,reason=="超级岛预览") nativeResult@{shown->
            if(request!=generation)return@nativeResult
            if(!preview&&reason=="充电盒已打开"&&DeviceRepository.state.value.lidOpen!=true)return@nativeResult
            if(shown)DeviceRepository.log("已提交 HyperOS 原生耳机提示：$reason")else showOverlay(reason)
            DeviceRepository.saveDiagnostics()
        } else showOverlay(reason)
    } }
    private fun showOverlay(reason:String) {
        if(!Settings.canDrawOverlays(context)||context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){notifyStatus(reason);return}
        val owner=OverlayOwner()
        val root=ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by DeviceRepository.state.collectAsState()
                GlasmioAppTheme {
                    val backdrop=rememberLayerBackdrop()
                    val colors=LocalGlassColors.current
                    GlasmioGroupedList {
                        Box(Modifier.fillMaxWidth()) {
                            Box(Modifier.matchParentSize().glassBackdropSource(backdrop).background(colors.elevatedBackground))
                            GlasmioBackdropProvider(backdrop) {
                                BatteryPopup(state,onSettings=::openSettings,onClose=::close)
                            }
                        }
                    }
                }
            }
        }
        root.setOnTouchListener{_,event->if(event.action==MotionEvent.ACTION_OUTSIDE){close();true}else false}
        val params=WindowManager.LayoutParams((context.resources.displayMetrics.widthPixels-dp(32)).coerceAtMost(dp(480)),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply{gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;y=dp(32)}
        runCatching{
            owner.start()
            windows.addView(root,params)
            view=root
            overlayOwner=owner
            DeviceRepository.log("显示电量卡片：$reason")
        }.onFailure{
            owner.destroy()
            root.disposeComposition()
            DeviceRepository.log("弹窗显示失败：${it.javaClass.simpleName}")
            notifyStatus(reason)
        }
        handler.postDelayed({if(view===root)close()},8000)
    }
    private fun openSettings(){
        context.startActivity(Intent(context,DeviceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("device_address",DeviceRepository.state.value.address));close()
    }
    private fun notifyStatus(reason:String){
        val pending=PendingIntent.getActivity(context,11,Intent(context,DeviceSettingsActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(11,NotificationCompat.Builder(context,"lid")
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("${DeviceRepository.state.value.name} · $reason")
            .setContentText(DeviceRepository.batterySummary()).setContentIntent(pending).setAutoCancel(true).build())
    }
    fun close(){
        val remove={
            generation++
            val root=view
            val owner=overlayOwner
            view=null
            overlayOwner=null
            owner?.destroy()
            root?.disposeComposition()
            root?.let{runCatching{windows.removeViewImmediate(it)}}
            Unit
        }
        if(Looper.myLooper()==Looper.getMainLooper())remove()else handler.post{remove()}
    }
    private fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
}

/** The window owns saveable state and recomposition without retaining its Service. */
private class OverlayOwner:LifecycleOwner,SavedStateRegistryOwner {
    private val registry=LifecycleRegistry(this)
    private val savedState=SavedStateRegistryController.create(this)
    override val lifecycle:Lifecycle get()=registry
    override val savedStateRegistry get()=savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if(registry.currentState==Lifecycle.State.RESUMED)registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if(registry.currentState.isAtLeast(Lifecycle.State.STARTED))registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        if(registry.currentState!=Lifecycle.State.DESTROYED)registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

@Composable
private fun BatteryPopup(state:PodsState,onSettings:()->Unit,onClose:()->Unit) {
    val colors=LocalGlassColors.current
    Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(state.displayName,style=IosTypography.title2,color=colors.content,
                modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
            GlasmioIconButton(onClick=onClose,size=44.dp) {
                SfIcon("xmark","关闭",size=15.dp,tint=colors.secondaryContent)
            }
        }
        Image(painterResource(R.drawable.airpods5_pair),contentDescription="AirPods",contentScale=ContentScale.Fit,
            modifier=Modifier.fillMaxWidth().height(136.dp).padding(vertical=8.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            for((id,label)in listOf(4 to "左",2 to "右",8 to "充电盒")) {
                val battery=batteryPresentation(state.batteries[id])
                Column(Modifier.weight(1f).semantics {
                    contentDescription="$label，${battery.label}${if(battery.note.isEmpty()) "" else "，${battery.note}"}"
                },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(label,style=IosTypography.footnote,color=colors.secondaryContent)
                    BatteryGlyph(battery)
                    Text(battery.label,style=IosTypography.body,color=colors.content)
                    if(battery.note=="上次更新")Text(battery.note,style=IosTypography.caption2,color=colors.secondaryContent)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        GlasmioButton(onClick=onSettings,modifier=Modifier.fillMaxWidth(),emphasis=GlassEmphasis.Prominent,
            size=GlasmioButtonSize.Large) {
            Text("耳机设置")
        }
    }
}
