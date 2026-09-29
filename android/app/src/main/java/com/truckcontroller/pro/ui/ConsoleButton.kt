package com.truckcontroller.pro.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.R
import kotlin.math.min

/**
 * Backlit cockpit switch: rounded tile with an optional status LED bar, icon and label.
 * Colors follow the web console (cyan for lights, amber for truck functions, red for brakes, ...).
 */
class ConsoleButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Shape { TILE, ROUND, CIRCLE_ICON }

    var label: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var icon: Drawable? = null
        set(value) {
            field = value?.mutate()
            invalidate()
        }
    var accentColor: Int = Color.parseColor("#FBBF24")
        set(value) {
            field = value
            invalidate()
        }
    var shape = Shape.TILE
    var showLed = true
    /** Icon tint override when inactive (e.g. the red hazard triangle). */
    var idleIconColor: Int? = null

    var isActive = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    /** Blink phase for flashing switches (turn signals, hazards, beacon): true = lamp momentarily off. */
    var blinkOff = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rect = RectF()

    private val idleBg = Color.parseColor("#10151E")
    private val idleFg = Color.parseColor("#94A3B8")
    private val idleBorder = Color.argb(26, 255, 255, 255)
    private val idleLed = Color.parseColor("#334155")

    init {
        isClickable = true
        isFocusable = true
        context.obtainStyledAttributes(attrs, R.styleable.ConsoleButton).apply {
            label = getString(R.styleable.ConsoleButton_cbLabel) ?: ""
            getResourceId(R.styleable.ConsoleButton_cbIcon, 0).takeIf { it != 0 }?.let {
                icon = ContextCompat.getDrawable(context, it)
            }
            accentColor = getColor(R.styleable.ConsoleButton_cbAccent, accentColor)
            showLed = getBoolean(R.styleable.ConsoleButton_cbShowLed, true)
            shape = Shape.entries[getInt(R.styleable.ConsoleButton_cbShape, 0)]
            if (hasValue(R.styleable.ConsoleButton_cbIdleIconColor)) {
                idleIconColor = getColor(R.styleable.ConsoleButton_cbIdleIconColor, idleFg)
            }
            recycle()
        }
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun blend(color: Int, alpha: Float, base: Int): Int {
        val a = alpha.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) * a + Color.red(base) * (1 - a)).toInt(),
            (Color.green(color) * a + Color.green(base) * (1 - a)).toInt(),
            (Color.blue(color) * a + Color.blue(base) * (1 - a)).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lit = isActive && !blinkOff
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        if (isPressed) canvas.scale(0.95f, 0.95f, w / 2f, h / 2f)

        val bg = if (lit) blend(accentColor, 0.16f, idleBg) else if (isPressed) Color.parseColor("#161C28") else idleBg
        val fg = if (lit) accentColor else (idleIconColor ?: idleFg)
        bgPaint.color = bg
        val stroke = if (shape == Shape.ROUND) 2f * density else 1.2f * density
        borderPaint.strokeWidth = stroke
        borderPaint.color = if (lit) accentColor else if (shape == Shape.ROUND) Color.parseColor("#334155") else idleBorder
        val half = stroke / 2f

        when (shape) {
            Shape.TILE -> {
                val r = 12f * density
                rect.set(half, half, w - half, h - half)
                if (lit) {
                    glowPaint.strokeWidth = 4f * density
                    glowPaint.color = Color.argb(60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    canvas.drawRoundRect(rect, r, r, glowPaint)
                }
                canvas.drawRoundRect(rect, r, r, bgPaint)
                canvas.drawRoundRect(rect, r, r, borderPaint)
            }
            Shape.ROUND, Shape.CIRCLE_ICON -> {
                val r = min(w, h) / 2f - half
                if (lit) {
                    glowPaint.strokeWidth = 4f * density
                    glowPaint.color = Color.argb(60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    canvas.drawCircle(w / 2f, h / 2f, r, glowPaint)
                }
                canvas.drawCircle(w / 2f, h / 2f, r, bgPaint)
                canvas.drawCircle(w / 2f, h / 2f, r, borderPaint)
            }
        }

        // Stack LED bar, icon and label vertically, centered
        val iconSize = when (shape) {
            Shape.CIRCLE_ICON -> min(w, h) * 0.45f
            Shape.ROUND -> min(w, h) * 0.3f
            Shape.TILE -> min(20f * density, h * 0.36f)
        }
        val ledH = if (showLed) 4f * density else 0f
        val ledGap = if (showLed) 4f * density else 0f
        val hasLabel = label.isNotEmpty()
        val labelLines = label.split('\n')
        val lineH = textPaint.textSize * 1.1f
        val labelH = if (hasLabel) lineH * labelLines.size + 2f * density else 0f
        val total = ledH + ledGap + iconSize + labelH
        var y = (h - total) / 2f

        if (showLed) {
            ledPaint.color = if (lit) accentColor else idleLed
            val ledW = 16f * density
            rect.set(w / 2f - ledW / 2f, y, w / 2f + ledW / 2f, y + ledH)
            canvas.drawRoundRect(rect, ledH, ledH, ledPaint)
            y += ledH + ledGap
        }
        icon?.let {
            it.setTint(fg)
            it.setBounds(
                (w / 2f - iconSize / 2f).toInt(), y.toInt(),
                (w / 2f + iconSize / 2f).toInt(), (y + iconSize).toInt()
            )
            it.draw(canvas)
        }
        y += iconSize + 2f * density
        if (hasLabel) {
            textPaint.color = if (lit) accentColor else if (shape == Shape.ROUND) Color.parseColor("#CBD5E1") else idleFg
            labelLines.forEach { line ->
                y += lineH
                canvas.drawText(line, w / 2f, y - textPaint.descent(), textPaint)
            }
        }
        canvas.restore()
    }
}
