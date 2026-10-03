package com.truckcontroller.pro.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.R
import com.truckcontroller.pro.bluetooth.BluetoothHidService
import com.truckcontroller.pro.model.BUTTON_MAPPINGS
import com.truckcontroller.pro.model.ConnectionState
import com.truckcontroller.pro.model.ControllerSettings
import com.truckcontroller.pro.model.ShifterMode
import com.truckcontroller.pro.model.WHEEL_ANGLES
import java.util.Locale

private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

private fun SeekBar.onChange(block: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = block(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    })
}

/**
 * Controller settings. Every change is applied live through [onChange].
 */
fun Activity.showSettingsDialog(initial: ControllerSettings, onChange: (ControllerSettings) -> Unit) {
    var settings = initial
    val view = layoutInflater.inflate(R.layout.dialog_settings, null)
    fun update(transform: (ControllerSettings) -> ControllerSettings) {
        settings = transform(settings)
        onChange(settings)
    }

    val rgWheel = view.findViewById<RadioGroup>(R.id.rgWheelAngle)
    WHEEL_ANGLES.forEach { angle ->
        rgWheel.addView(RadioButton(this).apply {
            id = View.generateViewId()
            text = "$angle°"
            setTextColor(ContextCompat.getColor(context, R.color.slate_300))
            isChecked = angle == settings.wheelMaxAngle
            setOnCheckedChangeListener { _, checked -> if (checked) update { it.copy(wheelMaxAngle = angle) } }
        })
    }

    val rgShifter = view.findViewById<RadioGroup>(R.id.rgShifter)
    ShifterMode.entries.forEach { mode ->
        rgShifter.addView(RadioButton(this).apply {
            id = View.generateViewId()
            text = mode.label.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.slate_300))
            isChecked = mode == settings.shifterMode
            setOnCheckedChangeListener { _, checked -> if (checked) update { it.copy(shifterMode = mode) } }
        })
    }

    val tvSpring = view.findViewById<TextView>(R.id.tvSpring)
    val tvDeadzone = view.findViewById<TextView>(R.id.tvDeadzone)
    val tvCurve = view.findViewById<TextView>(R.id.tvCurve)
    val tvHaptic = view.findViewById<TextView>(R.id.tvHapticIntensity)
    val tvVolume = view.findViewById<TextView>(R.id.tvVolume)
    fun refreshLabels() {
        tvSpring.text = "Auto-center spring: ${settings.autoCenterSpring}%"
        tvDeadzone.text = "Center deadzone: ${settings.deadzone}%"
        tvCurve.text = String.format(Locale.US, "Steering sensitivity curve: %.1fx", settings.nonLinearity)
        tvHaptic.text = "Vibration intensity: ${settings.hapticIntensity}%"
        tvVolume.text = "Volume: ${settings.soundVolume}%"
    }
    refreshLabels()

    view.findViewById<SeekBar>(R.id.sbSpring).apply {
        progress = settings.autoCenterSpring
        onChange { v -> update { it.copy(autoCenterSpring = v) }; refreshLabels() }
    }
    view.findViewById<SeekBar>(R.id.sbDeadzone).apply {
        progress = settings.deadzone
        onChange { v -> update { it.copy(deadzone = v) }; refreshLabels() }
    }
    view.findViewById<SeekBar>(R.id.sbCurve).apply {
        progress = ((settings.nonLinearity - 1f) * 10f).toInt()
        onChange { v -> update { it.copy(nonLinearity = 1f + v / 10f) }; refreshLabels() }
    }
    view.findViewById<SwitchMaterial>(R.id.swHaptics).apply {
        isChecked = settings.hapticsEnabled
        setOnCheckedChangeListener { _, checked -> update { it.copy(hapticsEnabled = checked) } }
    }
    view.findViewById<SeekBar>(R.id.sbHapticIntensity).apply {
        progress = settings.hapticIntensity
        onChange { v -> update { it.copy(hapticIntensity = v) }; refreshLabels() }
    }
    view.findViewById<SwitchMaterial>(R.id.swSound).apply {
        isChecked = settings.soundEnabled
        setOnCheckedChangeListener { _, checked -> update { it.copy(soundEnabled = checked) } }
    }
    view.findViewById<SeekBar>(R.id.sbVolume).apply {
        progress = settings.soundVolume
        onChange { v -> update { it.copy(soundVolume = v) }; refreshLabels() }
    }

    val root = (view as android.widget.ScrollView).getChildAt(0) as android.widget.LinearLayout

    // Brake strength
    root.addView(TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        text = "BRAKE"
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.accent))
        setPadding(0, dp(20), 0, 0)
    })
    val tvBrakeStrength = TextView(this).apply {
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.slate_400))
        setPadding(0, dp(6), 0, 0)
        text = "Brake strength: ${settings.brakeStrength}% at full pedal"
    }
    root.addView(tvBrakeStrength)
    root.addView(SeekBar(this).apply {
        max = (100 - 20) / 5
        progress = (settings.brakeStrength - 20) / 5
        onChange { v ->
            val strength = 20 + v * 5
            update { it.copy(brakeStrength = strength) }
            tvBrakeStrength.text = "Brake strength: $strength% at full pedal"
        }
    })
    root.addView(TextView(this).apply {
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.slate_500))
        text = "Light presses brake gently and it builds up toward the end of the pedal. " +
            "ETS2's own Options › Gameplay › Brake intensity also multiplies this."
    })

    // Tilt steering (added in code below the sound settings)
    root.addView(TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        text = "TILT STEERING"
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.accent))
        setPadding(0, dp(20), 0, 0)
    })
    root.addView(SwitchMaterial(this).apply {
        text = "Steer by turning the phone"
        isChecked = settings.tiltSteering
        setTextColor(ContextCompat.getColor(context, R.color.slate_300))
        setOnCheckedChangeListener { _, checked -> update { it.copy(tiltSteering = checked) } }
    })
    val tvTilt = TextView(this).apply {
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.slate_400))
        setPadding(0, dp(6), 0, 0)
        text = "Turn the phone ${settings.tiltRange}° for full lock"
    }
    root.addView(tvTilt)
    root.addView(SeekBar(this).apply {
        max = (180 - 30) / 5
        progress = (settings.tiltRange - 30) / 5
        onChange { v ->
            val range = 30 + v * 5
            update { it.copy(tiltRange = range) }
            tvTilt.text = "Turn the phone $range° for full lock"
        }
    })
    root.addView(TextView(this).apply {
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.slate_500))
        text = "Smaller = more sensitive. Tap the 0° button by the wheel to set the straight-ahead position."
    })

    MaterialAlertDialogBuilder(this)
        .setTitle("Controller Settings")
        .setView(view)
        .setPositiveButton("Done", null)
        .setNeutralButton("Reset defaults") { _, _ -> onChange(ControllerSettings()) }
        .show()
}

/**
 * Lists the HID button numbers. Tapping a row sends that button so it can be bound in the ETS2 controls menu.
 */
fun Activity.showMappingDialog(onTestButton: (Int) -> Unit) {
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(8))
    }
    list.addView(TextView(this).apply {
        text = "In ETS2 open Options → Controls, choose the \"TruckPad\" controller, " +
            "select an action and tap the matching row below to send that button."
        setTextColor(ContextCompat.getColor(context, R.color.slate_400))
        textSize = 12f
        setPadding(0, 0, 0, dp(8))
    })
    BUTTON_MAPPINGS.groupBy { it.category }.forEach { (category, mappings) ->
        list.addView(TextView(this).apply {
            text = category.uppercase(Locale.US)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            textSize = 11f
            setPadding(0, dp(10), 0, dp(4))
        })
        mappings.forEach { mapping ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = ContextCompat.getDrawable(context, R.drawable.bg_step_button)
                isClickable = true
                setOnClickListener { onTestButton(mapping.hidButton) }
            }
            row.addView(TextView(this).apply {
                text = String.format(Locale.US, "B%02d", mapping.hidButton)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.amber_400))
                textSize = 12f
                minWidth = dp(44)
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = mapping.name
                    setTextColor(Color.WHITE)
                    textSize = 13f
                })
                addView(TextView(context).apply {
                    text = mapping.description
                    setTextColor(ContextCompat.getColor(context, R.color.slate_500))
                    textSize = 11f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) })
        }
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("HID Button Mappings")
        .setView(ScrollView(this).apply { addView(list) })
        .setPositiveButton("Close", null)
        .show()
}

/**
 * Bluetooth HID connection manager: status, discoverability and connecting to a paired PC.
 * The returned handle lets the caller refresh the content when the connection state changes.
 */
class BluetoothDialog(val dialog: AlertDialog, val refresh: () -> Unit)

@SuppressLint("MissingPermission")
fun Activity.showBluetoothDialog(
    hid: BluetoothHidService,
    onMakeDiscoverable: () -> Unit,
): BluetoothDialog {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(4), dp(24), dp(8))
    }
    val status = TextView(this).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 13f
    }
    content.addView(status)
    content.addView(TextView(this).apply {
        text = "First time: tap \"Make visible\", then on the PC open Settings → Bluetooth → Add device " +
            "and pick this phone. Afterwards tap the PC below to reconnect."
        setTextColor(ContextCompat.getColor(context, R.color.slate_400))
        textSize = 12f
        setPadding(0, dp(8), 0, dp(8))
    })
    content.addView(TextView(this).apply {
        text = "PAIRED DEVICES"
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.accent))
        textSize = 11f
        setPadding(0, dp(6), 0, dp(4))
    })
    val deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(deviceList)

    fun refresh() {
        status.text = when (hid.connectionState) {
            ConnectionState.CONNECTED -> "● Connected to ${hid.connectedDeviceName}"
            ConnectionState.PAIRING -> "● Connecting to ${hid.connectedDeviceName ?: "PC"}…"
            ConnectionState.OFFLINE -> if (hid.isRegistered) "● Ready – waiting for PC" else "● Bluetooth HID not ready"
        }
        status.setTextColor(ContextCompat.getColor(this, when (hid.connectionState) {
            ConnectionState.CONNECTED -> R.color.emerald_400
            ConnectionState.PAIRING -> R.color.amber_400
            ConnectionState.OFFLINE -> R.color.slate_400
        }))
        deviceList.removeAllViews()
        val devices = hid.bondedDevices()
        if (devices.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = "No paired devices yet."
                setTextColor(ContextCompat.getColor(context, R.color.slate_500))
                textSize = 12f
            })
        }
        devices.forEach { device ->
            val isCurrent = hid.connectionState == ConnectionState.CONNECTED &&
                hid.connectedDeviceName == hid.safeName(device)
            deviceList.addView(TextView(this).apply {
                text = (if (isCurrent) "✓  " else "") + (hid.safeName(device) ?: device.address)
                setTextColor(if (isCurrent) ContextCompat.getColor(context, R.color.emerald_400) else Color.WHITE)
                textSize = 14f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ContextCompat.getDrawable(context, R.drawable.bg_step_button)
                setOnClickListener { if (!isCurrent) hid.connect(device) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) })
        }
    }
    refresh()

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle("Bluetooth HID Connection")
        .setView(ScrollView(this).apply { addView(content) })
        .setPositiveButton("Close", null)
        .setNeutralButton("Make visible", null)
        .setNegativeButton("Disconnect", null)
        .show()
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { onMakeDiscoverable() }
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { hid.disconnect() }
    return BluetoothDialog(dialog, ::refresh)
}
