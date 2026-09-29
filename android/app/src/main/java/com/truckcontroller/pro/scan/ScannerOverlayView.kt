package com.truckcontroller.pro.scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** Viewfinder drawn over the camera preview: dimmed surroundings, accent corners and a moving scan line. */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Green flash while a code has just been found. */
    var found = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val frame = RectF()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val cutout = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height) * 0.68f
        val left = (width - size) / 2f
        val top = (height - size) / 2f - height * 0.04f
        frame.set(left, top, left + size, top + size)
        val radius = 22f * density

        // Dim everything except the frame
        cutout.reset()
        cutout.fillType = Path.FillType.EVEN_ODD
        cutout.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        cutout.addRoundRect(frame, radius, radius, Path.Direction.CW)
        canvas.drawPath(cutout, dimPaint)

        // Corner brackets
        val accent = if (found) Color.parseColor("#34D399") else Color.parseColor("#F97316")
        cornerPaint.color = accent
        cornerPaint.strokeWidth = 5f * density
        val arm = size * 0.16f
        path.reset()
        path.moveTo(frame.left, frame.top + arm); path.lineTo(frame.left, frame.top + radius)
        path.quadTo(frame.left, frame.top, frame.left + radius, frame.top); path.lineTo(frame.left + arm, frame.top)
        path.moveTo(frame.right - arm, frame.top); path.lineTo(frame.right - radius, frame.top)
        path.quadTo(frame.right, frame.top, frame.right, frame.top + radius); path.lineTo(frame.right, frame.top + arm)
        path.moveTo(frame.right, frame.bottom - arm); path.lineTo(frame.right, frame.bottom - radius)
        path.quadTo(frame.right, frame.bottom, frame.right - radius, frame.bottom); path.lineTo(frame.right - arm, frame.bottom)
        path.moveTo(frame.left + arm, frame.bottom); path.lineTo(frame.left + radius, frame.bottom)
        path.quadTo(frame.left, frame.bottom, frame.left, frame.bottom - radius); path.lineTo(frame.left, frame.bottom - arm)
        canvas.drawPath(path, cornerPaint)

        // Sweeping scan line
        if (!found) {
            val phase = (sin(SystemClock.uptimeMillis() / 700.0) + 1.0) / 2.0
            val y = frame.top + radius + (frame.height() - 2 * radius) * phase.toFloat()
            linePaint.shader = LinearGradient(
                frame.left, y, frame.right, y,
                intArrayOf(Color.TRANSPARENT, accent, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(frame.left + radius, y - density, frame.right - radius, y + density, linePaint)
            postInvalidateOnAnimation()
        }
    }
}
