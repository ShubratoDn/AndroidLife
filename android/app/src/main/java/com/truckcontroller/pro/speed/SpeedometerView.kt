package com.truckcontroller.pro.speed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Analog speedometer: 270° dial with ticks, a colour arc up to the current speed, a needle,
 * a marker for the trip's max speed and a large digital readout. Values are in display units.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Current speed in display units (km/h or mph). */
    var speed = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            scaleMax = pickScale(maxOf(field, maxMark))
            invalidate()
        }
    /** Trip max speed in display units; 0 hides the marker. */
    var maxMark = 0f
        set(value) {
            field = value
            scaleMax = pickScale(maxOf(speed, field))
            invalidate()
        }
    var unit = "km/h"
        set(value) { field = value; invalidate() }
    var hasFix = false
        set(value) { field = value; invalidate() }
    var statusText = "WAITING FOR GPS"
        set(value) { field = value; invalidate() }

    private var scaleMax = 120f
    private var shown = 0f // animated needle value

    private val density = resources.displayMetrics.density
    private val startAngle = 135f
    private val sweep = 270f
    private val arcRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1E293B")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#94A3B8")
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#F97316")
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F141D") }
    private val hubRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F97316")
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#94A3B8")
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.15f
    }
    private val maxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#EF4444")
    }

    private fun pickScale(value: Float): Float =
        floatArrayOf(60f, 120f, 180f, 240f, 300f).firstOrNull { value <= it * 0.92f } ?: 300f

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

    private fun angleFor(value: Float) = startAngle + sweep * (value / scaleMax).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 8f * density
        if (radius <= 0f) return
        val stroke = radius * 0.09f

        // Ease the needle towards the target speed
        val diff = speed - shown
        shown += diff * 0.2f
        if (abs(diff) > 0.05f) postInvalidateOnAnimation() else shown = speed

        arcRect.set(cx - radius + stroke, cy - radius + stroke, cx + radius - stroke, cy + radius - stroke)
        trackPaint.strokeWidth = stroke
        canvas.drawArc(arcRect, startAngle, sweep, false, trackPaint)

        // Colour arc: green -> amber -> orange -> red as the speed rises
        valuePaint.strokeWidth = stroke
        valuePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.parseColor("#34D399"), Color.parseColor("#FBBF24"), Color.parseColor("#F97316"),
                Color.parseColor("#EF4444"), Color.parseColor("#34D399")),
            floatArrayOf(0f, 0.3f, 0.55f, 0.75f, 1f)
        ).also {
            val m = android.graphics.Matrix()
            m.setRotate(startAngle - 5f, cx, cy)
            it.setLocalMatrix(m)
        }
        val valueSweep = angleFor(shown) - startAngle
        if (valueSweep > 0.5f) canvas.drawArc(arcRect, startAngle, valueSweep, false, valuePaint)

        // Ticks and labels
        val major = if (scaleMax <= 60f) 10f else 20f
        val minor = major / 2f
        labelPaint.textSize = radius * 0.1f
        var v = 0f
        while (v <= scaleMax + 0.01f) {
            val isMajor = (v % major) < 0.01f
            val a = Math.toRadians(angleFor(v).toDouble())
            val outer = radius - stroke * 2.2f
            val inner = outer - if (isMajor) radius * 0.09f else radius * 0.05f
            tickPaint.strokeWidth = if (isMajor) 2.5f * density else 1.3f * density
            tickPaint.color = if (isMajor) Color.parseColor("#CBD5E1") else Color.parseColor("#475569")
            canvas.drawLine(
                cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                cx + (cos(a) * outer).toFloat(), cy + (sin(a) * outer).toFloat(), tickPaint
            )
            if (isMajor) {
                val lr = inner - radius * 0.1f
                canvas.drawText(
                    v.toInt().toString(),
                    cx + (cos(a) * lr).toFloat(),
                    cy + (sin(a) * lr).toFloat() + labelPaint.textSize / 3f, labelPaint
                )
            }
            v += minor
        }

        // Trip max-speed marker
        if (maxMark > 0f) {
            val a = Math.toRadians(angleFor(maxMark).toDouble())
            val o = radius
            val i = radius - stroke * 1.9f
            maxPaint.strokeWidth = 3f * density
            canvas.drawLine(
                cx + (cos(a) * i).toFloat(), cy + (sin(a) * i).toFloat(),
                cx + (cos(a) * o).toFloat(), cy + (sin(a) * o).toFloat(), maxPaint
            )
        }

        // Needle
        val na = Math.toRadians(angleFor(shown).toDouble())
        val len = radius - stroke * 3.2f
        needlePaint.strokeWidth = 4f * density
        canvas.drawLine(
            cx - (cos(na) * radius * 0.08f).toFloat(), cy - (sin(na) * radius * 0.08f).toFloat(),
            cx + (cos(na) * len).toFloat(), cy + (sin(na) * len).toFloat(), needlePaint
        )
        canvas.drawCircle(cx, cy, radius * 0.07f, hubPaint)
        hubRing.strokeWidth = 3f * density
        canvas.drawCircle(cx, cy, radius * 0.07f, hubRing)

        // Digital readout
        // Sits in the open gap at the bottom of the dial, clear of the needle and the 0 label
        speedPaint.textSize = radius * 0.28f
        speedPaint.color = if (hasFix) Color.WHITE else Color.parseColor("#475569")
        val text = if (hasFix) {
            if (speed < 10f && speed > 0f) String.format(java.util.Locale.US, "%.1f", speed) else speed.toInt().toString()
        } else "--"
        canvas.drawText(text, cx, cy + radius * 0.64f, speedPaint)
        unitPaint.textSize = radius * 0.075f
        canvas.drawText(unit.uppercase(), cx, cy + radius * 0.78f, unitPaint)
        unitPaint.textSize = radius * 0.06f
        unitPaint.color = if (hasFix) Color.parseColor("#34D399") else Color.parseColor("#FBBF24")
        canvas.drawText(statusText, cx, cy + radius * 0.9f, unitPaint)
        unitPaint.color = Color.parseColor("#94A3B8")
    }
}
