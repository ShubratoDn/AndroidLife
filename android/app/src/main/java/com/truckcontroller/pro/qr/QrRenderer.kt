package com.truckcontroller.pro.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.nayuki.qrcodegen.QrCode
import kotlin.math.min

/**
 * Draws a QR code in a [QrDesign]: module shapes, eye shapes, solid or gradient colours,
 * background, centre logo and an optional "SCAN ME" style frame.
 */
object QrRenderer {

    private const val QUIET_ZONE = 4 // modules of empty margin required by scanners

    /** Encodes [text]; a logo covers part of the code, so it gets the highest error correction. */
    fun encode(text: String, withLogo: Boolean): QrCode =
        QrCode.encodeText(text, if (withLogo) QrCode.Ecc.HIGH else QrCode.Ecc.MEDIUM)

    fun render(qr: QrCode, design: QrDesign, logo: Bitmap?, sizePx: Int): Bitmap {
        val frame = !design.frameText.isNullOrBlank()
        val height = if (frame) (sizePx * 1.22f).toInt() else sizePx
        val bitmap = Bitmap.createBitmap(sizePx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val modules = qr.size + QUIET_ZONE * 2
        val cell = sizePx.toFloat() / modules
        val origin = QUIET_ZONE * cell

        // Background (rounded card when framed)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = design.background }
        if (design.background != Color.TRANSPARENT) {
            if (frame) canvas.drawRoundRect(0f, 0f, sizePx.toFloat(), height.toFloat(), cell * 3, cell * 3, bgPaint)
            else canvas.drawRect(0f, 0f, sizePx.toFloat(), height.toFloat(), bgPaint)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = design.color
            design.color2?.let {
                shader = LinearGradient(origin, origin, origin + qr.size * cell, origin + qr.size * cell,
                    design.color, it, Shader.TileMode.CLAMP)
            }
        }

        // Area hidden behind the logo (kept clear so the logo is readable)
        val logoModules = if (logo != null) (qr.size * 0.22f).toInt().let { if (it % 2 == 0) it + 1 else it } else 0
        val logoStart = (qr.size - logoModules) / 2
        fun inLogo(x: Int, y: Int) = logo != null && x in logoStart until logoStart + logoModules && y in logoStart until logoStart + logoModules
        fun isEye(x: Int, y: Int) = (x < 7 && y < 7) || (x >= qr.size - 7 && y < 7) || (x < 7 && y >= qr.size - 7)
        fun dark(x: Int, y: Int) = x in 0 until qr.size && y in 0 until qr.size && qr.getModule(x, y) && !isEye(x, y) && !inLogo(x, y)

        drawModules(canvas, qr.size, ::dark, design.module, origin, cell, paint)

        // Eyes
        val eyePaint = if (design.eyeColor != null) Paint(Paint.ANTI_ALIAS_FLAG).apply { color = design.eyeColor } else paint
        drawEye(canvas, origin, origin, cell, design.eye, eyePaint, bgPaint, 0)
        drawEye(canvas, origin + (qr.size - 7) * cell, origin, cell, design.eye, eyePaint, bgPaint, 1)
        drawEye(canvas, origin, origin + (qr.size - 7) * cell, cell, design.eye, eyePaint, bgPaint, 2)

        // Logo on a rounded plate
        if (logo != null) {
            val plate = RectF(
                origin + logoStart * cell, origin + logoStart * cell,
                origin + (logoStart + logoModules) * cell, origin + (logoStart + logoModules) * cell
            )
            val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (design.background == Color.TRANSPARENT) Color.WHITE else design.background
            }
            canvas.drawRoundRect(plate, cell * 1.5f, cell * 1.5f, platePaint)
            val inset = cell * 0.6f
            val target = RectF(plate.left + inset, plate.top + inset, plate.right - inset, plate.bottom - inset)
            val scale = min(target.width() / logo.width, target.height() / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            canvas.drawBitmap(logo, null,
                RectF(target.centerX() - w / 2, target.centerY() - h / 2, target.centerX() + w / 2, target.centerY() + h / 2),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }

        // Frame label: a bar under the code with the caption
        if (frame) {
            val barTop = sizePx.toFloat() - cell
            val bar = RectF(origin, barTop, sizePx - origin, height - cell * 1.5f)
            canvas.drawRoundRect(bar, cell * 2, cell * 2, paint.apply {
                if (design.color2 != null) {
                    shader = LinearGradient(bar.left, 0f, bar.right, 0f, design.color, design.color2, Shader.TileMode.CLAMP)
                }
            })
            val text = design.frameText!!.uppercase()
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (design.background == Color.TRANSPARENT || isDark(design.background)) Color.WHITE
                else if (isDark(design.color)) Color.WHITE else Color.BLACK
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.12f
                textSize = bar.height() * 0.48f
                val maxWidth = bar.width() * 0.9f
                if (measureText(text) > maxWidth) textSize *= maxWidth / measureText(text)
            }
            canvas.drawText(text, bar.centerX(), bar.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        }
        return bitmap
    }

    private fun isDark(color: Int): Boolean =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 140

    private fun drawModules(
        canvas: Canvas, size: Int, dark: (Int, Int) -> Boolean, shape: ModuleShape,
        origin: Float, cell: Float, paint: Paint,
    ) {
        val path = Path()
        val r = RectF()
        for (y in 0 until size) for (x in 0 until size) {
            if (!dark(x, y)) continue
            val left = origin + x * cell
            val top = origin + y * cell
            r.set(left, top, left + cell, top + cell)
            when (shape) {
                ModuleShape.SQUARE -> path.addRect(left - 0.3f, top - 0.3f, left + cell + 0.3f, top + cell + 0.3f, Path.Direction.CW)
                ModuleShape.ROUNDED -> {
                    r.inset(cell * 0.06f, cell * 0.06f)
                    path.addRoundRect(r, cell * 0.3f, cell * 0.3f, Path.Direction.CW)
                }
                ModuleShape.DOTS -> path.addCircle(left + cell / 2, top + cell / 2, cell * 0.44f, Path.Direction.CW)
                ModuleShape.DIAMOND -> {
                    val cx = left + cell / 2
                    val cy = top + cell / 2
                    val h = cell * 0.56f
                    path.moveTo(cx, cy - h); path.lineTo(cx + h, cy); path.lineTo(cx, cy + h); path.lineTo(cx - h, cy); path.close()
                }
                ModuleShape.LIQUID -> {
                    // Round only the corners that have no neighbour on either side
                    val up = dark(x, y - 1)
                    val down = dark(x, y + 1)
                    val leftN = dark(x - 1, y)
                    val rightN = dark(x + 1, y)
                    val k = cell * 0.5f
                    val tl = if (!up && !leftN) k else 0f
                    val tr = if (!up && !rightN) k else 0f
                    val br = if (!down && !rightN) k else 0f
                    val bl = if (!down && !leftN) k else 0f
                    r.set(left - 0.3f, top - 0.3f, left + cell + 0.3f, top + cell + 0.3f)
                    path.addRoundRect(r, floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
                }
                ModuleShape.VERTICAL -> {
                    // Pill segments joining vertical runs
                    val up = dark(x, y - 1)
                    val down = dark(x, y + 1)
                    val inset = cell * 0.1f
                    val k = cell * 0.4f
                    r.set(left + inset, top - (if (up) 0.3f else -inset), left + cell - inset, top + cell + (if (down) 0.3f else -inset))
                    val tk = if (up) 0f else k
                    val bk = if (down) 0f else k
                    path.addRoundRect(r, floatArrayOf(tk, tk, tk, tk, bk, bk, bk, bk), Path.Direction.CW)
                }
                ModuleShape.HORIZONTAL -> {
                    val leftN = dark(x - 1, y)
                    val rightN = dark(x + 1, y)
                    val inset = cell * 0.1f
                    val k = cell * 0.4f
                    r.set(left - (if (leftN) 0.3f else -inset), top + inset, left + cell + (if (rightN) 0.3f else -inset), top + cell - inset)
                    val lk = if (leftN) 0f else k
                    val rk = if (rightN) 0f else k
                    path.addRoundRect(r, floatArrayOf(lk, lk, rk, rk, rk, rk, lk, lk), Path.Direction.CW)
                }
            }
        }
        canvas.drawPath(path, paint)
    }

    /** One 7x7 finder pattern: outer ring, gap, 3x3 centre. [corner] 0 = TL, 1 = TR, 2 = BL. */
    private fun drawEye(canvas: Canvas, x: Float, y: Float, cell: Float, shape: EyeShape, paint: Paint, bg: Paint, corner: Int) {
        val outer = RectF(x, y, x + cell * 7, y + cell * 7)
        val hole = RectF(x + cell, y + cell, x + cell * 6, y + cell * 6)
        val inner = RectF(x + cell * 2, y + cell * 2, x + cell * 5, y + cell * 5)
        val ring = Path().apply { fillType = Path.FillType.EVEN_ODD }
        when (shape) {
            EyeShape.SQUARE -> {
                // Ring as four bars (a rect-with-rect-hole path gets filled solid on some devices)
                canvas.drawRect(outer.left, outer.top, outer.right, hole.top, paint)
                canvas.drawRect(outer.left, hole.bottom, outer.right, outer.bottom, paint)
                canvas.drawRect(outer.left, hole.top, hole.left, hole.bottom, paint)
                canvas.drawRect(hole.right, hole.top, outer.right, hole.bottom, paint)
                canvas.drawRect(inner, paint)
            }
            EyeShape.ROUNDED -> {
                ring.addRoundRect(outer, cell * 2f, cell * 2f, Path.Direction.CW)
                ring.addRoundRect(hole, cell * 1.3f, cell * 1.3f, Path.Direction.CW)
                canvas.drawPath(ring, paint)
                canvas.drawRoundRect(inner, cell * 0.9f, cell * 0.9f, paint)
            }
            EyeShape.CIRCLE -> {
                ring.addCircle(outer.centerX(), outer.centerY(), cell * 3.5f, Path.Direction.CW)
                ring.addCircle(outer.centerX(), outer.centerY(), cell * 2.5f, Path.Direction.CW)
                canvas.drawPath(ring, paint)
                canvas.drawCircle(outer.centerX(), outer.centerY(), cell * 1.5f, paint)
            }
            EyeShape.LEAF -> {
                // Sharp corner points toward the centre of the code
                fun radii(big: Float) = when (corner) {
                    0 -> floatArrayOf(big, big, big, big, 0f, 0f, big, big)       // sharp bottom-right
                    1 -> floatArrayOf(big, big, big, big, big, big, 0f, 0f)       // sharp bottom-left
                    else -> floatArrayOf(big, big, 0f, 0f, big, big, big, big)    // sharp top-right
                }
                ring.addRoundRect(outer, radii(cell * 3f), Path.Direction.CW)
                ring.addRoundRect(hole, radii(cell * 2.2f), Path.Direction.CW)
                canvas.drawPath(ring, paint)
                canvas.drawPath(Path().apply { addRoundRect(inner, radii(cell * 1.3f), Path.Direction.CW) }, paint)
            }
        }
    }
}
