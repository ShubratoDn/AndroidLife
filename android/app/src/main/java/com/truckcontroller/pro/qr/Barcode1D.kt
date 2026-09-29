package com.truckcontroller.pro.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface

/** Linear barcode formats the generator can create. */
enum class BarcodeFormat(val label: String, val hint: String) {
    CODE_128("Code 128", "Any text, e.g. SHIP-2026-0042"),
    EAN_13("EAN-13", "12 digits (check digit is added)"),
    EAN_8("EAN-8", "7 digits (check digit is added)"),
    UPC_A("UPC-A", "11 digits (check digit is added)"),
    CODE_39("Code 39", "A–Z, 0–9, space and - . $ / + %"),
}

/** Encoded barcode: bar pattern (true = bar) plus the text printed under it. */
class Barcode(val modules: BooleanArray, val humanText: String)

/** Encoders for common 1D barcodes. Each returns null with [error] set when the input is invalid. */
object Barcode1D {

    var error: String? = null
        private set

    fun encode(format: BarcodeFormat, input: String): Barcode? {
        error = null
        val text = input.trim()
        if (text.isEmpty()) return fail("Enter a value")
        return when (format) {
            BarcodeFormat.CODE_128 -> code128(text)
            BarcodeFormat.EAN_13 -> ean(text, 12)
            BarcodeFormat.EAN_8 -> ean(text, 7)
            BarcodeFormat.UPC_A -> upcA(text)
            BarcodeFormat.CODE_39 -> code39(text)
        }
    }

    private fun fail(message: String): Barcode? {
        error = message
        return null
    }

    /** Appends alternating bar/space runs given as module widths, starting with a bar. */
    private fun MutableList<Boolean>.addWidths(widths: String, startWithBar: Boolean = true) {
        var bar = startWithBar
        widths.forEach { w ->
            repeat(w.digitToInt()) { add(bar) }
            bar = !bar
        }
    }

    private fun MutableList<Boolean>.addBits(bits: String) = bits.forEach { add(it == '1') }

    // ------------------------------------------------------------------
    // Code 128
    // ------------------------------------------------------------------

    private val CODE128 = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232",
    )
    private const val CODE128_STOP = "2331112"
    private const val START_B = 104
    private const val START_C = 105
    private const val SWITCH_B = 100
    private const val SWITCH_C = 99

    private fun code128(text: String): Barcode? {
        if (text.any { it.code !in 32..126 }) return fail("Code 128 supports plain ASCII characters only")
        val values = mutableListOf<Int>()
        var i = 0
        var set = -1 // 0 = B, 1 = C
        fun digitRun(from: Int): Int {
            var n = 0
            while (from + n < text.length && text[from + n].isDigit()) n++
            return n
        }
        while (i < text.length) {
            val run = digitRun(i)
            // Numbers in pairs (Code C) are half as long; worth it for 4+ digits at the ends, 6+ in the middle
            val useC = run >= 4 && (i == 0 || i + run == text.length || run >= 6)
            if (useC) {
                val even = run - run % 2
                if (run % 2 == 1 && i == 0) {
                    // Odd run at the start: first digit in B, rest as pairs
                    if (set != 0) { values += if (set == -1) START_B else SWITCH_B; set = 0 }
                    values += text[i].code - 32
                    i++
                    continue
                }
                if (set != 1) { values += if (set == -1) START_C else SWITCH_C; set = 1 }
                var k = 0
                while (k < even) {
                    values += text.substring(i + k, i + k + 2).toInt()
                    k += 2
                }
                i += even
            } else {
                if (set != 0) { values += if (set == -1) START_B else SWITCH_B; set = 0 }
                values += text[i].code - 32
                i++
            }
        }
        var checksum = values[0]
        for (p in 1 until values.size) checksum += values[p] * p
        values += checksum % 103

        val bars = mutableListOf<Boolean>()
        values.forEach { bars.addWidths(CODE128[it]) }
        bars.addWidths(CODE128_STOP)
        return Barcode(bars.toBooleanArray(), text)
    }

    // ------------------------------------------------------------------
    // EAN / UPC
    // ------------------------------------------------------------------

    private val EAN_L = arrayOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val EAN_G = arrayOf("0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111")
    private val EAN_R = arrayOf("1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100")
    private val EAN13_PARITY = arrayOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")

    /** Standard mod-10 check digit: weights 3,1,3,… from the right. */
    fun checkDigit(data: String): Int {
        var sum = 0
        data.reversed().forEachIndexed { i, c -> sum += c.digitToInt() * if (i % 2 == 0) 3 else 1 }
        return (10 - sum % 10) % 10
    }

    /** [dataLength] 12 = EAN-13, 7 = EAN-8. A full code including a correct check digit is accepted too. */
    private fun ean(text: String, dataLength: Int): Barcode? {
        val name = if (dataLength == 12) "EAN-13" else "EAN-8"
        if (!text.all { it.isDigit() }) return fail("$name uses digits only")
        val digits = when (text.length) {
            dataLength -> text + checkDigit(text)
            dataLength + 1 -> if (checkDigit(text.dropLast(1)) == text.last().digitToInt()) text
                else return fail("Wrong check digit — it should be ${checkDigit(text.dropLast(1))}")
            else -> return fail("$name needs $dataLength digits (you have ${text.length})")
        }
        val bars = mutableListOf<Boolean>()
        bars.addBits("101")
        if (dataLength == 12) {
            val parity = EAN13_PARITY[digits[0].digitToInt()]
            for (i in 1..6) {
                val d = digits[i].digitToInt()
                bars.addBits(if (parity[i - 1] == 'L') EAN_L[d] else EAN_G[d])
            }
            bars.addBits("01010")
            for (i in 7..12) bars.addBits(EAN_R[digits[i].digitToInt()])
        } else {
            for (i in 0..3) bars.addBits(EAN_L[digits[i].digitToInt()])
            bars.addBits("01010")
            for (i in 4..7) bars.addBits(EAN_R[digits[i].digitToInt()])
        }
        bars.addBits("101")
        return Barcode(bars.toBooleanArray(), digits)
    }

    /** UPC-A is EAN-13 with a leading 0; printed without it. */
    private fun upcA(text: String): Barcode? {
        if (!text.all { it.isDigit() }) return fail("UPC-A uses digits only")
        if (text.length != 11 && text.length != 12) return fail("UPC-A needs 11 digits (you have ${text.length})")
        val ean = ean("0$text", 12) ?: return fail(error?.replace("EAN-13", "UPC-A") ?: "Invalid UPC-A")
        return Barcode(ean.modules, ean.humanText.substring(1))
    }

    // ------------------------------------------------------------------
    // Code 39
    // ------------------------------------------------------------------

    private const val CODE39_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%*"
    private val CODE39 = arrayOf(
        "nnnwwnwnn", "wnnwnnnnw", "nnwwnnnnw", "wnwwnnnnn", "nnnwwnnnw", "wnnwwnnnn", "nnwwwnnnn", "nnnwnnwnw", "wnnwnnwnn", "nnwwnnwnn",
        "wnnnnwnnw", "nnwnnwnnw", "wnwnnwnnn", "nnnnwwnnw", "wnnnwwnnn", "nnwnwwnnn", "nnnnnwwnw", "wnnnnwwnn", "nnwnnwwnn", "nnnnwwwnn",
        "wnnnnnnww", "nnwnnnnww", "wnwnnnnwn", "nnnnwnnww", "wnnnwnnwn", "nnwnwnnwn", "nnnnnnwww", "wnnnnnwwn", "nnwnnnwwn", "nnnnwnwwn",
        "wwnnnnnnw", "nwwnnnnnw", "wwwnnnnnn", "nwnnwnnnw", "wwnnwnnnn", "nwwnwnnnn", "nwnnnnwnw", "wwnnnnwnn", "nwwnnnwnn", "nwnwnwnnn",
        "nwnwnnnwn", "nwnnnwnwn", "nnnwnwnwn", "nwnnwnwnn",
    )

    private fun code39(input: String): Barcode? {
        val text = input.uppercase()
        val bad = text.firstOrNull { it == '*' || CODE39_CHARS.indexOf(it) < 0 }
        if (bad != null) return fail("Code 39 can't encode \"$bad\"")
        // Each character: 5 bars and 4 spaces (3 of them wide), then a 1-module gap
        val bars = mutableListOf<Boolean>()
        fun addPattern(c: Char) {
            var bar = true
            CODE39[CODE39_CHARS.indexOf(c)].forEach { w ->
                repeat(if (w == 'w') 3 else 1) { bars.add(bar) }
                bar = !bar
            }
            bars.add(false)
        }
        addPattern('*')
        text.forEach { addPattern(it) }
        addPattern('*')
        bars.removeAt(bars.lastIndex)
        return Barcode(bars.toBooleanArray(), text)
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    fun render(code: Barcode, color: Int, color2: Int?, background: Int, showText: Boolean, widthPx: Int): Bitmap {
        val quiet = 10
        val totalModules = code.modules.size + quiet * 2
        val module = widthPx.toFloat() / totalModules
        val barHeight = widthPx * 0.36f
        val textSize = if (showText) widthPx * 0.065f else 0f
        val pad = widthPx * 0.05f
        val height = (pad + barHeight + (if (showText) textSize * 1.5f else 0f) + pad).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (background != Color.TRANSPARENT) canvas.drawColor(background)
        val paint = Paint().apply {
            this.color = color
            if (color2 != null) shader = LinearGradient(0f, 0f, widthPx.toFloat(), 0f, color, color2, Shader.TileMode.CLAMP)
        }
        // Draw each run of bars as one rectangle for crisp edges
        var i = 0
        while (i < code.modules.size) {
            if (code.modules[i]) {
                var j = i
                while (j < code.modules.size && code.modules[j]) j++
                canvas.drawRect((quiet + i) * module, pad, (quiet + j) * module, pad + barHeight, paint)
                i = j
            } else i++
        }
        if (showText) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.textSize = textSize
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.15f
                val maxWidth = widthPx - pad * 2
                if (measureText(code.humanText) > maxWidth) this.textSize *= maxWidth / measureText(code.humanText)
            }
            canvas.drawText(code.humanText, widthPx / 2f, pad + barHeight + textSize * 1.2f, textPaint)
        }
        return bitmap
    }
}
