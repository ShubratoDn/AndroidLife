package com.truckcontroller.pro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.bluetooth.BluetoothHidService
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.input.Hid
import com.truckcontroller.pro.input.InputMode
import com.truckcontroller.pro.input.InputSettings
import com.truckcontroller.pro.input.InputSettingsStore
import com.truckcontroller.pro.input.KeyboardLayout
import com.truckcontroller.pro.input.KeyboardLayouts
import com.truckcontroller.pro.input.KeyboardView
import com.truckcontroller.pro.input.TouchpadView
import com.truckcontroller.pro.ui.ActionTile
import java.util.Locale

/**
 * Keyboard & mouse screens: keyboard + touchpad, keyboard, num pad + touchpad,
 * complete keyboard and presentation remote.
 */
class InputActivity : HidActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"

        fun intent(context: Context, mode: InputMode): Intent =
            Intent(context, InputActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }

    private lateinit var mode: InputMode
    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var settingsStore: InputSettingsStore
    private var settings = InputSettings()
    private val handler = Handler(Looper.getMainLooper())

    private val keyboards = mutableListOf<KeyboardView>()
    /** Latest state of each keyboard panel; merged so split panels act as one keyboard. */
    private val panelStates = HashMap<KeyboardView, Pair<Int, List<Int>>>()
    private val touchpads = mutableListOf<TouchpadView>()

    private lateinit var connectionPill: LinearLayout
    private lateinit var connectionDot: View
    private lateinit var tvConnection: TextView

    // Presentation timer
    private var timerRunning = false
    private var timerElapsed = 0L
    private var timerStartedAt = 0L
    private var tvTimer: TextView? = null
    private var btnTimer: ActionTile? = null

    private enum class RotationLock(val label: String) { AUTO("Auto-rotate"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }

    private val orientationPrefs by lazy { getSharedPreferences("input_orientation", MODE_PRIVATE) }
    private var rotationLock = RotationLock.AUTO
    private var builtOrientation = Configuration.ORIENTATION_UNDEFINED
    private val isPortrait get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private val ledListener: (Int) -> Unit = { leds -> runOnUiThread { keyboards.forEach { it.leds = leds } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = runCatching { InputMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }
            .getOrDefault(InputMode.KEYBOARD_TOUCHPAD)
        // The standard keyboard is 15 keys wide, so it opens in landscape unless the user chose otherwise
        val defaultLock = if (mode == InputMode.KEYBOARD) RotationLock.LANDSCAPE else RotationLock.AUTO
        rotationLock = runCatching {
            RotationLock.valueOf(orientationPrefs.getString(mode.name, null) ?: defaultLock.name)
        }.getOrDefault(defaultLock)
        applyRotationLock()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_input)

        haptics = HapticFeedbackHelper(this)
        settingsStore = InputSettingsStore(this)
        settings = settingsStore.load()

        initHeader()
        buildContent()
        applySettings(settings, persist = false)
        setFullscreen(true)
        ensureBluetooth()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != builtOrientation) {
            // Release anything held on the old layout, then rebuild for the new orientation
            keyboards.forEach { it.reset() }
            touchpads.forEach { it.reset() }
            hidService.releaseAllInput()
            buildContent()
            applySettings(settings, persist = false)
        }
    }

    private fun applyRotationLock() {
        requestedOrientation = when (rotationLock) {
            RotationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            RotationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            RotationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** Header button: Auto-rotate -> Portrait -> Landscape. */
    private fun cycleRotationLock() {
        rotationLock = RotationLock.entries[(rotationLock.ordinal + 1) % RotationLock.entries.size]
        orientationPrefs.edit().putString(mode.name, rotationLock.name).apply()
        applyRotationLock()
        Toast.makeText(this, rotationLock.label, Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        hidService.addLedListener(ledListener)
        keyboards.forEach { it.leds = hidService.keyboardLeds }
    }

    override fun onStop() {
        super.onStop()
        hidService.removeLedListener(ledListener)
    }

    override fun onPause() {
        super.onPause()
        // Never leave a key or mouse button held down on the PC while this screen is hidden
        keyboards.forEach { it.reset() }
        touchpads.forEach { it.reset() }
        hidService.releaseAllInput()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onConnectionChanged() {
        if (::connectionPill.isInitialized) renderConnectionPill(connectionPill, connectionDot, tvConnection)
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    private fun initHeader() {
        connectionPill = findViewById(R.id.connectionPill)
        connectionDot = findViewById(R.id.connectionDot)
        tvConnection = findViewById(R.id.tvConnection)
        findViewById<TextView>(R.id.tvTitle).text = mode.title
        findViewById<ImageView>(R.id.ivModeIcon).apply {
            setImageResource(mode.icon)
            setColorFilter(color(mode.accent))
        }
        findViewById<View>(R.id.btnHome).setOnClickListener { click(); finish() }
        findViewById<View>(R.id.btnBluetooth).setOnClickListener { click(); openBluetoothDialog() }
        connectionPill.setOnClickListener { click(); openBluetoothDialog() }
        findViewById<View>(R.id.btnInputSettings).setOnClickListener { click(); showSettingsDialog() }
        findViewById<View>(R.id.btnRotate).setOnClickListener { click(); cycleRotationLock() }
    }

    /** Portrait headers are narrow: hide the title and move the media keys into the content. */
    private fun renderHeader() {
        val portrait = isPortrait
        findViewById<TextView>(R.id.tvTitle).visibility = if (portrait) View.GONE else View.VISIBLE
        tvConnection.maxWidth = dp(if (portrait) 110 else 150)
        val strip = findViewById<LinearLayout>(R.id.mediaStrip)
        strip.removeAllViews()
        val showInHeader = !portrait && mode != InputMode.PRESENTATION
        strip.visibility = if (showInHeader) View.VISIBLE else View.GONE
        if (showInHeader) buildMediaStrip(strip, weighted = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMediaStrip(strip: LinearLayout, weighted: Boolean) {
        listOf(
            R.drawable.ic_volume_x to Hid.C_MUTE,
            R.drawable.ic_volume_down to Hid.C_VOL_DOWN,
            R.drawable.ic_volume to Hid.C_VOL_UP,
            R.drawable.ic_skip_back to Hid.C_PREV,
            R.drawable.ic_play to Hid.C_PLAY_PAUSE,
            R.drawable.ic_skip_forward to Hid.C_NEXT,
        ).forEach { (icon, usage) ->
            val button = ImageButton(this, null, 0, R.style.Cockpit_HeaderIcon).apply {
                setImageResource(icon)
                setColorFilter(color(R.color.slate_300))
                contentDescription = "Media key"
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            click()
                            hidService.setConsumer(usage)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            hidService.setConsumer(0)
                        }
                    }
                    true
                }
            }
            val params = if (weighted) LinearLayout.LayoutParams(0, MATCH, 1f) else LinearLayout.LayoutParams(dp(34), dp(32))
            strip.addView(button, params.apply { marginStart = dp(if (weighted && strip.childCount == 0) 0 else 4) })
        }
        if (!weighted) strip.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
    }

    // ------------------------------------------------------------------
    // Content per mode
    // ------------------------------------------------------------------

    private fun buildContent() {
        builtOrientation = resources.configuration.orientation
        val content = findViewById<FrameLayout>(R.id.inputContent)
        content.removeAllViews()
        keyboards.clear()
        panelStates.clear()
        touchpads.clear()
        renderHeader()
        val view = if (isPortrait) portraitContent() else landscapeContent()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        renderTimer()
    }

    /** Landscape: keys and touchpad side by side. */
    private fun landscapeContent(): View = when (mode) {
        InputMode.KEYBOARD_TOUCHPAD -> row(
            keyboard(KeyboardLayouts.COMPACT) to 1.6f,
            touchpadPanel() to 1f,
        )
        InputMode.KEYBOARD -> keyboard(KeyboardLayouts.STANDARD)
        InputMode.NUMPAD_TOUCHPAD -> row(
            keyboard(KeyboardLayouts.NUMPAD) to 0.7f,
            touchpadPanel() to 1.5f,
        )
        InputMode.KEYBOARD_FULL -> keyboard(KeyboardLayouts.FULL)
        InputMode.PRESENTATION -> presentationRemote(portrait = false)
    }

    /** Portrait: touchpad above the keys, media keys as their own row. */
    private fun portraitContent(): View = when (mode) {
        InputMode.KEYBOARD_TOUCHPAD -> column(
            mediaRow() to 0f,
            resizableSplit(touchpadPanel(), keyboard(KeyboardLayouts.COMPACT), defaultKeyboardShare = 0.53f) to 1f,
        )
        InputMode.KEYBOARD -> column(mediaRow() to 0f, keyboard(KeyboardLayouts.PORTRAIT_STANDARD) to 1f)
        InputMode.NUMPAD_TOUCHPAD -> column(
            mediaRow() to 0f,
            resizableSplit(touchpadPanel(), keyboard(KeyboardLayouts.NUMPAD), defaultKeyboardShare = 0.45f) to 1f,
        )
        InputMode.KEYBOARD_FULL -> column(
            mediaRow() to 0f,
            resizableSplit(
                keyboard(KeyboardLayouts.PORTRAIT_NAVPAD),
                keyboard(KeyboardLayouts.PORTRAIT_MAIN),
                defaultKeyboardShare = 0.6f,
            ) to 1f,
        )
        InputMode.PRESENTATION -> presentationRemote(portrait = true)
    }

    /**
     * Touchpad above, keys below, with a drag handle between them to set the keyboard height.
     * The chosen height is remembered per mode.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun resizableSplit(top: View, bottom: View, defaultKeyboardShare: Float): LinearLayout {
        val prefKey = "keyboard_share_${mode.name}"
        var share = orientationPrefs.getFloat(prefKey, defaultKeyboardShare)
        val topParams = LinearLayout.LayoutParams(MATCH, 0, 1f - share)
        val bottomParams = LinearLayout.LayoutParams(MATCH, 0, share)
        val grip = View(this).apply { setBackgroundResource(R.drawable.bg_split_grip) }
        val handle = FrameLayout(this).apply {
            contentDescription = "Drag to resize the keyboard"
            addView(grip, FrameLayout.LayoutParams(dp(56), dp(5), Gravity.CENTER))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, topParams)
            addView(handle, LinearLayout.LayoutParams(MATCH, dp(26)))
            addView(bottom, bottomParams)
        }
        val location = IntArray(2)
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    grip.isActivated = true
                    click()
                }
                MotionEvent.ACTION_MOVE -> {
                    container.getLocationOnScreen(location)
                    val available = (container.height - v.height).coerceAtLeast(1)
                    val handleCenter = e.rawY - location[1]
                    share = (1f - (handleCenter - v.height / 2f) / available).coerceIn(0.25f, 0.8f)
                    topParams.weight = 1f - share
                    bottomParams.weight = share
                    container.requestLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    grip.isActivated = false
                    orientationPrefs.edit().putFloat(prefKey, share).apply()
                }
            }
            true
        }
        return container
    }

    private fun mediaRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buildMediaStrip(this, weighted = true)
    }

    /** Vertical stack; weight 0 means the fixed 40dp media row. */
    private fun column(vararg children: Pair<View, Float>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEachIndexed { i, (child, weight) ->
            val params = if (weight == 0f) LinearLayout.LayoutParams(MATCH, dp(40)) else LinearLayout.LayoutParams(MATCH, 0, weight)
            addView(child, params.apply { if (i > 0) topMargin = dp(10) })
        }
    }

    private fun row(vararg children: Pair<View, Float>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEachIndexed { i, (child, weight) ->
            addView(child, LinearLayout.LayoutParams(0, MATCH, weight).apply {
                if (i > 0) marginStart = dp(10)
            })
        }
    }

    private fun keyboard(layout: KeyboardLayout): KeyboardView = KeyboardView(this).apply {
        this.layout = layout
        leds = hidService.keyboardLeds
        val panel = this
        onStateChanged = { mods, keys ->
            panelStates[panel] = mods to keys
            var allMods = 0
            val allKeys = LinkedHashSet<Int>()
            panelStates.values.forEach { (m, k) -> allMods = allMods or m; allKeys += k }
            hidService.sendKeyboard(allMods, allKeys.toList())
        }
        onNormalKey = { down ->
            keyboards.filter { it !== panel }.forEach { other ->
                // Modifiers on the other panel apply to this key: count held ones as used,
                // and let one-shot ones expire once the key is released
                if (down) other.markHeldModifiersUsed() else other.consumeOneShot()
            }
        }
        onConsumer = { usage -> hidService.setConsumer(usage) }
        onKeyFeedback = { if (settings.keyHaptics) haptics.performButtonClickHaptic() }
        keyboards += this
    }

    private fun touchpad(): TouchpadView = TouchpadView(this).apply {
        onMove = { dx, dy -> hidService.moveMouse(dx, dy) }
        onScroll = { v, h -> hidService.scrollMouse(v, h) }
        onClick = { button ->
            haptics.performButtonClickHaptic()
            hidService.clickMouse(button)
            afterMouseClick()
        }
        onButton = { button, pressed ->
            if (pressed) haptics.performButtonClickHaptic()
            hidService.setMouseButton(button, pressed)
            if (!pressed) afterMouseClick()
        }
        touchpads += this
    }

    /** One-shot modifiers (e.g. Ctrl tapped before a click) apply to that click only. */
    private fun afterMouseClick() {
        handler.postDelayed({ keyboards.forEach { it.consumeOneShot() } }, 150)
    }

    /** Touchpad with physical-style Left / Middle / Right buttons underneath. */
    private fun touchpadPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(touchpad(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(mouseButton("LEFT", BluetoothHidService.MOUSE_LEFT), LinearLayout.LayoutParams(0, MATCH, 1.3f))
            addView(mouseButton("MID", BluetoothHidService.MOUSE_MIDDLE), LinearLayout.LayoutParams(0, MATCH, 0.6f).apply {
                marginStart = dp(6); marginEnd = dp(6)
            })
            addView(mouseButton("RIGHT", BluetoothHidService.MOUSE_RIGHT), LinearLayout.LayoutParams(0, MATCH, 1.3f))
        }
        addView(buttons, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mouseButton(label: String, mask: Int): TextView = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.slate_300))
        setBackgroundResource(R.drawable.bg_step_button)
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptics.performButtonClickHaptic()
                    hidService.setMouseButton(mask, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    hidService.setMouseButton(mask, false)
                    afterMouseClick()
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------
    // Presentation remote
    // ------------------------------------------------------------------

    private fun presentationRemote(portrait: Boolean): View {
        // Timer card
        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(dp(16), dp(6), dp(8), dp(6))
        }
        tvTimer = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
            text = "00:00"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.amber_400))
        }
        timerRow.addView(tvTimer, LinearLayout.LayoutParams(0, WRAP, 1f))
        btnTimer = tile("START", R.drawable.ic_play, R.color.emerald_400) { toggleTimer() }
        timerRow.addView(btnTimer, LinearLayout.LayoutParams(dp(78), dp(64)))
        timerRow.addView(tile("RESET", R.drawable.ic_rotate_ccw, R.color.slate_400) { resetTimer() },
            LinearLayout.LayoutParams(dp(78), dp(64)).apply { marginStart = dp(6) })

        val next = tile("NEXT SLIDE", R.drawable.ic_chevrons_right, R.color.emerald_400, "Page Down") {
            slide(Hid.PAGE_DOWN)
        }.apply { primary = true }
        val previous = tile("PREVIOUS", R.drawable.ic_chevrons_left, R.color.amber_400, "Page Up") { slide(Hid.PAGE_UP) }
        val showRow = tileRow(
            tile("START", R.drawable.ic_play, R.color.cyan_400, "F5") { keys(0, Hid.f(5)) },
            tile("CURRENT", R.drawable.ic_monitor, R.color.cyan_400, "Shift+F5") { keys(Hid.MOD_SHIFT, Hid.f(5)) },
            tile("BLACK", R.drawable.ic_moon, R.color.slate_400, "B") { keys(0, Hid.letter('b')) },
            tile("END", R.drawable.ic_exit, R.color.red_400, "Esc") { keys(0, Hid.ESC) },
        )
        val toolRow = tileRow(
            tile("LASER", R.drawable.ic_pointer, R.color.red_400, "Ctrl+L") { keys(Hid.MOD_CTRL, Hid.letter('l')) },
            tile("ERASE", R.drawable.ic_eraser, R.color.slate_400, "E") { keys(0, Hid.letter('e')) },
            tile("FIRST", R.drawable.ic_first, R.color.slate_400, "Home") { keys(0, Hid.HOME) },
            tile("LAST", R.drawable.ic_last, R.color.slate_400, "End") { keys(0, Hid.END) },
        )
        // Pointer pad for the laser pointer / clicking links
        val pad = touchpad().apply {
            hint = "POINTER"
            showScrollStrip = false
        }
        val hint = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
            text = "Phone volume keys: \u25B2 previous  \u00B7  \u25BC next"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.slate_500))
        }

        fun LinearLayout.add(view: View, weight: Float, gap: Int) = addView(
            view,
            (if (weight == 0f) LinearLayout.LayoutParams(MATCH, WRAP) else LinearLayout.LayoutParams(MATCH, 0, weight))
                .apply { if (childCount > 0) topMargin = dp(gap) }
        )

        if (portrait) {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                add(timerRow, 0f, 0)
                add(next, 3f, 10)
                add(previous, 1.3f, 10)
                add(showRow, 1f, 10)
                add(toolRow, 1f, 8)
                add(pad, 2f, 10)
                add(hint, 0f, 6)
            }
        }
        // Landscape: timer and a big NEXT on the left, everything else on the right
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            add(timerRow, 0f, 0)
            add(next, 1f, 10)
            add(hint, 0f, 6)
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            add(previous, 0.8f, 0)
            add(showRow, 1f, 8)
            add(toolRow, 1f, 8)
            add(pad, 1.3f, 8)
        }
        return row(left to 1f, right to 1.25f)
    }

    private fun tileRow(vararg tiles: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        tiles.forEachIndexed { i, t ->
            addView(t, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun tile(title: String, icon: Int, accent: Int, subtitle: String = "", onClick: () -> Unit) =
        ActionTile(this).apply {
            this.title = title
            this.subtitle = subtitle
            setIcon(icon)
            accentColor = color(accent)
            setOnClickListener {
                haptics.performButtonClickHaptic()
                onClick()
            }
        }

    private fun slide(usage: Int) {
        haptics.performGearShiftHaptic()
        hidService.tapKeys(0, usage)
    }

    private fun keys(modifiers: Int, usage: Int) = hidService.tapKeys(modifiers, usage)

    private val timerTick = object : Runnable {
        override fun run() {
            renderTimer()
            if (timerRunning) handler.postDelayed(this, 500)
        }
    }

    private fun toggleTimer() {
        if (timerRunning) {
            timerElapsed += SystemClock.elapsedRealtime() - timerStartedAt
            timerRunning = false
        } else {
            timerStartedAt = SystemClock.elapsedRealtime()
            timerRunning = true
            handler.post(timerTick)
        }
        renderTimer()
    }

    private fun resetTimer() {
        timerRunning = false
        timerElapsed = 0
        handler.removeCallbacks(timerTick)
        renderTimer()
    }

    private fun renderTimer() {
        val total = timerElapsed + if (timerRunning) SystemClock.elapsedRealtime() - timerStartedAt else 0
        val seconds = total / 1000
        tvTimer?.text = if (seconds >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        }
        btnTimer?.apply {
            title = if (timerRunning) "PAUSE" else "START"
            setIcon(if (timerRunning) R.drawable.ic_pause else R.drawable.ic_play)
            isActive = timerRunning
        }
    }

    /** In presentation mode the phone's volume keys change slides. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (mode == InputMode.PRESENTATION && settings.volumeKeysForSlides) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.repeatCount == 0) slide(Hid.PAGE_DOWN)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.repeatCount == 0) slide(Hid.PAGE_UP)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (mode == InputMode.PRESENTATION && settings.volumeKeysForSlides &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private fun applySettings(newSettings: InputSettings, persist: Boolean = true) {
        settings = newSettings
        if (persist) settingsStore.save(newSettings)
        touchpads.forEach {
            it.sensitivity = newSettings.pointerSpeed
            it.scrollSpeed = newSettings.scrollSpeed
            it.naturalScroll = newSettings.naturalScroll
            it.tapToClick = newSettings.tapToClick
        }
    }

    private fun showSettingsDialog() {
        var s = settings
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.slate_300))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        }

        fun slider(title: (Float) -> String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
            val tv = label(title(value))
            content.addView(tv)
            content.addView(SeekBar(this).apply {
                this.max = 100
                progress = (((value - min) / (max - min)) * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = min + (max - min) * p / 100f
                        tv.text = title(v)
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })
        }

        fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            content.addView(SwitchMaterial(this).apply {
                this.text = text
                isChecked = checked
                setTextColor(color(R.color.slate_300))
                setOnCheckedChangeListener { _, c -> onChange(c) }
            })
        }

        slider({ String.format(Locale.US, "Pointer speed: %.1fx", it) }, s.pointerSpeed, 0.5f, 4f) {
            s = s.copy(pointerSpeed = it); applySettings(s)
        }
        slider({ String.format(Locale.US, "Scroll speed: %.1fx", it) }, s.scrollSpeed, 0.3f, 3f) {
            s = s.copy(scrollSpeed = it); applySettings(s)
        }
        switch("Natural scrolling (content follows fingers)", s.naturalScroll) { s = s.copy(naturalScroll = it); applySettings(s) }
        switch("Tap to click", s.tapToClick) { s = s.copy(tapToClick = it); applySettings(s) }
        switch("Vibrate on key press", s.keyHaptics) { s = s.copy(keyHaptics = it); applySettings(s) }
        switch("Volume keys change slides (remote)", s.volumeKeysForSlides) { s = s.copy(volumeKeysForSlides = it); applySettings(s) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Keyboard & Mouse Settings")
            .setView(android.widget.ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset defaults") { _, _ -> applySettings(InputSettings()) }
            .show()
    }

    private fun click() = haptics.performButtonClickHaptic()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
