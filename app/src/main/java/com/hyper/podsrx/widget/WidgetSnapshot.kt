package com.hyper.podsrx.widget

import com.hyper.podsrx.protocol.Reading

/** One column of the widget: a level the hub may not hold, and whether that part is charging. */
internal data class WidgetCell(val level: Int?, val charging: Boolean) {
    /** "87%", or a dash while the hub has no fresh reading for this part. */
    val text: String get() = level?.let { "$it%" } ?: "—"
}

/** The three levels the widget shows, taken from the hub's merged readings. */
internal data class WidgetSnapshot(
    val connected: Boolean,
    val left: WidgetCell = WidgetCell(null, false),
    val right: WidgetCell = WidgetCell(null, false),
    val case: WidgetCell = WidgetCell(null, false)
) {
    companion object {
        /** AACP component ids: the primary ear, the secondary ear, then the case. */
        const val LEFT = 4
        const val RIGHT = 2
        const val CASE = 8

        val EMPTY = WidgetSnapshot(false)

        /**
         * Readings the hub has aged out are already gone from its map, so whatever is left is shown
         * as is; a level outside 0..100 never reaches the widget.
         */
        fun of(address: String, readings: Map<Int, Reading>): WidgetSnapshot {
            fun cell(component: Int): WidgetCell {
                val reading = readings[component] ?: return WidgetCell(null, false)
                val level = reading.level?.takeIf { it in 0..100 } ?: return WidgetCell(null, false)
                return WidgetCell(level, reading.charging)
            }
            return WidgetSnapshot(address.isNotEmpty(), cell(LEFT), cell(RIGHT), cell(CASE))
        }
    }
}
