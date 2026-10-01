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
 * Android Custom View rendering a realistic 900° Euro Truck Steering Wheel.
 * Features continuous multi-turn touch rotation, spring centering, deadzone / response curve,
 * and a hold-to-sound horn in the central boss.
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

    /** Text shown on the small display in the top spoke (e.g. the current gear). */
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

    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val perforationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 8, 9, 12) }
    private val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#D94141")
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F97316") }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spokeSlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#15181D") }
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1F25") }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#080B0F") }
    private val screenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2A3445")
    }
    private val screenTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val screenLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2D3748")
    }
    private val hubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val hornIcon = ContextCompat.getDrawable(context, R.drawable.ic_volume)!!.mutate()
    private val spokePath = Path()
    private val tmpRect = RectF()

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
        rimWidth = radius * 0.16f
        rimRadius = radius * 0.86f
        hubRadius = radius * 0.30f

        backPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.parseColor("#34373D"), Color.parseColor("#3F4248"), Color.parseColor("#6A6E75"), Color.parseColor("#4A4E55")),
            floatArrayOf(0f, 0.7f, 0.95f, 1f),
            Shader.TileMode.CLAMP
        )
        val rimInner = (rimRadius - rimWidth / 2f) / radius
        val rimOuter = (rimRadius + rimWidth / 2f) / radius
        rimPaint.strokeWidth = rimWidth
        rimPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.parseColor("#121418"), Color.parseColor("#121418"), Color.parseColor("#2E3238"), Color.parseColor("#1A1D22"), Color.parseColor("#0E1013")),
            floatArrayOf(0f, rimInner, (rimInner + rimOuter) / 2f - 0.02f, rimOuter - 0.02f, rimOuter),
            Shader.TileMode.CLAMP
        )
        innerPaint.shader = RadialGradient(
            cx, cy, rimRadius,
            intArrayOf(Color.parseColor("#2A2D32"), Color.parseColor("#3A3D43")),
            null,
            Shader.TileMode.CLAMP
        )
        spokePaint.shader = LinearGradient(
            0f, cy - radius * 0.1f, 0f, cy + radius * 0.1f,
            intArrayOf(Color.parseColor("#E2E5EA"), Color.parseColor("#A6ABB3"), Color.parseColor("#6B7079")),
            null,
            Shader.TileMode.CLAMP
        )
        hubPaint.shader = LinearGradient(
            0f, cy - hubRadius, 0f, cy + hubRadius,
            intArrayOf(Color.parseColor("#262A31"), Color.parseColor("#111419")),
            null,
            Shader.TileMode.CLAMP
        )
        stitchPaint.strokeWidth = max(1f, radius * 0.008f)
        stitchPaint.pathEffect = DashPathEffect(floatArrayOf(radius * 0.025f, radius * 0.02f), 0f)
        screenBorderPaint.strokeWidth = max(1f, radius * 0.008f)
        hubBorderPaint.strokeWidth = radius * 0.015f
        screenTextPaint.textSize = radius * 0.09f
        screenLabelPaint.textSize = radius * 0.045f
        hubTextPaint.textSize = radius * 0.075f
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        canvas.save()
        canvas.rotate(currentAngle, cx, cy)

        // Outer grey backplate / bezel
        canvas.drawCircle(cx, cy, radius, backPaint)
        // Inner field
        canvas.drawCircle(cx, cy, rimRadius - rimWidth / 2f, innerPaint)
        // Leather rim
        canvas.drawCircle(cx, cy, rimRadius, rimPaint)

        // Perforated grip pattern
        val dot = max(0.8f, radius * 0.007f)
        for (ring in -1..1) {
            val r = rimRadius + ring * rimWidth * 0.25f
            val steps = (2 * PI * r / (radius * 0.045f)).toInt()
            val offset = if (ring == 0) 0.5f else 0f
            for (i in 0 until steps) {
                val a = (i + offset) * 2 * PI / steps
                canvas.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), dot, perforationPaint)
            }
        }
        // Red contrast stitching along the inner edge
        canvas.drawCircle(cx, cy, rimRadius - rimWidth * 0.36f, stitchPaint)

        // Top stem with digital display cluster
        spokePath.reset()
        spokePath.moveTo(cx - radius * 0.07f, cy - hubRadius * 0.8f)
        spokePath.lineTo(cx - radius * 0.05f, cy - rimRadius + rimWidth * 0.3f)
        spokePath.lineTo(cx + radius * 0.05f, cy - rimRadius + rimWidth * 0.3f)
        spokePath.lineTo(cx + radius * 0.07f, cy - hubRadius * 0.8f)
        spokePath.close()
        canvas.drawPath(spokePath, stemPaint)
        tmpRect.set(cx - radius * 0.1f, cy - radius * 0.62f, cx + radius * 0.1f, cy - radius * 0.40f)
        canvas.drawRoundRect(tmpRect, radius * 0.03f, radius * 0.03f, screenPaint)
        canvas.drawRoundRect(tmpRect, radius * 0.03f, radius * 0.03f, screenBorderPaint)
        canvas.drawText(displayText, cx, tmpRect.centerY() + screenTextPaint.textSize * 0.2f, screenTextPaint)
        canvas.drawText("GEAR", cx, tmpRect.bottom - radius * 0.02f, screenLabelPaint)

        // Silver side spokes with dark inset slot
        drawSideSpoke(canvas, -1f)
        drawSideSpoke(canvas, 1f)
        // Bottom spoke
        spokePath.reset()
        spokePath.moveTo(cx - radius * 0.11f, cy + hubRadius * 0.7f)
        spokePath.lineTo(cx - radius * 0.08f, cy + rimRadius - rimWidth * 0.3f)
        spokePath.lineTo(cx + radius * 0.08f, cy + rimRadius - rimWidth * 0.3f)
        spokePath.lineTo(cx + radius * 0.11f, cy + hubRadius * 0.7f)
        spokePath.close()
        canvas.drawPath(spokePath, spokePaint)
        tmpRect.set(cx - radius * 0.035f, cy + hubRadius * 1.1f, cx + radius * 0.035f, cy + rimRadius - rimWidth * 0.9f)
        canvas.drawRoundRect(tmpRect, radius * 0.03f, radius * 0.03f, spokeSlotPaint)

        // 12 o'clock orange centering marker
        tmpRect.set(
            cx - radius * 0.055f, cy - rimRadius - rimWidth * 0.62f,
            cx + radius * 0.055f, cy - rimRadius + rimWidth * 0.62f
        )
        canvas.drawRoundRect(tmpRect, radius * 0.03f, radius * 0.03f, markerPaint)

        // Central horn boss
        canvas.drawCircle(cx, cy, hubRadius, hubPaint)
        canvas.drawCircle(cx, cy, hubRadius, hubBorderPaint)
        val hornColor = if (hornPressed) Color.parseColor("#FBBF24") else Color.parseColor("#CBD5E1")
        val iconSize = (hubRadius * 0.62f).toInt()
        hornIcon.setTint(hornColor)
        hornIcon.setBounds(
            (cx - iconSize / 2f).toInt(), (cy - iconSize * 0.85f).toInt(),
            (cx + iconSize / 2f).toInt(), (cy + iconSize * 0.15f).toInt()
        )
        hornIcon.draw(canvas)
        hubTextPaint.color = hornColor
        canvas.drawText("HORN", cx, cy + hubRadius * 0.5f, hubTextPaint)

        canvas.restore()
    }

    private fun drawSideSpoke(canvas: Canvas, side: Float) {
        val inner = hubRadius * 0.8f
        val outer = rimRadius - rimWidth * 0.3f
        spokePath.reset()
        spokePath.moveTo(cx + side * inner, cy - radius * 0.11f)
        spokePath.lineTo(cx + side * outer, cy - radius * 0.07f)
        spokePath.lineTo(cx + side * outer, cy + radius * 0.09f)
        spokePath.lineTo(cx + side * inner, cy + radius * 0.13f)
        spokePath.close()
        canvas.drawPath(spokePath, spokePaint)
        val slotInner = hubRadius * 1.15f
        val slotOuter = outer - rimWidth * 0.5f
        tmpRect.set(cx + side * slotInner, cy - radius * 0.03f, cx + side * slotOuter, cy + radius * 0.04f)
        tmpRect.sort()
        canvas.drawRoundRect(tmpRect, radius * 0.03f, radius * 0.03f, spokeSlotPaint)
    }
}
