package com.hyper.podsrx.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.PodsService
import com.hyper.podsrx.i18n.stringResolver
import org.json.JSONArray
import org.json.JSONObject

/** Shell-only test driver. The release manifest and APK contain no receiver. */
class DeviceTestReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        when(intent.getStringExtra("operation")) {
            "ear_status" -> {
                val state=DeviceRepository.state.value
                val audio=context.getSystemService(AudioManager::class.java)
                val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                val result=JSONObject().put("connected",state.connected).put("enabled",state.earAutomation)
                    .put("wearing",state.wearing?.toString()).put("status",state.earDetectionStatus.resolve(context.stringResolver()))
                    .put("musicActive",audio.isMusicActive).put("mode",audio.mode)
                runCatching{audio.getAudioDevicesForAttributes(attributes)}.onSuccess { routes ->
                    result.put("routes",JSONArray(routes.map{JSONObject().put("type",it.type)
                        .put("addressAvailable",it.address.isNotBlank()).put("boundDevice",it.address.equals(state.address,true))}))
                }.onFailure{result.put("routeError",it.javaClass.simpleName)}
                resultCode=1
                resultData=result.toString()
            }
            "preview" -> PodsService.previewPopup()
            "reconnect" -> PodsService.reconnect()
            "control" -> PodsService.setControl(intent.getIntExtra("id",-1),intent.getIntExtra("value",-1))
            "eq" -> {
                val original=DeviceRepository.state.value.equalizer?:return
                val band=intent.getIntExtra("band",-1)
                val value=intent.getIntExtra("value",-1)
                if(band in 1..3&&value in 0..100)PodsService.setEqualizer(original.toMutableList().also{it[band]=value})
            }
            "call" -> PodsService.setCallControl(intent.getBooleanExtra("flipped",false))
        }
    }
}
