package com.truckcontroller.pro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.charging.ChargingAnimation
import com.truckcontroller.pro.dimmer.NightScreen
import com.truckcontroller.pro.input.InputMode
import com.truckcontroller.pro.speed.SpeedTracker
import com.truckcontroller.pro.transfer.FileTransfer
import com.truckcontroller.pro.transfer.LiveShare
import com.truckcontroller.pro.ui.ActionTile
import java.util.Locale

/**
 * Landing page: a searchable hub of every PhoneDeck tool, grouped into Game Control,
 * Keyboard & Mouse, Phone Tools, Utilities and Root Tools.
 */
class HomeActivity : ToolActivity() {

    companion object {
        /** Set by the Quick Settings tile when Night Screen needs the app (e.g. for permissions). */
        const val EXTRA_OPEN_NIGHT_SCREEN = "open_night_screen"
    }

    private enum class Category(val label: String) {
        GAME("GAME CONTROL"), INPUT("KEYBOARD & MOUSE"), PHONE("PHONE TOOLS"), UTILITY("UTILITIES"), ROOT("ROOT TOOLS"),
    }

    private class Tool(
        val title: String,
        val subtitle: String,
        @DrawableRes val icon: Int,
        val accent: Int,
        val category: Category,
        val keywords: String,
        /** Live subtitle / active state (e.g. Night Screen on, trip recording). */
        val live: (() -> Pair<String, Boolean>)? = null,
        val open: () -> Unit,
    )

    private lateinit var tools: List<Tool>
    private lateinit var results: LinearLayout
    private lateinit var search: EditText
    private lateinit var clearButton: ImageButton
    private var nightDialog: AlertDialog? = null

    // Returning from the "Display over other apps" settings screen
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (NightScreen.canDrawOverlays(this)) startNightScreen()
            else Toast.makeText(this, "Night Screen needs \"Display over other apps\"", Toast.LENGTH_LONG).show()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Allow notifications to control the dimmer from the notification panel", Toast.LENGTH_LONG).show()
            }
            startNightScreen(askNotifications = false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tools = buildTools()
        setContentView(buildScreen())
        render()
        if (intent.getBooleanExtra(EXTRA_OPEN_NIGHT_SCREEN, false)) showNightScreenDialog()
    }

    override fun onResume() {
        super.onResume()
        // Refresh live tiles (Night Screen on/off, trip recording)
        if (::results.isInitialized) render()
    }

    // ------------------------------------------------------------------
    // Tool registry
    // ------------------------------------------------------------------

    private fun go(cls: Class<*>) = { startActivity(Intent(this, cls)) }

    private fun buildTools(): List<Tool> {
        val list = mutableListOf(
            Tool("ETS2 Truck Controller", "Wheel, pedals, shifter, cockpit", R.drawable.ic_truck, color(R.color.accent),
                Category.GAME, "game euro truck simulator ets2 steering wheel pedals gamepad joystick controller bluetooth pc",
                open = go(MainActivity::class.java)),
        )
        InputMode.entries.forEach { mode ->
            list += Tool(mode.title, mode.subtitle, mode.icon, color(mode.accent), Category.INPUT,
                "keyboard mouse touchpad trackpad bluetooth pc laptop computer remote typing " + when (mode) {
                    InputMode.NUMPAD_TOUCHPAD -> "numpad numeric calculator"
                    InputMode.KEYBOARD_FULL -> "full 104 function keys numpad"
                    InputMode.PRESENTATION -> "presentation slides powerpoint clicker laser pointer timer " +
                        "air mouse gyroscope gyro point motion remote tv"
                    InputMode.TYPE_TEXT -> "type text paste clipboard password code send text autotype typing"
                    else -> ""
                }, open = { startActivity(InputActivity.intent(this, mode)) })
        }
        list += listOf(
            Tool("Device Info", "Model, CPU, RAM, display", R.drawable.ic_smartphone, Color.parseColor("#60A5FA"),
                Category.PHONE, "device information phone specs hardware cpu processor ram memory android version display camera",
                open = go(DeviceInfoActivity::class.java)),
            Tool("Battery Info", "Health, capacity, wear", R.drawable.ic_battery, Color.parseColor("#22C55E"),
                Category.PHONE, "battery information health capacity cycle temperature voltage wear",
                open = go(BatteryInfoActivity::class.java)),
            Tool("Charging Meter", "Live charging current", R.drawable.ic_battery_charging, Color.parseColor("#1FB39A"),
                Category.PHONE, "charging current ampere ampere meter mA charger cable power watt",
                open = go(BatteryActivity::class.java)),
            Tool("Storage Analyzer", "Space, big files, apps", R.drawable.ic_storage, Color.parseColor("#F472B6"),
                Category.PHONE, "storage space memory disk files cleanup large files apps cache",
                open = go(StorageActivity::class.java)),
            Tool("Sensor Tester", "Sensors, radios & hardware", R.drawable.ic_activity, Color.parseColor("#2DD4BF"),
                Category.PHONE, "sensors accelerometer gyroscope compass magnetometer proximity light barometer level " +
                    "gps gnss satellite wifi wi-fi bluetooth nfc mobile network signal sim 5g 4g camera flashlight torch " +
                    "fingerprint face biometric microphone mic speaker earpiece headphone audio buttons volume power " +
                    "infrared ir remote vibration touch screen display dead pixel metal detector hardware test",
                open = go(SensorTesterActivity::class.java)),
            Tool("Screen Time", "Usage by app and hour", R.drawable.ic_hourglass, Color.parseColor("#818CF8"),
                Category.PHONE, "screen time usage apps digital wellbeing unlocks phone addiction",
                open = go(ScreenTimeActivity::class.java)),
            Tool("Speedometer", "GPS speed & trips", R.drawable.ic_gauge, color(R.color.emerald_400),
                Category.UTILITY, "speed speedometer gps trip distance km mph drive",
                live = {
                    SpeedTracker.load(this)
                    when (SpeedTracker.tripState) {
                        SpeedTracker.TripState.RUNNING ->
                            "Recording · ${SpeedTracker.formatDistance(SpeedTracker.distanceM)} ${SpeedTracker.distanceUnit}" to true
                        SpeedTracker.TripState.PAUSED -> "Trip paused" to true
                        SpeedTracker.TripState.IDLE -> "GPS speed & trips" to false
                    }
                },
                open = go(SpeedometerActivity::class.java)),
            Tool("Night Screen", "Screen dimmer", R.drawable.ic_moon, color(R.color.amber_400),
                Category.UTILITY, "night screen dimmer dark brightness filter blue light warm",
                live = {
                    if (NightScreen.isRunning) "On · ${NightScreen.level(this)} %" to true else "Screen dimmer" to false
                },
                open = { showNightScreenDialog() }),
            Tool("File Transfer", "Phone ⇄ PCs over Wi-Fi", R.drawable.ic_transfer, Color.parseColor("#38BDF8"),
                Category.UTILITY, "file transfer share send receive copy wifi wi-fi hotspot pc laptop computer browser " +
                    "upload download photos videos music documents folder zip wireless laptop to laptop text clipboard",
                live = {
                    if (FileTransfer.isRunning) {
                        val ip = FileTransfer.addresses().firstOrNull()?.ip
                        (if (ip != null) "On · $ip" else "On · no Wi-Fi") to true
                    } else "Phone ⇄ PCs over Wi-Fi" to false
                },
                open = go(FileTransferActivity::class.java)),
            Tool("Live View", "Camera & screen in a PC browser", R.drawable.ic_eye, Color.parseColor("#FB7185"),
                Category.UTILITY, "live view webcam camera security cam cctv baby monitor document camera stream " +
                    "screen mirror mirroring cast share screen phone screen on pc laptop browser wifi presentation demo",
                live = {
                    val what = listOfNotNull(
                        "camera".takeIf { LiveShare.camera.on },
                        "screen".takeIf { LiveShare.screen.on },
                    )
                    if (what.isEmpty()) "Camera & screen in a PC browser" to false
                    else "Live · ${what.joinToString(" + ")} · ${LiveShare.camera.viewerCount + LiveShare.screen.viewerCount} watching" to true
                },
                open = { startActivity(FileTransferActivity.live(this)) }),
            Tool("Scanner", "QR codes & barcodes", R.drawable.ic_qr, color(R.color.cyan_400),
                Category.UTILITY, "scanner scan qr code barcode camera wifi link",
                open = go(ScannerActivity::class.java)),
            Tool("QR & Barcode Maker", "Custom designs", R.drawable.ic_grid, color(R.color.accent),
                Category.UTILITY, "qr code generator barcode maker create wifi contact design ean",
                open = go(QrGeneratorActivity::class.java)),
            Tool("Charging Animation", "Lock screen, needs LSPosed", R.drawable.ic_battery_charging, Color.parseColor("#A78BFA"),
                Category.ROOT, "root rooted lsposed xposed magisk charging animation lock screen charger plug hyperos miui",
                live = {
                    if (ChargingAnimation.isModuleActive() && ChargingAnimation.isEnabled(this)) {
                        ChargingAnimation.style(this).title to true
                    } else "Lock screen, needs LSPosed" to false
                },
                open = go(ChargingAnimationActivity::class.java)),
        )
        return list
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildScreen(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.cockpit_bg))

        // Header: logo, name, fullscreen
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(TextView(context).apply {
                text = android.text.SpannableStringBuilder("PhoneDeck").apply {
                    setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#2DD4BF")), 5, 9, 0)
                }
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
            val fullscreenButton = ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon)
            addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)))
            bindFullscreenButton(fullscreenButton) { haptics.performButtonClickHaptic() }
        }, LinearLayout.LayoutParams(MATCH, dp(56)))

        // Search box
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#151A24"))
                setStroke(dp(1), Color.parseColor("#2A3445"))
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_search)
                setColorFilter(color(R.color.slate_400))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            search = EditText(context).apply {
                hint = "Search tools"
                setHintTextColor(color(R.color.slate_500))
                setTextColor(Color.WHITE)
                textSize = 15f
                background = null
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = render()
                })
                setOnEditorActionListener { v, _, _ ->
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                    true
                }
            }
            addView(search, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
            clearButton = ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(color(R.color.slate_400))
                background = null
                contentDescription = "Clear search"
                visibility = View.GONE
                setOnClickListener { search.setText("") }
            }
            addView(clearButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        results = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        addView(ScrollView(context).apply { addView(results) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun render() {
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        clearButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        results.removeAllViews()
        if (query.isEmpty()) {
            Category.entries.forEach { category ->
                val items = tools.filter { it.category == category }
                results.addView(sectionHeader(category.label, items.size))
                results.addView(grid(items))
            }
        } else {
            val words = query.split(' ').filter { it.isNotBlank() }
            val matches = tools.filter { tool ->
                val haystack = "${tool.title} ${tool.subtitle} ${tool.keywords} ${tool.category.label}".lowercase(Locale.getDefault())
                words.all { haystack.contains(it) }
            }
            results.addView(sectionHeader(if (matches.isEmpty()) "NO TOOLS FOUND" else "RESULTS", matches.size))
            if (matches.isEmpty()) {
                results.addView(TextView(this).apply {
                    text = "Try words like \"keyboard\", \"battery\", \"compass\" or \"qr\"."
                    setTextColor(color(R.color.slate_400))
                    textSize = 14f
                    setPadding(0, dp(8), 0, 0)
                })
            } else {
                results.addView(grid(matches))
            }
        }
    }

    private fun sectionHeader(label: String, count: Int) = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        text = if (count > 0) "$label · $count" else label
        textSize = 11f
        letterSpacing = 0.2f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.slate_500))
        setPadding(0, dp(18), 0, dp(8))
    }

    /** Tiles in rows: 2 per row in portrait, 4 in landscape; short rows keep equal widths. */
    private fun grid(items: List<Tool>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 2 else 4
        items.chunked(columns).forEachIndexed { r, chunk ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                chunk.forEachIndexed { c, tool ->
                    addView(tile(tool), LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (c > 0) marginStart = dp(10) })
                }
                repeat(columns - chunk.size) {
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
                }
            }, LinearLayout.LayoutParams(MATCH, dp(74)).apply { if (r > 0) topMargin = dp(10) })
        }
    }

    private fun tile(tool: Tool) = ActionTile(this).apply {
        val live = tool.live?.invoke()
        title = tool.title
        subtitle = live?.first ?: tool.subtitle
        isActive = live?.second ?: false
        setIcon(tool.icon)
        accentColor = tool.accent
        contentDescription = tool.title
        setOnClickListener {
            haptics.performButtonClickHaptic()
            tool.open()
        }
    }

    // ------------------------------------------------------------------
    // Night Screen
    // ------------------------------------------------------------------

    private fun showNightScreenDialog() {
        if (nightDialog?.isShowing == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        content.addView(TextView(this).apply {
            text = "Dims the whole screen below the lowest system brightness. " +
                "Adjust it any time from the notification panel or the Night Screen Quick Settings tile."
            setTextColor(color(R.color.slate_400))
            textSize = 12f
        })
        val levelLabel = TextView(this).apply {
            setTextColor(color(R.color.slate_300))
            textSize = 14f
            setPadding(0, dp(14), 0, dp(2))
        }
        fun renderLevel(level: Int) {
            levelLabel.text = "Dimness: $level %"
        }
        renderLevel(NightScreen.level(this))
        content.addView(levelLabel)
        content.addView(SeekBar(this).apply {
            max = (NightScreen.MAX_LEVEL - NightScreen.MIN_LEVEL) / 5
            progress = (NightScreen.level(context) - NightScreen.MIN_LEVEL) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val level = NightScreen.MIN_LEVEL + p * 5
                    renderLevel(level)
                    if (fromUser) NightScreen.setLevel(this@HomeActivity, level)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = render()
            })
        })
        content.addView(SwitchMaterial(this).apply {
            text = "Warm tint (reduce blue light)"
            isChecked = NightScreen.isWarm(context)
            setTextColor(color(R.color.slate_300))
            setOnCheckedChangeListener { _, checked -> NightScreen.setWarm(this@HomeActivity, checked) }
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Night Screen")
            .setView(content)
            .setPositiveButton(if (NightScreen.isRunning) "Turn off" else "Turn on", null)
            .setNegativeButton("Close", null)
            .setOnDismissListener { render() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            if (NightScreen.isRunning) {
                NightScreen.stop(this)
                (button as TextView).text = "Turn on"
            } else {
                startNightScreen()
                (button as TextView).text = "Turn off"
            }
            button.postDelayed({ render() }, 300)
        }
        nightDialog = dialog
    }

    /** Checks the overlay (and, on Android 13+, notification) permission, then starts the dimmer. */
    private fun startNightScreen(askNotifications: Boolean = true) {
        if (!NightScreen.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow display over other apps")
                .setMessage("Night Screen draws a dark layer above all apps. On the next screen, turn on " +
                    "\"Allow display over other apps\" for PhoneDeck, then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    overlayPermissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (askNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        NightScreen.start(this)
        window.decorView.postDelayed({ render() }, 300)
    }
}
