package com.truckcontroller.pro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.R
import kotlin.math.*

/**
 * A modern 3-spoke truck steering wheel: leather rim with perforated thumb grips, graphite spokes
 * with button pads, and an airbag hub with the gear display and horn. A steering arc around the
 * wheel shows how far it is turned toward full lock.
 * Continuous multi-turn touch rotation, spring centering, deadzone / response curve and a
 * hold-to-sound horn in the hub.
 */
class SteeringWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var maxDegrees = 900f // lock-to-lock (-450° to +450° for 900)
        set(value) {
            field = value
            currentAngle = currentAngle.coerceIn(-value / 2f, value / 2f)
            publish()
        }
    var springStrength = 0.75f   // 0 = no auto-centering, 1 = strongest
    var deadzone = 0.02f         // fraction of the half-range ignored around center
    var nonLinearity = 1.2f      // response curve exponent (1.0 = linear)

    var currentAngle = 0f
        private set
    var normalizedOutput = 0f // -1.0f to +1.0f after deadzone / curve
        private set

    /** Text shown on the hub display (the current gear). */
    var displayText = "N"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onAngleChanged: ((angle: Float, normalized: Float) -> Unit)? = null

    /** Steering comes from [setTiltAngle]; touches only work the horn. */
    var tiltMode = false
        set(value) {
            field = value
            steerPointerId = MotionEvent.INVALID_POINTER_ID
            springRunning = false
            invalidate()
        }
    var onWheelLockHit: (() -> Unit)? = null
    var onGrab: (() -> Unit)? = null
    var onHornChanged: ((pressed: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var steerPointerId = MotionEvent.INVALID_POINTER_ID
    private var hornPointerId = MotionEvent.INVALID_POINTER_ID
    private var prevTouchAngle = 0f
    private var atLock = false
    private var springRunning = false

    private var hornPressed = false
        set(value) {
            if (field != value) {
                field = value
                onHornChanged?.invoke(value)
                invalidate()
            }
        }

    // Geometry, recomputed in onSizeChanged
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var rimRadius = 0f
    private var rimWidth = 0f
    private var hubRadius = 0f

    private val accent = Color.parseColor("#F97316")

    // Static (doesn't turn): shadow, steering arc, light sheen
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1A2130")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#334155")
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Wheel (turns)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = Color.argb(22, 255, 255, 255)
    }
    private val perforationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 5, 6, 9) }
    private val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 249, 115, 22)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = accent
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spokeEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 255, 255, 255)
    }
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0D1117") }
    private val padBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3B4556")
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hubGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 251, 191, 36) }
    private val lcdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#05080C") }
    private val lcdBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1F2A3A")
    }
    private val lcdTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val lcdLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.15f
    }
    private val hubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    private val hornIcon = ContextCompat.getDrawable(context, R.drawable.ic_volume)!!.mutate()
    private val path = Path()
    private val rect = RectF()
    private val oval = RectF()

    private val springRunnable = object : Runnable {
        override fun run() {
            if (steerPointerId != MotionEvent.INVALID_POINTER_ID) {
                springRunning = false
                return
            }
            val rate = springStrength * 0.18f
            if (abs(currentAngle) > 0.4f && rate > 0f) {
                currentAngle *= (1f - rate)
                publish()
                postOnAnimation(this)
            } else {
                if (rate > 0f) {
                    currentAngle = 0f
                    publish()
                }
                springRunning = false
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Keep the wheel square: use the smaller of the offered dimensions
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
            else -> min(w, h)
        }
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 2f * density
        rimWidth = radius * 0.15f
        rimRadius = radius * 0.80f
        hubRadius = radius * 0.30f

        shadowPaint.shader = RadialGradient(
            cx, cy + radius * 0.04f, radius,
            intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        trackPaint.strokeWidth = radius * 0.035f
        arcPaint.strokeWidth = radius * 0.035f
        tickPaint.strokeWidth = max(1.5f, radius * 0.012f)
        sheenPaint.strokeWidth = rimWidth * 0.32f
        sheenPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.TRANSPARENT, Color.argb(46, 255, 255, 255), Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0.55f, 0.66f, 0.80f, 1f),
        )

        // Leather rim: dark with a rounded (lit) top surface
        val inner = (rimRadius - rimWidth / 2f) / radius
        val outer = (rimRadius + rimWidth / 2f) / radius
        rimPaint.strokeWidth = rimWidth
        rimPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.parseColor("#07090C"), Color.parseColor("#0B0E12"), Color.parseColor("#2A3039"),
                Color.parseColor("#1A1F26"), Color.parseColor("#090B0E")),
            floatArrayOf(0f, inner, (inner + outer) / 2f - 0.015f, outer - 0.03f, outer),
            Shader.TileMode.CLAMP
        )
        gripPaint.strokeWidth = rimWidth * 0.86f
        markerPaint.strokeWidth = rimWidth * 1.02f
        stitchPaint.strokeWidth = max(1f, radius * 0.007f)
        stitchPaint.pathEffect = DashPathEffect(floatArrayOf(radius * 0.022f, radius * 0.018f), 0f)

        // Brushed graphite spokes
        spokePaint.shader = LinearGradient(
            0f, cy - radius * 0.14f, 0f, cy + radius * 0.16f,
            intArrayOf(Color.parseColor("#4A5363"), Color.parseColor("#2A313D"), Color.parseColor("#171B23")),
            null,
            Shader.TileMode.CLAMP
        )
        spokeEdgePaint.strokeWidth = max(1f, radius * 0.006f)
        padBorderPaint.strokeWidth = max(1f, radius * 0.007f)

        hubPaint.shader = LinearGradient(
            0f, cy - hubRadius, 0f, cy + hubRadius,
            intArrayOf(Color.parseColor("#2B323E"), Color.parseColor("#161B23"), Color.parseColor("#0E1218")),
            null,
            Shader.TileMode.CLAMP
        )
        hubBorderPaint.strokeWidth = radius * 0.012f
        lcdBorderPaint.strokeWidth = max(1f, radius * 0.006f)
        lcdTextPaint.textSize = radius * 0.13f
        lcdLabelPaint.textSize = radius * 0.04f
        hubTextPaint.textSize = radius * 0.055f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val dist = hypot(x - cx, y - cy)
                parent.requestDisallowInterceptTouchEvent(true)
                if (dist <= hubRadius && hornPointerId == MotionEvent.INVALID_POINTER_ID) {
                    hornPointerId = event.getPointerId(index)
                    hornPressed = true
                } else if (!tiltMode && steerPointerId == MotionEvent.INVALID_POINTER_ID) {
                    steerPointerId = event.getPointerId(index)
                    prevTouchAngle = touchAngle(x, y)
                    onGrab?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(steerPointerId)
                if (index >= 0) {
                    val angle = touchAngle(event.getX(index), event.getY(index))
                    var delta = angle - prevTouchAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    prevTouchAngle = angle

                    val limit = maxDegrees / 2f
                    val newAngle = (currentAngle + delta).coerceIn(-limit, limit)
                    val hitLock = abs(newAngle) >= limit
                    if (hitLock && !atLock) onWheelLockHit?.invoke()
                    atLock = hitLock
                    if (newAngle != currentAngle) {
                        currentAngle = newAngle
                        publish()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointerId = event.getPointerId(event.actionIndex)
                val all = event.actionMasked != MotionEvent.ACTION_POINTER_UP
                if (all || pointerId == hornPointerId) {
                    hornPointerId = MotionEvent.INVALID_POINTER_ID
                    hornPressed = false
                }
                if (all || pointerId == steerPointerId) {
                    steerPointerId = MotionEvent.INVALID_POINTER_ID
                    startSpringCentering()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun touchAngle(x: Float, y: Float): Float = (atan2(y - cy, x - cx) * 180f / PI.toFloat())

    private fun startSpringCentering() {
        if (springRunning || springStrength <= 0f) return
        springRunning = true
        postOnAnimation(springRunnable)
    }

    /** Sets the wheel to [degrees] from the phone's tilt (clamped to the lock). */
    fun setTiltAngle(degrees: Float) {
        val limit = maxDegrees / 2f
        val newAngle = degrees.coerceIn(-limit, limit)
        val hitLock = abs(newAngle) >= limit
        if (hitLock && !atLock) onWheelLockHit?.invoke()
        atLock = hitLock
        if (abs(newAngle - currentAngle) < 0.05f) return
        currentAngle = newAngle
        publish()
    }

    /** Snaps the wheel back to dead center. */
    fun resetToCenter() {
        currentAngle = 0f
        atLock = false
        publish()
    }

    /** Applies deadzone and response curve, notifies the listener and redraws. */
    private fun publish() {
        val limit = maxDegrees / 2f
        val raw = if (limit > 0f) currentAngle / limit else 0f
        val magnitude = abs(raw)
        val shaped = if (magnitude <= deadzone) 0f else ((magnitude - deadzone) / (1f - deadzone)).pow(nonLinearity)
        normalizedOutput = sign(raw) * shaped.coerceIn(0f, 1f)
        onAngleChanged?.invoke(currentAngle, normalizedOutput)
        invalidate()
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, rimRadius + rimWidth * 0.75f, shadowPaint)
        drawSteeringArc(canvas)

        canvas.save()
        canvas.rotate(currentAngle, cx, cy)
        drawSpokes(canvas)
        drawRim(canvas)
        drawHub(canvas)
        canvas.restore()

        // Light from above: the sheen stays put while the wheel turns under it
        oval.set(cx - rimRadius, cy - rimRadius, cx + rimRadius, cy + rimRadius)
        canvas.drawArc(oval, 200f, 140f, false, sheenPaint)
    }

    /** Static arc around the wheel: fills from 12 o'clock toward the lock it is turned to. */
    private fun drawSteeringArc(canvas: Canvas) {
        val r = radius * 0.955f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val span = 130f
        canvas.drawArc(oval, -90f - span, span * 2, false, trackPaint)
        val limit = (maxDegrees / 2f).coerceAtLeast(1f)
        val fraction = (currentAngle / limit).coerceIn(-1f, 1f)
        if (abs(fraction) > 0.003f) {
            arcPaint.color = when {
                abs(fraction) > 0.9f -> Color.parseColor("#EF4444")
                abs(fraction) > 0.6f -> Color.parseColor("#FBBF24")
                else -> accent
            }
            canvas.drawArc(oval, -90f, span * fraction, false, arcPaint)
        }
        // Center and lock ticks
        for (a in floatArrayOf(-90f, -90f - span, -90f + span)) {
            val rad = Math.toRadians(a.toDouble())
            val r1 = radius * 0.915f
            val r2 = radius * 0.995f
            canvas.drawLine(
                cx + (r1 * cos(rad)).toFloat(), cy + (r1 * sin(rad)).toFloat(),
                cx + (r2 * cos(rad)).toFloat(), cy + (r2 * sin(rad)).toFloat(),
                tickPaint
            )
        }
    }

    private fun drawRim(canvas: Canvas) {
        oval.set(cx - rimRadius, cy - rimRadius, cx + rimRadius, cy + rimRadius)
        canvas.drawCircle(cx, cy, rimRadius, rimPaint)

        // Perforated thumb grips at 9 and 3 o'clock
        for (center in floatArrayOf(180f, 0f)) {
            canvas.drawArc(oval, center - 30f, 60f, false, gripPaint)
            val dot = max(0.8f, radius * 0.0065f)
            for (ring in -1..1) {
                val r = rimRadius + ring * rimWidth * 0.24f
                val step = 4.2f
                var a = center - 28f + (if (ring == 0) step / 2f else 0f)
                while (a <= center + 28f) {
                    val rad = Math.toRadians(a.toDouble())
                    canvas.drawCircle(cx + (r * cos(rad)).toFloat(), cy + (r * sin(rad)).toFloat(), dot, perforationPaint)
                    a += step
                }
            }
            // Contrast stitching framing the grip
            val inner = rimRadius - rimWidth * 0.42f
            rect.set(cx - inner, cy - inner, cx + inner, cy + inner)
            canvas.drawArc(rect, center - 30f, 60f, false, stitchPaint)
        }

        // 12 o'clock centering band
        canvas.drawArc(oval, -94f, 8f, false, markerPaint)
    }

    private fun drawSpokes(canvas: Canvas) {
        val reach = rimRadius - rimWidth * 0.2f
        // Side spokes, slightly swept up, with a row of button pads
        for (side in floatArrayOf(-1f, 1f)) {
            path.reset()
            path.moveTo(cx + side * hubRadius * 0.7f, cy - radius * 0.13f)
            path.quadTo(cx + side * radius * 0.45f, cy - radius * 0.11f, cx + side * reach, cy - radius * 0.09f)
            path.lineTo(cx + side * reach, cy + radius * 0.08f)
            path.quadTo(cx + side * radius * 0.45f, cy + radius * 0.13f, cx + side * hubRadius * 0.7f, cy + radius * 0.17f)
            path.close()
            canvas.drawPath(path, spokePaint)
            canvas.drawPath(path, spokeEdgePaint)
            for (i in 0..1) {
                val px = cx + side * radius * (0.40f + i * 0.13f)
                rect.set(px - radius * 0.045f, cy - radius * 0.045f, px + radius * 0.045f, cy + radius * 0.035f)
                canvas.drawRoundRect(rect, radius * 0.02f, radius * 0.02f, padPaint)
                canvas.drawRoundRect(rect, radius * 0.02f, radius * 0.02f, padBorderPaint)
            }
        }
        // Bottom spoke: two bars meeting at the rim
        for (side in floatArrayOf(-1f, 1f)) {
            path.reset()
            path.moveTo(cx + side * radius * 0.05f, cy + hubRadius * 0.75f)
            path.lineTo(cx + side * radius * 0.16f, cy + hubRadius * 0.75f)
            path.lineTo(cx + side * radius * 0.09f, cy + reach)
            path.lineTo(cx + side * radius * 0.015f, cy + reach)
            path.close()
            canvas.drawPath(path, spokePaint)
            canvas.drawPath(path, spokeEdgePaint)
        }
    }

    /** Airbag hub: gear display on top, horn below; glows amber while honking. */
    private fun drawHub(canvas: Canvas) {
        val w = hubRadius * 1.02f
        val h = hubRadius * 0.92f
        rect.set(cx - w, cy - h, cx + w, cy + h * 1.05f)
        if (hornPressed) {
            canvas.drawRoundRect(
                RectF(rect.left - radius * 0.03f, rect.top - radius * 0.03f, rect.right + radius * 0.03f, rect.bottom + radius * 0.03f),
                hubRadius * 0.55f, hubRadius * 0.55f, hubGlowPaint
            )
        }
        canvas.drawRoundRect(rect, hubRadius * 0.5f, hubRadius * 0.5f, hubPaint)
        hubBorderPaint.color = if (hornPressed) Color.parseColor("#FBBF24") else Color.parseColor("#334155")
        canvas.drawRoundRect(rect, hubRadius * 0.5f, hubRadius * 0.5f, hubBorderPaint)

        // Gear display
        val lcdW = hubRadius * 0.62f
        rect.set(cx - lcdW, cy - h * 0.78f, cx + lcdW, cy - h * 0.08f)
        canvas.drawRoundRect(rect, radius * 0.025f, radius * 0.025f, lcdPaint)
        canvas.drawRoundRect(rect, radius * 0.025f, radius * 0.025f, lcdBorderPaint)
        canvas.drawText("GEAR", cx, rect.top + lcdLabelPaint.textSize * 1.25f, lcdLabelPaint)
        canvas.drawText(displayText, cx, rect.bottom - radius * 0.025f, lcdTextPaint)

        // Horn
        val hornColor = if (hornPressed) Color.parseColor("#FBBF24") else Color.parseColor("#94A3B8")
        val icon = (hubRadius * 0.34f).toInt()
        val iconTop = (cy + h * 0.06f).toInt()
        hornIcon.setTint(hornColor)
        hornIcon.setBounds((cx - icon / 2f).toInt(), iconTop, (cx + icon / 2f).toInt(), iconTop + icon)
        hornIcon.draw(canvas)
        hubTextPaint.color = hornColor
        canvas.drawText("HORN", cx, iconTop + icon + hubTextPaint.textSize * 1.15f, hubTextPaint)
    }
}
