package com.truckcontroller.pro

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.screentime.UsageAnalyzer
import com.truckcontroller.pro.screentime.UsageBarChart
import com.truckcontroller.pro.screentime.UsageSummary
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen time from Android's own usage history: total, unlocks, first unlock, longest session,
 * hourly / daily chart and per-app time with opens. Reads data only while this screen is open;
 * nothing runs in the background.
 */
class ScreenTimeActivity : BaseActivity() {

    companion object {
        private val ACCENT = Color.parseColor("#818CF8")
        private const val APPS_COLLAPSED = 12
    }

    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var analyzer: UsageAnalyzer
    private val worker = Executors.newSingleThreadExecutor()
    private var loadVersion = 0

    private var weekMode = false
    private var daysBack = 0
    private var showAllApps = false
    private var lastSummary: UsageSummary? = null

    private lateinit var content: FrameLayout
    private lateinit var tvPeriod: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var tvTotal: TextView
    private lateinit var tvCompare: TextView
    private lateinit var statUnlocks: Pair<TextView, TextView>
    private lateinit var statFirst: Pair<TextView, TextView>
    private lateinit var statLongest: Pair<TextView, TextView>
    private lateinit var tvChartTitle: TextView
    private lateinit var chart: UsageBarChart
    private lateinit var appsList: LinearLayout
    private lateinit var tvAppsTitle: TextView
    private val modeChips = HashMap<Boolean, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = HapticFeedbackHelper(this)
        analyzer = UsageAnalyzer(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.cockpit_bg))
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, dp(46)))
        root.addView(View(this).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Also runs after returning from the Usage access settings
        if (UsageAnalyzer.hasAccess(this)) {
            if (!::tvTotal.isInitialized) {
                content.removeAllViews()
                content.addView(buildDashboard())
                setMode(weekMode)
            } else load()
        } else {
            content.removeAllViews()
            content.addView(buildAccessCard())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private fun setMode(week: Boolean) {
        weekMode = week
        showAllApps = false
        modeChips.forEach { (k, v) -> styleChip(v, k == week) }
        btnPrev.visibility = if (week) View.INVISIBLE else View.VISIBLE
        btnNext.visibility = if (week) View.INVISIBLE else View.VISIBLE
        load()
    }

    private fun load() {
        val version = ++loadVersion
        val week = weekMode
        val back = daysBack
        tvPeriod.text = if (week) "Last 7 days" else dayName(back)
        btnPrev.alpha = if (back < UsageAnalyzer.MAX_DAYS_BACK) 1f else 0.3f
        btnNext.alpha = if (back > 0) 1f else 0.3f
        tvTotal.text = "…"
        worker.execute {
            val summary = runCatching { if (week) analyzer.week() else analyzer.day(back) }.getOrNull()
            val previous = if (!week && back < UsageAnalyzer.MAX_DAYS_BACK) {
                runCatching { analyzer.day(back + 1).screenTimeMs }.getOrNull()
            } else null
            runOnUiThread {
                if (version != loadVersion || isFinishing) return@runOnUiThread
                if (summary != null) render(summary, previous)
            }
        }
    }

    private fun render(s: UsageSummary, previousMs: Long?) {
        lastSummary = s
        tvTotal.text = formatDuration(s.screenTimeMs)

        // Comparison / average line
        if (weekMode) {
            val activeDays = s.buckets.count { it > 0 }.coerceAtLeast(1)
            tvCompare.text = "Daily average ${formatDuration(s.screenTimeMs / activeDays)}"
            tvCompare.setTextColor(color(R.color.slate_400))
        } else if (previousMs != null && previousMs > 0) {
            val diff = s.screenTimeMs - previousMs
            val prevName = if (daysBack == 0) "yesterday" else "the day before"
            tvCompare.text = when {
                abs(diff) < 60_000 -> "Same as $prevName"
                diff > 0 -> "▲ ${formatDuration(diff)} more than $prevName"
                else -> "▼ ${formatDuration(-diff)} less than $prevName"
            }
            tvCompare.setTextColor(color(if (diff > 60_000) R.color.amber_400 else R.color.emerald_400))
        } else {
            tvCompare.text = ""
        }

        // Stats
        if (weekMode) {
            val days = s.buckets.count { it > 0 }.coerceAtLeast(1)
            setStat(statUnlocks, "UNLOCKS / DAY", (s.unlocks / days).toString())
            setStat(statFirst, "MOST USED DAY", s.buckets.indices.maxByOrNull { s.buckets[it] }
                ?.takeIf { s.buckets[it] > 0 }?.let { weekdayLabel(it) } ?: "—")
        } else {
            setStat(statUnlocks, "UNLOCKS", s.unlocks.toString())
            setStat(statFirst, "FIRST UNLOCK", s.firstUnlock?.let { DateFormat.getTimeFormat(this).format(Date(it)) } ?: "—")
        }
        setStat(statLongest, "LONGEST SESSION", if (s.longestSessionMs > 0) formatDuration(s.longestSessionMs) else "—")

        // Chart
        if (weekMode) {
            tvChartTitle.text = "BY DAY"
            chart.labels = (0 until 7).map { weekdayLabel(it) }
            chart.highlight = 6
            chart.showAverage = true
        } else {
            tvChartTitle.text = "BY HOUR"
            chart.labels = (0 until 24).map { if (it % 6 == 0) it.toString() else "" }
            chart.highlight = if (daysBack == 0) Calendar.getInstance().get(Calendar.HOUR_OF_DAY) else -1
            chart.showAverage = false
        }
        chart.values = s.buckets

        renderApps(s)
    }

    private fun renderApps(s: UsageSummary) {
        appsList.removeAllViews()
        tvAppsTitle.text = "APPS · ${s.apps.size}"
        if (s.apps.isEmpty()) {
            appsList.addView(TextView(this).apply {
                text = "No app usage recorded for this period."
                setTextColor(color(R.color.slate_400))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val top = s.apps.first().timeMs.coerceAtLeast(1)
        val shown = if (showAllApps) s.apps else s.apps.take(APPS_COLLAPSED)
        shown.forEach { app ->
            appsList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                // Tap: open the app's system page (e.g. to set a timer in Digital Wellbeing)
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                    }
                }
                addView(ImageView(context).apply {
                    if (app.icon != null) setImageDrawable(app.icon) else {
                        setImageResource(R.drawable.ic_smartphone)
                        setColorFilter(color(R.color.slate_400))
                    }
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(TextView(context).apply {
                            text = app.label
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            maxLines = 1
                        }, LinearLayout.LayoutParams(0, WRAP, 1f))
                        addView(TextView(context).apply {
                            text = formatDuration(app.timeMs)
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(typeface, Typeface.BOLD)
                        })
                    })
                    // Share bar relative to the most used app
                    addView(FrameLayout(context).apply {
                        background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(Color.parseColor("#1E293B")) }
                        addView(View(context).apply {
                            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(ACCENT) }
                        }, FrameLayout.LayoutParams(0, MATCH).also { lp ->
                            post { lp.width = (width * app.timeMs / top).toInt().coerceAtLeast(dp(3)); requestLayout() }
                        })
                    }, LinearLayout.LayoutParams(MATCH, dp(5)).apply { topMargin = dp(5) })
                    addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                        val pct = if (s.screenTimeMs > 0) (app.timeMs * 100 / s.screenTimeMs) else 0
                        text = "${app.opens} opens · $pct% of screen time"
                        textSize = 10f
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(3) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            })
        }
        if (s.apps.size > APPS_COLLAPSED) {
            appsList.addView(TextView(this).apply {
                text = if (showAllApps) "Show less" else "Show all ${s.apps.size} apps"
                setTextColor(ACCENT)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
                setOnClickListener {
                    showAllApps = !showAllApps
                    lastSummary?.let { renderApps(it) }
                }
            })
        }
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }

    private fun dayName(back: Int): String = when (back) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(UsageAnalyzer.startOfDay(back)))
    }

    /** Label for week bucket [i] (0 = six days ago, 6 = today). */
    private fun weekdayLabel(i: Int): String =
        java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(Date(UsageAnalyzer.startOfDay(6 - i)))

    private fun setStat(stat: Pair<TextView, TextView>, label: String, value: String) {
        stat.first.text = label
        stat.second.text = value
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(color(R.color.header_bg))
        addView(headerButton(R.drawable.ic_home, "Home") { finish() }, LinearLayout.LayoutParams(dp(38), dp(34)))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_hourglass)
            setColorFilter(ACCENT)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
        addView(TextView(context).apply {
            text = "Screen Time"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
        val fullscreenButton = ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon)
        addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)))
        bindFullscreenButton(fullscreenButton) { haptics.performButtonClickHaptic() }
    }

    private fun buildAccessCard(): View = ScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_hourglass)
                setColorFilter(ACCENT)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(40, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)))
                }
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }, LinearLayout.LayoutParams(dp(84), dp(84)))
            addView(TextView(context).apply {
                text = "Allow usage access"
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(18) })
            addView(TextView(context).apply {
                text = "Android already keeps a record of when the screen is on and which app is open. " +
                    "PhoneDeck needs permission to read it.\n\n" +
                    "It is only read while this screen is open — nothing runs in the background, " +
                    "and no data leaves your phone.\n\n" +
                    "On the next screen, find PhoneDeck and turn on \"Permit usage access\"."
                setTextColor(color(R.color.slate_300))
                textSize = 14f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            addView(TextView(context).apply {
                text = "Open settings"
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(ACCENT) }
                setOnClickListener {
                    haptics.performButtonClickHaptic()
                    runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }
            }, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(24) })
        })
    }

    private fun buildDashboard(): View {
        // Controls: Day / Week and the date
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                listOf(false to "Day", true to "Week").forEach { (week, label) ->
                    addView(chip(label) { haptics.performButtonClickHaptic(); daysBack = 0; setMode(week) }.also { modeChips[week] = it },
                        LinearLayout.LayoutParams(dp(96), dp(36)).apply { marginEnd = dp(8) })
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                btnPrev = headerButton(R.drawable.ic_chevron_left, "Previous day") {
                    if (daysBack < UsageAnalyzer.MAX_DAYS_BACK) { daysBack++; load() }
                }
                btnNext = headerButton(R.drawable.ic_chevron_right, "Next day") {
                    if (daysBack > 0) { daysBack--; load() }
                }
                tvPeriod = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
                addView(btnPrev, LinearLayout.LayoutParams(dp(38), dp(34)))
                addView(tvPeriod, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(btnNext, LinearLayout.LayoutParams(dp(38), dp(34)))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }

        // Summary card
        val summary = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("SCREEN TIME"))
            tvTotal = TextView(context).apply {
                textSize = 40f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                text = "…"
            }
            addView(tvTotal, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            tvCompare = TextView(context, null, 0, R.style.Cockpit_Mono).apply { textSize = 12f }
            addView(tvCompare)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                statUnlocks = stat(this, R.drawable.ic_unlock)
                statFirst = stat(this, R.drawable.ic_clock)
                statLongest = stat(this, R.drawable.ic_smartphone)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        }

        // Chart card
        val chartCard = card().apply {
            tvChartTitle = label("BY HOUR")
            addView(tvChartTitle)
            chart = UsageBarChart(context).apply { color = ACCENT }
            addView(chart, LinearLayout.LayoutParams(MATCH, dp(140)).apply { topMargin = dp(6) })
        }

        // Apps card
        val appsCard = card().apply {
            tvAppsTitle = label("APPS")
            addView(tvAppsTitle)
            appsList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(appsList, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        val footnote = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
            text = "Calculated on this phone from Android's usage history · nothing runs in the background"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.slate_600))
        }

        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return if (portrait) {
            ScrollView(this).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(24))
                    addView(controls)
                    addView(summary, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
                    addView(chartCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
                    addView(appsCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
                    addView(footnote, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
                })
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ScrollView(context).apply {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(12), dp(8), dp(16))
                        addView(controls)
                        addView(summary, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
                        addView(chartCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
                    })
                }, LinearLayout.LayoutParams(0, MATCH, 1f))
                addView(ScrollView(context).apply {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(12), dp(16), dp(16))
                        addView(appsCard)
                        addView(footnote, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
                    })
                }, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
        }
    }

    // ------------------------------------------------------------------
    // Small widgets
    // ------------------------------------------------------------------

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#111620"))
            setStroke(dp(1), Color.parseColor("#1E293B"))
        }
    }

    private fun label(text: String) = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.slate_400))
    }

    /** One stat column (icon, label, value) inside [parent]; returns label and value views. */
    private fun stat(parent: LinearLayout, @DrawableRes icon: Int): Pair<TextView, TextView> {
        val labelView = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
            textSize = 8.5f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.slate_400))
        }
        val valueView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            text = "—"
        }
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(ACCENT)
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(valueView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            addView(labelView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        return labelView to valueView
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { onClick() }
        styleChip(this, false)
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) Color.argb(55, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)) else Color.parseColor("#151A24"))
            setStroke(dp(1), if (selected) ACCENT else Color.parseColor("#2A3445"))
        }
        chip.setTextColor(if (selected) ACCENT else color(R.color.slate_300))
    }

    private fun headerButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) =
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
