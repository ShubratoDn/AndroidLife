package com.truckcontroller.pro.screentime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Bar chart of time per bucket (hours of a day or days of a week) with labels and an average line. */
class UsageBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var values: LongArray = LongArray(0)
        set(value) { field = value; invalidate() }
    var labels: List<String> = emptyList()
        set(value) { field = value; invalidate() }
    /** Index to highlight (current hour / today); -1 for none. */
    var highlight = -1
        set(value) { field = value; invalidate() }
    var showAverage = false
        set(value) { field = value; invalidate() }
    var color = Color.parseColor("#818CF8")
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        textSize = 9f * density
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val labelArea = 16 * density
        val top = 16 * density
        val bottom = height - labelArea
        val maxValue = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
        val slot = width.toFloat() / values.size
        val barW = min(slot * 0.62f, 28 * density)
        val radius = min(barW / 2, 5 * density)

        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val h = if (v > 0) ((bottom - top) * v / maxValue).coerceAtLeast(3 * density) else 2 * density
            rect.set(cx - barW / 2, bottom - h, cx + barW / 2, bottom)
            barPaint.color = when {
                i == highlight -> color
                v == maxValue && v > 0 -> Color.argb(210, Color.red(color), Color.green(color), Color.blue(color))
                v > 0 -> Color.argb(120, Color.red(color), Color.green(color), Color.blue(color))
                else -> Color.parseColor("#1E293B")
            }
            canvas.drawRoundRect(rect, radius, radius, barPaint)
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                labelPaint.color = if (i == highlight) color else Color.parseColor("#64748B")
                canvas.drawText(it, cx, height - 4 * density, labelPaint)
            }
        }

        if (showAverage) {
            val active = values.filter { it > 0 }
            if (active.isNotEmpty()) {
                val avg = active.average()
                val y = (bottom - (bottom - top) * (avg / maxValue)).toFloat()
                avgPaint.color = Color.parseColor("#94A3B8")
                canvas.drawLine(0f, y, width.toFloat(), y, avgPaint)
                labelPaint.textAlign = Paint.Align.RIGHT
                labelPaint.color = Color.parseColor("#94A3B8")
                canvas.drawText("avg", width.toFloat() - 2 * density, y - 4 * density, labelPaint)
                labelPaint.textAlign = Paint.Align.CENTER
            }
        }
    }
}
