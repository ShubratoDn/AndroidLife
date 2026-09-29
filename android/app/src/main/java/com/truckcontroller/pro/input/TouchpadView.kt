package com.truckcontroller.pro.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.truckcontroller.pro.bluetooth.BluetoothHidService
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Laptop-style touchpad.
 *
 * - One finger: move the pointer (with acceleration)
 * - Tap: left click · two-finger tap: right click · three-finger tap: middle click
 * - Tap, then touch again and drag: drag with the left button held
 * - Two fingers: scroll (vertical and horizontal)
 * - Strip along the right edge: one-finger vertical scroll
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Mode { NONE, MOVE, SCROLL, STRIP }

    var sensitivity = 1.6f
    var scrollSpeed = 1f
    var naturalScroll = true
    var tapToClick = true
    var showScrollStrip = true
        set(value) {
            field = value
            invalidate()
        }
    var hint = "TOUCHPAD"

    var onMove: ((dx: Int, dy: Int) -> Unit)? = null
    var onScroll: ((vertical: Int, horizontal: Int) -> Unit)? = null
    var onClick: ((button: Int) -> Unit)? = null
    var onButton: ((button: Int, pressed: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeout = 220L
    private val doubleTapWindow = 260L

    private var mode = Mode.NONE
    private var primaryId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 0
    private var moved = false
    private var accX = 0f
    private var accY = 0f
    private var scrollAccV = 0f
    private var scrollAccH = 0f
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dragCandidate = false
    private var dragging = false
        set(value) {
            field = value
            invalidate()
        }

    private val padRect = RectF()
    private val stripRect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F141D") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.parseColor("#2A3445")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C2433") }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.parseColor("#334155")
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#334155")
        letterSpacing = 0.25f
    }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 249, 115, 22) }
    private val touches = HashMap<Int, Pair<Float, Float>>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        padRect.set(density, density, w - density, h - density)
        val stripW = min(w * 0.13f, 56f * density)
        stripRect.set(w - stripW, 0f, w.toFloat(), h.toFloat())
        hintPaint.textSize = min(h * 0.06f, 14f * density)
    }

    /** Ends a drag and forgets gesture state (screen hidden). */
    fun reset() {
        if (dragging) onButton?.invoke(BluetoothHidService.MOUSE_LEFT, false)
        dragging = false
        dragCandidate = false
        mode = Mode.NONE
        touches.clear()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                primaryId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = now
                lastMoveTime = now
                maxPointers = 1
                moved = false
                accX = 0f
                accY = 0f
                scrollAccV = 0f
                scrollAccH = 0f
                mode = if (showScrollStrip && stripRect.contains(event.x, event.y)) Mode.STRIP else Mode.MOVE
                dragCandidate = mode == Mode.MOVE && now - lastTapUpTime < doubleTapWindow &&
                    hypot(event.x - lastTapX, event.y - lastTapY) < 60f * density
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (!dragging && event.pointerCount >= 2 && mode != Mode.SCROLL) {
                    mode = Mode.SCROLL
                    val (cx, cy) = centroid(event)
                    lastX = cx
                    lastY = cy
                    // Measure two-finger movement from where both fingers landed
                    downX = cx
                    downY = cy
                } else if (mode == Mode.SCROLL) {
                    // Another finger joined: re-anchor so the centroid shift is not read as a scroll
                    val (cx, cy) = centroid(event)
                    lastX = cx
                    lastY = cy
                }
            }
            MotionEvent.ACTION_MOVE -> handleMove(event, now)
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == primaryId) {
                    // Hand the pointer over to a remaining finger without a jump
                    val next = (0 until event.pointerCount).first { it != event.actionIndex }
                    primaryId = event.getPointerId(next)
                    if (mode != Mode.SCROLL) {
                        lastX = event.getX(next)
                        lastY = event.getY(next)
                    }
                }
                if (mode == Mode.SCROLL) {
                    val (cx, cy) = centroid(event, excludeIndex = event.actionIndex)
                    lastX = cx
                    lastY = cy
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = now - downTime < tapTimeout && !moved
                when {
                    dragging -> {
                        onButton?.invoke(BluetoothHidService.MOUSE_LEFT, false)
                        dragging = false
                    }
                    quick && maxPointers >= 3 -> onClick?.invoke(BluetoothHidService.MOUSE_MIDDLE)
                    quick && maxPointers == 2 -> onClick?.invoke(BluetoothHidService.MOUSE_RIGHT)
                    quick && mode == Mode.MOVE && tapToClick -> {
                        onClick?.invoke(BluetoothHidService.MOUSE_LEFT)
                        lastTapUpTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                }
                dragCandidate = false
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        updateTouches(event)
        return true
    }

    private fun handleMove(event: MotionEvent, now: Long) {
        when (mode) {
            Mode.MOVE -> {
                val i = event.findPointerIndex(primaryId).takeIf { it >= 0 } ?: return
                val x = event.getX(i)
                val y = event.getY(i)
                if (!moved && hypot(x - downX, y - downY) > slop) {
                    moved = true
                    if (dragCandidate && !dragging) {
                        dragging = true
                        onButton?.invoke(BluetoothHidService.MOUSE_LEFT, true)
                    }
                }
                if (!moved) return
                val dx = x - lastX
                val dy = y - lastY
                val dt = (now - lastMoveTime).coerceAtLeast(1)
                lastX = x
                lastY = y
                lastMoveTime = now
                // Pointer acceleration: faster swipes travel further
                val speed = hypot(dx, dy) / density / dt // dp per ms
                val gain = sensitivity * (1f + 1.4f * min(speed, 2.5f))
                accX += dx / density * gain
                accY += dy / density * gain
                val outX = accX.toInt()
                val outY = accY.toInt()
                accX -= outX
                accY -= outY
                if (outX != 0 || outY != 0) onMove?.invoke(outX, outY)
            }
            Mode.SCROLL -> {
                val (cx, cy) = centroid(event)
                if (!moved && hypot(cx - downX, cy - downY) > slop * 1.5f) moved = true
                scrollBy(cx - lastX, cy - lastY)
                lastX = cx
                lastY = cy
            }
            Mode.STRIP -> {
                val y = event.y
                if (!moved && abs(y - downY) > slop) moved = true
                scrollBy(0f, y - lastY)
                lastY = y
            }
            Mode.NONE -> Unit
        }
    }

    private fun scrollBy(dx: Float, dy: Float) {
        val tick = 18f * density / scrollSpeed
        val sign = if (naturalScroll) 1 else -1
        // Natural: fingers down = content follows = scroll up (positive wheel)
        scrollAccV += dy * sign
        scrollAccH -= dx * sign
        val v = (scrollAccV / tick).toInt()
        val h = (scrollAccH / tick).toInt()
        scrollAccV -= v * tick
        scrollAccH -= h * tick
        if (v != 0 || h != 0) onScroll?.invoke(v, h)
    }

    private fun centroid(event: MotionEvent, excludeIndex: Int = -1): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sx += event.getX(i)
            sy += event.getY(i)
            n++
        }
        return if (n == 0) lastX to lastY else sx / n to sy / n
    }

    private fun updateTouches(event: MotionEvent) {
        touches.clear()
        val gone = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> -2
            MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            else -> -1
        }
        if (gone != -2) {
            for (i in 0 until event.pointerCount) if (i != gone) touches[event.getPointerId(i)] = event.getX(i) to event.getY(i)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val corner = 14f * density
        canvas.drawRoundRect(padRect, corner, corner, bgPaint)
        // Subtle dot grid
        val step = 22f * density
        var y = step
        while (y < height) {
            var x = step
            while (x < width) {
                canvas.drawCircle(x, y, density, dotPaint)
                x += step
            }
            y += step
        }
        borderPaint.color = if (dragging) Color.parseColor("#F97316") else Color.parseColor("#2A3445")
        canvas.drawRoundRect(padRect, corner, corner, borderPaint)
        if (showScrollStrip) {
            canvas.drawLine(stripRect.left, corner, stripRect.left, height - corner, stripPaint)
            canvas.save()
            canvas.rotate(90f, stripRect.centerX(), stripRect.centerY())
            canvas.drawText("SCROLL", stripRect.centerX(), stripRect.centerY() + hintPaint.textSize / 3f, hintPaint)
            canvas.restore()
        }
        val cx = if (showScrollStrip) stripRect.left / 2f else width / 2f
        canvas.drawText(if (dragging) "DRAGGING" else hint, cx, height / 2f + hintPaint.textSize / 3f, hintPaint)
        touches.values.forEach { (x, yy) -> canvas.drawCircle(x, yy, 26f * density, touchPaint) }
    }
}
