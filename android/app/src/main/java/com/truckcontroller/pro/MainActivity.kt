package com.truckcontroller.pro

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.audio.SoundEngine
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.input.TiltSensor
import com.truckcontroller.pro.model.CB_PRESETS
import com.truckcontroller.pro.model.ConnectionState
import com.truckcontroller.pro.model.ControllerSettings
import com.truckcontroller.pro.model.ControllerState
import com.truckcontroller.pro.model.HidButton
import com.truckcontroller.pro.model.LIGHT_LABELS
import com.truckcontroller.pro.model.SettingsStore
import com.truckcontroller.pro.model.TurnSignal
import com.truckcontroller.pro.model.WIPER_LABELS
import com.truckcontroller.pro.ui.ConsoleButton
import com.truckcontroller.pro.ui.LookPanView
import com.truckcontroller.pro.ui.PedalView
import com.truckcontroller.pro.ui.SteeringWheelView
import com.truckcontroller.pro.ui.showMappingDialog
import com.truckcontroller.pro.ui.showSettingsDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : HidActivity() {

    companion object {
        private const val BLINK_MS = 380L
        private const val QUICK_LOOK_MS = 450L
        private val SIGNAL_BARS = intArrayOf(3, 4, 5, 4, 5, 4)
        /** Keyboard "1": ETS2's interior camera (cam 1) is bound to it by default. */
        private const val KEY_1 = 0x1E
        /** Brake response curve: light presses stay gentle, the last part of the pedal brakes hard. */
        private const val BRAKE_CURVE = 1.6f
    }

    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var sounds: SoundEngine
    private lateinit var settingsStore: SettingsStore
    private var settings = ControllerSettings()
    private val state = ControllerState()
    private val handler = Handler(Looper.getMainLooper())
    private var blinkOn = true

    // Header
    private lateinit var connectionPill: LinearLayout
    private lateinit var connectionDot: View
    private lateinit var tvConnection: TextView
    private lateinit var tvModeInfo: TextView
    private lateinit var btnFullscreen: ImageButton

    // Wheel assembly
    private lateinit var steeringWheel: SteeringWheelView
    private lateinit var btnTurnLeft: ConsoleButton
    private lateinit var btnTurnRight: ConsoleButton
    private lateinit var btnHazard: ConsoleButton
    private lateinit var tvAngle: TextView
    private lateinit var tvOut: TextView
    private lateinit var tilt: TiltSensor
    private lateinit var tvTilt: TextView
    /** Phone angle that counts as straight ahead. */
    private var tiltCenter = 0f

    // Console
    private lateinit var tvGear: TextView
    private lateinit var tvRetarder: TextView
    private lateinit var btnLights: ConsoleButton
    private lateinit var btnBeacon: ConsoleButton
    private lateinit var btnCamera: ConsoleButton
    private lateinit var btnDiffLock: ConsoleButton
    private lateinit var btnAxleLift: ConsoleButton
    private lateinit var btnCruise: ConsoleButton
    private lateinit var btnEngine: ConsoleButton
    private lateinit var btnTrailer: ConsoleButton
    private lateinit var btnInterior: ConsoleButton
    private lateinit var btnWipers: ConsoleButton
    private lateinit var btnParkingBrake: ConsoleButton

    // CB radio
    private lateinit var btnCbMute: ConsoleButton
    private lateinit var btnCbPtt: ConsoleButton
    private lateinit var cbStatusDot: View
    private lateinit var tvCbChannel: TextView
    private lateinit var tvCbFrequency: TextView
    private lateinit var tvCbClock: TextView
    private lateinit var cbSignalBars: LinearLayout
    private val cbPresetViews = mutableListOf<TextView>()

    // Pedals & look
    private lateinit var lookPan: LookPanView
    private lateinit var gasPedal: PedalView
    private lateinit var brakePedal: PedalView
    private lateinit var tvGas: TextView
    private lateinit var tvBrake: TextView

    // Status bar
    private lateinit var tvStatusLeft: TextView
    private lateinit var tvStatusRight: TextView
    private var reportRate = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on while driving
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        haptics = HapticFeedbackHelper(this)
        sounds = SoundEngine()
        settingsStore = SettingsStore(this)
        tilt = TiltSensor(this) { degrees -> onTilt(degrees) }

        initViews()
        buildCbRadio()
        bindListeners()
        applySettings(settingsStore.load(), persist = false)
        render()
        startTickers()

        ensureBluetooth()
    }

    override val offerPairingOnFirstRun = true

    override fun onConnectionChanged() = renderConnection()

    override fun onResume() {
        super.onResume()
        if (settings.tiltSteering) tilt.start()
    }

    override fun onPause() {
        super.onPause()
        tilt.stop()
        // Never leave the truck accelerating, braking or honking while this screen is hidden
        steeringWheel.resetToCenter()
        state.gas = 0f
        state.brake = 0f
        state.hornActive = false
        state.cbTransmitting = false
        sounds.stopHorn()
        hidService.resetGamepad()
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun initViews() {
        connectionPill = findViewById(R.id.connectionPill)
        connectionDot = findViewById(R.id.connectionDot)
        tvConnection = findViewById(R.id.tvConnection)
        tvModeInfo = findViewById(R.id.tvModeInfo)
        btnFullscreen = findViewById(R.id.btnFullscreen)

        steeringWheel = findViewById(R.id.steeringWheel)
        btnTurnLeft = findViewById(R.id.btnTurnLeft)
        btnTurnRight = findViewById(R.id.btnTurnRight)
        btnHazard = findViewById(R.id.btnHazard)
        tvAngle = findViewById(R.id.tvAngle)
        tvOut = findViewById(R.id.tvOut)

        tvGear = findViewById(R.id.tvGear)
        tvRetarder = findViewById(R.id.tvRetarder)
        btnLights = findViewById(R.id.btnLights)
        btnBeacon = findViewById(R.id.btnBeacon)
        btnCamera = findViewById(R.id.btnCamera)
        btnDiffLock = findViewById(R.id.btnDiffLock)
        btnAxleLift = findViewById(R.id.btnAxleLift)
        btnCruise = findViewById(R.id.btnCruise)
        btnEngine = findViewById(R.id.btnEngine)
        btnTrailer = findViewById(R.id.btnTrailer)
        btnInterior = findViewById(R.id.btnInterior)
        btnWipers = findViewById(R.id.btnWipers)
        btnParkingBrake = findViewById(R.id.btnParkingBrake)

        btnCbMute = findViewById(R.id.btnCbMute)
        btnCbPtt = findViewById(R.id.btnCbPtt)
        cbStatusDot = findViewById(R.id.cbStatusDot)
        tvCbChannel = findViewById(R.id.tvCbChannel)
        tvCbFrequency = findViewById(R.id.tvCbFrequency)
        tvCbClock = findViewById(R.id.tvCbClock)
        cbSignalBars = findViewById(R.id.cbSignalBars)

        lookPan = findViewById(R.id.lookPan)
        gasPedal = findViewById(R.id.pedalGas)
        brakePedal = findViewById(R.id.pedalBrake)
        gasPedal.pedalType = PedalView.PedalType.GAS
        brakePedal.pedalType = PedalView.PedalType.BRAKE
        tvGas = findViewById(R.id.tvGas)
        tvBrake = findViewById(R.id.tvBrake)

        tvStatusLeft = findViewById(R.id.tvStatusLeft)
        tvStatusRight = findViewById(R.id.tvStatusRight)

        findViewById<TextView>(R.id.tvTitle).text = spans(
            "Truck" to null,
            "Pad" to R.color.accent
        )
    }

    private fun buildCbRadio() {
        val presets = findViewById<LinearLayout>(R.id.cbPresets)
        CB_PRESETS.forEach { preset ->
            val tv = TextView(this, null, 0, R.style.Cockpit_CbPreset).apply {
                text = preset.preset.toString()
                setOnClickListener {
                    feedbackClick()
                    state.cbPreset = preset
                    render()
                }
            }
            presets.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
            cbPresetViews += tv
        }
        SIGNAL_BARS.forEach { height ->
            cbSignalBars.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_signal_bar)
            }, LinearLayout.LayoutParams(dp(3), dp(height * 2.5f)).apply { marginStart = dp(1.5f) })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindListeners() {
        // Header
        findViewById<View>(R.id.btnPairPc).setOnClickListener { feedbackClick(); openBluetoothDialog() }
        connectionPill.setOnClickListener { feedbackClick(); openBluetoothDialog() }
        findViewById<View>(R.id.btnBluetooth).setOnClickListener { feedbackClick(); openBluetoothDialog() }
        findViewById<View>(R.id.btnMappings).setOnClickListener {
            feedbackClick()
            showMappingDialog { button ->
                haptics.performButtonClickHaptic()
                hidService.pressButton(button)
                if (hidService.connectionState != ConnectionState.CONNECTED) {
                    Toast.makeText(this, "Not connected to a PC", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            feedbackClick()
            showSettingsDialog(settings) { applySettings(it) }
        }
        bindFullscreenButton(btnFullscreen) { feedbackClick() }
        findViewById<View>(R.id.btnHome).setOnClickListener { feedbackClick(); finish() }
        tvModeInfo.setOnClickListener { feedbackClick(); cycleShifterMode() }

        // Steering wheel
        steeringWheel.onAngleChanged = { angle, normalized ->
            state.steeringAngle = angle
            state.steeringNormalized = normalized
            hidService.updateAxes(state)
            renderWheelReadout()
        }
        steeringWheel.onGrab = { haptics.performButtonClickHaptic() }
        steeringWheel.onWheelLockHit = { haptics.performWheelLockHaptic() }
        steeringWheel.onHornChanged = { pressed ->
            state.hornActive = pressed
            hidService.setButtonHeld(HidButton.HORN, pressed)
            if (pressed) {
                sounds.startHorn()
                haptics.performHornHaptic()
            } else {
                sounds.stopHorn()
            }
        }
        findViewById<View>(R.id.btnCenter).setOnClickListener {
            feedbackClick()
            if (settings.tiltSteering) {
                // In tilt mode 0° means "the way I'm holding the phone now is straight"
                tiltCenter = tilt.angle
                getPreferences(MODE_PRIVATE).edit().putFloat("tiltCenter", tiltCenter).apply()
                onTilt(tilt.angle)
                Toast.makeText(this, "Straight ahead set", Toast.LENGTH_SHORT).show()
            } else {
                steeringWheel.resetToCenter()
            }
        }
        // TILT toggle next to the 0° button
        val center = findViewById<View>(R.id.btnCenter)
        val row = center.parent as LinearLayout
        tvTilt = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
            text = "TILT"
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                feedbackClick()
                val on = !settings.tiltSteering
                applySettings(settings.copy(tiltSteering = on))
                Toast.makeText(
                    this@MainActivity,
                    if (on) "Tilt steering on: turn the phone like a wheel. Tap 0° to set straight ahead." else "Tilt steering off",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        row.addView(tvTilt, row.indexOfChild(center) + 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)).apply {
            marginStart = dp(6)
        })
        tiltCenter = getPreferences(MODE_PRIVATE).getFloat("tiltCenter", 0f)

        btnTurnLeft.setOnClickListener { toggleTurnSignal(TurnSignal.LEFT) }
        btnTurnRight.setOnClickListener { toggleTurnSignal(TurnSignal.RIGHT) }
        btnHazard.setOnClickListener {
            state.hazardActive = !state.hazardActive
            if (state.hazardActive) state.turnSignal = TurnSignal.OFF
            action(HidButton.HAZARDS)
        }

        // Pedals
        gasPedal.onPressed = { haptics.performButtonClickHaptic() }
        gasPedal.onValueChanged = { pct ->
            state.gas = pct
            hidService.updateAxes(state)
            if (state.engineRunning) sounds.updateEngineRpm(650f + pct * 15f)
            tvGas.text = "${pct.roundToInt()}%"
        }
        brakePedal.onPressed = {
            sounds.playAirBrake()
            haptics.performButtonClickHaptic()
        }
        brakePedal.onValueChanged = { pct -> applyBrake(pct) }

        // Look / pan pad: drag to look around, tap for the interior camera
        lookPan.onGrab = { haptics.performButtonClickHaptic() }
        lookPan.onTap = {
            feedbackClick()
            hidService.tapKeys(0, KEY_1)
            state.cameraView = 1
            render()
        }
        lookPan.onLookChanged = { x, y ->
            handler.removeCallbacks(quickLookReset)
            state.lookPanX = x
            state.lookPanY = y
            hidService.updateAxes(state)
        }

        // Shifter
        findViewById<View>(R.id.btnGearUp).setOnClickListener {
            if (state.gear < 12) state.gear++
            gearShiftFeedback()
            hidService.pressButton(HidButton.GEAR_UP)
            render()
        }
        findViewById<View>(R.id.btnGearDown).setOnClickListener {
            if (state.gear > -2) state.gear--
            gearShiftFeedback()
            hidService.pressButton(HidButton.GEAR_DOWN)
            render()
        }
        findViewById<View>(R.id.btnShifterMode).setOnClickListener { feedbackClick(); cycleShifterMode() }

        // Row 1
        findViewById<View>(R.id.btnRetarderUp).setOnClickListener { changeRetarder(+1) }
        findViewById<View>(R.id.btnRetarderDown).setOnClickListener { changeRetarder(-1) }
        btnLights.setOnClickListener {
            state.lightMode = (state.lightMode + 1) % 4
            action(HidButton.LIGHTS)
        }
        btnBeacon.setOnClickListener {
            state.beaconActive = !state.beaconActive
            action(HidButton.BEACON)
        }
        btnCamera.setOnClickListener {
            state.cameraView = if (state.cameraView >= 5) 1 else state.cameraView + 1
            action(HidButton.CAMERA)
        }
        btnDiffLock.setOnClickListener {
            state.diffLock = !state.diffLock
            action(HidButton.DIFF_LOCK)
        }
        btnAxleLift.setOnClickListener {
            state.axleLift = !state.axleLift
            action(HidButton.AXLE_LIFT)
        }
        btnCruise.setOnClickListener {
            state.cruiseControlActive = !state.cruiseControlActive
            action(HidButton.CRUISE_TOGGLE)
        }
        findViewById<View>(R.id.btnCruiseUp).setOnClickListener {
            state.cruiseControlSpeed = (state.cruiseControlSpeed + 5).coerceAtMost(130)
            action(HidButton.CRUISE_UP)
        }
        findViewById<View>(R.id.btnCruiseDown).setOnClickListener {
            state.cruiseControlSpeed = (state.cruiseControlSpeed - 5).coerceAtLeast(30)
            action(HidButton.CRUISE_DOWN)
        }

        // Row 2
        btnEngine.setOnClickListener {
            state.engineRunning = !state.engineRunning
            if (state.engineRunning) {
                haptics.performEngineStartHaptic()
                sounds.startEngine()
                sounds.updateEngineRpm(650f + state.gas * 15f)
            } else {
                haptics.performButtonClickHaptic()
                sounds.stopEngine()
            }
            hidService.pressButton(HidButton.ENGINE)
            render()
        }
        findViewById<View>(R.id.btnLookLeft).setOnClickListener { quickLook(-1f, HidButton.LOOK_LEFT) }
        findViewById<View>(R.id.btnLookRight).setOnClickListener { quickLook(1f, HidButton.LOOK_RIGHT) }
        btnTrailer.setOnClickListener {
            state.trailerAttached = !state.trailerAttached
            action(HidButton.TRAILER)
        }
        btnInterior.setOnClickListener {
            state.highBeam = !state.highBeam
            action(HidButton.HIGH_BEAM)
        }
        btnWipers.setOnClickListener {
            state.wiperSpeed = (state.wiperSpeed + 1) % 4
            action(HidButton.WIPERS)
        }
        btnParkingBrake.setOnClickListener {
            state.parkingBrake = !state.parkingBrake
            haptics.performAirBrakeHaptic()
            sounds.playAirBrake()
            hidService.pressButton(HidButton.PARKING_BRAKE)
            render()
        }

        // CB radio: hidden unless switched on in the header
        val cbPanel = findViewById<View>(R.id.cbPanel)
        val cbToggle = findViewById<android.widget.ImageButton>(R.id.btnCbToggle)
        fun showCb(visible: Boolean) {
            cbPanel.visibility = if (visible) View.VISIBLE else View.GONE
            cbToggle.setColorFilter(color(if (visible) R.color.amber_400 else R.color.slate_300))
        }
        showCb(getPreferences(MODE_PRIVATE).getBoolean("cbVisible", false))
        cbToggle.setOnClickListener {
            feedbackClick()
            val visible = cbPanel.visibility != View.VISIBLE
            getPreferences(MODE_PRIVATE).edit().putBoolean("cbVisible", visible).apply()
            showCb(visible)
        }
        btnCbMute.setOnClickListener {
            state.cbMuted = !state.cbMuted
            sounds.muted = state.cbMuted
            feedbackClick()
            render()
        }
        btnCbPtt.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    state.cbTransmitting = true
                    sounds.playCbSquelch()
                    haptics.performRadioPttHaptic()
                    hidService.setButtonHeld(HidButton.CB_PTT, true)
                    render()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    state.cbTransmitting = false
                    sounds.playCbSquelch()
                    hidService.setButtonHeld(HidButton.CB_PTT, false)
                    render()
                }
            }
            true
        }
    }

    private fun startTickers() {
        // Blinker relay: flashes turn signals / hazards / beacon and ticks in sync
        handler.post(object : Runnable {
            override fun run() {
                blinkOn = !blinkOn
                if (state.turnSignal != TurnSignal.OFF || state.hazardActive) sounds.playTurnSignal(!blinkOn)
                renderBlink()
                handler.postDelayed(this, BLINK_MS)
            }
        })
        // Clock, report rate and CB signal meter
        val clockFormat = SimpleDateFormat("HH:mm z", Locale.getDefault())
        handler.post(object : Runnable {
            override fun run() {
                tvCbClock.text = clockFormat.format(Date())
                reportRate = hidService.reportsSent.getAndSet(0)
                for (i in 0 until cbSignalBars.childCount) {
                    val bar = cbSignalBars.getChildAt(i)
                    val level = (SIGNAL_BARS[i] + Random.nextInt(-1, 2)).coerceIn(2, 6)
                    bar.layoutParams = bar.layoutParams.apply { height = dp(level * 2.5f) }
                }
                renderStatusBar()
                handler.postDelayed(this, 1000L)
            }
        })
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Generic toggle-switch action: click feedback, HID button press, re-render. */
    private fun action(button: Int) {
        feedbackClick()
        hidService.pressButton(button)
        render()
    }

    private fun feedbackClick() {
        sounds.playClick()
        haptics.performButtonClickHaptic()
    }

    private fun gearShiftFeedback() {
        sounds.playGearShift()
        haptics.performGearShiftHaptic()
    }

    /**
     * Always sends the press: the game knows the real retarder step (it changes on its own, e.g.
     * when the truck stops), so the app's display must never block a press at 0 or 5.
     */
    private fun changeRetarder(delta: Int) {
        sounds.playRetarderClick()
        haptics.performButtonClickHaptic()
        state.retarderLevel = (state.retarderLevel + delta).coerceIn(0, 5)
        hidService.pressButton(if (delta > 0) HidButton.RETARDER_UP else HidButton.RETARDER_DOWN)
        render()
    }

    /** Pedal travel → brake output: curved, and scaled to the brake strength setting. */
    private fun applyBrake(pedal: Float) {
        val travel = (pedal / 100f).coerceIn(0f, 1f)
        state.brake = travel.pow(BRAKE_CURVE) * settings.brakeStrength
        hidService.updateAxes(state)
        tvBrake.text = "${state.brake.roundToInt()}%"
    }

    private fun toggleTurnSignal(signal: TurnSignal) {
        state.turnSignal = if (state.turnSignal == signal) TurnSignal.OFF else signal
        state.hazardActive = false
        action(if (signal == TurnSignal.LEFT) HidButton.TURN_LEFT else HidButton.TURN_RIGHT)
    }

    private val quickLookReset = Runnable {
        state.lookPanX = 0f
        state.lookPanY = 0f
        lookPan.setLook(0f, 0f)
        hidService.updateAxes(state)
    }

    private fun quickLook(direction: Float, button: Int) {
        feedbackClick()
        hidService.pressButton(button)
        state.lookPanX = 0.9f * direction
        state.lookPanY = 0f
        lookPan.setLook(state.lookPanX, 0f)
        hidService.updateAxes(state)
        handler.removeCallbacks(quickLookReset)
        handler.postDelayed(quickLookReset, QUICK_LOOK_MS)
    }

    private fun cycleShifterMode() {
        applySettings(settings.copy(shifterMode = settings.shifterMode.next()))
    }

    private fun applySettings(newSettings: ControllerSettings, persist: Boolean = true) {
        settings = newSettings
        if (persist) settingsStore.save(newSettings)
        steeringWheel.maxDegrees = newSettings.wheelMaxAngle.toFloat()
        steeringWheel.springStrength = newSettings.autoCenterSpring / 100f
        steeringWheel.deadzone = newSettings.deadzone / 100f
        steeringWheel.nonLinearity = newSettings.nonLinearity
        haptics.isEnabled = newSettings.hapticsEnabled
        haptics.intensityFactor = newSettings.hapticIntensity / 100f
        sounds.enabled = newSettings.soundEnabled
        sounds.volume = newSettings.soundVolume / 100f
        if (!newSettings.soundEnabled && state.engineRunning) sounds.stopEngine()
        state.shifterMode = newSettings.shifterMode
        applyTilt()
        render()
    }

    /** Starts or stops the tilt sensor and keeps the screen from flipping while steering by tilt. */
    private fun applyTilt() {
        val on = settings.tiltSteering
        if (on && !tilt.available) {
            Toast.makeText(this, "This phone has no motion sensor for tilt steering", Toast.LENGTH_LONG).show()
        }
        val wasTilt = steeringWheel.tiltMode
        steeringWheel.tiltMode = on
        // Always landscape. Tilt steering pins the current landscape side so turning the phone
        // like a wheel can't flip the screen; "lock current" could catch portrait while opening.
        requestedOrientation = when {
            !on -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            androidx.core.content.ContextCompat.getDisplayOrDefault(this).rotation == android.view.Surface.ROTATION_270 ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (on) {
            tilt.start()
        } else {
            tilt.stop()
            if (wasTilt) steeringWheel.resetToCenter()
        }
        if (::tvTilt.isInitialized) {
            tvTilt.setTextColor(color(if (on) R.color.cockpit_bg else R.color.slate_300))
            tvTilt.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(color(if (on) R.color.accent else R.color.step_bg))
            }
        }
    }

    /** Phone angle → wheel angle: [ControllerSettings.tiltRange] of phone rotation is full lock. */
    private fun onTilt(phoneDegrees: Float) {
        if (!settings.tiltSteering) return
        var delta = phoneDegrees - tiltCenter
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        val fraction = (delta / settings.tiltRange).coerceIn(-1f, 1f)
        steeringWheel.setTiltAngle(fraction * steeringWheel.maxDegrees / 2f)
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render() {
        // Header
        tvModeInfo.text = spans(
            "SHIFTER: " to null,
            state.shifterMode.label to R.color.amber_400,
            "  ·  WHEEL: " to null,
            "${settings.wheelMaxAngle}°" to R.color.slate_300
        )
        renderConnection()

        // Shifter & wheel display
        tvGear.text = state.gearText
        steeringWheel.displayText = state.gearText
        renderWheelReadout()

        // Row 1
        tvRetarder.text = state.retarderLevel.toString()
        tvRetarder.setTextColor(color(if (state.retarderLevel > 0) R.color.amber_400 else R.color.slate_400))
        btnLights.isActive = state.lightMode > 0
        btnLights.label = LIGHT_LABELS[state.lightMode]
        btnBeacon.isActive = state.beaconActive
        btnCamera.label = "CAM ${state.cameraView}"
        btnDiffLock.isActive = state.diffLock
        btnAxleLift.isActive = state.axleLift
        btnCruise.isActive = state.cruiseControlActive
        btnCruise.label = state.cruiseControlSpeed.toString()

        // Row 2
        btnEngine.isActive = state.engineRunning
        btnEngine.label = if (state.engineRunning) "ENGINE\nSTOP" else "ENGINE\nSTART"
        btnTrailer.isActive = state.trailerAttached
        btnInterior.isActive = state.highBeam
        btnWipers.isActive = state.wiperSpeed > 0
        btnWipers.label = WIPER_LABELS[state.wiperSpeed]
        btnParkingBrake.isActive = state.parkingBrake

        // Turn signals
        btnTurnLeft.isActive = state.turnSignal == TurnSignal.LEFT || state.hazardActive
        btnTurnRight.isActive = state.turnSignal == TurnSignal.RIGHT || state.hazardActive
        btnHazard.isActive = state.hazardActive

        // CB radio
        tvCbChannel.text = "CB CH ${state.cbPreset.channel} ${state.cbPreset.name}"
        tvCbFrequency.text = "${state.cbPreset.frequency} · Trucker CB"
        btnCbMute.isActive = state.cbMuted
        btnCbMute.icon = ContextCompat.getDrawable(this, if (state.cbMuted) R.drawable.ic_volume_x else R.drawable.ic_volume)
        btnCbPtt.isActive = state.cbTransmitting
        btnCbPtt.label = if (state.cbTransmitting) "TX" else "PTT"
        cbSignalBars.alpha = if (state.cbMuted) 0.3f else 1f
        cbPresetViews.forEachIndexed { index, tv ->
            val selected = CB_PRESETS[index] == state.cbPreset
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(if (selected) color(R.color.amber_500) else Color.argb(102, 0, 0, 0))
            }
            tv.setTextColor(if (selected) Color.BLACK else Color.argb(204, 251, 191, 36))
        }

        renderBlink()
        renderStatusBar()
    }

    private fun renderBlink() {
        val off = !blinkOn
        btnTurnLeft.blinkOff = off
        btnTurnRight.blinkOff = off
        btnHazard.blinkOff = off
        btnBeacon.blinkOff = off
        cbStatusDot.backgroundTintList = ColorStateList.valueOf(
            when {
                state.cbTransmitting -> if (off) Color.argb(80, 239, 68, 68) else color(R.color.red_500)
                else -> color(R.color.emerald_400)
            }
        )
    }

    private fun renderWheelReadout() {
        val angle = state.steeringAngle.roundToInt()
        tvAngle.text = spans("ANG " to null, (if (angle > 0) "+$angle°" else "$angle°") to android.R.color.white)
        tvOut.text = spans("OUT " to null, "${(state.steeringNormalized * 100).roundToInt()}%" to R.color.amber_400)
    }

    private fun renderConnection() {
        if (!::connectionPill.isInitialized) return
        renderConnectionPill(connectionPill, connectionDot, tvConnection)
        findViewById<TextView>(R.id.tvPairPc).text =
            if (hidService.connectionState == ConnectionState.CONNECTED) "PC LINKED" else "PAIR PC"
        renderStatusBar()
    }

    private fun renderStatusBar() {
        tvStatusLeft.text = spans(
            "ENGINE: " to null,
            (if (state.engineRunning) "RUNNING" else "STOPPED") to (if (state.engineRunning) R.color.emerald_400 else R.color.slate_400),
            "   |   P-BRAKE: " to null,
            (if (state.parkingBrake) "ENGAGED" else "RELEASED") to (if (state.parkingBrake) R.color.red_500 else R.color.emerald_400),
        )
        val connected = hidService.connectionState == ConnectionState.CONNECTED
        tvStatusRight.text = spans(
            "LINK: " to null,
            (if (connected) "BT HID" else "OFFLINE") to (if (connected) R.color.amber_400 else R.color.slate_500),
            "   |   RATE: " to null,
            "$reportRate Hz" to R.color.cyan_400,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sounds.release()
    }
}
