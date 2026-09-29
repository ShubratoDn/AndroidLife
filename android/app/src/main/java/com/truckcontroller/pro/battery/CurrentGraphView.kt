package com.truckcontroller.pro.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** Line graph of recent current readings (mA) with a zero line and scale labels. */
class CurrentGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var values: List<Int> = emptyList()
        set(value) { field = value; invalidate() }
    var color: Int = Color.parseColor("#1FB39A")
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 10 * density
        typeface = Typeface.MONOSPACE
    }
    private val line = Path()
    private val area = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 14 * density
        val bottom = h - 6 * density
        if (values.size < 2) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Measuring…", w / 2, h / 2, labelPaint)
            return
        }
        // Symmetric scale around zero when both signs appear, else from zero
        val maxAbs = max(values.maxOf { abs(it) }, 100)
        val scale = niceCeil(maxAbs)
        val hasNeg = values.any { it < 0 }
        val hasPos = values.any { it > 0 }
        val lo = if (hasNeg) -scale else 0
        val hi = if (hasPos || !hasNeg) scale else 0
        fun y(v: Int) = bottom - (v - lo).toFloat() / (hi - lo) * (bottom - top)

        // Zero line and scale labels
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawLine(0f, y(0), w, y(0), gridPaint)
        canvas.drawText("${hi} mA", 4 * density, top - 3 * density, labelPaint)
        if (lo < 0) canvas.drawText("${lo} mA", 4 * density, bottom - 2 * density, labelPaint)

        val step = w / (BatteryReader.HISTORY_SIZE - 1)
        val startX = w - (values.size - 1) * step
        line.reset()
        area.reset()
        values.forEachIndexed { i, v ->
            val x = startX + i * step
            if (i == 0) {
                line.moveTo(x, y(v)); area.moveTo(x, y(0)); area.lineTo(x, y(v))
            } else {
                line.lineTo(x, y(v)); area.lineTo(x, y(v))
            }
        }
        area.lineTo(w, y(0))
        area.close()
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom,
            Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fillPaint)
        linePaint.color = color
        canvas.drawPath(line, linePaint)
    }

    private fun niceCeil(v: Int): Int {
        val steps = intArrayOf(100, 250, 500, 1000, 1500, 2000, 3000, 4000, 5000, 7500, 10000)
        return steps.firstOrNull { it >= v } ?: ((v / 1000) + 1) * 1000
    }
}

