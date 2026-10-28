package com.hyper.podsrx.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hyper.podsrx.DeviceSettingsActivity
import com.hyper.podsrx.hook.LidWire

object NativePopupBridge {
    private const val HOST="com.xiaomi.bluetooth"
    private val main=Handler(Looper.getMainLooper())
    fun show(context:Context,preview:Boolean,island:Boolean,onResult:(Boolean)->Unit) {
        var completed=false
        fun complete(shown:Boolean) {
            if(completed)return
            completed=true
            DeviceRepository.update{it.copy(nativePopupStatus=if(shown)"已提交系统原生耳机提示"else"原生通道未就绪，使用兼容卡片")}
            onResult(shown)
        }
        try {
            val state=DeviceRepository.state.value
            FrameworkBridge.publish()
            val target=PendingIntent.getActivity(context,21,Intent(context,DeviceSettingsActivity::class.java)
                .putExtra("device_address",state.address),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val request=Intent("${context.packageName}.SHOW_NATIVE_POPUP").setPackage(HOST)
                .putExtra("address",state.address).putExtra("name",state.name)
                .putExtra("preview",preview).putExtra("island",island)
                .putExtra("model",state.model).putExtra("product",state.product)
                .putExtra("native_connected",state.connected)
                .putExtra("native_left",state.batteries[4]?.level?:-1)
                .putExtra("native_right",state.batteries[2]?.level?:-1)
                .putExtra("native_box",state.batteries[8]?.level?:-1)
                .putExtra("native_box_history",state.batteries[8]?.level?:-1)
                .putExtra("native_charging",listOf(4 to 1,2 to 2,8 to 4).sumOf{(id,mask)->if(state.batteries[id]?.charging==true)mask else 0})
                .putExtra("target",target)
                .putExtra("created",SystemClock.elapsedRealtime())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendOrderedBroadcast(request,null,object:BroadcastReceiver(){
                override fun onReceive(context:Context,intent:Intent){complete(resultCode==1)}
            },main,0,null,null)
            main.postDelayed({complete(false)},1500)
        } catch(error:Exception) {
            DeviceRepository.log("原生提示请求失败：${error.javaClass.simpleName}")
            complete(false)
        }
    }
    /** Slides out the bound device's headset dialog as soon as the lid closes. */
    fun close(context:Context) {
        runCatching{context.sendBroadcast(Intent(LidWire.CLOSE_POPUP).setPackage(HOST).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))}
    }
}
