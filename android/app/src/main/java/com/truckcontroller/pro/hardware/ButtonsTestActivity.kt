package com.truckcontroller.pro.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.R

/** Hardware buttons test: volume up/down, power (via screen off) and back each light up and count presses. */
class ButtonsTestActivity : HardwareTestActivity() {

    private class Tile(val view: LinearLayout, val count: TextView, var presses: Int = 0)

    private val tiles = HashMap<String, Tile>()
    private lateinit var otherKeys: TextView
    private val others = LinkedHashSet<String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) press("Power")
        }
    }

    override fun buildTest() {
        page.addCard(titledCard("PRESS EACH BUTTON", R.drawable.ic_square_power, accent).apply {
            addView(row(tile("Volume up", R.drawable.ic_volume), tile("Volume down", R.drawable.ic_volume_down)))
            addView(row(tile("Power", R.drawable.ic_power), tile("Back", R.drawable.ic_arrow_left)),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(smallText("Volume keys don't change the volume while this test is open. Back is counted " +
                "instead of leaving — use the Home button at the top to exit."),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        })
        page.addCard(titledCard("OTHER KEYS", R.drawable.ic_keyboard, accent).apply {
            otherKeys = smallText("Camera, headset or keyboard keys appear here when pressed.")
            addView(otherKeys)
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = press("Back")
        })
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        // The screen-off broadcast has to be received while the activity is stopped (screen off)
        if (started) runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!started) return super.dispatchKeyEvent(event)
        val name = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "Volume up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume down"
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            else -> null
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (name != null) press(name) else {
                others += KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
                otherKeys.text = others.joinToString(", ") { it.replaceFirstChar(Char::uppercase) }
                otherKeys.setTextColor(accent)
            }
        }
        return name != null || super.dispatchKeyEvent(event)
    }

    private fun press(name: String) {
        val t = tiles[name] ?: return
        t.presses++
        t.count.text = "Pressed ${t.presses}×"
        t.count.setTextColor(hex("#04201C"))
        (t.view.background as GradientDrawable).setColor(accent)
        (t.view.getChildAt(0) as ImageView).setColorFilter(hex("#04201C"))
        (t.view.getChildAt(1) as TextView).setTextColor(hex("#04201C"))
        t.view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction {
            t.view.animate().scaleX(1f).scaleY(1f).setDuration(120)
        }
        haptics.performButtonClickHaptic()
    }

    private fun row(a: LinearLayout, b: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, dp(110), 1f))
        addView(b, LinearLayout.LayoutParams(0, dp(110), 1f).apply { marginStart = dp(10) })
    }

    private fun tile(name: String, icon: Int): LinearLayout {
        val count = TextView(this).apply {
            text = "Not pressed"
            textSize = 12f
            setTextColor(color(R.color.slate_400))
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(hex("#1E293B"))
            }
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(context).apply {
                text = name
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addView(count)
        }
        tiles[name] = Tile(view, count)
        return view
    }
}
