package com.hyper.podsrx.i18n

import com.hyper.podsrx.R
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.device.SpatialMode
import com.hyper.podsrx.device.SpatialState
import com.hyper.podsrx.ui.batteryPresentation
import com.hyper.podsrx.ui.listeningPresentation
import com.hyper.podsrx.ui.spatialPresentation
import com.hyper.podsrx.ui.SettingsCatalog
import com.hyper.podsrx.ui.SettingsPage
import com.hyper.podsrx.ui.ios.listeningPresentation as iosListeningPresentation
import com.hyper.podsrx.ui.ios.actionFailureMessage
import com.hyper.podsrx.protocol.Reading
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationTest {
    private val root = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")

    @Test fun languageSelectionRecognizesSystemAndRegionalLocales() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags("de-DE"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US,zh-CN"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromLanguageTags("zh-Hans"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromLanguageTags("zh-CN,en-US"))
        assertEquals("", AppLanguage.SYSTEM.languageTag)
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
        assertEquals("zh-Hans", AppLanguage.CHINESE.languageTag)
    }

    @Test fun allTranslatableResourcesHaveMatchingChineseFormatArguments() {
        for (resources in listOf(root, File(root, "../../../../hook/src/main/res"))) {
            val defaults = File(resources, "values/strings.xml")
            val translations = TestStrings.catalog(File(resources, "values-zh/strings.xml"))
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(defaults).getElementsByTagName("string")
            val names = mutableSetOf<String>()
            val placeholders = Regex("%(\\d+)\\$[a-zA-Z]")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                assertTrue("Duplicate resource $name", names.add(name))
                if (node.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                assertTrue("Missing Chinese translation for $name", name in translations)
                assertEquals("Format arguments differ for $name",
                    placeholders.findAll(node.textContent).map { it.value }.sorted().toList(),
                    placeholders.findAll(translations.getValue(name)).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun existingStatusAndNestedArgumentsResolveInTheNewLanguage() {
        val state = PodsState()
        assertEquals("准备连接耳机", state.status.resolve(TestStrings.chinese))
        assertEquals("Ready to connect earbuds", state.status.resolve(TestStrings.english))
        val error = text(R.string.setting_unconfirmed, text(R.string.listening_mode))
        assertEquals("聆听模式未确认目标值，已保留设备回读结果", error.resolve(TestStrings.chinese))
        assertTrue(error.resolve(TestStrings.english).startsWith("Listening Mode did not confirm"))
        assertEquals("Charging", batteryPresentation(Reading(70, true, false, 0), TestStrings.english).note)
        assertEquals("充电中", batteryPresentation(Reading(70, true, false, 0), TestStrings.chinese).note)
    }

    @Test fun presentationMessagesUseTheRequestedLocaleWithoutChangingDeviceState() {
        val state = PodsState()
        assertEquals("连接耳机后可切换聆听模式", listeningPresentation(state, TestStrings.chinese).notice)
        assertEquals("Connect your earbuds to change listening modes", listeningPresentation(state, TestStrings.english).notice)
        val spatial = SpatialState(modeChanging = true, requestedMode = SpatialMode.FIXED)
        assertEquals("正在切换为固定…", spatial.spatialPresentation(true, TestStrings.chinese).description)
        assertEquals("Switching to Fixed…", spatial.spatialPresentation(true, TestStrings.english).description)
    }

    @Test fun listeningFailuresDependOnCodesNotTranslatedText() {
        val state = PodsState(connected = true, controls = mapOf(0x0d to listOf(3), 0x2e to listOf(50)),
            message = "Arbitrary text in any language", messageCode = R.string.setting_unconfirmed, messageControl = 0x0d)
        assertEquals("聆听模式未能更新，请重试。", iosListeningPresentation(state, TestStrings.chinese).modeNotice)
        assertEquals("Listening Mode could not be updated. Try again.", iosListeningPresentation(state, TestStrings.english).modeNotice)
        assertNull(actionFailureMessage(state, TestStrings.english))
        val rejected = state.copy(messageCode = R.string.wait_adaptive_readback)
        assertEquals("Reading earbud settings. Try again shortly.", actionFailureMessage(rejected, TestStrings.english))
    }

    @Test fun sharedSettingsCatalogKeepsLocalizedControlsAndLanguageNavigation() {
        val state = PodsState()
        assertEquals("Microphone", SettingsCatalog.title("control/1", state, TestStrings.english))
        assertEquals("麦克风", SettingsCatalog.title("control/1", state, TestStrings.chinese))
        assertEquals(SettingsPage.LANGUAGE, SettingsCatalog.page("language#retained-entry"))
        assertEquals("module", SettingsCatalog.parentRoute("language"))
        assertTrue("language" in SettingsCatalog.moduleSection("appearance").items)
        val rejected = state.copy(messageCode = R.string.wait_adaptive_readback)
        assertEquals("Reading earbud settings. Try again shortly.",
            SettingsCatalog.actionFailureMessage(rejected, TestStrings.english))
    }
}
