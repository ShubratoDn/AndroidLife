package com.truckcontroller.pro

import android.content.Context
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Base for every screen: applies the app-wide fullscreen setting. The setting is remembered, so
 * toggling it on one screen applies to all screens and to the next launch.
 */
abstract class BaseActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "ui"
        private const val KEY_FULLSCREEN = "fullscreen"

        fun isFullscreen(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FULLSCREEN, true)

        private fun saveFullscreen(context: Context, enabled: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FULLSCREEN, enabled).apply()
    }

    protected val fullscreen get() = isFullscreen(this)
    private val fullscreenButtons = mutableListOf<ImageButton>()

    /** Makes [button] the fullscreen toggle for this screen and keeps its icon in sync. */
    protected fun bindFullscreenButton(button: ImageButton, onClick: () -> Unit = {}) {
        fullscreenButtons += button
        button.contentDescription = "Toggle fullscreen"
        button.setOnClickListener {
            onClick()
            toggleFullscreen()
        }
        renderFullscreenButtons()
    }

    protected fun toggleFullscreen() {
        saveFullscreen(this, !fullscreen)
        applyFullscreen()
    }

    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        renderFullscreenButtons()
    }

    private fun renderFullscreenButtons() {
        val icon = if (fullscreen) R.drawable.ic_minimize else R.drawable.ic_maximize
        fullscreenButtons.forEach {
            it.setImageResource(icon)
            it.setColorFilter(ContextCompat.getColor(this, R.color.slate_300))
        }
    }

    override fun onResume() {
        super.onResume()
        // Another screen may have changed the setting
        applyFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and transient swipes bring the bars back; hide them again in fullscreen
        if (hasFocus && fullscreen) applyFullscreen()
    }
}
