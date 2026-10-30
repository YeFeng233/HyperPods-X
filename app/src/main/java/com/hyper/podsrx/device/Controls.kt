package com.hyper.podsrx.device

import androidx.annotation.StringRes
import com.hyper.podsrx.R
import com.hyper.podsrx.protocol.ControlCommands

data class Choice(val value: Int, @param:StringRes val labelRes: Int)
data class ControlSpec(val id: Int, @param:StringRes val titleRes: Int, @param:StringRes val groupRes: Int,val choices: List<Choice> = emptyList(),val toggle: Boolean=false,val range: IntRange?=null,val writable:Boolean=true)

object Controls {
    private fun choices(vararg labels:Pair<Int,Int>)=labels.map{Choice(it.first,it.second)}
    val listeningChoices = choices(1 to R.string.mode_off, 3 to R.string.mode_transparency, 4 to R.string.mode_adaptive, 2 to R.string.mode_noise_cancellation)
    val all=listOf(
        ControlSpec(0x0d,R.string.listening_mode,R.string.sound_and_listening,choices(1 to R.string.mode_off,2 to R.string.mode_noise_cancellation,3 to R.string.mode_transparency,4 to R.string.mode_adaptive)),
        ControlSpec(0x1b,R.string.single_ear_noise_cancellation,R.string.sound_and_listening,toggle=true),
        ControlSpec(0x28,R.string.conversation_awareness,R.string.sound_and_listening,toggle=true),
        ControlSpec(0x26,R.string.personalized_volume,R.string.sound_and_listening,toggle=true),
        ControlSpec(0x2e,R.string.adaptive_noise_strength,R.string.sound_and_listening,range=0..100),
        ControlSpec(0x34,R.string.allow_off_listening_mode,R.string.sound_and_listening,toggle=true),
        ControlSpec(0x0a,R.string.automatic_ear_detection,R.string.wearing_and_audio,toggle=true),
        ControlSpec(0x01,R.string.microphone,R.string.wearing_and_audio,choices(0 to R.string.automatic,1 to R.string.right_earbud,2 to R.string.left_earbud)),
        ControlSpec(0x17,R.string.press_speed,R.string.press_and_gestures,choices(0 to R.string.default_option,1 to R.string.slow,2 to R.string.slowest)),
        ControlSpec(0x18,R.string.press_and_hold_duration,R.string.press_and_gestures,choices(0 to R.string.default_option,1 to R.string.shorter,2 to R.string.shortest)),
        ControlSpec(0x25,R.string.swipe_volume,R.string.press_and_gestures,toggle=true),
        ControlSpec(0x23,R.string.volume_swipe_duration,R.string.press_and_gestures,choices(0 to R.string.default_option,1 to R.string.longer,2 to R.string.longest)),
        ControlSpec(0x1f,R.string.tone_volume,R.string.sound_and_listening,range=15..100),
        ControlSpec(0x35,R.string.pause_when_asleep,R.string.wearing_and_audio,toggle=true)
    )
    fun adaptiveStrength(controls: Map<Int, List<Int>>): Int? = ControlCommands.adaptiveStrength(controls)

    fun canAdjustAdaptive(connected: Boolean, controls: Map<Int, List<Int>>, pending: Set<Int>): Boolean =
        ControlCommands.canAdjustAdaptive(connected, controls, pending)

    fun shouldSendAdaptive(value:Int,reported:Int?,target:Int?):Boolean =
        ControlCommands.shouldSendAdaptive(value, reported, target)

    fun valuesForWrite(id: Int, value: Int, current: List<Int>): List<Int> =
        // LibrePods sends AUTO_ANC_STRENGTH as one byte; the packet encoder zero-pads it.
        if (id == 0x2e) listOf(value) else listOf(value) + current.drop(1).take(3)

    fun allowed(id:Int,values:List<Int>):Boolean {
        val spec=all.find{it.id==id} ?: return false
        return spec.writable && values.size in 1..4 && values.all{it in 0..255} && when {
            spec.toggle -> values[0] in 1..2
            spec.range != null -> values[0] in spec.range
            else -> spec.choices.any{it.value==values[0]}
        }
    }
}
