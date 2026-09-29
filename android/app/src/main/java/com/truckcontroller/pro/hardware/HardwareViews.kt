package com.truckcontroller.pro.hardware

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One satellite as seen by the GNSS receiver. */
class Satellite(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean,
    val carrierMhz: Float?,
)

/** Colour per GNSS constellation (GnssStatus.CONSTELLATION_*). */
fun constellationColor(c: Int) = Color.parseColor(when (c) {
    1 -> "#60A5FA"   // GPS
    2 -> "#A3E635"   // SBAS
    3 -> "#F87171"   // GLONASS
    4 -> "#C084FC"   // QZSS
    5 -> "#FBBF24"   // BeiDou
    6 -> "#2DD4BF"   // Galileo
    7 -> "#FB923C"   // IRNSS / NavIC
    else -> "#94A3B8"
})

fun constellationName(c: Int) = when (c) {
    1 -> "GPS"; 2 -> "SBAS"; 3 -> "GLONASS"; 4 -> "QZSS"; 5 -> "BeiDou"; 6 -> "Galileo"; 7 -> "NavIC"
    else -> "Other"
}

/** Polar sky plot: centre = straight up, edge = horizon, north at the top. */
class SkyPlotView(context: Context) : View(context) {
    var satellites: List<Satellite> = emptyList()
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#334155")
        strokeWidth = d
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
        textSize = 11 * d
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 16 * d
        listOf(1f, 2 / 3f, 1 / 3f).forEach { canvas.drawCircle(cx, cy, r * it, ring) }
        canvas.drawLine(cx - r, cy, cx + r, cy, ring)
        canvas.drawLine(cx, cy - r, cx, cy + r, ring)
        canvas.drawText("N", cx, cy - r - 4 * d, label)
        canvas.drawText("S", cx, cy + r + 13 * d, label)
        canvas.drawText("E", cx + r + 8 * d, cy + 4 * d, label)
        canvas.drawText("W", cx - r - 8 * d, cy + 4 * d, label)
        satellites.forEach { s ->
            val dist = r * (90 - s.elevation.coerceIn(0f, 90f)) / 90f
            val a = Math.toRadians((s.azimuth - 90).toDouble())
            val x = cx + (cos(a) * dist).toFloat()
            val y = cy + (sin(a) * dist).toFloat()
            dot.color = constellationColor(s.constellation)
            if (s.usedInFix) {
                dot.style = Paint.Style.FILL
                canvas.drawCircle(x, y, 6 * d, dot)
            } else {
                dot.style = Paint.Style.STROKE
                dot.strokeWidth = 2 * d
                canvas.drawCircle(x, y, 5 * d, dot)
            }
        }
    }
}

/** Vertical bars of signal strength (C/N0 dB-Hz) per satellite, strongest first. */
class SnrBarsView(context: Context) : View(context) {
    var satellites: List<Satellite> = emptyList()
        set(value) { field = value.sortedByDescending { it.cn0 }.take(40); invalidate() }

    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = Color.parseColor("#1E293B"); strokeWidth = d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 9 * d
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val bottom = height - 14 * d
        val top = 4 * d
        listOf(10, 20, 30, 40, 50).forEach {
            val y = bottom - (bottom - top) * it / 50f
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
        }
        if (satellites.isEmpty()) {
            label.textSize = 12 * d
            canvas.drawText("Searching for satellites…", width / 2f, height / 2f, label)
            label.textSize = 9 * d
            return
        }
        val slot = width.toFloat() / max(satellites.size, 12)
        satellites.forEachIndexed { i, s ->
            val h = (bottom - top) * (s.cn0.coerceIn(0f, 50f) / 50f)
            rect.set(i * slot + slot * 0.15f, bottom - h, (i + 1) * slot - slot * 0.15f, bottom)
            bar.color = constellationColor(s.constellation)
            bar.alpha = if (s.usedInFix) 255 else 110
            canvas.drawRoundRect(rect, 2 * d, 2 * d, bar)
            canvas.drawText("${s.svid}", i * slot + slot / 2, height - 3 * d, label)
        }
    }
}

/** Horizontal level meter (0..1) with a peak marker, green → amber → red. */
class LevelMeterView(context: Context) : View(context) {
    var level = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var peak = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        paint.color = Color.parseColor("#1E293B")
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = Color.parseColor(when {
            level > 0.85f -> "#EF4444"; level > 0.6f -> "#F59E0B"; else -> "#22C55E"
        })
        rect.set(0f, 0f, width * level, height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = Color.WHITE
        val px = width * peak
        canvas.drawRect(px - d, 0f, px + d, height.toFloat(), paint)
    }
}

/** Four-step signal bars icon for list rows (level 0..4). */
class SignalBarsView(context: Context) : View(context) {
    var level = 0
        set(value) { field = value.coerceIn(0, 4); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val gap = width * 0.08f
        val w = (width - gap * 3) / 4
        val on = Color.parseColor(when {
            level >= 3 -> "#34D399"; level == 2 -> "#FBBF24"; else -> "#F87171"
        })
        for (i in 0 until 4) {
            val h = height * (i + 1) / 4f
            rect.set(i * (w + gap), height - h, i * (w + gap) + w, height.toFloat())
            paint.color = if (i < level) on else Color.parseColor("#334155")
            canvas.drawRoundRect(rect, w / 4, w / 4, paint)
        }
    }
}

/** Bars (0..4) for an RSSI in dBm, the usual Wi-Fi / Bluetooth thresholds. */
fun rssiLevel(dbm: Int) = when {
    dbm >= -55 -> 4; dbm >= -67 -> 3; dbm >= -78 -> 2; dbm >= -90 -> 1; else -> 0
}
