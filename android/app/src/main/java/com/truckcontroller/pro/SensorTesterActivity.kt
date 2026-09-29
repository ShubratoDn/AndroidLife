package com.truckcontroller.pro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.truckcontroller.pro.hardware.HardwareTest
import com.truckcontroller.pro.hardware.HardwareTests
import com.truckcontroller.pro.sensors.SensorVisualView
import com.truckcontroller.pro.sensors.TouchTestView
import java.util.Locale
import kotlin.math.pow

/**
 * Sensor Tester: every sensor with its specs; tap one for a live test with a purpose-built visual
 * (level, compass, light meter, proximity, pressure) plus live values and a multi-axis graph.
 */
class SensorTesterActivity : ToolActivity(), SensorEventListener {

    private val accent = Color.parseColor("#2DD4BF")
    private lateinit var sensorManager: SensorManager
    private lateinit var content: FrameLayout
    private var testing: Sensor? = null
    private var visual: SensorVisualView? = null
    private var graph: SensorVisualView? = null
    private var valueViews: List<TextView> = emptyList()
    private var headline: TextView? = null
    private var sampleCount = 0
    private var lastRateAt = 0L
    private var rateView: TextView? = null

    // Compass needs accelerometer + magnetometer together
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    private val activityPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        testing?.let { startTest(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SensorManager::class.java)
        content = FrameLayout(this)
        setContentView(toolPage("Sensor Tester", R.drawable.ic_activity, accent, content))
        showList()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inTest) showList() else finish()
            }
        })
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        vibrator()?.cancel()
    }

    override fun onResume() {
        super.onResume()
        testing?.let { register(it) }
    }

    // ------------------------------------------------------------------
    // Sensor list
    // ------------------------------------------------------------------

    private fun showList() {
        sensorManager.unregisterListener(this)
        testing = null
        metal = false
        inTest = false
        vibrator()?.cancel()
        closeDisplayTest()
        content.removeAllViews()
        // The list is built once and reused, so returning from a test keeps the scroll position
        val scroll = listScroll ?: buildList().also { listScroll = it }
        content.addView(scroll)
        scroll.post { scroll.scrollTo(0, listScrollY) }
    }

    private var listScroll: ScrollView? = null
    private var listScrollY = 0

    private fun buildList(): ScrollView {
        val sensors = uniqueSensors()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val connectivity = hardwareTests(HardwareTest.Section.CONNECTIVITY)
        val hardware = hardwareTests(HardwareTest.Section.HARDWARE) + extraTests()
        list.addView(TextView(this).apply {
            text = "${connectivity.size + hardware.size + sensors.size} tests"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        list.addView(TextView(this).apply {
            text = "Tap a test to run it. Tap ⓘ to see what it does and how to test it."
            textSize = 13f
            setTextColor(color(R.color.slate_400))
        })

        fun section(title: String, tests: List<ExtraTest>) {
            list.addView(sectionTitle(title), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
            tests.forEach { t ->
                list.addView(listCard(t.icon, t.title, t.subtitle, t.what, t.how) { t.open() },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }
        }
        section("CONNECTIVITY · ${connectivity.size}", connectivity)
        section("HARDWARE · ${hardware.size}", hardware)
        section("SENSORS · ${sensors.size}", sensors.map { s ->
            val (what, how) = describe(s.type)
            ExtraTest(iconFor(s.type), typeName(s), "${s.name} · ${s.vendor}", what, how) { startTest(s) }
        })
        return ScrollView(this).apply { addView(list) }
    }

    /** Hardware tests that open their own screen; missing hardware is listed but marked. */
    private fun hardwareTests(section: HardwareTest.Section) = HardwareTests.all.filter { it.section == section }.map { t ->
        ExtraTest(t.icon, t.title, if (t.available(this)) t.tag else "Not on this phone", t.what, t.how) {
            listScroll?.let { listScrollY = it.scrollY }
            startActivity(Intent(this, t.activity))
        }
    }

    /**
     * One entry per kind of sensor: phones often expose duplicates (wake-up and non-wake-up copies,
     * several vendor implementations), which would otherwise show the same test twice.
     */
    private fun uniqueSensors(): List<Sensor> =
        sensorManager.getSensorList(Sensor.TYPE_ALL)
            .groupBy { typeName(it).lowercase(Locale.US) }
            .values
            .map { group ->
                sensorManager.getDefaultSensor(group[0].type)?.takeIf { it in group }
                    ?: group.firstOrNull { !it.isWakeUpSensor } ?: group[0]
            }
            .sortedWith(compareBy({ priority(it.type) }, { typeName(it) }))

    private fun sectionTitle(title: String) = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        text = title
        textSize = 10f
        letterSpacing = 0.18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.slate_400))
    }

    private fun listCard(icon: Int, title: String, subtitle: String, what: String, how: String, onOpen: () -> Unit): LinearLayout {
        val about = TextView(this).apply {
            text = android.text.SpannableStringBuilder().apply {
                append("What it does\n", android.text.style.StyleSpan(Typeface.BOLD), 0)
                append(what)
                append("\n\n")
                val start = length
                append("How to test\n", android.text.style.StyleSpan(Typeface.BOLD), 0)
                append(how.replaceFirstChar(Char::uppercase))
                setSpan(android.text.style.ForegroundColorSpan(accent), start, length, 0)
            }
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(color(R.color.slate_300))
            setPadding(dp(50), dp(8), dp(4), 0)
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(accent)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                    text = subtitle
                    textSize = 10f
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_info)
                setColorFilter(color(R.color.slate_400))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "What it does"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1E293B"))
                }
                setOnClickListener {
                    haptics.performButtonClickHaptic()
                    val show = about.visibility != View.VISIBLE
                    about.visibility = if (show) View.VISIBLE else View.GONE
                    setColorFilter(if (show) accent else color(R.color.slate_400))
                }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(color(R.color.slate_500))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        return card().apply {
            isClickable = true
            setOnClickListener { haptics.performButtonClickHaptic(); onOpen() }
            addView(row)
            addView(about)
        }
    }

    // ------------------------------------------------------------------
    // Live test
    // ------------------------------------------------------------------

    private fun startTest(s: Sensor) {
        testing = s
        metal = false
        if ((s.type == Sensor.TYPE_STEP_COUNTER || s.type == Sensor.TYPE_STEP_DETECTOR) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        sampleCount = 0
        gravity = null
        geomagnetic = null
        val (what, how) = describe(s.type)
        val page = openTestPage(typeName(s), s.name, what, how)

        // Main visual
        val mode = modeFor(s.type)
        val mainVisual = SensorVisualView(this).apply {
            accent = this@SensorTesterActivity.accent
            this.mode = mode
            maxValue = when (s.type) {
                Sensor.TYPE_PROXIMITY -> s.maximumRange
                Sensor.TYPE_LIGHT -> 40_000f
                Sensor.TYPE_PRESSURE -> 1100f
                else -> s.maximumRange.coerceAtLeast(1f)
            }
            unit = unitFor(s.type)
        }
        visual = mainVisual
        page.addView(card().apply {
            addView(mainVisual, LinearLayout.LayoutParams(MATCH, dp(if (mode == SensorVisualView.Mode.GRAPH) 200 else 250)))
            headline = TextView(context).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.slate_300))
            }
            addView(headline, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        // Values
        val axes = axisNames(s.type)
        val valuesCard = titledCard("LIVE VALUES", R.drawable.ic_activity, accent)
        graph = if (mode != SensorVisualView.Mode.GRAPH) SensorVisualView(this).apply { this.mode = SensorVisualView.Mode.GRAPH } else null
        valueViews = axes.mapIndexed { i, name ->
            valuesCard.infoRow(name).apply { setTextColor((graph ?: mainVisual).axisColor(i)) }
        }
        rateView = valuesCard.infoRow("Update rate")
        graph?.let { valuesCard.addView(it, LinearLayout.LayoutParams(MATCH, dp(110)).apply { topMargin = dp(6) }) }
        page.addView(valuesCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        // Specs
        val specs = titledCard("SPECIFICATIONS", R.drawable.ic_info, accent)
        specs.infoRow("Vendor", s.vendor)
        specs.infoRow("Version", s.version.toString())
        specs.infoRow("Range", "${fmt(s.maximumRange)} ${unitFor(s.type)}")
        specs.infoRow("Resolution", "${fmt(s.resolution)} ${unitFor(s.type)}")
        specs.infoRow("Power", "${fmt(s.power)} mA")
        specs.infoRow("Fastest rate", if (s.minDelay > 0) "${1_000_000 / s.minDelay} Hz" else "On change")
        specs.infoRow("Wake-up sensor", if (s.isWakeUpSensor) "Yes" else "No")
        page.addView(specs, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        register(s)
    }

    private fun register(s: Sensor) {
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        // Compass also needs the accelerometer
        if (s.type == Sensor.TYPE_MAGNETIC_FIELD) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        lastRateAt = System.currentTimeMillis()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = testing ?: return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && s.type == Sensor.TYPE_MAGNETIC_FIELD) {
            gravity = event.values.copyOf()
            return
        }
        if (event.sensor != s) return
        val v = event.values
        sampleCount++
        val now = System.currentTimeMillis()
        if (now - lastRateAt >= 1000) {
            rateView?.text = "$sampleCount Hz"
            sampleCount = 0
            lastRateAt = now
        }
        if (metal) {
            showMetalReading(kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
            return
        }
        valueViews.forEachIndexed { i, tv -> tv.text = "${fmt(v.getOrElse(i) { 0f })} ${unitFor(s.type)}".trim() }

        val main = visual ?: return
        when (main.mode) {
            SensorVisualView.Mode.COMPASS -> {
                geomagnetic = v.copyOf()
                val g = gravity
                if (g != null) {
                    val r = FloatArray(9)
                    if (SensorManager.getRotationMatrix(r, null, g, v)) {
                        val o = FloatArray(3)
                        SensorManager.getOrientation(r, o)
                        main.values = floatArrayOf(Math.toDegrees(o[0].toDouble()).toFloat())
                    }
                }
                val strength = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                headline?.text = "Field strength ${fmt(strength)} µT"
            }
            SensorVisualView.Mode.GAUGE -> {
                main.values = floatArrayOf(v[0])
                headline?.text = when (s.type) {
                    Sensor.TYPE_PRESSURE -> "Altitude ≈ ${altitude(v[0]).toInt()} m above sea level"
                    Sensor.TYPE_LIGHT -> lightDescription(v[0])
                    Sensor.TYPE_STEP_COUNTER -> "Steps since the phone restarted"
                    else -> ""
                }
            }
            else -> main.values = v.copyOf()
        }
        if (main.mode == SensorVisualView.Mode.GRAPH) main.pushHistory(v.copyOf(axisNames(s.type).size))
        graph?.pushHistory(v.copyOf(axisNames(s.type).size))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------
    // Extra tests (not a single raw sensor)
    // ------------------------------------------------------------------

    private class ExtraTest(val icon: Int, val title: String, val subtitle: String, val what: String, val how: String, val open: () -> Unit)

    private fun extraTests() = buildList {
        if (sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) add(ExtraTest(
            R.drawable.ic_magnet, "Metal detector", "Uses the magnetometer", METAL_WHAT, METAL_HOW, ::startMetalDetector))
        add(ExtraTest(R.drawable.ic_hand, "Multi-touch screen", "Touchscreen", TOUCH_WHAT, TOUCH_HOW, ::startTouchTest))
        if (vibrator()?.hasVibrator() == true) add(ExtraTest(
            R.drawable.ic_activity, "Vibration motor", "Haptics", VIBRATION_WHAT, VIBRATION_HOW, ::startVibrationTest))
        add(ExtraTest(R.drawable.ic_sun, "Display colours", "Screen", DISPLAY_WHAT, DISPLAY_HOW, ::startDisplayTest))
    }

    private companion object {
        const val METAL_WHAT = "Finds iron and steel (nails, screws, pipes, wiring behind walls) by measuring the total " +
            "magnetic field. The Earth's field is about 25–65 µT; metal near the top of the phone pushes it higher."
        const val METAL_HOW = "move the top of the phone slowly along a wall, table leg or near keys. The reading " +
            "turns amber, then red, near steel. Keep away from speakers and magnets."
        const val TOUCH_WHAT = "Shows every finger the touchscreen detects, numbered, and the most fingers seen at once. " +
            "Lines left behind reveal areas that don't respond."
        const val TOUCH_HOW = "put several fingers down together to check the maximum count, then draw slowly over the " +
            "whole box. Gaps in a line mean a dead spot."
        const val VIBRATION_WHAT = "Checks the vibration motor with system haptic effects, timed buzzes and strength levels."
        const val VIBRATION_HOW = "tap each button. Short effects should feel crisp, long ones steady, and Weak/Medium/Strong " +
            "clearly different. Rattling or no response means a faulty motor."
        const val DISPLAY_WHAT = "Fills the screen with solid colours to reveal dead or stuck pixels, uneven backlight " +
            "and screen burn-in."
        const val DISPLAY_HOW = "tap to cycle colours and look closely. A dot that stays black or one colour is a bad pixel; " +
            "ghost images on grey are burn-in. Press Back to exit."
    }

    // ------------------------------------------------------------------
    // Test page shell
    // ------------------------------------------------------------------

    private var inTest = false

    /** Clears the screen for a test: back arrow + title, and a "What it does / How to test" card. */
    private fun openTestPage(title: String, subtitle: String, what: String, how: String, scroll: Boolean = true): LinearLayout {
        listScroll?.let { listScrollY = it.scrollY }
        inTest = true
        content.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(if (scroll) 24 else 12))
        }
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(headerButton(R.drawable.ic_chevron_left, "All sensors") { showList() }, LinearLayout.LayoutParams(dp(38), dp(34)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                    text = subtitle
                    textSize = 11f
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        })
        page.addView(aboutCard(what, how, accent), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        if (scroll) content.addView(ScrollView(this).apply { addView(page) })
        else content.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    // ------------------------------------------------------------------
    // Metal detector
    // ------------------------------------------------------------------

    private var metal = false

    private fun startMetalDetector() {
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        val page = openTestPage("Metal detector", s.name, METAL_WHAT, METAL_HOW)
        testing = s
        metal = true
        sampleCount = 0
        gravity = null
        val gauge = SensorVisualView(this).apply {
            accent = this@SensorTesterActivity.accent
            mode = SensorVisualView.Mode.GAUGE
            maxValue = 250f
            logScale = false
            unit = "µT"
        }
        visual = gauge
        page.addView(card().apply {
            addView(gauge, LinearLayout.LayoutParams(MATCH, dp(230)))
            headline = TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(headline, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        val valuesCard = titledCard("LIVE VALUES", R.drawable.ic_magnet, accent)
        val history = SensorVisualView(this).apply { mode = SensorVisualView.Mode.GRAPH }
        graph = history
        valueViews = listOf(valuesCard.infoRow("Field strength").apply { setTextColor(history.axisColor(0)) })
        rateView = valuesCard.infoRow("Update rate")
        valuesCard.addView(history, LinearLayout.LayoutParams(MATCH, dp(110)).apply { topMargin = dp(6) })
        page.addView(valuesCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        register(s)
    }

    private fun showMetalReading(strength: Float) {
        valueViews.firstOrNull()?.text = "${fmt(strength)} µT"
        graph?.pushHistory(floatArrayOf(strength))
        val (label, hex) = when {
            strength < 75 -> "No metal nearby" to "#22C55E"
            strength < 130 -> "Metal nearby" to "#F59E0B"
            else -> "Strong metal or magnet!" to "#EF4444"
        }
        headline?.text = label
        headline?.setTextColor(Color.parseColor(hex))
        visual?.apply {
            accent = Color.parseColor(hex)
            values = floatArrayOf(strength)
        }
    }

    // ------------------------------------------------------------------
    // Multi-touch
    // ------------------------------------------------------------------

    private fun startTouchTest() {
        val page = openTestPage("Multi-touch screen", "Touchscreen", TOUCH_WHAT, TOUCH_HOW, scroll = false)
        val stats = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            text = "Fingers: 0 · Max: 0"
        }
        val touch = TouchTestView(this).apply {
            onStats = { now, max -> stats.text = "Fingers: $now · Max: $max" }
        }
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stats, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(headerButton(R.drawable.ic_trash, "Clear") { touch.clear() }, LinearLayout.LayoutParams(dp(38), dp(34)))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        page.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0B0F16"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            clipToOutline = true
            addView(touch, FrameLayout.LayoutParams(MATCH, MATCH))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
    }

    // ------------------------------------------------------------------
    // Vibration
    // ------------------------------------------------------------------

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

    private fun startVibrationTest() {
        val vib = vibrator() ?: return
        val page = openTestPage("Vibration motor", "Haptics", VIBRATION_WHAT, VIBRATION_HOW)
        fun effect(e: VibrationEffect) { vib.cancel(); vib.vibrate(e) }
        fun oneShot(ms: Long, amp: Int = VibrationEffect.DEFAULT_AMPLITUDE) = effect(VibrationEffect.createOneShot(ms, amp))

        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            buttons += "Tick" to { effect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
            buttons += "Click" to { effect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) }
            buttons += "Heavy click" to { effect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }
            buttons += "Double click" to { effect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) }
        }
        buttons += "0.5 second" to { oneShot(500) }
        buttons += "2 seconds" to { oneShot(2000) }
        buttons += "Heartbeat" to { effect(VibrationEffect.createWaveform(longArrayOf(0, 80, 120, 80, 600, 80, 120, 80), -1)) }
        buttons += "SOS" to {
            val dot = 120L; val dash = 360L; val gap = 120L; val letter = 360L
            effect(VibrationEffect.createWaveform(longArrayOf(0, dot, gap, dot, gap, dot, letter, dash, gap, dash, gap, dash,
                letter, dot, gap, dot, gap, dot), -1))
        }
        if (vib.hasAmplitudeControl()) {
            buttons += "Weak" to { oneShot(600, 40) }
            buttons += "Medium" to { oneShot(600, 128) }
            buttons += "Strong" to { oneShot(600, 255) }
            buttons += "Ramp up" to {
                effect(VibrationEffect.createWaveform(LongArray(10) { 120L }, IntArray(10) { (it + 1) * 25 }, -1))
            }
        }
        buttons += "Stop" to { vib.cancel() }

        val grid = titledCard("EFFECTS", R.drawable.ic_activity, accent)
        buttons.chunked(2).forEach { pair ->
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { i, (label, action) ->
                    addView(TextView(context).apply {
                        text = label
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setTextColor(if (label == "Stop") Color.parseColor("#F87171") else Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(Color.parseColor("#1E293B"))
                        }
                        setOnClickListener { action() }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i == 1) marginStart = dp(8) })
                }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        page.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        val info = titledCard("MOTOR", R.drawable.ic_info, accent)
        info.infoRow("Strength control", if (vib.hasAmplitudeControl()) "Yes" else "No (fixed strength)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rich = vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            info.infoRow("Rich haptics", if (rich) "Yes" else "No")
        }
        page.addView(info, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    // ------------------------------------------------------------------
    // Display colours
    // ------------------------------------------------------------------

    private var displayOverlay: View? = null

    private fun startDisplayTest() {
        listScroll?.let { listScrollY = it.scrollY }
        inTest = true
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK,
            Color.rgb(128, 128, 128), Color.CYAN, Color.MAGENTA, Color.YELLOW)
        var index = 0
        val hint = TextView(this).apply {
            text = "Tap for the next colour · Back to exit"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(170, 0, 0, 0))
            }
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(colors[0])
            addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            setOnClickListener {
                index = (index + 1) % colors.size
                setBackgroundColor(colors[index])
                hint.visibility = View.GONE
            }
        }
        (window.decorView as ViewGroup).addView(overlay, ViewGroup.LayoutParams(MATCH, MATCH))
        displayOverlay = overlay
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(400) }, 2500)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun closeDisplayTest() {
        val overlay = displayOverlay ?: return
        (window.decorView as ViewGroup).removeView(overlay)
        displayOverlay = null
        if (!fullscreen) WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------------
    // Sensor descriptions
    // ------------------------------------------------------------------

    /** Plain-language explanation of a sensor and a quick way to see it react. */
    private fun describe(type: Int): Pair<String, String> = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Measures acceleration along 3 axes, including gravity. Used for auto-rotate, " +
            "step counting, shake gestures and games." to "lay the phone flat and tilt it; the bubble moves like a spirit level."
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "The raw accelerometer reading before the phone applies its bias correction." to
            "move the phone and compare with the calibrated accelerometer."
        Sensor.TYPE_GYROSCOPE -> "Measures how fast the phone rotates around each axis. Used for 360° photos, " +
            "VR, image stabilization and motion-controlled games." to "rotate the phone; values spike while turning and return to 0 when still."
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "The raw gyroscope reading without drift compensation." to
            "keep the phone still; any non-zero value is drift."
        Sensor.TYPE_MAGNETIC_FIELD -> "Measures the Earth's magnetic field. Combined with the accelerometer it " +
            "works as a digital compass for maps and navigation." to "hold the phone flat and turn around; move it in a figure 8 to calibrate."
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "The raw magnetic field reading without hard-iron calibration." to
            "bring a magnet or speaker near the phone and watch the values jump."
        Sensor.TYPE_LIGHT -> "Measures how bright the surroundings are. The phone uses it for automatic screen brightness." to
            "cover the top of the phone, then point it at a lamp."
        Sensor.TYPE_PROXIMITY -> "Detects when something is close to the top of the screen. It turns the screen off " +
            "during calls so your ear doesn't press buttons." to "cover the area near the front camera with your hand."
        Sensor.TYPE_PRESSURE -> "A barometer measuring air pressure. Used for faster GPS altitude, counting floors " +
            "climbed and weather trends." to "carry the phone up or down stairs; pressure drops as you go up."
        Sensor.TYPE_GRAVITY -> "Shows only the direction and strength of gravity, calculated from the motion sensors." to
            "tilt the phone; the total always stays about 9.8 m/s²."
        Sensor.TYPE_LINEAR_ACCELERATION -> "Acceleration with gravity removed, i.e. only the movement you make. " +
            "Used for gesture and motion detection." to "hold still (≈ 0), then push the phone quickly sideways."
        Sensor.TYPE_ROTATION_VECTOR -> "The phone's orientation in space, fused from accelerometer, gyroscope and compass. " +
            "Used by AR apps, maps and sky viewers." to "rotate the phone slowly in every direction."
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Orientation like the rotation vector, but without the compass, so it is not " +
            "disturbed by magnets. Used by games and VR." to "rotate the phone; it tracks smoothly even near metal."
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Low-power orientation from accelerometer and compass only (no gyroscope)." to
            "turn the phone slowly and watch the values change."
        Sensor.TYPE_STEP_COUNTER -> "Counts steps since the phone last restarted, in low-power hardware. Used by " +
            "fitness and health apps." to "walk a few steps with the phone; the count goes up after a short delay."
        Sensor.TYPE_STEP_DETECTOR -> "Fires an event every time a step is detected." to "walk a few steps with the phone in your hand."
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Wakes the phone when a big change in location is likely, like starting to walk " +
            "or drive. Fires once, then turns off." to "walk around for a while; it triggers only once."
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Measures the air temperature around the phone." to "breathe near the phone or go outside."
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Measures the humidity of the surrounding air." to "breathe on the phone; humidity rises."
        Sensor.TYPE_HEART_RATE -> "Measures your pulse, usually through a sensor on the back." to "rest a finger on the sensor and stay still."
        Sensor.TYPE_ORIENTATION -> "Legacy orientation (azimuth, pitch, roll) kept for old apps." to "rotate the phone in each direction."
        Sensor.TYPE_HINGE_ANGLE -> "Measures the angle between the two halves of a foldable phone." to "open and close the phone."
        Sensor.TYPE_MOTION_DETECT -> "Reports when the phone has been moving for a few seconds." to "pick up the phone and move it around."
        Sensor.TYPE_STATIONARY_DETECT -> "Reports when the phone has been completely still for a few seconds." to
            "put the phone on a table and don't touch it."
        Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> "Detects whether the device is being worn or held." to "pick up and put down the phone."
        else -> "A sensor specific to this phone model, often used internally by the system (tilt, pickup, " +
            "screen-orientation or gesture detection)." to "move, tilt or pick up the phone and watch whether the values change."
    }

    private fun modeFor(type: Int) = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY -> SensorVisualView.Mode.LEVEL
        Sensor.TYPE_MAGNETIC_FIELD -> SensorVisualView.Mode.COMPASS
        Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE, Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_HEART_RATE -> SensorVisualView.Mode.GAUGE
        Sensor.TYPE_PROXIMITY -> SensorVisualView.Mode.PROXIMITY
        else -> SensorVisualView.Mode.GRAPH
    }

    private fun priority(type: Int) = when (type) {
        Sensor.TYPE_ACCELEROMETER -> 0; Sensor.TYPE_GYROSCOPE -> 1; Sensor.TYPE_MAGNETIC_FIELD -> 2
        Sensor.TYPE_LIGHT -> 3; Sensor.TYPE_PROXIMITY -> 4; Sensor.TYPE_PRESSURE -> 5
        Sensor.TYPE_GRAVITY -> 6; Sensor.TYPE_LINEAR_ACCELERATION -> 7; Sensor.TYPE_ROTATION_VECTOR -> 8
        Sensor.TYPE_STEP_COUNTER -> 9; else -> 20
    }

    private fun typeName(s: Sensor): String = when (s.type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer (compass)"
        Sensor.TYPE_LIGHT -> "Light sensor"
        Sensor.TYPE_PROXIMITY -> "Proximity sensor"
        Sensor.TYPE_PRESSURE -> "Barometer"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation"
        Sensor.TYPE_STEP_COUNTER -> "Step counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step detector"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Thermometer"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Humidity"
        Sensor.TYPE_HEART_RATE -> "Heart rate"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
        else -> s.stringType.substringAfterLast('.').replace('_', ' ').replaceFirstChar(Char::uppercase)
            .ifBlank { s.name }
    }

    private fun unitFor(type: Int) = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> "m/s²"
        Sensor.TYPE_GYROSCOPE -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD -> "µT"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_HEART_RATE -> "bpm"
        Sensor.TYPE_STEP_COUNTER -> "steps"
        else -> ""
    }

    private fun axisNames(type: Int): List<String> = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD -> listOf("X", "Y", "Z")
        Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ->
            listOf("X", "Y", "Z", "Scalar")
        Sensor.TYPE_LIGHT -> listOf("Illuminance")
        Sensor.TYPE_PROXIMITY -> listOf("Distance")
        Sensor.TYPE_PRESSURE -> listOf("Pressure")
        Sensor.TYPE_STEP_COUNTER -> listOf("Steps")
        else -> listOf("Value 1", "Value 2", "Value 3")
    }

    private fun iconFor(type: Int) = when (type) {
        Sensor.TYPE_MAGNETIC_FIELD -> R.drawable.ic_compass
        Sensor.TYPE_LIGHT -> R.drawable.ic_sun
        Sensor.TYPE_PROXIMITY -> R.drawable.ic_hand
        Sensor.TYPE_PRESSURE -> R.drawable.ic_gauge
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> R.drawable.ic_rotate_3d
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> R.drawable.ic_steps
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> R.drawable.ic_activity
        else -> R.drawable.ic_radar
    }

    private fun lightDescription(lux: Float) = when {
        lux < 10 -> "Dark"
        lux < 200 -> "Dim indoor light"
        lux < 1000 -> "Bright indoor light"
        lux < 10_000 -> "Overcast daylight"
        else -> "Direct sunlight"
    }

    /** Barometric altitude from standard sea-level pressure. */
    private fun altitude(hPa: Float) = 44330.0 * (1.0 - (hPa / 1013.25).toDouble().pow(1 / 5.255))

    private fun fmt(v: Float) = when {
        v == 0f -> "0"
        kotlin.math.abs(v) >= 1000 -> String.format(Locale.US, "%.0f", v)
        kotlin.math.abs(v) >= 10 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.3f", v)
    }
}
