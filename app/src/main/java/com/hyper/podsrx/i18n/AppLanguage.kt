package com.hyper.podsrx.i18n

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.hyper.podsrx.R
import java.util.Locale

enum class AppLanguage(val languageTag: String, @param:StringRes val labelRes: Int) {
    SYSTEM("", R.string.language_follow_system),
    CHINESE("zh-Hans", R.string.language_chinese),
    ENGLISH("en", R.string.language_english);

    companion object {
        fun fromLanguageTags(tags: String): AppLanguage = when (Locale.forLanguageTag(tags.substringBefore(',')).language) {
            "zh" -> CHINESE
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}

@Composable
internal fun currentAppLanguage(): AppLanguage {
    // Read the configuration so selections made in Android's app-language settings update this page.
    LocalConfiguration.current
    val manager = LocalContext.current.getSystemService(LocaleManager::class.java)
    return AppLanguage.fromLanguageTags(manager.applicationLocales.toLanguageTags())
}

fun setAppLanguage(context: Context, language: AppLanguage) {
    val manager = context.getSystemService(LocaleManager::class.java)
    val locales = LocaleList.forLanguageTags(language.languageTag)
    // Android persists this choice and recreates activities without restarting the earbud service.
    if (manager.applicationLocales != locales) manager.applicationLocales = locales
}
