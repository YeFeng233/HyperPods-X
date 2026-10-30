package com.hyper.podsrx.widget

import android.content.Context

/**
 * Whether the launcher holds one of our widgets. The hub announces content changes to the app only
 * while this is set, so a phone without the widget never starts our process for battery updates.
 */
internal object WidgetPresence {
    private const val PREFS = "widget_presence"
    private const val KEY = "installed"

    @Volatile var installed: Boolean = false
        private set

    /** Cheap binder read; runs on every process start and on every widget callback. */
    fun sync(context: Context) = runCatching {
        val present = WidgetRegistry.isBound(context)
        installed = present
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, present).apply()
    }
}
