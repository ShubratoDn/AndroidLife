package com.truckcontroller.pro.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Hero of the Charging Meter: a ring filled to the battery level, the live current in the centre
 * and a status chip. While charging a light travels around the ring and the glow pulses.
 */
class BatteryRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var level = 0
        set(value) { field = value.coerceIn(0, 100); invalidate() }
    var color = Color.parseColor("#22C55E")
        set(value) { field = value; invalidate() }
    /** Main readout, e.g. "+850", "Full", "…". */
    var valueText = "…"
        set(value) { field = value; invalidate() }
    /** Small text under the readout, e.g. "mA"; empty hides it. */
    var unitText = "mA"
        set(value) { field = value; invalidate() }
    /** Chip text, e.g. "Charging · USB". */
    var chipText = ""
        set(value) { field = value; invalidate() }
    /** Animates the ring (charging). */
    var animated = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val arc = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1A2230")
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#263041") }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipRect = RectF()

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
            else -> min(w, h)
        }
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        if (radius <= 0f) return
        val stroke = radius * 0.085f
        val ringR = radius - stroke * 1.4f
        val t = SystemClock.uptimeMillis()

        // Soft glow behind the ring (pulses while charging)
        val pulse = if (animated) 0.75f + 0.25f * sin(t / 450.0).toFloat() else 0.6f
        glowPaint.shader = RadialGradient(cx, cy, radius,
            intArrayOf(alpha(color, (60 * pulse).toInt()), alpha(color, (22 * pulse).toInt()), Color.TRANSPARENT),
            floatArrayOf(0.35f, 0.8f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, glowPaint)

        // Fine tick marks just inside the ring
        tickPaint.strokeWidth = density
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val r1 = ringR - stroke * 1.05f
            val r2 = r1 - (if (i % 5 == 0) stroke * 0.55f else stroke * 0.3f)
            canvas.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(),
                cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), tickPaint)
        }

        // Track and level arc
        arc.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        trackPaint.strokeWidth = stroke
        canvas.drawArc(arc, 0f, 360f, false, trackPaint)
        val sweep = 360f * level / 100f
        if (sweep > 0f) {
            levelPaint.strokeWidth = stroke
            levelPaint.shader = SweepGradient(cx, cy, intArrayOf(alpha(color, 110), color, color), floatArrayOf(0f, 0.6f, 1f)).apply {
                val m = android.graphics.Matrix()
                m.setRotate(-90f, cx, cy)
                setLocalMatrix(m)
            }
            canvas.drawArc(arc, -90f, sweep, false, levelPaint)

            // Travelling spark along the filled part while charging
            if (animated) {
                val phase = (t % 1800L) / 1800f
                val a = Math.toRadians((-90 + sweep * phase).toDouble())
                val sx = cx + (cos(a) * ringR).toFloat()
                val sy = cy + (sin(a) * ringR).toFloat()
                sparkPaint.shader = RadialGradient(sx, sy, stroke * 1.4f,
                    intArrayOf(Color.WHITE, alpha(Color.WHITE, 90), Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(sx, sy, stroke * 1.4f, sparkPaint)
            }
        }

        // Level on top
        smallPaint.textSize = radius * 0.085f
        smallPaint.color = Color.parseColor("#94A3B8")
        canvas.drawText("$level%", cx, cy - radius * 0.36f, smallPaint)

        // Main readout
        valuePaint.textSize = radius * (if (valueText.length > 6) 0.24f else 0.34f)
        val maxW = ringR * 1.45f
        if (valuePaint.measureText(valueText) > maxW) valuePaint.textSize *= maxW / valuePaint.measureText(valueText)
        canvas.drawText(valueText, cx, cy + valuePaint.textSize * 0.3f, valuePaint)
        if (unitText.isNotEmpty()) {
            smallPaint.textSize = radius * 0.1f
            smallPaint.color = color
            canvas.drawText(unitText, cx, cy + radius * 0.27f, smallPaint)
        }

        // Status chip
        if (chipText.isNotEmpty()) {
            smallPaint.textSize = radius * 0.075f
            smallPaint.color = color
            val w = smallPaint.measureText(chipText) + radius * 0.14f
            val h = radius * 0.16f
            val top = cy + radius * 0.4f
            chipRect.set(cx - w / 2, top, cx + w / 2, top + h)
            chipPaint.color = alpha(color, 40)
            canvas.drawRoundRect(chipRect, h / 2, h / 2, chipPaint)
            canvas.drawText(chipText, cx, chipRect.centerY() - (smallPaint.ascent() + smallPaint.descent()) / 2, smallPaint)
        }

        if (animated) postInvalidateOnAnimation()
    }
}
