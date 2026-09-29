package com.truckcontroller.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import kotlin.math.roundToInt

/**
 * Base for the phone tools: shared header (home, icon, title, extra buttons, fullscreen),
 * cards, section labels and "label — value" rows, all in the app's dark style.
 */
abstract class ToolActivity : BaseActivity() {

    protected lateinit var haptics: HapticFeedbackHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = HapticFeedbackHelper(this)
    }

    /** Page skeleton: header + divider + [content] filling the rest. */
    protected fun toolPage(
        title: String, @DrawableRes icon: Int, accent: Int, content: View,
        vararg actions: Pair<Int, () -> Unit>,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.cockpit_bg))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(color(R.color.header_bg))
            addView(headerButton(R.drawable.ic_home, "Home") { finish() }, LinearLayout.LayoutParams(dp(38), dp(34)))
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(accent)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
            actions.forEach { (res, action) ->
                addView(headerButton(res, "") { action() }, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(6) })
            }
            val fullscreenButton = ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon)
            addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(6) })
            bindFullscreenButton(fullscreenButton) { haptics.performButtonClickHaptic() }
        }, LinearLayout.LayoutParams(MATCH, dp(46)))
        addView(View(context).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    protected fun headerButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) =
        ImageButton(this, null, 0, R.style.Cockpit_HeaderIcon).apply {
            setImageResource(icon)
            setColorFilter(color(R.color.slate_300))
            contentDescription = description
            setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        }

    protected fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#111620"))
            setStroke(dp(1), Color.parseColor("#1E293B"))
        }
    }

    /** Card with an icon + uppercase title row. */
    protected fun titledCard(title: String, @DrawableRes icon: Int, accent: Int): LinearLayout = card().apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(accent)
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(sectionLabel(title), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
    }

    /** "What it does" + "How to test" card shown at the top of every hardware / sensor test. */
    protected fun aboutCard(what: String, how: String, accent: Int): LinearLayout =
        titledCard("WHAT IT DOES", R.drawable.ic_info, accent).apply {
            addView(TextView(context).apply {
                text = what
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setTextColor(Color.WHITE)
            })
            addView(sectionLabel("HOW TO TEST").apply { setTextColor(accent) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            addView(TextView(context).apply {
                text = how.replaceFirstChar(Char::uppercase)
                textSize = 13f
                setLineSpacing(0f, 1.15f)
                setTextColor(color(R.color.slate_300))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }

    protected fun sectionLabel(text: String) = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.slate_400))
    }

    /** "Label ........ value" row; long-press copies the value. Returns the value view for updates. */
    protected fun LinearLayout.infoRow(label: String, value: String = "—"): TextView {
        val valueView = TextView(context).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.END
            setTextIsSelectable(false)
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            addView(TextView(context).apply {
                text = label
                setTextColor(color(R.color.slate_400))
                textSize = 14f
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(valueView, LinearLayout.LayoutParams(0, WRAP, 1.3f).apply { marginStart = dp(10) })
            isLongClickable = true
            setOnLongClickListener {
                copy(label, valueView.text.toString())
                true
            }
        })
        return valueView
    }

    protected fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    protected fun shareText(title: String, text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
    }

    protected fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    protected fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()

    protected companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** Formats a byte count as "12.3 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(java.util.Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
}
