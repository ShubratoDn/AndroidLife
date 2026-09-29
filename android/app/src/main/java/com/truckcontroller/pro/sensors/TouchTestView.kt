package com.truckcontroller.pro.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * Multi-touch test: a circle under every finger with its number, trails that show dead zones on
 * the screen, and the maximum number of fingers seen at once.
 */
class TouchTestView(context: Context) : View(context) {

    var onStats: ((current: Int, max: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val colors = intArrayOf(
        Color.parseColor("#F87171"), Color.parseColor("#34D399"), Color.parseColor("#60A5FA"),
        Color.parseColor("#FBBF24"), Color.parseColor("#C084FC"), Color.parseColor("#2DD4BF"),
        Color.parseColor("#FB923C"), Color.parseColor("#F472B6"), Color.parseColor("#A3E635"),
        Color.parseColor("#E2E8F0"),
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16 * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textAlign = Paint.Align.CENTER
        textSize = 14 * density
    }
    private var trails: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private val last = HashMap<Int, Pair<Float, Float>>()
    private var maxFingers = 0

    fun clear() {
        trails?.eraseColor(Color.TRANSPARENT)
        maxFingers = 0
        onStats?.invoke(0, 0)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        trails = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { trailCanvas = Canvas(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)
            last[id]?.let { (px, py) ->
                trail.color = colors[id % colors.size]
                trail.alpha = 140
                trailCanvas?.drawLine(px, py, x, y, trail)
            }
            last[id] = x to y
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> last.clear()
            MotionEvent.ACTION_POINTER_UP -> last.remove(event.getPointerId(event.actionIndex))
        }
        val down = if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) 0
        else event.pointerCount - if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) 1 else 0
        maxFingers = maxOf(maxFingers, down)
        onStats?.invoke(down, maxFingers)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B0F16"))
        trails?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (last.isEmpty() && maxFingers == 0) {
            canvas.drawText("Touch with one or more fingers", width / 2f, height / 2f, hint)
        }
        last.forEach { (id, pos) ->
            fill.color = colors[id % colors.size]
            fill.alpha = 70
            canvas.drawCircle(pos.first, pos.second, 38 * density, fill)
            fill.alpha = 255
            canvas.drawCircle(pos.first, pos.second, 22 * density, fill)
            canvas.drawText("${id + 1}", pos.first, pos.second + label.textSize / 3, label)
        }
    }
}
