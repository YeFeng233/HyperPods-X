package com.hyper.podsrx.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/** Callback wiring both cards need; the subclass only names its style. */
abstract class WidgetProviderBase : AppWidgetProvider() {
    internal abstract val style: WidgetRegistry.Style

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetPresence.sync(context)
        // Nothing reaches us until the hub's next change, and a card that just appeared has none.
        WidgetHub.requestSnapshot(context)
        render(context, manager, ids)
    }

    override fun onEnabled(context: Context) {
        WidgetPresence.sync(context)
    }

    override fun onDisabled(context: Context) {
        WidgetPresence.sync(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        WidgetPresence.sync(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, intArrayOf(id))
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val snapshot = WidgetStore.read(context)
        for (id in ids) manager.updateAppWidget(id, WidgetRegistry.build(context, style, snapshot, manager, id))
    }
}
