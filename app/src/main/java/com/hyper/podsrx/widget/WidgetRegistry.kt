package com.hyper.podsrx.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** The two cards share one state, so both live in one registry that answers "is any card bound". */
internal object WidgetRegistry {
    internal enum class Style { ICON, RING }

    internal class Entry(val type: Class<out AppWidgetProvider>, val style: Style)

    private val entries = listOf(
        Entry(BatteryWidgetProvider::class.java, Style.ICON),
        Entry(RingWidgetProvider::class.java, Style.RING)
    )

    fun isBound(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return entries.any { manager.getAppWidgetIds(ComponentName(context, it.type)).isNotEmpty() }
    }

    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val snapshot = WidgetStore.read(context)
        var bound = false
        for (entry in entries) {
            for (id in manager.getAppWidgetIds(ComponentName(context, entry.type))) {
                bound = true
                manager.updateAppWidget(id, build(context, entry.style, snapshot, manager, id))
            }
        }
        // The notice outlived the last card; drop the hub's reason to keep sending them.
        if (!bound) WidgetPresence.sync(context)
    }

    fun build(context: Context, style: Style, snapshot: WidgetSnapshot, manager: AppWidgetManager, id: Int): RemoteViews = when (style) {
        Style.ICON -> WidgetViews.build(context, snapshot)
        Style.RING -> RingWidgetViews.build(context, snapshot, WidgetRing.cellSizePx(context, manager, id))
    }
}
