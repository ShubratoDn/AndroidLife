package com.truckcontroller.pro.charging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen charging animation with the live battery level, charging speed and time to full.
 * Drawn entirely in code (no PhoneDeck resources) because it also runs inside System UI.
 * Faster charging makes every style move faster.
 */
class ChargeAnimationView(
    context: Context,
    val style: ChargeStyle,
    private val showDetails: Boolean = true,
) : View(context) {

    private val battery = context.getSystemService(BatteryManager::class.java)
    private var startTime = 0L
    private var lastRead = 0L

    private var level = 0
    private var voltageV = 0f
    private var currentMa = 0
    private var charging = true
    private var timeToFullMs = 0L
    private val watts get() = currentMa * voltageV / 1000f
    /** 0.25 (slow) .. 1 (turbo): drives animation speed. */
    private val intensity get() = (watts / 33f).coerceIn(0.25f, 1f)

    private val random = Random(7)
    private val particles = Array(70) { Particle() }
    private val bubbles = Array(26) { Bubble() }
    private val bolts = ArrayList<Path>()
    private var boltsAt = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val levelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val path = Path()
    private val rect = RectF()

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = SystemClock.uptimeMillis()
        lastRead = 0L
        readBattery()
    }

    // ------------------------------------------------------------------
    // Battery
    // ------------------------------------------------------------------

    private fun readBattery() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRead < 1000) return
        lastRead = now
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        level = (raw * 100 / scale).coerceIn(0, 100)
        val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        voltageV = if (mv in 1..99) mv.toFloat() else mv / 1000f
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        // Phones report µA or mA with either sign; the size of the number tells the unit
        val reading = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        currentMa = if (reading == Int.MIN_VALUE) 0 else abs(reading).let { if (it > 20_000) it / 1000 else it }
        timeToFullMs = battery?.computeChargeTimeRemaining()?.takeIf { it > 0 } ?: 0L
    }

    /** Counts up to the real level during the first second, like HyperOS. */
    private fun shownLevel(t: Float): Int {
        val from = (level - 20).coerceAtLeast(0)
        val p = (t / 1.2f).coerceIn(0f, 1f)
        val eased = 1 - (1 - p) * (1 - p) * (1 - p)
        return (from + (level - from) * eased).roundToInt()
    }

    private fun speedLabel() = when {
        !charging -> "CONNECTED"
        level >= 100 -> "FULLY CHARGED"
        watts >= 30 -> "TURBO CHARGING"
        watts >= 15 -> "FAST CHARGING"
        else -> "CHARGING"
    }

    private fun detailsLine(): String? {
        if (!showDetails || currentMa <= 0) return null
        return String.format(Locale.US, "%.1f W  ·  %,d mA", watts, currentMa)
    }

    private fun fullLine(): String? {
        // Slow chargers (PC USB) give estimates of days; skip those
        if (!showDetails || timeToFullMs <= 0 || timeToFullMs > 12 * 3_600_000L || level >= 100) return null
        val minutes = (timeToFullMs / 60_000).toInt()
        return if (minutes >= 60) "Full in ${minutes / 60} h ${minutes % 60} min" else "Full in $minutes min"
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        readBattery()
        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        val enter = (t / 0.45f).coerceIn(0f, 1f)
        // The black background covers HyperOS's own animation from the first frame; only the
        // content fades and zooms in on top of it
        if (enter < 1f) canvas.saveLayerAlpha(null, (enter * 255).toInt()) else canvas.save()
        val s = 0.92f + 0.08f * enter
        canvas.scale(s, s, width / 2f, height / 2f)
        when (style) {
            ChargeStyle.NEON_RING -> drawNeonRing(canvas, t)
            ChargeStyle.LIQUID -> drawLiquid(canvas, t)
            ChargeStyle.ORB -> drawOrb(canvas, t)
            ChargeStyle.GAUGE -> drawGauge(canvas, t)
            ChargeStyle.AURORA -> drawAurora(canvas, t)
            ChargeStyle.HEARTBEAT -> drawHeartbeat(canvas, t)
            ChargeStyle.DIGITAL_RAIN -> drawDigitalRain(canvas, t)
            ChargeStyle.HEX_SHIELD -> drawHexShield(canvas, t)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }

    /** Level with a small "%", then speed label and details below [y]. */
    private fun drawReadout(canvas: Canvas, cx: Float, y: Float, size: Float, t: Float, accent: Int, belowGap: Float) {
        val value = shownLevel(t).toString()
        levelText.textSize = size
        val w = levelText.measureText(value)
        canvas.drawText(value, cx - size * 0.12f, y, levelText)
        smallText.textSize = size * 0.28f
        smallText.color = Color.WHITE
        smallText.textAlign = Paint.Align.LEFT
        canvas.drawText("%", cx - size * 0.12f + w / 2f + size * 0.04f, y, smallText)
        smallText.textAlign = Paint.Align.CENTER

        var line = y + belowGap
        smallText.textSize = size * 0.16f
        smallText.color = accent
        canvas.drawText(speedLabel(), cx, line, smallText)
        smallText.color = Color.argb(200, 255, 255, 255)
        smallText.textSize = size * 0.15f
        detailsLine()?.let { line += size * 0.26f; canvas.drawText(it, cx, line, smallText) }
        fullLine()?.let { line += size * 0.24f; canvas.drawText(it, cx, line, smallText) }
    }

    // --- Neon Ring --------------------------------------------------------

    private class Particle {
        var angle = 0f
        var dist = 0f
        var speed = 0f
        var size = 0f
        var hue = 0f
    }

    private fun drawNeonRing(canvas: Canvas, t: Float) {
        val cx = width / 2f
        val cy = height * 0.45f
        val r = min(width, height) * 0.3f
        val colors = intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#6366F1"),
            Color.parseColor("#D946EF"), Color.parseColor("#22D3EE"))

        // Particles spiral in from the edges and vanish into the ring
        val maxDist = r * 2.4f
        particles.forEach { p ->
            if (p.dist <= r * 1.08f || p.speed == 0f) {
                p.angle = random.nextFloat() * 360f
                p.dist = r * 1.2f + random.nextFloat() * (maxDist - r * 1.2f)
                p.speed = 0.4f + random.nextFloat() * 0.8f
                p.size = r * (0.008f + random.nextFloat() * 0.018f)
                p.hue = random.nextFloat()
            }
            p.dist -= p.speed * r * 0.012f * (0.6f + intensity * 1.6f)
            p.angle += p.speed * 0.9f * (0.6f + intensity)
            val a = Math.toRadians(p.angle.toDouble())
            val fade = ((p.dist - r) / (maxDist - r)).coerceIn(0f, 1f)
            fill.shader = null
            fill.color = if (p.hue < 0.5f) colors[0] else colors[2]
            fill.alpha = (255 * (1 - fade) * 0.9f).toInt()
            canvas.drawCircle(cx + (cos(a) * p.dist).toFloat(), cy + (sin(a) * p.dist).toFloat(), p.size, fill)
        }

        // Glow: wide faint strokes under a sharp gradient ring that spins
        val spin = t * (50f + 160f * intensity)
        canvas.save()
        canvas.rotate(spin, cx, cy)
        stroke.shader = SweepGradient(cx, cy, colors, null)
        for (i in 4 downTo 1) {
            stroke.strokeWidth = r * 0.05f * i * 1.6f
            stroke.alpha = 22 + (4 - i) * 10
            canvas.drawCircle(cx, cy, r, stroke)
        }
        stroke.alpha = 255
        stroke.strokeWidth = r * 0.07f
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.restore()

        // Thin outer progress arc = battery level
        stroke.shader = null
        stroke.color = Color.argb(60, 255, 255, 255)
        stroke.strokeWidth = r * 0.015f
        rect.set(cx - r * 1.22f, cy - r * 1.22f, cx + r * 1.22f, cy + r * 1.22f)
        canvas.drawArc(rect, 0f, 360f, false, stroke)
        stroke.color = colors[0]
        stroke.strokeWidth = r * 0.03f
        canvas.drawArc(rect, -90f, 360f * shownLevel(t) / 100f, false, stroke)

        // Breathing core
        val pulse = 0.5f + 0.5f * sin(t * (2f + 4f * intensity))
        fill.shader = RadialGradient(cx, cy, r * 0.95f,
            intArrayOf(Color.argb((40 + 50 * pulse).toInt(), 99, 102, 241), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.95f, fill)
        fill.shader = null

        drawReadout(canvas, cx, cy + r * 0.2f, r * 0.62f, t, colors[0], r * 1.62f)
    }

    // --- Liquid Battery ---------------------------------------------------

    private class Bubble {
        var x = 0f
        var y = 2f
        var speed = 0f
        var size = 0f
    }

    private fun drawLiquid(canvas: Canvas, t: Float) {
        val cx = width / 2f
        val bw = min(width, height) * 0.42f
        val bh = bw * 1.9f
        val left = cx - bw / 2
        val top = height * 0.42f - bh / 2
        val corner = bw * 0.16f
        val green = Color.parseColor("#34D399")
        val teal = Color.parseColor("#06B6D4")

        // Shell and cap
        stroke.shader = null
        stroke.color = Color.argb(150, 255, 255, 255)
        stroke.strokeWidth = bw * 0.035f
        rect.set(left, top, left + bw, top + bh)
        canvas.drawRoundRect(rect, corner, corner, stroke)
        fill.shader = null
        fill.color = Color.argb(150, 255, 255, 255)
        rect.set(cx - bw * 0.18f, top - bw * 0.1f, cx + bw * 0.18f, top - bw * 0.01f)
        canvas.drawRoundRect(rect, bw * 0.04f, bw * 0.04f, fill)

        // Liquid clipped to the inside of the shell
        val inset = bw * 0.07f
        rect.set(left + inset, top + inset, left + bw - inset, top + bh - inset)
        canvas.save()
        path.reset()
        path.addRoundRect(rect, corner * 0.7f, corner * 0.7f, Path.Direction.CW)
        canvas.clipPath(path)
        val innerH = rect.height()
        val surface = rect.bottom - innerH * shownLevel(t) / 100f
        val amp = innerH * (0.012f + 0.02f * intensity)
        val speed = 2f + 3f * intensity
        for (layer in 0..1) {
            path.reset()
            path.moveTo(rect.left, rect.bottom)
            var x = rect.left
            while (x <= rect.right + 4) {
                val k = (x - rect.left) / rect.width() * 2 * PI.toFloat()
                val y = surface + amp * sin(k * 1.3f + t * speed * (if (layer == 0) 1f else -0.8f) + layer * 2f)
                path.lineTo(x, y)
                x += 6f
            }
            path.lineTo(rect.right, rect.bottom)
            path.close()
            fill.shader = LinearGradient(0f, surface, 0f, rect.bottom,
                if (layer == 0) teal else green, Color.parseColor("#065F46"), Shader.TileMode.CLAMP)
            fill.alpha = if (layer == 0) 150 else 235
            canvas.drawPath(path, fill)
        }
        fill.shader = null

        // Bubbles rise through the liquid
        bubbles.forEach { b ->
            if (b.y < surface || b.speed == 0f) {
                b.x = rect.left + random.nextFloat() * rect.width()
                b.y = rect.bottom + random.nextFloat() * innerH * 0.3f
                b.speed = 0.4f + random.nextFloat()
                b.size = bw * (0.012f + random.nextFloat() * 0.03f)
            }
            b.y -= b.speed * bw * 0.006f * (0.7f + intensity * 1.5f)
            stroke.color = Color.argb(170, 255, 255, 255)
            stroke.strokeWidth = bw * 0.006f
            canvas.drawCircle(b.x + sin(b.y / 30f) * bw * 0.01f, b.y, b.size, stroke)
        }
        canvas.restore()

        // Lightning bolt in the middle of the battery
        val bolt = bw * 0.22f
        val by = top + bh / 2
        path.reset()
        path.moveTo(cx + bolt * 0.15f, by - bolt)
        path.lineTo(cx - bolt * 0.45f, by + bolt * 0.12f)
        path.lineTo(cx - bolt * 0.02f, by + bolt * 0.12f)
        path.lineTo(cx - bolt * 0.15f, by + bolt)
        path.lineTo(cx + bolt * 0.45f, by - bolt * 0.12f)
        path.lineTo(cx + bolt * 0.02f, by - bolt * 0.12f)
        path.close()
        fill.color = Color.argb((170 + 85 * sin(t * 3f)).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawPath(path, fill)

        drawReadout(canvas, cx, top + bh + bw * 0.62f, bw * 0.5f, t, green, bw * 0.3f)
    }

    // --- Energy Orb -------------------------------------------------------

    private fun drawOrb(canvas: Canvas, t: Float) {
        val cx = width / 2f
        val cy = height * 0.44f
        val base = min(width, height) * 0.2f
        val pulse = 0.5f + 0.5f * sin(t * (2.5f + 5f * intensity))
        val r = base * (1f + 0.06f * pulse)
        val violet = Color.parseColor("#A78BFA")
        val blue = Color.parseColor("#38BDF8")

        // Corona
        fill.shader = RadialGradient(cx, cy, r * 2.6f,
            intArrayOf(Color.argb((90 + 60 * pulse).toInt(), 124, 58, 237), Color.argb(40, 56, 189, 248), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 2.6f, fill)

        // Orbiting rings
        stroke.shader = null
        stroke.strokeWidth = base * 0.02f
        for (i in 0..2) {
            canvas.save()
            canvas.rotate(t * (20f + i * 15f) * (0.6f + intensity) + i * 60f, cx, cy)
            stroke.color = if (i % 2 == 0) blue else violet
            stroke.alpha = 120
            rect.set(cx - r * 1.7f, cy - r * (0.5f + 0.2f * i), cx + r * 1.7f, cy + r * (0.5f + 0.2f * i))
            canvas.drawOval(rect, stroke)
            canvas.restore()
        }

        // Lightning arcs, re-rolled a few times a second (faster when charging faster)
        val now = SystemClock.uptimeMillis()
        if (now - boltsAt > (220 - 140 * intensity).toLong()) {
            boltsAt = now
            bolts.clear()
            repeat(2 + (intensity * 4).toInt()) {
                val a = random.nextFloat() * 2 * PI.toFloat()
                val len = r * (1.2f + random.nextFloat() * 1.1f)
                val p = Path()
                var x = cx + cos(a) * r
                var y = cy + sin(a) * r
                p.moveTo(x, y)
                val steps = 7
                for (s in 1..steps) {
                    val d = r + len * s / steps
                    val jitter = (random.nextFloat() - 0.5f) * 0.5f
                    x = cx + cos(a + jitter * 0.4f) * d
                    y = cy + sin(a + jitter * 0.4f) * d
                    p.lineTo(x, y)
                }
                bolts += p
            }
        }
        stroke.strokeWidth = base * 0.035f
        stroke.color = Color.argb(70, 167, 139, 250)
        bolts.forEach { canvas.drawPath(it, stroke) }
        stroke.strokeWidth = base * 0.012f
        stroke.color = Color.argb(230, 224, 242, 254)
        bolts.forEach { canvas.drawPath(it, stroke) }

        // Plasma core
        fill.shader = RadialGradient(cx - r * 0.25f, cy - r * 0.25f, r * 1.1f,
            intArrayOf(Color.WHITE, blue, Color.parseColor("#6D28D9"), Color.parseColor("#1E1B4B")),
            floatArrayOf(0f, 0.25f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null

        drawReadout(canvas, cx, cy + r * 2.9f, base * 0.95f, t, blue, base * 0.5f)
    }

    // --- Truck Gauge ------------------------------------------------------

    private fun drawGauge(canvas: Canvas, t: Float) {
        val cx = width / 2f
        val cy = height * 0.42f
        val r = min(width, height) * 0.36f
        val orange = Color.parseColor("#F97316")
        val amber = Color.parseColor("#FBBF24")
        val shown = shownLevel(t)
        val startAngle = 150f
        val sweep = 240f

        // Dial face
        fill.shader = RadialGradient(cx, cy, r * 1.1f,
            intArrayOf(Color.parseColor("#1A1F2B"), Color.parseColor("#0B0E14")), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.1f, fill)
        fill.shader = null

        // Track and lit segment
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = r * 0.08f
        stroke.color = Color.parseColor("#1E293B")
        canvas.drawArc(rect, startAngle, sweep, false, stroke)
        stroke.shader = SweepGradient(cx, cy, intArrayOf(Color.parseColor("#EF4444"), amber, orange, Color.parseColor("#EF4444")),
            floatArrayOf(0f, 0.2f, 0.45f, 1f))
        canvas.save()
        canvas.rotate(startAngle, cx, cy)
        canvas.drawArc(rect, 0f, sweep * shown / 100f, false, stroke)
        canvas.restore()
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.ROUND

        // Ticks and numbers every 10 %
        smallText.color = Color.parseColor("#94A3B8")
        smallText.textSize = r * 0.09f
        for (i in 0..50) {
            val a = Math.toRadians((startAngle + sweep * i / 50f).toDouble())
            val major = i % 5 == 0
            val r1 = r * 0.84f
            val r2 = r * (if (major) 0.72f else 0.78f)
            stroke.color = if (i * 2 <= shown) orange else Color.parseColor("#475569")
            stroke.strokeWidth = r * (if (major) 0.018f else 0.008f)
            canvas.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(),
                cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), stroke)
            if (major && i % 10 == 0) {
                val rt = r * 0.6f
                canvas.drawText("${i * 2}", cx + (cos(a) * rt).toFloat(), cy + (sin(a) * rt).toFloat() + smallText.textSize / 3, smallText)
            }
        }

        // Needle trembles a little with charging power, like an engine at idle
        val wobble = sin(t * 30f) * 0.6f * intensity
        val needle = Math.toRadians((startAngle + sweep * shown / 100f + wobble).toDouble())
        stroke.color = orange
        stroke.strokeWidth = r * 0.03f
        canvas.drawLine(cx - (cos(needle) * r * 0.12f).toFloat(), cy - (sin(needle) * r * 0.12f).toFloat(),
            cx + (cos(needle) * r * 0.8f).toFloat(), cy + (sin(needle) * r * 0.8f).toFloat(), stroke)
        fill.color = Color.parseColor("#0B0E14")
        canvas.drawCircle(cx, cy, r * 0.07f, fill)
        stroke.strokeWidth = r * 0.02f
        canvas.drawCircle(cx, cy, r * 0.07f, stroke)

        // Digital LCD under the hub
        val lcdTop = cy + r * 0.28f
        rect.set(cx - r * 0.42f, lcdTop, cx + r * 0.42f, lcdTop + r * 0.34f)
        fill.color = Color.parseColor("#161205")
        canvas.drawRoundRect(rect, r * 0.05f, r * 0.05f, fill)
        stroke.color = Color.argb(90, 249, 115, 22)
        stroke.strokeWidth = r * 0.01f
        canvas.drawRoundRect(rect, r * 0.05f, r * 0.05f, stroke)
        levelText.color = amber
        drawReadout(canvas, cx, lcdTop + r * 0.29f, r * 0.3f, t, orange, r * 0.95f)
        levelText.color = Color.WHITE
    }

    // --- Aurora -----------------------------------------------------------

    private val stars = Array(90) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }

    private fun drawAurora(canvas: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(width, height).toFloat()

        // Twinkling stars in the upper sky
        fill.shader = null
        stars.forEach { (x, y, phase) ->
            fill.color = Color.WHITE
            fill.alpha = (90 + 110 * sin(t * 2f + phase * 12f)).toInt().coerceIn(20, 255)
            canvas.drawCircle(x * w, y * h * 0.7f, unit * (0.002f + phase * 0.003f), fill)
        }

        // Ribbons climb higher as the battery fills and sway faster with more power
        val top = h * (0.78f - 0.42f * shownLevel(t) / 100f)
        val colors = intArrayOf(Color.parseColor("#34D399"), Color.parseColor("#22D3EE"), Color.parseColor("#A78BFA"))
        val speed = 0.6f + 1.4f * intensity
        for (i in colors.indices) {
            val base = top + i * unit * 0.06f
            val depth = unit * (0.5f - i * 0.08f)
            path.reset()
            var x = 0f
            path.moveTo(0f, base + depth)
            while (x <= w + 8) {
                val k = x / w * 2 * PI.toFloat()
                val y = base + unit * 0.05f * sin(k * 1.4f + t * speed + i * 1.7f) +
                    unit * 0.025f * sin(k * 3.1f - t * speed * 1.3f + i)
                path.lineTo(x, y)
                x += 8f
            }
            path.lineTo(w, base + depth)
            path.close()
            fill.shader = LinearGradient(0f, base - unit * 0.06f, 0f, base + depth,
                intArrayOf(Color.TRANSPARENT, colors[i], Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            fill.alpha = 150 - i * 25
            canvas.drawPath(path, fill)
        }
        fill.shader = null

        drawReadout(canvas, w / 2f, h * 0.3f, unit * 0.3f, t, colors[0], unit * 0.16f)
    }

    // --- Heartbeat --------------------------------------------------------

    /** One heartbeat from 0 to 1: P wave, QRS spike, T wave; returns -0.35..1. */
    private fun ecg(p: Float): Float = when {
        p < 0.10f -> 0f
        p < 0.20f -> 0.12f * sin((p - 0.10f) / 0.10f * PI.toFloat())
        p < 0.28f -> 0f
        p < 0.31f -> -0.12f * (p - 0.28f) / 0.03f
        p < 0.35f -> -0.12f + 1.12f * (p - 0.31f) / 0.04f
        p < 0.39f -> 1f - 1.35f * (p - 0.35f) / 0.04f
        p < 0.42f -> -0.35f + 0.35f * (p - 0.39f) / 0.03f
        p < 0.55f -> 0f
        p < 0.72f -> 0.25f * sin((p - 0.55f) / 0.17f * PI.toFloat())
        else -> 0f
    }

    private fun drawHeartbeat(canvas: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(width, height).toFloat()
        val red = Color.parseColor("#F43F5E")
        val green = Color.parseColor("#4ADE80")
        val line = if (level >= 100) green else red
        val midY = h * 0.56f
        val amp = unit * 0.28f

        // Monitor grid
        stroke.shader = null
        stroke.color = Color.argb(28, 74, 222, 128)
        stroke.strokeWidth = 1f
        val cell = unit * 0.08f
        var gx = 0f
        while (gx < w) { canvas.drawLine(gx, 0f, gx, h, stroke); gx += cell }
        var gy = midY % cell
        while (gy < h) { canvas.drawLine(0f, gy, w, gy, stroke); gy += cell }

        // Trace scrolls left; beats per minute follow charging power
        val bpm = 55f + 95f * intensity
        val beatsOnScreen = 2.6f
        val headX = w * 0.82f
        val phaseAtHead = t * bpm / 60f
        path.reset()
        var x = 0f
        while (x <= headX) {
            val beat = phaseAtHead - (headX - x) / w * beatsOnScreen
            val y = midY - ecg(((beat % 1f) + 1f) % 1f) * amp
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 3f
        }
        val headY = midY - ecg(((phaseAtHead % 1f) + 1f) % 1f) * amp
        stroke.shader = LinearGradient(0f, 0f, headX, 0f, Color.TRANSPARENT, line, Shader.TileMode.CLAMP)
        stroke.strokeWidth = unit * 0.03f
        stroke.alpha = 60
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = unit * 0.009f
        stroke.alpha = 255
        canvas.drawPath(path, stroke)
        stroke.shader = null
        fill.shader = RadialGradient(headX, headY, unit * 0.05f, line, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(headX, headY, unit * 0.05f, fill)
        fill.shader = null
        fill.color = Color.WHITE
        canvas.drawCircle(headX, headY, unit * 0.012f, fill)

        // Heart icon pulses on every beat
        val beatPhase = ((phaseAtHead % 1f) + 1f) % 1f
        val thump = 1f + 0.18f * (if (beatPhase in 0.3f..0.45f) sin((beatPhase - 0.3f) / 0.15f * PI.toFloat()) else 0f)
        val hs = unit * 0.05f * thump
        val hx = w / 2f
        val hy = h * 0.14f
        path.reset()
        path.moveTo(hx, hy + hs * 0.9f)
        path.cubicTo(hx - hs * 1.6f, hy - hs * 0.2f, hx - hs * 0.7f, hy - hs * 1.3f, hx, hy - hs * 0.45f)
        path.cubicTo(hx + hs * 0.7f, hy - hs * 1.3f, hx + hs * 1.6f, hy - hs * 0.2f, hx, hy + hs * 0.9f)
        fill.color = line
        canvas.drawPath(path, fill)
        smallText.textSize = unit * 0.035f
        smallText.color = Color.argb(170, 255, 255, 255)
        canvas.drawText("${bpm.roundToInt()} BPM", hx, hy + unit * 0.11f, smallText)

        drawReadout(canvas, w / 2f, h * 0.36f, unit * 0.3f, t, line, h * 0.42f)
    }

    // --- Digital Rain -----------------------------------------------------

    private val glyphs = "0123456789ABCDEF<>=+*#".toCharArray()

    private fun drawDigitalRain(canvas: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(width, height).toFloat()
        val green = Color.parseColor("#22C55E")
        val size = unit * 0.045f
        val cols = (w / size).toInt().coerceAtLeast(1)
        val rows = (h / size).toInt() + 1
        val pile = h * (1f - shownLevel(t) / 100f)
        smallText.textSize = size * 0.9f
        smallText.letterSpacing = 0f
        val tick = (t * 10).toInt()

        for (c in 0 until cols) {
            val seed = c * 7919
            val speed = (0.35f + (seed % 97) / 97f * 0.5f) * (0.7f + intensity * 1.4f)
            val trail = 6 + seed % 10
            val headRow = ((t * speed * rows * 0.35f + (seed % 53)) % (rows + trail)).toInt()
            val cx = c * size + size / 2
            for (k in 0..trail) {
                val row = headRow - k
                if (row < 0) continue
                val y = row * size + size
                if (y > pile) continue
                val glyph = glyphs[(seed + row * 31 + tick / (1 + k % 3)) % glyphs.size]
                smallText.color = if (k == 0) Color.WHITE else green
                smallText.alpha = if (k == 0) 255 else (220 * (1f - k.toFloat() / trail)).toInt()
                canvas.drawText(glyph.toString(), cx, y, smallText)
            }
            // The pile: settled code up to the battery level, gently flickering
            var y = h - size * 0.2f
            var row = 0
            while (y > pile) {
                val glyph = glyphs[(seed + row * 17 + tick / 4) % glyphs.size]
                smallText.color = green
                smallText.alpha = (70 + 60 * sin(t * 3f + c * 0.7f + row * 0.4f)).toInt().coerceIn(20, 255)
                canvas.drawText(glyph.toString(), cx, y, smallText)
                y -= size
                row++
            }
        }
        smallText.letterSpacing = 0.12f
        smallText.alpha = 255

        // Readout on a dark panel so it stays readable over the rain
        val cy = h * 0.4f
        rect.set(w / 2 - unit * 0.34f, cy - unit * 0.3f, w / 2 + unit * 0.34f, cy + unit * 0.3f)
        fill.shader = null
        fill.color = Color.argb(215, 0, 0, 0)
        canvas.drawRoundRect(rect, unit * 0.04f, unit * 0.04f, fill)
        stroke.shader = null
        stroke.color = Color.argb(140, 34, 197, 94)
        stroke.strokeWidth = unit * 0.004f
        canvas.drawRoundRect(rect, unit * 0.04f, unit * 0.04f, stroke)
        levelText.color = Color.parseColor("#BBF7D0")
        drawReadout(canvas, w / 2f, cy + unit * 0.04f, unit * 0.26f, t, green, unit * 0.12f)
        levelText.color = Color.WHITE
    }

    // --- Hex Shield -------------------------------------------------------

    /** Hex cell centres, bottom row first, for the current size. */
    private var hexCells = FloatArray(0)
    private var hexFor = 0
    private var hexSize = 0f

    private fun buildHexCells(cx: Float, cy: Float, radius: Float) {
        hexSize = radius / 6.2f
        val dx = hexSize * 1.5f
        val dy = hexSize * sqrt3
        val cells = ArrayList<Pair<Float, Float>>()
        for (q in -7..7) {
            val x = cx + q * dx
            val offset = if (q % 2 == 0) 0f else dy / 2
            for (r in -7..7) {
                val y = cy + r * dy + offset
                val ddx = x - cx
                val ddy = y - cy
                if (ddx * ddx + ddy * ddy <= radius * radius) cells += x to y
            }
        }
        cells.sortWith(compareByDescending<Pair<Float, Float>> { it.second }.thenBy { it.first })
        hexCells = FloatArray(cells.size * 2).also { a -> cells.forEachIndexed { i, (x, y) -> a[i * 2] = x; a[i * 2 + 1] = y } }
        hexFor = width * 31 + height
    }

    private fun hexPath(x: Float, y: Float, s: Float) {
        path.reset()
        for (i in 0..5) {
            val a = PI / 3 * i
            val px = x + (cos(a) * s).toFloat()
            val py = y + (sin(a) * s).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    private fun drawHexShield(canvas: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(width, height).toFloat()
        val cx = w / 2f
        val cy = h * 0.4f
        val radius = unit * 0.42f
        if (hexFor != width * 31 + height) buildHexCells(cx, cy, radius)
        val cyan = Color.parseColor("#22D3EE")
        val blue = Color.parseColor("#3B82F6")
        val count = hexCells.size / 2
        val lit = (count * shownLevel(t) / 100f).roundToInt()
        val waveSpeed = 1.5f + 3.5f * intensity

        stroke.shader = null
        for (i in 0 until count) {
            val x = hexCells[i * 2]
            val y = hexCells[i * 2 + 1]
            hexPath(x, y, hexSize * 0.9f)
            if (i < lit) {
                // An energy wave travels upward through the lit cells
                val wave = 0.5f + 0.5f * sin((cy - y) / radius * 6f - t * waveSpeed)
                fill.shader = null
                fill.color = if (i >= lit - (count / 20).coerceAtLeast(1)) cyan else blue
                fill.alpha = (70 + 150 * wave).toInt()
                canvas.drawPath(path, fill)
                stroke.color = cyan
                stroke.alpha = (120 + 135 * wave).toInt()
            } else {
                stroke.color = Color.parseColor("#1E3A5F")
                stroke.alpha = 255
            }
            stroke.strokeWidth = unit * 0.004f
            canvas.drawPath(path, stroke)
        }

        // Shield rim
        stroke.color = cyan
        stroke.alpha = (110 + 80 * sin(t * waveSpeed)).toInt()
        stroke.strokeWidth = unit * 0.008f
        canvas.drawCircle(cx, cy, radius + hexSize * 0.9f, stroke)
        stroke.alpha = 255

        drawReadout(canvas, cx, cy + radius + unit * 0.3f, unit * 0.26f, t, cyan, unit * 0.14f)
    }

    private companion object {
        val sqrt3 = kotlin.math.sqrt(3f)
    }
}
