package com.hyper.podsrx.widget

import android.content.Context
import android.widget.RemoteViews
import com.hyper.podsrx.R

/** Builds the 1x3 ring card. Each cell is one bitmap: track, charge arc, glyph and charge badge. */
internal object RingWidgetViews {
    fun build(context: Context, snapshot: WidgetSnapshot, sizePx: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_battery_ring)
        bind(context, views, R.id.widget_ring_left, R.drawable.widget_bud_left, R.string.left, snapshot.left, sizePx)
        bind(context, views, R.id.widget_ring_right, R.drawable.widget_bud_right, R.string.right, snapshot.right, sizePx)
        bind(context, views, R.id.widget_ring_case, R.drawable.widget_case, R.string.case_short, snapshot.case, sizePx)
        views.setContentDescription(
            R.id.widget_ring_root,
            if (snapshot.connected) WidgetViews.summary(context, snapshot) else context.getString(R.string.earbuds_not_connected)
        )
        views.setOnClickPendingIntent(R.id.widget_ring_root, WidgetViews.open(context))
        return views
    }

    private fun bind(context: Context, views: RemoteViews, id: Int, icon: Int, label: Int, cell: WidgetCell, sizePx: Int) {
        views.setImageViewBitmap(id, WidgetRing.bitmap(context, cell, icon, sizePx))
        views.setContentDescription(id, context.getString(R.string.widget_cell_description, context.getString(label), cell.text))
    }
}
