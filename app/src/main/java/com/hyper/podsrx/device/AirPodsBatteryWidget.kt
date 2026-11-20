package com.hyper.podsrx.device

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.widget.RemoteViews
import com.hyper.podsrx.MainActivity
import com.hyper.podsrx.R
import com.hyper.podsrx.hook.HubWire

class AirPodsBatteryWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HubWire.WIDGET_BATTERY) {
            super.onReceive(context, intent)
            return
        }
        if (sentFromUid != Process.BLUETOOTH_UID || !HubWire.isAosp(context)) return
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
        if (ids.isEmpty()) return
        val battery = context.getSharedPreferences("battery_widget", Context.MODE_PRIVATE)
        battery.edit()
            .putInt("left", intent.getIntExtra("left", -1))
            .putInt("right", intent.getIntExtra("right", -1))
            .putInt("case", intent.getIntExtra("case", -1))
            .apply()
        onUpdate(context, manager, ids)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val battery = context.getSharedPreferences("battery_widget", Context.MODE_PRIVATE)
        fun level(key: String) = battery.getInt(key, -1).let { if (it in 0..100) "$it%" else "—" }
        val readings = context.getString(R.string.battery_widget_readings, level("left"), level("right"), level("case"))
        val opensApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.airpods_battery_widget).apply {
            setTextViewText(R.id.widget_readings, readings)
            setOnClickPendingIntent(R.id.widget_content, opensApp)
        }
        manager.updateAppWidget(ids, views)
    }
}
