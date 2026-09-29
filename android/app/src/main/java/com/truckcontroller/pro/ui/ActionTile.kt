package com.truckcontroller.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Large tappable tile with icon, title and optional subtitle that scales with its size.
 * Wide tiles lay out horizontally (icon left), tall or square tiles stack vertically.
 */
class ActionTile @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var title = ""
        set(value) { field = value; invalidate() }
    var subtitle = ""
        set(value) { field = value; invalidate() }
    var icon: Drawable? = null
        set(value) { field = value?.mutate(); invalidate() }
    var accentColor = Color.parseColor("#F97316")
        set(value) { field = value; invalidate() }
    /** Lit state: tinted background and accent border. */
    var isActive = false
        set(value) { field = value; invalidate() }
    /** Filled accent style for the primary action. */
    var primary = false
        set(value) { field = value; invalidate() }

    fun setIcon(@DrawableRes res: Int) {
        icon = ContextCompat.getDrawable(context, res)
    }

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        color = Color.WHITE
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#94A3B8") }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        if (isPressed) canvas.scale(0.97f, 0.97f, w / 2f, h / 2f)

        val corner = min(18f * density, min(w, h) * 0.2f)
        val stroke = 1.2f * density
        rect.set(stroke, stroke, w - stroke, h - stroke)
        bgPaint.color = when {
            primary && isPressed -> alpha(accentColor, 90)
            primary -> alpha(accentColor, 56)
            isActive -> alpha(accentColor, 40)
            isPressed -> Color.parseColor("#1A2130")
            else -> Color.parseColor("#10151E")
        }
        canvas.drawRoundRect(rect, corner, corner, bgPaint)
        borderPaint.strokeWidth = stroke
        borderPaint.color = if (primary || isActive) accentColor else Color.argb(30, 255, 255, 255)
        canvas.drawRoundRect(rect, corner, corner, borderPaint)

        val wide = w > h * 1.9f
        val fg = if (primary || isActive) accentColor else Color.parseColor("#CBD5E1")
        if (wide) drawHorizontal(canvas, w, h) else drawVertical(canvas, w, h, fg)
        canvas.restore()
    }

    private fun drawVertical(canvas: Canvas, w: Float, h: Float, fg: Int) {
        val iconSize = min(min(w, h) * 0.34f, 64f * density)
        titlePaint.textAlign = Paint.Align.CENTER
        subPaint.textAlign = Paint.Align.CENTER
        titlePaint.textSize = min(min(w, h) * 0.13f, 20f * density).coerceAtLeast(10f * density)
        subPaint.textSize = titlePaint.textSize * 0.72f
        fitText(titlePaint, title, w * 0.9f)
        fitText(subPaint, subtitle, w * 0.92f)
        val hasSub = subtitle.isNotEmpty()
        val gap = 6f * density
        val total = iconSize + gap + titlePaint.textSize + if (hasSub) subPaint.textSize * 1.4f else 0f
        var y = (h - total) / 2f
        icon?.let {
            it.setTint(fg)
            it.setBounds((w / 2 - iconSize / 2).toInt(), y.toInt(), (w / 2 + iconSize / 2).toInt(), (y + iconSize).toInt())
            it.draw(canvas)
        }
        y += iconSize + gap + titlePaint.textSize
        titlePaint.color = if (primary || isActive) accentColor else Color.WHITE
        canvas.drawText(title, w / 2, y - titlePaint.descent() / 2, titlePaint)
        if (hasSub) canvas.drawText(subtitle, w / 2, y + subPaint.textSize * 1.3f, subPaint)
    }

    private fun drawHorizontal(canvas: Canvas, w: Float, h: Float) {
        val circle = min(h * 0.62f, 72f * density)
        val left = h * 0.2f
        val cx = left + circle / 2
        val cy = h / 2
        circlePaint.color = alpha(accentColor, 38)
        canvas.drawCircle(cx, cy, circle / 2, circlePaint)
        val iconSize = circle * 0.5f
        icon?.let {
            it.setTint(accentColor)
            it.setBounds((cx - iconSize / 2).toInt(), (cy - iconSize / 2).toInt(), (cx + iconSize / 2).toInt(), (cy + iconSize / 2).toInt())
            it.draw(canvas)
        }
        val textX = left + circle + h * 0.18f
        val textW = w - textX - h * 0.15f
        titlePaint.textAlign = Paint.Align.LEFT
        subPaint.textAlign = Paint.Align.LEFT
        titlePaint.textSize = min(h * 0.2f, 22f * density)
        subPaint.textSize = min(h * 0.13f, 14f * density)
        fitText(titlePaint, title, textW)
        fitText(subPaint, subtitle, textW)
        titlePaint.color = if (primary || isActive) accentColor else Color.WHITE
        if (subtitle.isEmpty()) {
            canvas.drawText(title, textX, cy - (titlePaint.ascent() + titlePaint.descent()) / 2, titlePaint)
        } else {
            canvas.drawText(title, textX, cy - 2f * density, titlePaint)
            canvas.drawText(subtitle, textX, cy + subPaint.textSize * 1.3f, subPaint)
        }
    }

    private fun fitText(paint: Paint, text: String, maxWidth: Float) {
        if (text.isEmpty()) return
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured
    }
}
