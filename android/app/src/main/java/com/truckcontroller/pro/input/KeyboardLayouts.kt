package com.truckcontroller.pro.input

/** USB HID usage IDs (Keyboard page 0x07 and Consumer page 0x0C). */
object Hid {
    fun letter(c: Char) = 0x04 + (c.lowercaseChar() - 'a')

    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val EQUAL = 0x2E
    const val LBRACKET = 0x2F
    const val RBRACKET = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val QUOTE = 0x34
    const val GRAVE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val CAPS_LOCK = 0x39
    fun f(n: Int) = 0x3A + (n - 1) // F1..F12
    const val PRINT_SCREEN = 0x46
    const val SCROLL_LOCK = 0x47
    const val PAUSE = 0x48
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val NUM_LOCK = 0x53
    const val KP_SLASH = 0x54
    const val KP_STAR = 0x55
    const val KP_MINUS = 0x56
    const val KP_PLUS = 0x57
    const val KP_ENTER = 0x58
    fun kp(n: Int) = if (n == 0) 0x62 else 0x58 + n // Keypad 1..9, 0
    const val KP_DOT = 0x63
    const val MENU = 0x65

    // Modifier usages; bit index in the modifier byte = usage - 0xE0
    const val L_CTRL = 0xE0
    const val L_SHIFT = 0xE1
    const val L_ALT = 0xE2
    const val L_GUI = 0xE3
    const val R_CTRL = 0xE4
    const val R_SHIFT = 0xE5
    const val R_ALT = 0xE6
    const val R_GUI = 0xE7

    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08

    // Consumer page
    const val C_MUTE = 0xE2
    const val C_VOL_UP = 0xE9
    const val C_VOL_DOWN = 0xEA
    const val C_PLAY_PAUSE = 0xCD
    const val C_NEXT = 0xB5
    const val C_PREV = 0xB6

    // Host LED bits
    const val LED_NUM = 0x01
    const val LED_CAPS = 0x02
}

enum class KeyType {
    /** Ordinary key: held while touched. */
    NORMAL,
    /** Ctrl / Shift / Alt: tap = next key only, tap again = lock, or hold together with another key. */
    MODIFIER_LATCH,
    /** Win key: behaves like a real key, so a tap opens Start and a hold combines with other keys. */
    MODIFIER_HOLD,
    /** Media key on the consumer page. */
    CONSUMER,
}

/** One key, positioned in key units (1 unit = one letter key). */
data class KeyDef(
    val label: String,
    val usage: Int,
    val x: Float,
    val y: Float,
    val w: Float = 1f,
    val h: Float = 1f,
    val shiftLabel: String? = null,
    val type: KeyType = KeyType.NORMAL,
    val accent: Boolean = false,
) {
    val isLetter get() = label.length == 1 && label[0].isLetter()
    val modifierBit get() = if (usage in Hid.L_CTRL..Hid.R_GUI) 1 shl (usage - Hid.L_CTRL) else 0
}

class KeyboardLayout(val keys: List<KeyDef>) {
    val width = keys.maxOf { it.x + it.w }
    val height = keys.maxOf { it.y + it.h }
}

private class LayoutBuilder {
    val keys = mutableListOf<KeyDef>()
    var y = 0f

    fun row(height: Float = 1f, startX: Float = 0f, block: RowBuilder.() -> Unit) {
        RowBuilder(this, startX, y).block()
        y += height
    }

    fun gapRow(height: Float) {
        y += height
    }
}

private class RowBuilder(val layout: LayoutBuilder, var x: Float, val y: Float) {
    fun key(
        label: String, usage: Int, w: Float = 1f, h: Float = 1f, shift: String? = null,
        type: KeyType = KeyType.NORMAL, accent: Boolean = false,
    ) {
        layout.keys += KeyDef(label, usage, x, y, w, h, shift, type, accent)
        x += w
    }

    fun gap(w: Float) {
        x += w
    }

    fun at(newX: Float) {
        x = newX
    }

    fun letters(chars: String) = chars.forEach { key(it.toString(), Hid.letter(it)) }

    fun numberRow() {
        val shifted = "!@#$%^&*()"
        "1234567890".forEachIndexed { i, c ->
            val usage = if (c == '0') 0x27 else 0x1E + (c - '1')
            key(c.toString(), usage, shift = shifted[i].toString())
        }
    }

    fun ctrl(w: Float = 1f, right: Boolean = false) =
        key("Ctrl", if (right) Hid.R_CTRL else Hid.L_CTRL, w, type = KeyType.MODIFIER_LATCH)
    fun shift(w: Float = 1f, right: Boolean = false) =
        key("Shift", if (right) Hid.R_SHIFT else Hid.L_SHIFT, w, type = KeyType.MODIFIER_LATCH)
    fun alt(w: Float = 1f, right: Boolean = false) =
        key("Alt", if (right) Hid.R_ALT else Hid.L_ALT, w, type = KeyType.MODIFIER_LATCH)
    fun win(w: Float = 1f) = key("Win", Hid.L_GUI, w, type = KeyType.MODIFIER_HOLD)
}

private fun build(block: LayoutBuilder.() -> Unit) = KeyboardLayout(LayoutBuilder().apply(block).keys)

object KeyboardLayouts {

    /** 12-unit keyboard that fits next to the touchpad. */
    val COMPACT = build {
        row {
            key("Esc", Hid.ESC); numberRow(); key("Bksp", Hid.BACKSPACE, accent = true)
        }
        row {
            key("Tab", Hid.TAB); letters("qwertyuiop"); key("Del", Hid.DELETE)
        }
        row {
            key("Caps", Hid.CAPS_LOCK, 1.5f); letters("asdfghjkl"); key("Enter", Hid.ENTER, 1.5f, accent = true)
        }
        row {
            shift(2f); letters("zxcvbnm")
            key(",", Hid.COMMA, shift = "<"); key(".", Hid.PERIOD, shift = ">"); key("/", Hid.SLASH, shift = "?")
        }
        row {
            ctrl(1.5f); win(); alt(); key("Space", Hid.SPACE, 4.5f)
            key("←", Hid.LEFT); key("↑", Hid.UP); key("↓", Hid.DOWN); key("→", Hid.RIGHT)
        }
    }

    /** 15-unit keyboard with function row and arrows. */
    val STANDARD = build {
        row(0.85f) {
            key("Esc", Hid.ESC, h = 0.85f)
            for (n in 1..12) key("F$n", Hid.f(n), h = 0.85f)
            key("PrtSc", Hid.PRINT_SCREEN, h = 0.85f); key("Del", Hid.DELETE, h = 0.85f)
        }
        row {
            key("`", Hid.GRAVE, shift = "~"); numberRow()
            key("-", Hid.MINUS, shift = "_"); key("=", Hid.EQUAL, shift = "+"); key("Bksp", Hid.BACKSPACE, 2f, accent = true)
        }
        row {
            key("Tab", Hid.TAB, 1.5f); letters("qwertyuiop")
            key("[", Hid.LBRACKET, shift = "{"); key("]", Hid.RBRACKET, shift = "}"); key("\\", Hid.BACKSLASH, 1.5f, shift = "|")
        }
        row {
            key("Caps", Hid.CAPS_LOCK, 1.75f); letters("asdfghjkl")
            key(";", Hid.SEMICOLON, shift = ":"); key("'", Hid.QUOTE, shift = "\""); key("Enter", Hid.ENTER, 2.25f, accent = true)
        }
        row {
            shift(2f); letters("zxcvbnm")
            key(",", Hid.COMMA, shift = "<"); key(".", Hid.PERIOD, shift = ">"); key("/", Hid.SLASH, shift = "?")
            shift(1f, right = true); key("↑", Hid.UP); key("Menu", Hid.MENU)
        }
        row {
            ctrl(1.25f); win(1.25f); alt(1.25f); key("Space", Hid.SPACE, 6.25f); alt(1f, right = true); ctrl(1f, right = true)
            key("←", Hid.LEFT); key("↓", Hid.DOWN); key("→", Hid.RIGHT)
        }
    }

    /** Full 104-key ANSI keyboard: main block, navigation cluster and numeric keypad. */
    val FULL = build {
        val nav = 15.25f
        val pad = 18.5f
        row(1.2f) {
            key("Esc", Hid.ESC); gap(1f)
            for (n in 1..4) key("F$n", Hid.f(n)); gap(0.5f)
            for (n in 5..8) key("F$n", Hid.f(n)); gap(0.5f)
            for (n in 9..12) key("F$n", Hid.f(n))
            at(nav); key("PrtSc", Hid.PRINT_SCREEN); key("ScrLk", Hid.SCROLL_LOCK); key("Pause", Hid.PAUSE)
        }
        row {
            key("`", Hid.GRAVE, shift = "~"); numberRow()
            key("-", Hid.MINUS, shift = "_"); key("=", Hid.EQUAL, shift = "+"); key("Bksp", Hid.BACKSPACE, 2f, accent = true)
            at(nav); key("Ins", Hid.INSERT); key("Home", Hid.HOME); key("PgUp", Hid.PAGE_UP)
            at(pad); key("Num", Hid.NUM_LOCK); key("/", Hid.KP_SLASH); key("*", Hid.KP_STAR); key("-", Hid.KP_MINUS)
        }
        row {
            key("Tab", Hid.TAB, 1.5f); letters("qwertyuiop")
            key("[", Hid.LBRACKET, shift = "{"); key("]", Hid.RBRACKET, shift = "}"); key("\\", Hid.BACKSLASH, 1.5f, shift = "|")
            at(nav); key("Del", Hid.DELETE); key("End", Hid.END); key("PgDn", Hid.PAGE_DOWN)
            at(pad); key("7", Hid.kp(7)); key("8", Hid.kp(8)); key("9", Hid.kp(9)); key("+", Hid.KP_PLUS, h = 2f)
        }
        row {
            key("Caps", Hid.CAPS_LOCK, 1.75f); letters("asdfghjkl")
            key(";", Hid.SEMICOLON, shift = ":"); key("'", Hid.QUOTE, shift = "\""); key("Enter", Hid.ENTER, 2.25f, accent = true)
            at(pad); key("4", Hid.kp(4)); key("5", Hid.kp(5)); key("6", Hid.kp(6))
        }
        row {
            shift(2.25f); letters("zxcvbnm")
            key(",", Hid.COMMA, shift = "<"); key(".", Hid.PERIOD, shift = ">"); key("/", Hid.SLASH, shift = "?")
            shift(2.75f, right = true)
            at(nav + 1f); key("↑", Hid.UP)
            at(pad); key("1", Hid.kp(1)); key("2", Hid.kp(2)); key("3", Hid.kp(3)); key("Enter", Hid.KP_ENTER, h = 2f, accent = true)
        }
        row {
            ctrl(1.25f); win(1.25f); alt(1.25f); key("Space", Hid.SPACE, 6.25f)
            alt(1.25f, right = true); key("Win", Hid.R_GUI, 1.25f, type = KeyType.MODIFIER_HOLD)
            key("Menu", Hid.MENU, 1.25f); ctrl(1.25f, right = true)
            at(nav); key("←", Hid.LEFT); key("↓", Hid.DOWN); key("→", Hid.RIGHT)
            at(pad); key("0", Hid.kp(0), 2f); key(".", Hid.KP_DOT)
        }
    }

    /**
     * 11-unit main block for portrait: F-keys and symbols get their own rows so keys stay
     * phone-keyboard sized. [navRow] adds Ins/Home/End/PgUp/PgDn; [arrows] ends with arrow keys.
     */
    private fun LayoutBuilder.portraitMain(navRow: Boolean, arrows: Boolean) {
        row(0.85f) {
            key("Esc", Hid.ESC, h = 0.85f)
            for (n in 1..10) key("F$n", Hid.f(n), h = 0.85f)
        }
        row(0.85f) {
            key("F11", Hid.f(11), h = 0.85f); key("F12", Hid.f(12), h = 0.85f)
            key("`", Hid.GRAVE, h = 0.85f, shift = "~"); key("-", Hid.MINUS, h = 0.85f, shift = "_")
            key("=", Hid.EQUAL, h = 0.85f, shift = "+"); key("[", Hid.LBRACKET, h = 0.85f, shift = "{")
            key("]", Hid.RBRACKET, h = 0.85f, shift = "}"); key("\\", Hid.BACKSLASH, h = 0.85f, shift = "|")
            key(";", Hid.SEMICOLON, h = 0.85f, shift = ":"); key("'", Hid.QUOTE, h = 0.85f, shift = "\"")
            key("Del", Hid.DELETE, h = 0.85f)
        }
        if (navRow) row(0.85f) {
            val w = 11f / 7f
            key("PrtSc", Hid.PRINT_SCREEN, w, 0.85f); key("Ins", Hid.INSERT, w, 0.85f)
            key("Home", Hid.HOME, w, 0.85f); key("End", Hid.END, w, 0.85f)
            key("PgUp", Hid.PAGE_UP, w, 0.85f); key("PgDn", Hid.PAGE_DOWN, w, 0.85f); key("Menu", Hid.MENU, w, 0.85f)
        }
        row { numberRow(); key("Bksp", Hid.BACKSPACE, accent = true) }
        row { key("Tab", Hid.TAB); letters("qwertyuiop") }
        row { key("Caps", Hid.CAPS_LOCK); letters("asdfghjkl"); key("Enter", Hid.ENTER, accent = true) }
        row {
            shift(); letters("zxcvbnm")
            key(",", Hid.COMMA, shift = "<"); key(".", Hid.PERIOD, shift = ">"); key("/", Hid.SLASH, shift = "?")
        }
        row {
            if (arrows) {
                ctrl(); win(); alt(); key("Space", Hid.SPACE, 4f)
                key("←", Hid.LEFT); key("↑", Hid.UP); key("↓", Hid.DOWN); key("→", Hid.RIGHT)
            } else {
                ctrl(1.25f); win(1.25f); alt(1.25f); key("Space", Hid.SPACE, 4.5f)
                alt(1.25f, right = true); ctrl(1.5f, right = true)
            }
        }
    }

    /** Portrait keyboard: all keys of [STANDARD] in 11-unit rows. */
    val PORTRAIT_STANDARD = build { portraitMain(navRow = true, arrows = true) }

    /** Portrait complete keyboard, upper panel: navigation cluster, arrows and numpad. */
    val PORTRAIT_NAVPAD = build {
        val n = 1.3f      // nav key width (3 keys)
        val p = 1.625f    // numpad key width (4 keys), starting at 4.5
        val pad = 4.5f
        row {
            key("Ins", Hid.INSERT, n); key("Home", Hid.HOME, n); key("PgUp", Hid.PAGE_UP, n)
            at(pad); key("Num", Hid.NUM_LOCK, p); key("/", Hid.KP_SLASH, p); key("*", Hid.KP_STAR, p); key("-", Hid.KP_MINUS, p)
        }
        row {
            key("Del", Hid.DELETE, n); key("End", Hid.END, n); key("PgDn", Hid.PAGE_DOWN, n)
            at(pad); key("7", Hid.kp(7), p); key("8", Hid.kp(8), p); key("9", Hid.kp(9), p); key("+", Hid.KP_PLUS, p, 2f)
        }
        row {
            key("PrtSc", Hid.PRINT_SCREEN, n); key("ScrLk", Hid.SCROLL_LOCK, n); key("Pause", Hid.PAUSE, n)
            at(pad); key("4", Hid.kp(4), p); key("5", Hid.kp(5), p); key("6", Hid.kp(6), p)
        }
        row {
            at(n); key("↑", Hid.UP, n)
            at(pad); key("1", Hid.kp(1), p); key("2", Hid.kp(2), p); key("3", Hid.kp(3), p)
            key("Enter", Hid.KP_ENTER, p, 2f, accent = true)
        }
        row {
            key("←", Hid.LEFT, n); key("↓", Hid.DOWN, n); key("→", Hid.RIGHT, n)
            at(pad); key("0", Hid.kp(0), p * 2); key(".", Hid.KP_DOT, p)
        }
    }

    /** Portrait complete keyboard, lower panel: the typing block (height adjustable). */
    val PORTRAIT_MAIN = build { portraitMain(navRow = false, arrows = false) }

    /** Numeric keypad with a few editing keys on top. */
    val NUMPAD = build {
        row {
            key("Esc", Hid.ESC); key("Tab", Hid.TAB); key("=", Hid.EQUAL); key("Bksp", Hid.BACKSPACE, accent = true)
        }
        row {
            key("Num", Hid.NUM_LOCK); key("/", Hid.KP_SLASH); key("*", Hid.KP_STAR); key("-", Hid.KP_MINUS)
        }
        row {
            key("7", Hid.kp(7)); key("8", Hid.kp(8)); key("9", Hid.kp(9)); key("+", Hid.KP_PLUS, h = 2f)
        }
        row {
            key("4", Hid.kp(4)); key("5", Hid.kp(5)); key("6", Hid.kp(6))
        }
        row {
            key("1", Hid.kp(1)); key("2", Hid.kp(2)); key("3", Hid.kp(3)); key("Enter", Hid.KP_ENTER, h = 2f, accent = true)
        }
        row {
            key("0", Hid.kp(0), 2f); key(".", Hid.KP_DOT)
        }
    }
}
