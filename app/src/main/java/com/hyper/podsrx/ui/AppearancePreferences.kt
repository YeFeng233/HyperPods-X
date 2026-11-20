package com.hyper.podsrx.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UiStyle(val label: String) {
    MIUIX("miuix"), IOS("iOS");

    companion object {
        fun fromStored(value: String?): UiStyle = entries.firstOrNull { it.name == value } ?: MIUIX
    }
}

/** App appearance is independent of the bound headset and its control session. */
object AppearancePreferences {
    private lateinit var preferences: SharedPreferences
    private val mutable = MutableStateFlow(UiStyle.MIUIX)
    val style = mutable.asStateFlow()
    private val floating = MutableStateFlow(true)
    val floatingBottomBar = floating.asStateFlow()
    private val glass = MutableStateFlow(true)
    val glassBottomBar = glass.asStateFlow()

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        mutable.value = UiStyle.fromStored(preferences.getString("style", null))
        floating.value = preferences.getBoolean("floating_bottom_bar", true)
        glass.value = floating.value && preferences.getBoolean("glass_bottom_bar", true)
    }

    fun setStyle(style: UiStyle) {
        preferences.edit().putString("style", style.name).apply()
        mutable.value = style
    }

    fun setFloatingBottomBar(enabled: Boolean) {
        val glassEnabled = enabled && glass.value
        preferences.edit()
            .putBoolean("floating_bottom_bar", enabled)
            .putBoolean("glass_bottom_bar", glassEnabled)
            .apply()
        glass.value = glassEnabled
        floating.value = enabled
    }

    fun setGlassBottomBar(enabled: Boolean) {
        preferences.edit().putBoolean("glass_bottom_bar", enabled).apply()
        glass.value = enabled
    }
}
