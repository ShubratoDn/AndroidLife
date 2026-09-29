package com.truckcontroller.pro.sensors

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Live visual for a sensor: bubble level, compass, single-value gauge, proximity indicator,
 * or (for everything) a scrolling multi-axis graph.
 */
class SensorVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { LEVEL, COMPASS, GAUGE, PROXIMITY, GRAPH }

    var mode = Mode.GRAPH
        set(value) { field = value; invalidate() }
    var accent = Color.parseColor("#2DD4BF")
    /** Latest values (for LEVEL: x, y in m/s²; COMPASS: azimuth °; GAUGE: value; PROXIMITY: distance). */
    var values = FloatArray(0)
        set(value) { field = value; invalidate() }
    /** GAUGE: max of the scale; PROXIMITY: maximum range (far). */
    var maxValue = 1f
    var unit = ""
    /** GAUGE: log scale fits dim rooms and sunlight on one bar; linear suits narrow ranges. */
    var logScale = true
    var bigLabel = ""

    private val history = ArrayList<FloatArray>()
    private val historySize = 200
    private val axisColors = intArrayOf(Color.parseColor("#F87171"), Color.parseColor("#34D399"), Color.parseColor("#60A5FA"),
        Color.parseColor("#FBBF24"), Color.parseColor("#C084FC"))

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#94A3B8")
        typeface = Typeface.MONOSPACE
    }
    private val path = Path()

    fun pushHistory(v: FloatArray) {
        history.add(v.copyOf())
        if (history.size > historySize) history.removeAt(0)
        invalidate()
    }

    fun clearHistory() = history.clear()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.LEVEL -> drawLevel(canvas)
            Mode.COMPASS -> drawCompass(canvas)
            Mode.GAUGE -> drawGauge(canvas)
            Mode.PROXIMITY -> drawProximity(canvas)
            Mode.GRAPH -> drawGraph(canvas, 0f, 0f, width.toFloat(), height.toFloat())
        }
    }

    private fun drawLevel(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8 * density
        stroke.color = Color.parseColor("#334155"); stroke.strokeWidth = 2 * density
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.drawCircle(cx, cy, r * 0.5f, stroke)
        canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
        canvas.drawLine(cx, cy - r, cx, cy + r, stroke)
        val x = values.getOrElse(0) { 0f }
        val y = values.getOrElse(1) { 0f }
        // Bubble moves opposite to gravity like a real spirit level
        var bx = -x / 9.81f * r
        var by = y / 9.81f * r
        val d = hypot(bx, by)
        if (d > r * 0.85f) { bx *= r * 0.85f / d; by *= r * 0.85f / d }
        val level = abs(x) < 0.3f && abs(y) < 0.3f
        val bubble = if (level) Color.parseColor("#22C55E") else accent
        fill.shader = RadialGradient(cx + bx, cy + by, r * 0.16f, intArrayOf(Color.WHITE, bubble), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx + bx, cy + by, r * 0.14f, fill)
        fill.shader = null
        val tiltX = Math.toDegrees(Math.asin((x / 9.81f).coerceIn(-1f, 1f).toDouble()))
        val tiltY = Math.toDegrees(Math.asin((y / 9.81f).coerceIn(-1f, 1f).toDouble()))
        small.textSize = 12 * density
        canvas.drawText(if (level) "LEVEL" else String.format("X %.1f°   Y %.1f°", tiltX, tiltY), cx, height - 2 * density, small)
    }

    private fun drawCompass(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 14 * density
        val azimuth = values.getOrElse(0) { 0f }
        stroke.color = Color.parseColor("#334155"); stroke.strokeWidth = 2 * density
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.save()
        canvas.rotate(-azimuth, cx, cy)
        small.textSize = 14 * density
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (label, deg) ->
            val a = Math.toRadians((deg - 90).toDouble())
            small.color = if (label == "N") Color.parseColor("#EF4444") else Color.parseColor("#CBD5E1")
            canvas.drawText(label, cx + (cos(a) * r * 0.8f).toFloat(), cy + (sin(a) * r * 0.8f).toFloat() + small.textSize / 3, small)
        }
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0)
            val r2 = if (i % 6 == 0) r - 12 * density else r - 6 * density
            stroke.strokeWidth = if (i % 6 == 0) 2 * density else density
            canvas.drawLine(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), stroke)
        }
        canvas.restore()
        // Fixed needle pointing up (direction the phone faces)
        path.reset()
        path.moveTo(cx, cy - r * 0.6f); path.lineTo(cx - r * 0.07f, cy); path.lineTo(cx + r * 0.07f, cy); path.close()
        fill.color = Color.parseColor("#EF4444"); canvas.drawPath(path, fill)
        path.reset()
        path.moveTo(cx, cy + r * 0.6f); path.lineTo(cx - r * 0.07f, cy); path.lineTo(cx + r * 0.07f, cy); path.close()
        fill.color = Color.parseColor("#64748B"); canvas.drawPath(path, fill)
        text.textSize = 22 * density
        small.color = Color.parseColor("#94A3B8")
        val heading = ((azimuth % 360) + 360) % 360
        canvas.drawText("${heading.toInt()}° ${direction(heading)}", cx, cy + r * 0.35f + text.textSize, text)
    }

    private fun direction(d: Float) = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((d + 22.5f) % 360) / 45).toInt()]

    private fun drawGauge(canvas: Canvas) {
        val v = values.getOrElse(0) { 0f }
        text.textSize = min(width, height) * 0.22f
        canvas.drawText(bigLabel.ifEmpty { format(v) }, width / 2f, height * 0.45f, text)
        small.textSize = 14 * density
        small.color = accent
        canvas.drawText(unit, width / 2f, height * 0.45f + 22 * density, small)
        // Bar (log scale so both dim rooms and sunlight fit)
        val barTop = height * 0.72f
        val barH = 12 * density
        val left = 24 * density
        val right = width - 24 * density
        fill.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(left, barTop, right, barTop + barH, barH / 2, barH / 2, fill)
        val fraction = (if (logScale) ln(1 + max(v, 0f)) / ln(1 + maxValue) else v / maxValue).coerceIn(0f, 1f)
        fill.color = accent
        canvas.drawRoundRect(left, barTop, left + (right - left) * fraction, barTop + barH, barH / 2, barH / 2, fill)
    }

    private fun drawProximity(canvas: Canvas) {
        val d = values.getOrElse(0) { maxValue }
        val near = d < maxValue
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.7f
        fill.color = if (near) Color.parseColor("#EF4444") else Color.parseColor("#22C55E")
        fill.alpha = 60
        canvas.drawCircle(cx, cy, r, fill)
        fill.alpha = 255
        canvas.drawCircle(cx, cy, r * 0.55f, fill)
        text.textSize = r * 0.28f
        canvas.drawText(if (near) "NEAR" else "FAR", cx, cy + text.textSize / 3, text)
        small.textSize = 13 * density
        small.color = Color.parseColor("#94A3B8")
        canvas.drawText("${format(d)} cm · cover the top of the screen", cx, height - 4 * density, small)
    }

    private fun drawGraph(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        if (history.size < 2) {
            small.textSize = 12 * density
            canvas.drawText("Waiting for data…", (l + r) / 2, (t + b) / 2, small)
            return
        }
        val axes = history.last().size.coerceAtMost(axisColors.size)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        history.forEach { v -> for (i in 0 until axes) { lo = min(lo, v.getOrElse(i) { 0f }); hi = max(hi, v.getOrElse(i) { 0f }) } }
        if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
        val pad = (hi - lo) * 0.1f
        lo -= pad; hi += pad
        stroke.color = Color.parseColor("#1E293B"); stroke.strokeWidth = density
        if (lo < 0 && hi > 0) {
            val y0 = b - (0 - lo) / (hi - lo) * (b - t)
            canvas.drawLine(l, y0, r, y0, stroke)
        }
        val step = (r - l) / (historySize - 1)
        val startX = r - (history.size - 1) * step
        stroke.strokeWidth = 2 * density
        for (axis in 0 until axes) {
            path.reset()
            history.forEachIndexed { i, v ->
                val x = startX + i * step
                val y = b - (v.getOrElse(axis) { 0f } - lo) / (hi - lo) * (b - t)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            stroke.color = axisColors[axis]
            canvas.drawPath(path, stroke)
        }
    }

    private fun format(v: Float) = if (abs(v) >= 100) String.format("%.0f", v) else String.format("%.2f", v)

    fun axisColor(i: Int) = axisColors[i % axisColors.size]
}
