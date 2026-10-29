package com.hyper.podsrx.ui

import com.hyper.podsrx.device.Choice
import com.hyper.podsrx.device.ControlSpec
import com.hyper.podsrx.device.Controls
import com.hyper.podsrx.device.PodsState

/** Routes and data shared by the two renderers; no layout or material belongs here. */
internal enum class SettingsPage(val route: String, val title: String?, val parent: SettingsPage?) {
    DEVICE("device", null, null),
    NAME("name", null, DEVICE),
    AUDIO("audio", "音频与路径", DEVICE),
    CONTROLS("controls", "控制与手势", DEVICE),
    ACCESSIBILITY("accessibility", "无障碍", DEVICE),
    SPATIAL("spatial", "空间音频", AUDIO),
    EQUALIZER("equalizer", "均衡器", AUDIO),
    BATTERY("battery", "电池", DEVICE),
    MODULE("module", "更多设置", DEVICE),
    CONNECTION("connection", "连接设置", MODULE),
    DIAGNOSTICS("diagnostics", "连接诊断", MODULE),
    ABOUT("about", "关于", MODULE),
    LICENSES("licenses", "开源许可", ABOUT),
    CALL_MUTE("call/mute", "静音与取消静音", CONTROLS),
    CALL_END("call/end", "结束通话", CONTROLS),
}

internal data class SettingsControlGroup(
    val key: String,
    val title: String?,
    val controlIds: List<Int>,
    val choiceLabels: Map<Int, String> = emptyMap(),
)

internal data class SettingsPageLink(
    val page: SettingsPage,
    val title: String,
    val symbol: String? = null,
    val argb: Long? = null,
)

internal enum class SettingsToggle(val key: String, val title: String) {
    EAR_AUTOMATION("earAutomation", "自动入耳检测"),
    OPEN_CASE_POPUP("popup", "开盖弹窗"),
    NATIVE_POPUP("nativePopup", "系统原生弹窗"),
    CONNECTION_POPUP("connectionPopup", "连接电量卡片"),
    BATTERY_ALERTS("batteryAlerts", "电量提醒");

    fun checked(state: PodsState): Boolean = when (this) {
        EAR_AUTOMATION -> state.earAutomation
        OPEN_CASE_POPUP -> state.popupEnabled
        NATIVE_POPUP -> state.nativePopup
        CONNECTION_POPUP -> state.connectionPopup
        BATTERY_ALERTS -> state.batteryAlerts
    }
}

internal data class SettingsSectionSpec(val key: String, val title: String?, val items: List<String>)

internal object SettingsCatalog {
    private val pages = SettingsPage.entries.associateBy(SettingsPage::route)

    fun page(route: String): SettingsPage? = pages[route.substringBefore('#')]

    fun initialPage(value: String?): SettingsPage? = when (value) {
        "more" -> SettingsPage.MODULE
        "advanced" -> SettingsPage.AUDIO
        "spatial", "audio", "controls", "accessibility", "module", "connection", "battery" -> pages[value]
        else -> null
    }

    fun controlRoute(id: Int): String = "control/$id"

    fun title(route: String, state: PodsState): String {
        val name = route.substringBefore('#')
        return if (name.startsWith("control/")) {
            Controls.all.firstOrNull { it.id == name.substringAfter('/').toIntOrNull() }?.title ?: "设置"
        } else page(name)?.title ?: state.displayName
    }

    fun parentRoute(route: String): String? {
        val name = route.substringBefore('#')
        if (!name.startsWith("control/")) return page(name)?.parent?.route
        val id = name.substringAfter('/').toIntOrNull() ?: return null
        return when (id) {
            0x0d -> SettingsPage.DEVICE
            0x26, 0x28, 0x01, 0x35 -> SettingsPage.AUDIO
            0x25, 0x23 -> SettingsPage.CONTROLS
            0x17, 0x18, 0x1b, 0x34, 0x1f, 0x2e, 0x0a -> SettingsPage.ACCESSIBILITY
            else -> null
        }?.route
    }

    val rootFeatureLinks = listOf(
        SettingsPageLink(SettingsPage.AUDIO, "音频与路径", "speaker.wave.3.fill", 0xffff2d55),
        SettingsPageLink(SettingsPage.CONTROLS, "控制与手势", "hand.pinch.fill", 0xff8e8e93),
        SettingsPageLink(SettingsPage.ACCESSIBILITY, "无障碍", "accessibility", 0xff00a7ed),
    )
    val batteryLink = SettingsPageLink(SettingsPage.BATTERY, "电池", "battery.100percent", 0xff34c759)
    val rootSettingsLinks = listOf(
        SettingsPageLink(SettingsPage.MODULE, "更多设置"),
        SettingsPageLink(SettingsPage.ABOUT, "关于 HyperPods-X"),
    )
    val rootLinks = rootFeatureLinks + batteryLink + rootSettingsLinks

    val personalizedVolume = SettingsControlGroup("personalizedVolume", null, listOf(0x26))
    val conversationAwareness = SettingsControlGroup("conversationAwareness", null, listOf(0x28))
    val microphone = SettingsControlGroup("microphone", null, listOf(0x01))
    val sleepPause = SettingsControlGroup("sleepPause", null, listOf(0x35))
    val swipeControls = SettingsControlGroup("swipeControls", "滑动控制", listOf(0x25, 0x23))
    val pressSpeed = SettingsControlGroup("pressSpeed", "按下速度", listOf(0x17), mapOf(1 to "较慢"))
    val pressDuration = SettingsControlGroup("pressDuration", "按住时长", listOf(0x18))
    val noiseControls = SettingsControlGroup("noiseControls", "噪声控制", listOf(0x1b, 0x34))
    val chimeVolume = SettingsControlGroup("chimeVolume", "音效音量", listOf(0x1f))
    val adaptiveAudio = SettingsControlGroup("adaptiveAudio", "自适应音频", listOf(0x2e))
    val sensors = SettingsControlGroup("sensors", "耳机传感器", listOf(0x0a))

    val audioControlGroups = listOf(personalizedVolume, conversationAwareness, microphone, sleepPause)
    val gestureControlGroups = listOf(swipeControls)
    val accessibilityControlGroups = listOf(pressSpeed, pressDuration, noiseControls, chimeVolume, adaptiveAudio, sensors)
    val audioSectionOrder = audioControlGroups.take(2).map(SettingsControlGroup::key) +
        listOf("equalizer", "earDetection", "spatial") +
        audioControlGroups.drop(2).map(SettingsControlGroup::key) + "systemAudio"
    val gestureSectionOrder = listOf("callControls") + gestureControlGroups.map(SettingsControlGroup::key) + "connection"
    val accessibilitySectionOrder = accessibilityControlGroups.map(SettingsControlGroup::key) + "connection"
    val gesturePresenceIds = swipeControls.controlIds + 0x24
    val accessibilityPresenceIds = accessibilityControlGroups.flatMap(SettingsControlGroup::controlIds)

    fun availableControls(state: PodsState, group: SettingsControlGroup): List<ControlSpec> =
        availableControls(state, group.controlIds)

    fun availableControls(state: PodsState, ids: List<Int>): List<ControlSpec> =
        ids.mapNotNull { id -> Controls.all.firstOrNull { it.id == id && state.controls[id]?.firstOrNull() != null } }

    fun controlEnabled(state: PodsState, spec: ControlSpec): Boolean =
        state.connected && spec.id !in state.pending && spec.writable

    fun toggleTitle(spec: ControlSpec): String = when (spec.id) {
        0x0a -> "耳机侧入耳检测"
        0x1b -> "一只 AirPod 入耳时使用降噪"
        else -> spec.title
    }

    val listeningChoices = listOf(Choice(1, "关闭"), Choice(3, "通透模式"), Choice(4, "自适应"), Choice(2, "降噪"))
    val adaptiveAnchors = listOf(0, 50, 100)
    val popupToggles = listOf(SettingsToggle.OPEN_CASE_POPUP, SettingsToggle.NATIVE_POPUP,
        SettingsToggle.CONNECTION_POPUP, SettingsToggle.BATTERY_ALERTS)

    val moduleSections = listOf(
        SettingsSectionSpec("appearance", "外观", listOf("style")),
        SettingsSectionSpec("popups", "弹窗与提醒", popupToggles.map(SettingsToggle::key)),
        SettingsSectionSpec("previews", null, listOf("batteryPreview", "islandPreview")),
        SettingsSectionSpec("systemIntegration", "系统集成", listOf("nativeScope", "restartHosts", "overlayPermission", "bluetoothSettings")),
        SettingsSectionSpec("moduleInformation", null, listOf("connection", "diagnostics", "exportLogs", "about")),
    )
    fun moduleSection(key: String): SettingsSectionSpec = moduleSections.first { it.key == key }
    private val moduleLabels = mapOf(
        "style" to "UI 风格", "batteryPreview" to "电量卡片预览", "islandPreview" to "超级岛预览",
        "nativeScope" to "系统原生适配", "restartHosts" to "重启作用域", "overlayPermission" to "悬浮显示权限",
        "bluetoothSettings" to "系统蓝牙设置", "connection" to "连接设置", "diagnostics" to "连接诊断",
        "exportLogs" to "导出日志", "about" to "关于 HyperPods-X",
    )
    fun moduleLabel(key: String): String = moduleLabels.getValue(key)

    fun actionFailureMessage(message: String): String? = when {
        message.startsWith("聆听模式未确认目标值") ||
            message.startsWith("自适应降噪幅度未确认目标值") -> null
        message.endsWith("未确认目标值，已保留设备回读结果") ->
            "${message.substringBefore("未确认目标值")}未能更新，请重试。"
        message.startsWith("设置发送失败：") -> "无法更新耳机设置，请重试。"
        else -> when (message) {
            "请先连接耳机", "耳机控制尚未连接" -> "耳机已断开连接，请重新连接后重试。"
            "请在自适应模式下调整降噪幅度" -> "请先切换到自适应模式。"
            "请等待自适应模式与降噪幅度读取完成" -> "正在读取耳机设置，请稍后重试。"
            "请等待降噪幅度确认后再切换模式" -> "正在更新自适应音频，请稍后切换模式。"
            "请等待降噪指令发送完成后再切换模式" -> "正在发送自适应音频设置，请稍后切换模式。"
            "当前设置或取值尚未开放写入" -> "此设置暂时无法更改。"
            "无法调节媒体音量" -> message
            "请先连接并佩戴双耳" -> message
            "请先取得姿态流并完成三轴校准" -> "请先完成三轴校准。"
            "系统追踪启动失败" -> "无法开启头部追踪，请重试。"
            else -> null
        }
    }
}
