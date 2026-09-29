package com.truckcontroller.pro

import android.animation.ValueAnimator
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.truckcontroller.pro.battery.BatteryReader
import com.truckcontroller.pro.battery.BatteryRingView
import com.truckcontroller.pro.battery.BatterySnapshot
import com.truckcontroller.pro.battery.ChargeState
import com.truckcontroller.pro.battery.CurrentGraphView
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Charging Meter on one screen: battery ring with the live current, six key stats and a short
 * current graph. The whole screen takes the state colour: green charging, amber on battery,
 * blue full, violet plugged-but-not-charging, red problem.
 */
class BatteryActivity : BaseActivity() {

    companion object {
        private const val SAMPLE_MS = 1000L
    }

    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var reader: BatteryReader
    private val handler = Handler(Looper.getMainLooper())
    private var last: BatterySnapshot? = null
    private var themeColor = Color.parseColor("#64748B")
    private var themeAnimator: ValueAnimator? = null

    private lateinit var root: LinearLayout
    private lateinit var headerIcon: ImageView
    private lateinit var ring: BatteryRingView
    private lateinit var graph: CurrentGraphView
    private val cards = HashMap<String, StatCard>()

    private class StatCard(val icon: ImageView, val label: TextView, val value: TextView, val themed: Boolean)

    private val sampler = object : Runnable {
        override fun run() {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            render(reader.sample(battery))
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        haptics = HapticFeedbackHelper(this)
        reader = BatteryReader(this)
        setContentView(buildScreen())
        applyTheme(themeColor)
    }

    override fun onStart() {
        super.onStart()
        handler.post(sampler)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(sampler)
    }

    // ------------------------------------------------------------------
    // State colours
    // ------------------------------------------------------------------

    private fun stateColor(state: ChargeState) = Color.parseColor(when (state) {
        ChargeState.CHARGING -> "#22C55E"
        ChargeState.DISCHARGING -> "#F59E0B"
        ChargeState.FULL -> "#38BDF8"
        ChargeState.NOT_CHARGING -> "#A78BFA"
        ChargeState.PROBLEM -> "#EF4444"
    })

    private fun temperatureColor(c: Float) = Color.parseColor(when {
        c < 38f -> "#22C55E"
        c < 43f -> "#F59E0B"
        else -> "#EF4444"
    })

    /** Fades every themed element to the new state colour. */
    private fun animateTheme(target: Int) {
        if (target == themeColor) return
        themeAnimator?.cancel()
        themeAnimator = ValueAnimator.ofArgb(themeColor, target).apply {
            duration = 600
            addUpdateListener { applyTheme(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyTheme(c: Int) {
        themeColor = c
        val bg = color(R.color.cockpit_bg)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.blendARGB(bg, c, 0.22f), ColorUtils.blendARGB(bg, c, 0.06f), bg)
        )
        headerIcon.setColorFilter(c)
        ring.color = c
        graph.color = c
        cards.values.filter { it.themed }.forEach { it.icon.setColorFilter(c) }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render(s: BatterySnapshot) {
        last = s
        animateTheme(stateColor(s.state))

        ring.level = s.level
        ring.animated = s.state == ChargeState.CHARGING
        val fullAndIdle = s.state == ChargeState.FULL && (s.currentMa == null || abs(s.currentMa) < 50)
        when {
            s.problem != null -> {
                ring.valueText = s.problem
                ring.unitText = ""
                ring.chipText = "CHECK THE BATTERY"
            }
            fullAndIdle -> {
                ring.valueText = "Full"
                ring.unitText = ""
                ring.chipText = "FULL · ${s.plugged.uppercase(Locale.US)}"
            }
            s.currentMa == null -> {
                ring.valueText = "…"
                ring.unitText = "MEASURING"
                ring.chipText = chipFor(s)
            }
            else -> {
                ring.valueText = signed(s.currentMa)
                ring.unitText = "mA"
                ring.chipText = chipFor(s)
            }
        }

        setCard("MIN", s.minMa?.let { signed(it) } ?: "—", "mA")
        setCard("MAX", s.maxMa?.let { signed(it) } ?: "—", "mA")
        setCard("POWER", s.powerW?.let { String.format(Locale.US, "%.2f", it) } ?: "—", "W")
        setCard("VOLTAGE", String.format(Locale.US, "%.2f", s.voltageV), "V")
        setCard("TEMP", String.format(Locale.US, "%.1f", s.temperatureC), "°C")
        cards["TEMP"]?.icon?.setColorFilter(temperatureColor(s.temperatureC))

        // Last card: time to full while charging, battery health otherwise
        val info = cards.getValue("INFO")
        if (s.state == ChargeState.CHARGING) {
            info.label.text = "TO FULL"
            info.icon.setImageResource(R.drawable.ic_timer)
            val ms = s.timeToFullMs
            if (ms != null) {
                val min = ms / 60_000
                setCard("INFO", if (min >= 60) "${min / 60}h ${min % 60}" else "$min", if (min >= 60) "min" else "min")
            } else setCard("INFO", "…", "")
        } else {
            info.label.text = "HEALTH"
            info.icon.setImageResource(R.drawable.ic_battery)
            setCard("INFO", s.health, "")
        }

        graph.values = reader.history.toList()
    }

    private fun chipFor(s: BatterySnapshot) = when (s.state) {
        ChargeState.CHARGING -> "CHARGING · ${s.plugged.uppercase(Locale.US)}"
        ChargeState.DISCHARGING -> "ON BATTERY"
        ChargeState.FULL -> "FULL · ${s.plugged.uppercase(Locale.US)}"
        ChargeState.NOT_CHARGING -> "PLUGGED · NOT CHARGING"
        ChargeState.PROBLEM -> "CHECK THE BATTERY"
    }

    private fun signed(ma: Int) = if (ma > 0) "+$ma" else "$ma"

    private fun setCard(key: String, value: String, unit: String) {
        val card = cards[key] ?: return
        card.value.text = SpannableStringBuilder(value).apply {
            if (unit.isNotEmpty()) {
                val start = length
                append(" ").append(unit)
                setSpan(RelativeSizeSpan(0.6f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(color(R.color.slate_400)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun shareSummary() {
        val s = last ?: return
        val text = buildString {
            append("PhoneDeck Charging Meter\n")
            append("${chipFor(s)} · ${s.level}%\n")
            append("Current: ${s.currentMa?.let { "${signed(it)} mA" } ?: "measuring"}")
            if (s.minMa != null) append(" (min ${signed(s.minMa)}, max ${signed(s.maxMa ?: s.minMa)} mA)")
            append('\n')
            s.powerW?.let { append(String.format(Locale.US, "Power: %.2f W\n", it)) }
            append(String.format(Locale.US, "Voltage: %.2f V · Temperature: %.1f °C · Health: %s", s.voltageV, s.temperatureC, s.health))
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }

    // ------------------------------------------------------------------
    // Layout (single screen, no scrolling)
    // ------------------------------------------------------------------

    private fun buildScreen(): View {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, dp(46)))

        ring = BatteryRingView(this)
        graph = CurrentGraphView(this)
        val stats = buildStats()
        val graphCard = FrameLayout(this).apply {
            background = cardBackground()
            addView(graph, FrameLayout.LayoutParams(MATCH, MATCH).apply {
                setMargins(dp(8), dp(22), dp(8), dp(6))
            })
            addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                text = "CURRENT · LAST 3 MIN"
                textSize = 9f
                letterSpacing = 0.18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.slate_400))
            }, FrameLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(12), dp(8), 0, 0) })
        }

        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val content = if (portrait) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(16))
                addView(FrameLayout(context).apply {
                    addView(ring, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
                }, LinearLayout.LayoutParams(MATCH, 0, 1.25f))
                addView(stats, LinearLayout.LayoutParams(MATCH, 0, 0.62f).apply { topMargin = dp(10) })
                addView(graphCard, LinearLayout.LayoutParams(MATCH, 0, 0.42f).apply { topMargin = dp(10) })
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(4), dp(16), dp(12))
                addView(FrameLayout(context).apply {
                    addView(ring, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, MATCH, 0.9f))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(stats, LinearLayout.LayoutParams(MATCH, 0, 1f))
                    addView(graphCard, LinearLayout.LayoutParams(MATCH, 0, 0.75f).apply { topMargin = dp(10) })
                }, LinearLayout.LayoutParams(0, MATCH, 1.2f).apply { marginStart = dp(16) })
            }
        }
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        addView(headerButton(R.drawable.ic_home, "Home") { finish() }, LinearLayout.LayoutParams(dp(38), dp(34)))
        headerIcon = ImageView(context).apply { setImageResource(R.drawable.ic_battery_charging) }
        addView(headerIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
        addView(TextView(context).apply {
            text = "Charging Meter"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
        addView(headerButton(R.drawable.ic_share, "Share") { shareSummary() }, LinearLayout.LayoutParams(dp(38), dp(34)))
        val fullscreenButton = ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon)
        addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(6) })
        bindFullscreenButton(fullscreenButton) { haptics.performButtonClickHaptic() }
    }

    /** 2 rows x 3 cards: Min, Max, Power / Voltage, Temperature, Time to full or Health. */
    private fun buildStats(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        listOf(
            listOf(Triple("MIN", R.drawable.ic_chevron_down, true), Triple("MAX", R.drawable.ic_chevron_up, true),
                Triple("POWER", R.drawable.ic_zap, true)),
            listOf(Triple("VOLTAGE", R.drawable.ic_battery, true), Triple("TEMP", R.drawable.ic_thermometer, false),
                Triple("INFO", R.drawable.ic_timer, true)),
        ).forEachIndexed { r, row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, (key, icon, themed) ->
                    addView(statCard(key, icon, themed), LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        if (i > 0) marginStart = dp(10)
                    })
                }
            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { if (r > 0) topMargin = dp(10) })
        }
    }

    private fun statCard(key: String, @DrawableRes icon: Int, themed: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardBackground()
        setPadding(dp(12), dp(6), dp(8), dp(6))
        val iconView = ImageView(context).apply { setImageResource(icon) }
        val label = TextView(context, null, 0, R.style.Cockpit_Mono).apply {
            text = key
            textSize = 9f
            letterSpacing = 0.15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.slate_400))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        })
        val value = TextView(context).apply {
            text = "—"
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(11, 22, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        addView(value, LinearLayout.LayoutParams(MATCH, dp(30)).apply { topMargin = dp(4) })
        cards[key] = StatCard(iconView, label, value, themed)
        if (key == "MIN" || key == "MAX") {
            isClickable = true
            setOnClickListener {
                haptics.performButtonClickHaptic()
                reader.resetStats()
                Toast.makeText(context, "Min / max reset", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cardBackground() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(200, 17, 22, 32))
        setStroke(dp(1), Color.parseColor("#1E293B"))
    }

    private fun headerButton(icon: Int, description: String, onClick: () -> Unit) =
        ImageButton(this, null, 0, R.style.Cockpit_HeaderIcon).apply {
            setImageResource(icon)
            setColorFilter(color(R.color.slate_300))
            contentDescription = description
            setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
