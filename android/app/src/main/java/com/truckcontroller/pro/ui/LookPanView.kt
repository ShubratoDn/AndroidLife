package com.truckcontroller.pro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar-style touch pad for interior camera look / pan. Springs back to center on release.
 */
class LookPanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onLookChanged: ((x: Float, y: Float) -> Unit)? = null
    var onGrab: (() -> Unit)? = null

    /** Current thumb position (-1..1). Can also be driven externally, e.g. by quick-look buttons. */
    var lookX = 0f
        private set
    var lookY = 0f
        private set

    private val density = resources.displayMetrics.density
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(20, 255, 255, 255)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(26, 255, 255, 255)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B2230") }
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 6, 182, 212) }
    private val eyeIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye)!!.mutate()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - density
        bgPaint.shader = RadialGradient(
            cx, cy, radius.coerceAtLeast(1f),
            Color.parseColor("#131924"), Color.parseColor("#0A0D13"),
            Shader.TileMode.CLAMP
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                    pointerId = event.getPointerId(event.actionIndex)
                    parent.requestDisallowInterceptTouchEvent(true)
                    onGrab?.invoke()
                    update(event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) update(event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val all = event.actionMasked != MotionEvent.ACTION_POINTER_UP
                if (all || event.getPointerId(event.actionIndex) == pointerId) {
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    setLook(0f, 0f, notify = true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun update(x: Float, y: Float) {
        val dx = x - cx
        val dy = y - cy
        val dist = min(hypot(dx, dy), radius)
        val angle = atan2(dy, dx)
        setLook(cos(angle) * dist / radius, sin(angle) * dist / radius, notify = true)
    }

    fun setLook(x: Float, y: Float, notify: Boolean = false) {
        lookX = x
        lookY = y
        if (notify) onLookChanged?.invoke(x, y)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)
        // Radar rings and crosshair
        canvas.drawCircle(cx, cy, radius * 0.84f, linePaint)
        canvas.drawCircle(cx, cy, radius * 0.62f, linePaint)
        canvas.drawCircle(cx, cy, radius * 0.40f, linePaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, linePaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, linePaint)

        // Floating reticle thumb with eye icon
        val active = pointerId != MotionEvent.INVALID_POINTER_ID
        val thumbR = radius * 0.36f
        val travel = radius - thumbR
        val tx = cx + lookX * travel
        val ty = cy + lookY * travel
        if (active) canvas.drawCircle(tx, ty, thumbR * 1.25f, glowPaint)
        canvas.drawCircle(tx, ty, thumbR, thumbPaint)
        thumbBorderPaint.color = if (active) Color.parseColor("#22D3EE") else Color.argb(102, 6, 182, 212)
        canvas.drawCircle(tx, ty, thumbR, thumbBorderPaint)
        val icon = (thumbR * 1.0f).toInt()
        eyeIcon.setTint(if (active) Color.parseColor("#22D3EE") else Color.parseColor("#94A3B8"))
        eyeIcon.setBounds((tx - icon / 2f).toInt(), (ty - icon / 2f).toInt(), (tx + icon / 2f).toInt(), (ty + icon / 2f).toInt())
        eyeIcon.draw(canvas)
    }
}
