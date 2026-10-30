package com.hyper.podsrx.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.hyper.podsrx.MainActivity
import com.hyper.podsrx.R

/** Builds the 1x3 card. RemoteViews only, so every value is pushed per update. */
internal object WidgetViews {
    fun build(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_battery)
        bind(context, views, R.id.widget_left_level, R.id.widget_left_bolt, R.string.left, snapshot.left)
        bind(context, views, R.id.widget_right_level, R.id.widget_right_bolt, R.string.right, snapshot.right)
        bind(context, views, R.id.widget_case_level, R.id.widget_case_bolt, R.string.case_short, snapshot.case)
        // The host reuses the existing view hierarchy, so colours are set on every update: a bound
        // pair keeps the level colour, an empty card steps the dashes back to the label colour.
        val color = context.getColor(if (snapshot.connected) R.color.widget_level else R.color.widget_label)
        for (level in intArrayOf(R.id.widget_left_level, R.id.widget_right_level, R.id.widget_case_level)) {
            views.setTextColor(level, color)
        }
        views.setContentDescription(
            R.id.widget_root,
            if (snapshot.connected) summary(context, snapshot) else context.getString(R.string.earbuds_not_connected)
        )
        views.setOnClickPendingIntent(R.id.widget_root, open(context))
        return views
    }

    private fun bind(context: Context, views: RemoteViews, levelId: Int, boltId: Int, label: Int, cell: WidgetCell) {
        views.setTextViewText(levelId, cell.text)
        views.setViewVisibility(boltId, if (cell.charging) View.VISIBLE else View.GONE)
        views.setContentDescription(levelId, context.getString(R.string.widget_cell_description, context.getString(label), cell.text))
    }

    internal fun summary(context: Context, snapshot: WidgetSnapshot): String = listOf(
        context.getString(R.string.left) to snapshot.left,
        context.getString(R.string.right) to snapshot.right,
        context.getString(R.string.case_short) to snapshot.case
    ).joinToString(context.getString(R.string.widget_summary_separator)) { (label, cell) -> "$label ${cell.text}" }

    internal fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
