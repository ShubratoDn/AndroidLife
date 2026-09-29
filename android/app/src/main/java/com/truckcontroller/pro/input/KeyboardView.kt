package com.truckcontroller.pro.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Multi-touch keyboard drawn on a canvas from a [KeyboardLayout].
 *
 * Ctrl / Shift / Alt latch: tap once for the next key only, tap again to lock, tap a third time to
 * release; holding them while pressing another key with a second finger also works.
 * The full keyboard state (modifier byte + pressed usages) is reported through [onStateChanged].
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Latch { NONE, ONE_SHOT, LOCKED }

    var layout: KeyboardLayout = KeyboardLayouts.COMPACT
        set(value) {
            field = value
            computeRects()
            invalidate()
        }

    /** Host keyboard LEDs (Num / Caps lock) used for key labels and indicators. */
    var leds = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onStateChanged: ((modifiers: Int, keys: List<Int>) -> Unit)? = null
    var onConsumer: ((usage: Int) -> Unit)? = null
    var onKeyFeedback: (() -> Unit)? = null
    /** Normal (non-modifier) key pressed or released; lets sibling panels share modifiers. */
    var onNormalKey: ((down: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val rects = HashMap<KeyDef, RectF>()
    private val pointerKeys = HashMap<Int, KeyDef>()
    private val pressedNormal = LinkedHashSet<Int>()
    private val latches = HashMap<Int, Latch>()           // modifier bit -> latch state
    private var heldLatchBits = 0                         // latch modifiers physically held
    private var usedWhileHeldBits = 0                     // held modifiers that combined with a key
    private var heldGuiBits = 0

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#64748B")
    }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cKey = Color.parseColor("#1A2130")
    private val cSpecial = Color.parseColor("#141A25")
    private val cAccentKey = Color.parseColor("#2A1A0C")
    private val cPressed = Color.parseColor("#3B4A63")
    private val cBorder = Color.parseColor("#2A3445")
    private val cText = Color.parseColor("#E2E8F0")
    private val cMuted = Color.parseColor("#94A3B8")
    private val cAmber = Color.parseColor("#FBBF24")
    private val cOrange = Color.parseColor("#F97316")
    private val cGreen = Color.parseColor("#34D399")

    /** Modifier byte currently in effect. */
    private val modifiers: Int
        get() {
            var bits = heldLatchBits or heldGuiBits
            latches.forEach { (bit, latch) -> if (latch != Latch.NONE) bits = bits or bit }
            return bits
        }

    private val shiftActive get() = modifiers and (0x02 or 0x20) != 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRects()
    }

    private fun computeRects() {
        rects.clear()
        if (width == 0 || height == 0) return
        val unitW = width / layout.width
        val unitH = height / layout.height
        val gap = min(3f * density, min(unitW, unitH) * 0.08f)
        layout.keys.forEach { k ->
            rects[k] = RectF(
                k.x * unitW + gap, k.y * unitH + gap,
                (k.x + k.w) * unitW - gap, (k.y + k.h) * unitH - gap
            )
        }
    }

    /** A key on another panel combined with modifiers held here, so their release is not a "tap". */
    fun markHeldModifiersUsed() {
        usedWhileHeldBits = usedWhileHeldBits or heldLatchBits
    }

    /** Clears one-shot modifiers, e.g. after a mouse click used them. */
    fun consumeOneShot() {
        if (latches.values.none { it == Latch.ONE_SHOT }) return
        latches.entries.forEach { if (it.value == Latch.ONE_SHOT) it.setValue(Latch.NONE) }
        publish()
    }

    /** Releases everything (screen hidden). */
    fun reset() {
        pointerKeys.clear()
        pressedNormal.clear()
        latches.clear()
        heldLatchBits = 0
        usedWhileHeldBits = 0
        heldGuiBits = 0
        publish()
    }

    private fun publish() {
        onStateChanged?.invoke(modifiers, pressedNormal.toList())
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val key = keyAt(event.getX(i), event.getY(i)) ?: return true
                pointerKeys[event.getPointerId(i)] = key
                parent?.requestDisallowInterceptTouchEvent(true)
                keyDown(key)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointerKeys.remove(event.getPointerId(event.actionIndex))?.let { keyUp(it) }
            }
            MotionEvent.ACTION_CANCEL -> {
                val keys = pointerKeys.values.toList()
                pointerKeys.clear()
                keys.forEach { keyUp(it) }
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): KeyDef? {
        rects.entries.firstOrNull { it.value.contains(x, y) }?.let { return it.key }
        // Touch landed in a gap: use the nearest key
        return rects.minByOrNull { (_, r) ->
            val dx = maxOf(r.left - x, 0f, x - r.right)
            val dy = maxOf(r.top - y, 0f, y - r.bottom)
            dx * dx + dy * dy
        }?.key
    }

    private fun keyDown(key: KeyDef) {
        onKeyFeedback?.invoke()
        when (key.type) {
            KeyType.NORMAL -> {
                pressedNormal += key.usage
                usedWhileHeldBits = usedWhileHeldBits or heldLatchBits
                publish()
                onNormalKey?.invoke(true)
            }
            KeyType.MODIFIER_LATCH -> {
                heldLatchBits = heldLatchBits or key.modifierBit
                usedWhileHeldBits = usedWhileHeldBits and key.modifierBit.inv()
                publish()
            }
            KeyType.MODIFIER_HOLD -> {
                heldGuiBits = heldGuiBits or key.modifierBit
                publish()
            }
            KeyType.CONSUMER -> {
                onConsumer?.invoke(key.usage)
                invalidate()
            }
        }
    }

    private fun keyUp(key: KeyDef) {
        when (key.type) {
            KeyType.NORMAL -> {
                pressedNormal -= key.usage
                if (pressedNormal.isEmpty()) {
                    // One-shot modifiers apply to a single key
                    latches.entries.forEach { if (it.value == Latch.ONE_SHOT) it.setValue(Latch.NONE) }
                }
                publish()
                onNormalKey?.invoke(false)
            }
            KeyType.MODIFIER_LATCH -> {
                val bit = key.modifierBit
                heldLatchBits = heldLatchBits and bit.inv()
                if (usedWhileHeldBits and bit == 0) {
                    // A plain tap: cycle none -> one-shot -> locked -> none
                    latches[bit] = when (latches[bit] ?: Latch.NONE) {
                        Latch.NONE -> Latch.ONE_SHOT
                        Latch.ONE_SHOT -> Latch.LOCKED
                        Latch.LOCKED -> Latch.NONE
                    }
                }
                usedWhileHeldBits = usedWhileHeldBits and bit.inv()
                publish()
            }
            KeyType.MODIFIER_HOLD -> {
                heldGuiBits = heldGuiBits and key.modifierBit.inv()
                publish()
            }
            KeyType.CONSUMER -> {
                onConsumer?.invoke(0)
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rects.isEmpty()) computeRects()
        val pressedKeys = pointerKeys.values.toSet()
        val caps = leds and Hid.LED_CAPS != 0
        val num = leds and Hid.LED_NUM != 0
        val shift = shiftActive
        val corner = 6f * density

        layout.keys.forEach { k ->
            val r = rects[k] ?: return@forEach
            val latch = latches[k.modifierBit] ?: Latch.NONE
            val modOn = k.type == KeyType.MODIFIER_LATCH &&
                (latch != Latch.NONE || heldLatchBits and k.modifierBit != 0)
            val pressed = k in pressedKeys

            keyPaint.color = when {
                pressed -> cPressed
                modOn -> Color.parseColor("#3A2A10")
                k.accent -> cAccentKey
                k.label.length > 1 && !k.isLetter -> cSpecial
                else -> cKey
            }
            canvas.drawRoundRect(r, corner, corner, keyPaint)
            borderPaint.color = when {
                modOn -> cAmber
                pressed -> cOrange
                k.accent -> Color.parseColor("#7C3F12")
                else -> cBorder
            }
            canvas.drawRoundRect(r, corner, corner, borderPaint)

            // Label: letters follow Shift xor Caps; symbols show their shifted form while Shift is on
            val label = when {
                k.isLetter -> if (shift xor caps) k.label.uppercase() else k.label.lowercase()
                shift && k.shiftLabel != null -> k.shiftLabel
                else -> k.label
            }
            val base = min(r.height() * 0.38f, 20f * density)
            textPaint.textSize = if (label.length > 2) base * 0.72f else base
            val maxW = r.width() * 0.86f
            val measured = textPaint.measureText(label)
            if (measured > maxW) textPaint.textSize *= maxW / measured
            textPaint.color = when {
                modOn -> cAmber
                k.accent -> cOrange
                k.label.length > 1 && !k.isLetter -> cMuted
                else -> cText
            }
            canvas.drawText(label, r.centerX(), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)

            // Secondary (shifted) symbol in the corner
            if (!shift && k.shiftLabel != null) {
                smallPaint.textSize = base * 0.55f
                canvas.drawText(k.shiftLabel, r.left + r.width() * 0.1f, r.top + smallPaint.textSize * 1.1f, smallPaint)
            }

            // Lock indicators: LED dot for Caps / Num and a bar for locked modifiers
            val ledOn = when (k.usage) {
                Hid.CAPS_LOCK -> caps
                Hid.NUM_LOCK -> num
                else -> null
            }
            if (ledOn != null) {
                ledPaint.color = if (ledOn) cGreen else Color.parseColor("#334155")
                canvas.drawCircle(r.right - r.width() * 0.14f, r.top + r.height() * 0.18f, 3f * density, ledPaint)
            }
            if (latch == Latch.LOCKED) {
                ledPaint.color = cAmber
                val bw = r.width() * 0.3f
                canvas.drawRoundRect(
                    r.centerX() - bw / 2, r.bottom - 5f * density, r.centerX() + bw / 2, r.bottom - 3f * density,
                    density, density, ledPaint
                )
            }
        }
    }
}
