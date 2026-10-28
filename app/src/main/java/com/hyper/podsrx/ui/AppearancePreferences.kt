package com.hyper.podsrx.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UiStyle(val label: String) {
    MIUIX("miuix风格"), IOS("iOS风格");

    companion object {
        fun fromStored(value: String?): UiStyle = entries.firstOrNull { it.name == value } ?: MIUIX
    }
}

/** App appearance is independent of the bound headset and its control session. */
object AppearancePreferences {
    private lateinit var preferences: SharedPreferences
    private val mutable = MutableStateFlow(UiStyle.MIUIX)
    val style = mutable.asStateFlow()

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        mutable.value = UiStyle.fromStored(preferences.getString("style", null))
    }

    fun setStyle(style: UiStyle) {
        preferences.edit().putString("style", style.name).apply()
        mutable.value = style
    }
}
