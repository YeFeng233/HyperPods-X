package com.hyper.podsrx.device

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.hyper.podsrx.DeviceSettingsActivity
import com.hyper.podsrx.R
import kotlinx.coroutines.*

/**
 * The overlay is native Android so it does not invent an Activity lifecycle in a Service. The
 * native popup and island belong to the Bluetooth host's hub; [native] asks it for one.
 */
class PopupController(private val context:Context,private val native:(preview:Boolean,island:Boolean,onResult:(Boolean)->Unit)->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private val windows=context.getSystemService(WindowManager::class.java)
    private var view:View?=null
    private var updates:Job?=null
    private var lastAutomatic=Long.MIN_VALUE/2
    private var generation=0L
    fun show(reason:String="充电盒已打开",preview:Boolean=false) { handler.post {
        // A closed advertisement may arrive before this queued UI operation executes.
        if(!preview&&reason=="充电盒已打开"&&DeviceRepository.state.value.lidOpen!=true)return@post
        val now=android.os.SystemClock.elapsedRealtime()
        // Each confirmed lid open is a real reopen; only the connection card is held back after a popup.
        if(!preview&&reason!="充电盒已打开"&&now-lastAutomatic<10000)return@post
        if(!preview)lastAutomatic=now
        if(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){notifyStatus(reason);return@post}
        close()
        val request=generation
        if(DeviceRepository.state.value.nativePopup)native(preview,reason=="超级岛预览") nativeResult@{shown->
            if(request!=generation)return@nativeResult
            DeviceRepository.update{it.copy(nativePopupStatus=if(shown)"已提交系统原生耳机提示"else"原生通道未就绪，使用兼容卡片")}
            if(!preview&&reason=="充电盒已打开"&&DeviceRepository.state.value.lidOpen!=true)return@nativeResult
            if(shown)DeviceRepository.log("已提交 HyperOS 原生耳机提示：$reason")else showOverlay(reason)
            DeviceRepository.saveDiagnostics()
        } else showOverlay(reason)
    } }
    /** The hub showed no native popup for this lid opening; the card or a notification stands in. */
    fun showFallback(reason:String="充电盒已打开") { handler.post {
        if(DeviceRepository.state.value.lidOpen!=true)return@post
        lastAutomatic=android.os.SystemClock.elapsedRealtime()
        if(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){notifyStatus(reason);return@post}
        close()
        showOverlay(reason)
    } }
    private fun showOverlay(reason:String) {
        if(!Settings.canDrawOverlays(context)||context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){notifyStatus(reason);return}
        val root=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(20),dp(24),dp(22))
            background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(30).toFloat()}
            elevation=dp(12).toFloat()
        }
        val title=label(DeviceRepository.state.value.name,22,Color.rgb(25,28,35)).apply{setTypeface(typeface,Typeface.BOLD)}
        root.addView(title)
        root.addView(label("HyperPods-X · $reason",13,Color.GRAY))
        root.addView(ImageView(context).apply{setImageResource(DeviceRepository.state.value.headset.pairArtwork);scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(-1,dp(140)))
        val batteries=label("",17,Color.rgb(40,43,50)).apply{setPadding(0,dp(22),0,dp(22))}
        root.addView(batteries)
        val row=LinearLayout(context)
        row.addView(label("耳机设置  ›",16,Color.rgb(52,130,255)).apply{setOnClickListener{openSettings()}},LinearLayout.LayoutParams(0,dp(44),1f))
        row.addView(label("关闭",15,Color.GRAY).apply{gravity=Gravity.CENTER_VERTICAL;setOnClickListener{close(true)}},LinearLayout.LayoutParams(dp(56),dp(44)))
        root.addView(row)
        root.setOnTouchListener{_,event->if(event.action==MotionEvent.ACTION_OUTSIDE){close(true);true}else false}
        val params=WindowManager.LayoutParams(context.resources.displayMetrics.widthPixels-dp(32),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply{gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;y=dp(32)}
        runCatching{windows.addView(root,params);view=root;DeviceRepository.log("显示电量卡片：$reason")}.onFailure{DeviceRepository.log("弹窗显示失败：${it.javaClass.simpleName}");notifyStatus(reason)}
        updates=CoroutineScope(Dispatchers.Main).launch {DeviceRepository.state.collect{batteries.text=DeviceRepository.batterySummary()}}
        handler.postDelayed({if(view===root)close(true)},8000)
    }
    private fun openSettings(){
        context.startActivity(Intent(context,DeviceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("device_address",DeviceRepository.state.value.address));close()
    }
    private fun notifyStatus(reason:String){
        val pending=PendingIntent.getActivity(context,11,Intent(context,DeviceSettingsActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(11,NotificationCompat.Builder(context,"lid")
            .setSmallIcon(R.drawable.ic_headphones).setContentTitle("${DeviceRepository.state.value.name} · $reason")
            .setContentText(DeviceRepository.batterySummary()).setContentIntent(pending).setAutoCancel(true).build())
    }
    /** [animated] slides the overlay down before removing it; replacing or destroying it removes it at once. */
    fun close(animated:Boolean=false){
        val remove={
            generation++;updates?.cancel();updates=null
            val closing=view;view=null
            if(closing!=null)if(animated&&closing.isAttachedToWindow)slideOut(closing)else runCatching{windows.removeViewImmediate(closing)}
            Unit
        }
        if(Looper.myLooper()==Looper.getMainLooper())remove()else handler.post{remove()}
    }
    private fun slideOut(closing:View) {
        val params=closing.layoutParams as? WindowManager.LayoutParams
        if(params==null){runCatching{windows.removeViewImmediate(closing)};return}
        ValueAnimator.ofInt(params.y,-closing.height).apply {
            duration=220
            addUpdateListener{params.y=it.animatedValue as Int;closing.alpha=1f-it.animatedFraction;runCatching{windows.updateViewLayout(closing,params)}}
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:Animator){runCatching{windows.removeViewImmediate(closing)}}
            })
            start()
        }
    }
    private fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Int,color:Int)=TextView(context).apply{text=value;textSize=size.toFloat();setTextColor(color);gravity=Gravity.CENTER_VERTICAL}
}
