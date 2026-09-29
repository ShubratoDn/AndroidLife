package com.truckcontroller.pro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Android Custom View rendering a tactile vertical Truck Pedal (Gas or Brake).
 * Supports progressive drag, spring release, and ribbed metal texture.
 */
class PedalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PedalType { GAS, BRAKE }

    var pedalType = PedalType.GAS
        set(value) {
            field = value
            updateShaders()
            invalidate()
        }
    var valuePercent: Float = 0f // 0f to 100f
        private set

    var onValueChanged: ((value: Float) -> Unit)? = null
    var onPressed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var pointerId = MotionEvent.INVALID_POINTER_ID

    private val accent: Int
        get() = if (pedalType == PedalType.GAS) Color.parseColor("#10B981") else Color.parseColor("#EF4444")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E131B") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 32, 39, 53) }
    private val ribHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(14, 255, 255, 255) }
    private val hingePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F2735") }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161C27") }
    private val capLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val hingeHeight get() = 8f * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaders()
    }

    private fun updateShaders() {
        if (height == 0) return
        val strong = if (pedalType == PedalType.GAS) Color.argb(205, 5, 150, 105) else Color.argb(205, 220, 38, 38)
        val soft = if (pedalType == PedalType.GAS) Color.argb(77, 16, 185, 129) else Color.argb(77, 239, 68, 68)
        fillPaint.shader = LinearGradient(0f, height.toFloat(), 0f, hingeHeight, strong, soft, Shader.TileMode.CLAMP)
        capLinePaint.color = accent
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                    pointerId = event.getPointerId(event.actionIndex)
                    parent.requestDisallowInterceptTouchEvent(true)
                    onPressed?.invoke()
                    updateFromY(event.getY(event.actionIndex))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) updateFromY(event.getY(index))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val all = event.actionMasked != MotionEvent.ACTION_POINTER_UP
                if (all || event.getPointerId(event.actionIndex) == pointerId) {
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    setValue(0f)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromY(y: Float) {
        // Vertical position: bottom is 0%, top is 100%
        val top = hingeHeight
        val span = (height - top).coerceAtLeast(1f)
        val clampedY = y.coerceIn(top, height.toFloat())
        setValue(((height - clampedY) / span) * 100f)
    }

    private fun setValue(value: Float) {
        val rounded = value.coerceIn(0f, 100f).let { kotlin.math.round(it) }
        if (rounded == valuePercent) return
        valuePercent = rounded
        onValueChanged?.invoke(valuePercent)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = 16f * density
        val inset = 1f * density
        val top = hingeHeight

        // Hinge tab above the pedal face
        rect.set(w * 0.3f, 0f, w * 0.7f, top + corner / 2f)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, hingePaint)

        // Pedal face
        rect.set(inset, top, w - inset, h - inset)
        canvas.drawRoundRect(rect, corner, corner, bgPaint)

        // Active fill from bottom up
        val pad = 5f * density
        val fillHeight = (valuePercent / 100f) * (h - top - pad * 2)
        if (fillHeight > 0f) {
            rect.set(inset + pad, h - inset - pad - fillHeight, w - inset - pad, h - inset - pad)
            canvas.drawRoundRect(rect, corner * 0.7f, corner * 0.7f, fillPaint)
        }

        // Ribbed horizontal grooves (truck pedal pattern)
        val ribCount = 7
        val spacing = (h - top) / (ribCount + 1)
        val ribH = 3f * density
        for (i in 1..ribCount) {
            val ry = top + i * spacing
            rect.set(12f * density, ry - ribH / 2f, w - 12f * density, ry + ribH / 2f)
            canvas.drawRoundRect(rect, ribH, ribH, ribPaint)
            rect.set(12f * density, ry - ribH / 2f, w - 12f * density, ry - ribH / 2f + density)
            canvas.drawRect(rect, ribHighlightPaint)
        }

        // Heel cap with accent line
        rect.set(w * 0.2f, h - inset - 14f * density, w * 0.8f, h - inset)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, capPaint)
        rect.set(w * 0.35f, h - inset - 10f * density, w * 0.65f, h - inset - 8f * density)
        canvas.drawRoundRect(rect, density, density, capLinePaint)

        // Outer border, glowing when pressed
        borderPaint.color = if (valuePercent > 0) accent else Color.argb(26, 255, 255, 255)
        rect.set(inset, top, w - inset, h - inset)
        canvas.drawRoundRect(rect, corner, corner, borderPaint)
    }
}
