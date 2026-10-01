package com.truckcontroller.pro.input

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.truckcontroller.pro.R

enum class InputMode(
    val title: String,
    val subtitle: String,
    @DrawableRes val icon: Int,
    @ColorRes val accent: Int,
) {
    KEYBOARD_TOUCHPAD("Keyboard + Touchpad", "Compact keys with a touchpad", R.drawable.ic_mouse, R.color.cyan_400),
    KEYBOARD("Keyboard", "Standard keys with F-row and arrows", R.drawable.ic_keyboard, R.color.amber_400),
    NUMPAD_TOUCHPAD("Num Pad + Touchpad", "Numeric keypad with a touchpad", R.drawable.ic_numpad, R.color.emerald_400),
    KEYBOARD_FULL("Keyboard Complete", "Full 104 keys, nav cluster, numpad", R.drawable.ic_grid, R.color.blue_400),
    PRESENTATION("Presentation Remote", "Slides, timer, laser pointer", R.drawable.ic_presentation, R.color.red_400),
    AIR_MOUSE("Air Mouse", "Point the phone to move the pointer", R.drawable.ic_pointer, R.color.cyan_300),
    TYPE_TEXT("Type on PC", "Paste text, the PC types it", R.drawable.ic_type, R.color.amber_300),
}

data class InputSettings(
    val pointerSpeed: Float = 1.6f,     // 0.5 .. 4.0
    val scrollSpeed: Float = 1f,        // 0.3 .. 3.0
    val naturalScroll: Boolean = true,
    val tapToClick: Boolean = true,
    val keyHaptics: Boolean = true,
    val volumeKeysForSlides: Boolean = true,
)

class InputSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("input_settings", Context.MODE_PRIVATE)

    fun load(): InputSettings {
        val d = InputSettings()
        return InputSettings(
            pointerSpeed = prefs.getFloat("pointerSpeed", d.pointerSpeed),
            scrollSpeed = prefs.getFloat("scrollSpeed", d.scrollSpeed),
            naturalScroll = prefs.getBoolean("naturalScroll", d.naturalScroll),
            tapToClick = prefs.getBoolean("tapToClick", d.tapToClick),
            keyHaptics = prefs.getBoolean("keyHaptics", d.keyHaptics),
            volumeKeysForSlides = prefs.getBoolean("volumeKeysForSlides", d.volumeKeysForSlides),
        )
    }

    fun save(s: InputSettings) {
        prefs.edit()
            .putFloat("pointerSpeed", s.pointerSpeed)
            .putFloat("scrollSpeed", s.scrollSpeed)
            .putBoolean("naturalScroll", s.naturalScroll)
            .putBoolean("tapToClick", s.tapToClick)
            .putBoolean("keyHaptics", s.keyHaptics)
            .putBoolean("volumeKeysForSlides", s.volumeKeysForSlides)
            .apply()
    }
}
