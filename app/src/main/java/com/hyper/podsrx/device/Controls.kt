package com.hyper.podsrx.device

data class Choice(val value: Int, val label: String)
data class ControlSpec(val id: Int,val title: String,val group: String,val choices: List<Choice> = emptyList(),val toggle: Boolean=false,val range: IntRange?=null,val writable:Boolean=true)

object Controls {
    private fun choices(vararg labels:Pair<Int,String>)=labels.map{Choice(it.first,it.second)}
    val listeningChoices = choices(3 to "通透", 4 to "自适应", 2 to "降噪")
    val all=listOf(
        ControlSpec(0x0d,"聆听模式","声音与聆听",choices(1 to "关闭",2 to "降噪",3 to "通透",4 to "自适应")),
        ControlSpec(0x1b,"单耳降噪","声音与聆听",toggle=true),
        ControlSpec(0x28,"对话感知","声音与聆听",toggle=true),
        ControlSpec(0x26,"个性化音量","声音与聆听",toggle=true),
        ControlSpec(0x2e,"自适应降噪幅度","声音与聆听",range=0..100),
        ControlSpec(0x34,"允许关闭聆听模式","声音与聆听",toggle=true),
        ControlSpec(0x0a,"自动入耳检测","佩戴与音频",toggle=true),
        ControlSpec(0x01,"麦克风","佩戴与音频",choices(0 to "自动",1 to "右耳",2 to "左耳")),
        ControlSpec(0x17,"连按速度","按压与手势",choices(0 to "默认",1 to "慢",2 to "最慢")),
        ControlSpec(0x18,"长按持续时间","按压与手势",choices(0 to "默认",1 to "较短",2 to "最短")),
        ControlSpec(0x25,"滑动调节音量","按压与手势",toggle=true),
        ControlSpec(0x23,"滑动音量所需时长","按压与手势",choices(0 to "默认",1 to "较长",2 to "最长")),
        ControlSpec(0x1f,"耳机提示音音量","声音与聆听",range=15..100),
        ControlSpec(0x35,"入睡后暂停","佩戴与音频",toggle=true)
    )
    fun adaptiveStrength(controls: Map<Int, List<Int>>): Int? =
        controls[0x2e]?.firstOrNull()?.takeIf { it in 0..100 }

    fun canAdjustAdaptive(connected: Boolean, controls: Map<Int, List<Int>>, pending: Set<Int>): Boolean =
        connected && controls[0x0d]?.firstOrNull() == 4 && adaptiveStrength(controls) != null &&
            0x0d !in pending

    fun shouldSendAdaptive(value:Int,reported:Int?,target:Int?):Boolean =
        value != target && (target != null || value != reported)

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
