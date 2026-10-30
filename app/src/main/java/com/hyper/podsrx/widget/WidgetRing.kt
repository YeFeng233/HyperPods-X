package com.hyper.podsrx.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.hyper.podsrx.R
import kotlin.math.roundToInt

/** Arc geometry of the ring card, kept separate from the drawing so it can be asserted in tests. */
internal object WidgetRingArc {
    const val START_ANGLE = -90f

    fun sweep(level: Int?): Float = when {
        level == null || level <= 0 -> 0f
        level >= 100 -> 360f
        else -> 360f * level / 100f
    }
}

/** Paints one ring cell. RemoteViews cannot host a custom view, so the cell ships as a bitmap. */
internal object WidgetRing {
    private const val STROKE_RATIO = 0.075f
    private const val EDGE_RATIO = 0.065f
    private const val GLYPH_RATIO = 0.52f
    private const val BADGE_RATIO = 0.19f
    private const val BADGE_GLYPH_RATIO = 0.28f
    private const val FALLBACK_DP = 60
    private const val MAX_DP = 76
    private const val PADDING_DP = 2

    /** Cell edge in pixels, taken from the size the launcher actually granted the card. */
    fun cellSizePx(context: Context, manager: AppWidgetManager, id: Int): Int {
        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val dp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)?.takeIf { it > 0 } ?: FALLBACK_DP
        val edge = dp.coerceAtMost(MAX_DP) - PADDING_DP
        return (edge * context.resources.displayMetrics.density).roundToInt()
    }

    fun bitmap(context: Context, cell: WidgetCell, icon: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = sizePx * STROKE_RATIO
        val edge = sizePx * EDGE_RATIO
        val inset = edge + stroke / 2f
        val ring = RectF(inset, inset, sizePx - inset, sizePx - inset)
        val center = sizePx / 2f
        val radius = (sizePx - 2f * inset) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        paint.color = context.getColor(R.color.widget_ring_track)
        canvas.drawArc(ring, 0f, 360f, false, paint)

        val sweep = WidgetRingArc.sweep(cell.level)
        if (sweep > 0f) {
            paint.color = context.getColor(R.color.widget_ring_progress)
            canvas.drawArc(ring, WidgetRingArc.START_ANGLE, sweep, false, paint)
        }

        val glyphSize = (radius * 2f * GLYPH_RATIO).roundToInt()
        draw(context, canvas, icon, R.color.widget_icon, centered(sizePx, glyphSize))

        if (cell.charging) {
            val badgeRadius = sizePx * BADGE_RATIO
            val offset = radius * 0.707f
            val badgeCx = center + offset
            val badgeCy = center - offset
            paint.style = Paint.Style.FILL
            paint.color = context.getColor(R.color.widget_ring_progress)
            canvas.drawCircle(badgeCx, badgeCy, badgeRadius, paint)
            val badge = (sizePx * BADGE_GLYPH_RATIO).roundToInt()
            val left = (badgeCx - badge / 2f).roundToInt()
            val top = (badgeCy - badge / 2f).roundToInt()
            draw(context, canvas, R.drawable.widget_bolt, R.color.widget_surface, Rect(left, top, left + badge, top + badge))
        }
        return bitmap
    }

    private fun centered(sizePx: Int, side: Int): Rect {
        val left = (sizePx - side) / 2
        return Rect(left, left, left + side, left + side)
    }

    private fun draw(context: Context, canvas: Canvas, res: Int, tint: Int, bounds: Rect) {
        val drawable: Drawable = context.getDrawable(res)?.mutate() ?: return
        drawable.setTint(context.getColor(tint))
        drawable.bounds = bounds
        drawable.draw(canvas)
    }
}
