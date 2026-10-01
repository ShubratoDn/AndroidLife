package com.truckcontroller.pro

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.charging.ChargeAnimationView
import com.truckcontroller.pro.charging.ChargeStyle
import com.truckcontroller.pro.charging.ChargingAnimation
import kotlin.concurrent.thread

/**
 * Charging Animation (root + LSPosed): pick the lock screen animation HyperOS shows when a
 * charger is plugged in. Every style is previewed live with this phone's battery readings.
 */
class ChargingAnimationActivity : ToolActivity() {

    private val accent = Color.parseColor("#A78BFA")
    private lateinit var preview: FrameLayout
    private lateinit var previewTitle: TextView
    private val styleCards = HashMap<ChargeStyle, View>()
    private lateinit var status: TextView
    private lateinit var statusSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        page.addCard(statusCard())
        page.addCard(previewCard())
        page.addCard(stylesCard())
        page.addCard(optionsCard())
        setContentView(toolPage("Charging Animation", R.drawable.ic_battery_charging, accent,
            ScrollView(this).apply { addView(page) }))
        renderStyle()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun LinearLayout.addCard(view: View) =
        addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

    // ------------------------------------------------------------------
    // Setup status
    // ------------------------------------------------------------------

    private fun statusCard() = titledCard("SETUP", R.drawable.ic_shield, accent).apply {
        status = TextView(context).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        statusSub = TextView(context).apply {
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(color(R.color.slate_300))
        }
        addView(status)
        addView(statusSub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(button("Restart System UI", filled = false) { restartSystemUi() },
            LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(12) })
    }

    private fun renderStatus() {
        if (ChargingAnimation.isModuleActive()) {
            status.text = "Module active"
            status.setTextColor(Color.parseColor("#34D399"))
            statusSub.text = "Plug in your charger with the screen locked to see the animation. " +
                "Style changes apply on the next plug-in. After updating PhoneDeck, restart System UI once."
        } else {
            status.text = "Module not active"
            status.setTextColor(Color.parseColor("#F87171"))
            statusSub.text = "Needs a rooted phone with LSPosed:\n" +
                "1. Open LSPosed › Modules › PhoneDeck and enable it.\n" +
                "2. Keep System UI and PhoneDeck ticked in its scope.\n" +
                "3. Tap Restart System UI below, then reopen this screen."
        }
    }

    private fun restartSystemUi() {
        thread {
            val ok = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f com.android.systemui")).waitFor() == 0
            }.getOrDefault(false)
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Root access is needed to restart System UI", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Preview and styles
    // ------------------------------------------------------------------

    private fun previewCard() = card().apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        previewTitle = sectionLabel("")
        addView(previewTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        preview = roundedFrame()
        addView(preview, LinearLayout.LayoutParams(MATCH, dp(420)))
    }

    private fun stylesCard() = titledCard("STYLES", R.drawable.ic_grid, accent).apply {
        ChargeStyle.entries.chunked(2).forEachIndexed { row, pair ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { i, style ->
                    addView(styleTile(style), LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(10) })
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (row > 0) topMargin = dp(10) })
        }
    }

    private fun styleTile(style: ChargeStyle) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(8))
        addView(roundedFrame().apply {
            addView(ChargeAnimationView(context, style, showDetails = false))
        }, LinearLayout.LayoutParams(MATCH, dp(190)))
        addView(TextView(context).apply {
            text = style.title
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        addView(TextView(context).apply {
            text = style.subtitle
            textSize = 11f
            setTextColor(color(R.color.slate_400))
        })
        setOnClickListener {
            haptics.performButtonClickHaptic()
            ChargingAnimation.setStyle(this@ChargingAnimationActivity, style)
            renderStyle()
        }
        styleCards[style] = this
    }

    private fun renderStyle() {
        val style = ChargingAnimation.style(this)
        previewTitle.text = "PREVIEW · ${style.title.uppercase()}"
        preview.removeAllViews()
        preview.addView(ChargeAnimationView(this, style, ChargingAnimation.showDetails(this)))
        styleCards.forEach { (s, view) ->
            view.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (s == style) {
                    setColor(Color.parseColor("#1A1530"))
                    setStroke(dp(2), accent)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.parseColor("#1E293B"))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    private fun optionsCard() = titledCard("OPTIONS", R.drawable.ic_sliders_horizontal, accent).apply {
        addView(switch("Use PhoneDeck animation", ChargingAnimation.isEnabled(context)) {
            ChargingAnimation.setEnabled(this@ChargingAnimationActivity, it)
        })
        addView(TextView(context).apply {
            text = "Turn off to get the HyperOS animation back."
            textSize = 12f
            setTextColor(color(R.color.slate_400))
        })
        addView(switch("Show charging speed & time to full", ChargingAnimation.showDetails(context)) {
            ChargingAnimation.setShowDetails(this@ChargingAnimationActivity, it)
            renderStyle()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(switch("Show for ${ChargingAnimation.STAY_ON_MINUTES} minutes", ChargingAnimation.stayOn(context)) {
            ChargingAnimation.setStayOn(this@ChargingAnimationActivity, it)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(TextView(context).apply {
            text = "Off: the animation shows for ${ChargingAnimation.SHOW_SECONDS} seconds. On: the screen stays on and it " +
                "shows for ${ChargingAnimation.STAY_ON_MINUTES} minutes. Tap, unlock, press a button or unplug to close it sooner."
            textSize = 12f
            setTextColor(color(R.color.slate_400))
        })
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    /** Black frame with rounded corners that clips the animation inside it. */
    private fun roundedFrame() = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.BLACK)
        }
        clipToOutline = true
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchMaterial(this).apply {
        text = label
        isChecked = checked
        textSize = 14f
        setTextColor(color(R.color.slate_300))
        setOnCheckedChangeListener { _, value -> haptics.performButtonClickHaptic(); onChange(value) }
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (filled) Color.parseColor("#1A1530") else accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (filled) accent else Color.parseColor("#1E293B"))
        }
        setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
    }
}
