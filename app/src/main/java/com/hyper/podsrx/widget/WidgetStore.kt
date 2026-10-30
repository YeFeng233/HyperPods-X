package com.hyper.podsrx.widget

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.hyper.podsrx.hook.HubBundles

/**
 * The widget's last snapshot. The hub sends one with every notice, and the system re-inflates
 * widgets after a launcher restart, so the levels survive without waking the Bluetooth host.
 */
internal object WidgetStore {
    private const val PREFS = "widget_snapshot"
    private const val CONNECTED = "connected"
    private const val LEVEL_SUFFIX = "_level"
    private const val CHARGING_SUFFIX = "_charging"
    private const val NONE = -1

    fun read(context: Context): WidgetSnapshot {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun cell(key: String): WidgetCell {
            val level = values.getInt(key + LEVEL_SUFFIX, NONE)
            return WidgetCell(level.takeIf { it >= 0 }, values.getBoolean(key + CHARGING_SUFFIX, false))
        }
        return WidgetSnapshot(values.getBoolean(CONNECTED, false), cell("left"), cell("right"), cell("case"))
    }

    fun write(context: Context, snapshot: WidgetSnapshot) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        put(editor, "left", snapshot.left)
        put(editor, "right", snapshot.right)
        put(editor, "case", snapshot.case)
        editor.putBoolean(CONNECTED, snapshot.connected).apply()
    }

    private fun put(editor: SharedPreferences.Editor, key: String, cell: WidgetCell) {
        editor.putInt(key + LEVEL_SUFFIX, cell.level ?: NONE)
        editor.putBoolean(key + CHARGING_SUFFIX, cell.charging)
    }

    /** The hub's payload is the same bundle its provider method returns. */
    fun of(bundle: Bundle?): WidgetSnapshot? {
        val state = bundle?.let { HubBundles.State.from(it) } ?: return null
        return WidgetSnapshot.of(state.address, state.readings)
    }
}
