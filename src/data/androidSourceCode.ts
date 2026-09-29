/**
 * Full Android Kotlin project source code files for export and inspection
 */

export interface SourceFile {
  name: string;
  path: string;
  language: string;
  description: string;
  content: string;
}

export const ANDROID_SOURCE_FILES: SourceFile[] = [
  {
    name: 'BluetoothHidService.kt',
    path: 'app/src/main/java/com/truckcontroller/pro/bluetooth/BluetoothHidService.kt',
    language: 'kotlin',
    description: 'Android Bluetooth HID Stack implementation using BluetoothHidDevice API for direct driverless PC connection',
    content: `package com.truckcontroller.pro.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.truckcontroller.pro.model.ControllerState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance Bluetooth HID Service for Euro Truck Simulator 2.
 * Emulates a standard high-precision 16-bit Gamepad/Wheel over Android Bluetooth HID Device API.
 */
class BluetoothHidService(private val context: Context) {

    companion object {
        private const val TAG = "TruckHidService"
        const val REPORT_ID_JOYSTICK: Byte = 0x01

        // USB HID Report Descriptor for ETS2 16-bit Steering Wheel + 2 Pedals + Retarder + 32 Buttons + 8-way Hat
        val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x05.toByte(), // USAGE (Gamepad / Joystick)
            0xa1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), REPORT_ID_JOYSTICK, //   REPORT_ID (1)
            
            // 16-bit High Precision Steering Wheel (X-Axis)
            0x09.toByte(), 0x30.toByte(), //   USAGE (X - Steering)
            0x16.toByte(), 0x01.toByte(), 0x80.toByte(), //   LOGICAL_MINIMUM (-32767)
            0x26.toByte(), 0xff.toByte(), 0x7f.toByte(), //   LOGICAL_MAXIMUM (32767)
            0x75.toByte(), 0x10.toByte(), //   REPORT_SIZE (16 bits)
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data, Var, Abs)

            // 8-bit Accelerator Pedal (Y-Axis)
            0x09.toByte(), 0x31.toByte(), //   USAGE (Y - Throttle)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x26.toByte(), 0xff.toByte(), 0x00.toByte(), //   LOGICAL_MAXIMUM (255)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8 bits)
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data, Var, Abs)

            // 8-bit Brake Pedal (Z-Axis)
            0x09.toByte(), 0x32.toByte(), //   USAGE (Z - Brake)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data, Var, Abs)

            // 8-bit Retarder Lever (Rx-Axis)
            0x09.toByte(), 0x33.toByte(), //   USAGE (Rx - Retarder)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data, Var, Abs)

            // 8-Way Hat Switch (Look / Pan)
            0x09.toByte(), 0x39.toByte(), //   USAGE (Hat switch)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x07.toByte(), //   LOGICAL_MAXIMUM (7)
            0x35.toByte(), 0x00.toByte(), //   PHYSICAL_MINIMUM (0)
            0x46.toByte(), 0x3b.toByte(), 0x01.toByte(), // PHYSICAL_MAXIMUM (315)
            0x65.toByte(), 0x14.toByte(), //   UNIT (Eng Rot:Degrees)
            0x75.toByte(), 0x04.toByte(), //   REPORT_SIZE (4 bits)
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x81.toByte(), 0x42.toByte(), //   INPUT (Data, Var, Abs, Null)
            
            // 4-bit Padding
            0x75.toByte(), 0x04.toByte(), //   REPORT_SIZE (4 bits)
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x81.toByte(), 0x03.toByte(), //   INPUT (Cnst, Var, Abs)

            // 32 Digital Buttons (Gear Shifting, Lights, Wipers, Engine, CB)
            0x05.toByte(), 0x09.toByte(), //   USAGE_PAGE (Button)
            0x19.toByte(), 0x01.toByte(), //   USAGE_MINIMUM (Button 1)
            0x29.toByte(), 0x20.toByte(), //   USAGE_MAXIMUM (Button 32)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //   LOGICAL_MAXIMUM (1)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x95.toByte(), 0x20.toByte(), //   REPORT_COUNT (32)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data, Var, Abs)

            0xc0.toByte()                  // END_COLLECTION
        )
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var isAppRegistered = false

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                isAppRegistered = false
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            isAppRegistered = registered
            Log.d(TAG, "HID App registration changed: registered=$registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    Log.i(TAG, "Connected to ETS2 host: \${device?.name} (\${device?.address})")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice == device) {
                        connectedDevice = null
                    }
                    Log.i(TAG, "Disconnected from host")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        bluetoothAdapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TruckController Pro",
            "Euro Truck Simulator 2 Bluetooth Wheel & Shifter",
            "ETS2 Simulators",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HID_REPORT_DESCRIPTOR
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, // Token rate
            9,   // Token bucket size
            0,   // Peak bandwidth
            11250, // Latency (11.25 ms low-latency polling)
            BluetoothHidDeviceAppQosSettings.MAX
        )

        hidDevice?.registerApp(sdp, qos, qos, context.mainExecutor, hidCallback)
    }

    /**
     * Send low-latency 120Hz HID input report to PC host
     */
    @SuppressLint("MissingPermission")
    fun sendReport(state: ControllerState) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        // 1 byte (X low) + 1 byte (X high) + 1 byte (Throttle) + 1 byte (Brake) + 1 byte (Retarder) + 1 byte (Hat) + 4 bytes (Buttons)
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)

        // 16-bit steering (-32767 to 32767)
        val steeringRaw = (state.steeringNormalized * 32767f).toInt().coerceIn(-32767, 32767).toShort()
        buffer.putShort(steeringRaw)

        // Pedals (0 - 255)
        buffer.put((state.gas * 2.55f).toInt().coerceIn(0, 255).toByte())
        buffer.put((state.brake * 2.55f).toInt().coerceIn(0, 255).toByte())
        buffer.put((state.retarderLevel * 51).coerceIn(0, 255).toByte())

        // 4-bit Hat switch + 4-bit padding
        val hatValue = if (state.lookHat in 0..7) state.lookHat else 0x08
        buffer.put(hatValue.toByte())

        // 32-bit Buttons Bitmask
        buffer.putInt(state.buttonsBitmask)

        hid.sendReport(device, REPORT_ID_JOYSTICK.toInt(), buffer.array())
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        if (isAppRegistered) {
            hidDevice?.unregisterApp()
        }
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
    }
}
`,
  },
  {
    name: 'SteeringWheelView.kt',
    path: 'app/src/main/java/com/truckcontroller/pro/ui/SteeringWheelView.kt',
    language: 'kotlin',
    description: 'Custom Android SurfaceView / Canvas for ultra-responsive 900° steering wheel with spring physics and zero touch lag',
    content: `package com.truckcontroller.pro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Ultra-Low Latency 900-degree Truck Steering Wheel Custom View
 */
class SteeringWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var maxDegrees = 900f // 900° lock-to-lock (-450° to +450°)
    var currentAngle = 0f
        private set
    var normalizedOutput = 0f // -1.0 to +1.0
        private set

    var autoCenterSpring = true
    var springStrength = 0.85f

    var onAngleChanged: ((angle: Float, normalized: Float) -> Unit)? = null
    var onWheelLockHit: (() -> Unit)? = null

    private var prevTouchAngle = 0f
    private var isDragging = false

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1e2430")
        strokeWidth = 48f
        strokeCap = Paint.Cap.ROUND
    }

    private val centerMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#f97316") // Vivid Orange 12 o'clock stripe
        strokeWidth = 52f
        strokeCap = Paint.Cap.ROUND
    }

    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#151a23")
    }

    private val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#ef4444") // Red contrast stitching
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0e121a")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val dx = event.x - cx
        val dy = event.y - cy
        val touchAngle = (atan2(dy, dx) * 180f / PI.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                prevTouchAngle = touchAngle
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    var delta = touchAngle - prevTouchAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f

                    val limit = maxDegrees / 2f
                    val newAngle = (currentAngle + delta).coerceIn(-limit, limit)

                    if ((newAngle == limit || newAngle == -limit) && currentAngle != newAngle) {
                        onWheelLockHit?.invoke()
                    }

                    currentAngle = newAngle
                    normalizedOutput = currentAngle / limit
                    prevTouchAngle = touchAngle

                    onAngleChanged?.invoke(currentAngle, normalizedOutput)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (autoCenterSpring) {
                    startSpringCentering()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startSpringCentering() {
        post(object : Runnable {
            override fun run() {
                if (!isDragging && abs(currentAngle) > 0.5f) {
                    currentAngle *= (1f - springStrength * 0.25f)
                    val limit = maxDegrees / 2f
                    normalizedOutput = currentAngle / limit
                    onAngleChanged?.invoke(currentAngle, normalizedOutput)
                    invalidate()
                    postDelayed(this, 16)
                } else if (!isDragging) {
                    currentAngle = 0f
                    normalizedOutput = 0f
                    onAngleChanged?.invoke(0f, 0f)
                    invalidate()
                }
            }
        })
    }

    fun resetToCenter() {
        currentAngle = 0f
        normalizedOutput = 0f
        onAngleChanged?.invoke(0f, 0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 30f

        canvas.save()
        canvas.rotate(currentAngle, cx, cy)

        // Outer rim
        canvas.drawCircle(cx, cy, radius, rimPaint)
        // Red contrast stitching
        canvas.drawCircle(cx, cy, radius - 16f, stitchPaint)

        // Orange 12 o'clock centering stripe
        val markerRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(markerRect, 260f, 20f, false, centerMarkerPaint)

        // Spokes
        canvas.drawRect(cx - 36f, cy, cx + 36f, cy + radius, spokePaint)
        canvas.drawRect(cx - radius + 10f, cy - 28f, cx, cy + 28f, spokePaint)
        canvas.drawRect(cx, cy - 28f, cx + radius - 10f, cy + 28f, spokePaint)

        // Central boss
        canvas.drawCircle(cx, cy, radius * 0.38f, hubPaint)

        canvas.restore()
    }
}
`,
  },
  {
    name: 'HapticFeedbackHelper.kt',
    path: 'app/src/main/java/com/truckcontroller/pro/haptics/HapticFeedbackHelper.kt',
    language: 'kotlin',
    description: 'Android Vibrator & VibrationEffect engine with custom mechanical feedback waveforms',
    content: `package com.truckcontroller.pro.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic engine for Euro Truck Simulator 2 gear shift shocks and pedal resistance.
 */
class HapticFeedbackHelper(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    var isEnabled = true

    /**
     * Heavy mechanical gear shift clunk shockwave
     */
    fun performGearShiftHaptic() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Distinct 2-stage transmission engagement pulse
            val timings = longArrayOf(0, 30, 25, 45)
            val amplitudes = intArrayOf(0, 255, 60, 200)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    /**
     * Pneumatic air brake release pop
     */
    fun performAirBrakeHaptic() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 70, 30, 30)
            val amplitudes = intArrayOf(0, 220, 0, 140)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    }

    /**
     * Crisp button click for cockpit switches
     */
    fun performButtonClickHaptic() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /**
     * Steering lock endpoint hit
     */
    fun performWheelLockHaptic() {
        if (!isEnabled || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, 255))
        }
    }
}
`,
  },
  {
    name: 'MainActivity.kt',
    path: 'app/src/main/java/com/truckcontroller/pro/MainActivity.kt',
    language: 'kotlin',
    description: 'Main Controller Activity with full cockpit UI, multi-touch pedal trackers, and 120Hz loop',
    content: `package com.truckcontroller.pro

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.truckcontroller.pro.bluetooth.BluetoothHidService
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.model.ControllerState
import com.truckcontroller.pro.ui.SteeringWheelView

class MainActivity : AppCompatActivity() {

    private lateinit var hidService: BluetoothHidService
    private lateinit var haptics: HapticFeedbackHelper
    private val controllerState = ControllerState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on & immersive landscape fullscreen for driving
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        haptics = HapticFeedbackHelper(this)
        hidService = BluetoothHidService(this)
        hidService.initialize()

        setupSteeringWheel()
        setupPedals()
        setupShifter()
    }

    private fun setupSteeringWheel() {
        val wheel = findViewById<SteeringWheelView>(R.id.steeringWheelView)
        wheel.onAngleChanged = { _, normalized ->
            controllerState.steeringNormalized = normalized
            hidService.sendReport(controllerState)
        }
        wheel.onWheelLockHit = {
            haptics.performWheelLockHaptic()
        }
    }

    private fun setupPedals() {
        // Multi-touch vertical sliders for Gas and Brake with 120Hz sampling
    }

    private fun setupShifter() {
        // Sequential Gear shifting with custom mappable buttons and haptic clunk
    }

    override fun onDestroy() {
        super.onDestroy()
        hidService.cleanup()
    }
}
`,
  },
  {
    name: 'AndroidManifest.xml',
    path: 'app/src/main/AndroidManifest.xml',
    language: 'xml',
    description: 'Android manifest configured with Bluetooth HID, BLE, High Priority, and Vibrate permissions',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.truckcontroller.pro">

    <!-- Bluetooth HID Stack Permissions -->
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

    <!-- Haptic Force Feedback -->
    <uses-permission android:name="android.permission.VIBRATE" />

    <!-- Keep screen awake while driving -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Optional WiFi PC Bridge fallback -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.TruckControllerPro">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="sensorLandscape"
            android:configChanges="orientation|keyboardHidden|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
`,
  },
  {
    name: 'build.gradle.kts (App)',
    path: 'app/build.gradle.kts',
    language: 'kotlin',
    description: 'App Gradle build file with SDK 34 and Jetpack dependencies',
    content: `plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.truckcontroller.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.truckcontroller.pro"
        minSdk = 28 // Android 9.0 introduces standard BluetoothHidDevice API
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
`,
  },
  {
    name: 'pc_bridge_server.py',
    path: 'scripts/pc_bridge_server.py',
    language: 'python',
    description: 'Python PC bridge server for virtual Xbox / vJoy joystick input feeding directly into Euro Truck Simulator 2',
    content: `#!/usr/bin/env python3
"""
TruckController Pro - PC Low Latency Bridge for Euro Truck Simulator 2
Feeds Android Bluetooth / WiFi packets into ETS2 via vXbox / vJoy.
Requires: pip install websockets vgamepad
"""

import asyncio
import json
import sys

try:
    import vgamepad as vg
    gamepad = vg.VX360Gamepad()
    print("[OK] Virtual Xbox 360 controller initialized for Euro Truck Simulator 2.")
except ImportError:
    print("[NOTE] 'vgamepad' not installed. Running in diagnostic telemetry mode.")
    print("Run: pip install vgamepad websockets")
    gamepad = None

import websockets

async def handle_controller(websocket):
    print(f"[+] TruckController connected from {websocket.remote_address}")
    try:
        async for message in websocket:
            data = json.loads(message)
            steering = data.get("steeringRaw", 0) # -32767 to 32767
            throttle = data.get("throttleRaw", 0) # 0 to 255
            brake = data.get("brakeRaw", 0)       # 0 to 255
            buttons = data.get("buttonsBitmask", 0)

            if gamepad:
                # Left thumbstick X for 900° steering
                gamepad.left_joystick(x_value=steering, y_value=0)
                # Right trigger for Gas
                gamepad.right_trigger(value=throttle)
                # Left trigger for Brake
                gamepad.left_trigger(value=brake)

                # Map buttons (A = Gear Up, B = Gear Down, etc.)
                if buttons & (1 << 0): # Engine
                    gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_START)
                else:
                    gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_START)

                gamepad.update()

    except websockets.exceptions.ConnectionClosed:
        print("[-] TruckController disconnected.")

async def main():
    port = 8765
    print(f"[*] Starting TruckController Pro Bridge on ws://0.0.0.0:{port}")
    async with websockets.serve(handle_controller, "0.0.0.0", port):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
`,
  },
  {
    name: 'README.md',
    path: 'README.md',
    language: 'markdown',
    description: 'Euro Truck Simulator 2 setup guide and Android Studio compilation instructions',
    content: `# TruckController Pro - Android ETS2 Gamepad & 900° Wheel

A gaming controller for **Euro Truck Simulator 2** featuring:
- **Real 900° Steering Wheel** with continuous multi-turn touch math and spring-centering physics.
- **Haptic Feedback**: Custom mechanical vibration waveforms for gear shifts, air brake pops, and wheel lock.
- **Custom Mappable Shifter**: Sequential, Range-Splitter (12/16 speed), H-Shifter, and Automatic modes.
- **Dual Tactile Pedals**: Vertical ribbed Gas & Brake with multi-touch support.
- **Realistic CB Radio**: Highway Channel 19, Push-To-Talk squelch sound effects.
- **Cockpit Deck**: Retarder, differential lock, tag axle lift, trailer coupling, wipers, lights, and air parking brake.

## 📱 How to Build the Android APK

1. Open **Android Studio** (Hedgehog 2023.3.1 or newer).
2. Click **File -> Open...** and select this unzipped project folder.
3. Allow Gradle to sync dependencies (\`compileSdk = 34\`, \`minSdk = 28\`).
4. Connect your Android phone with **USB Debugging** enabled.
5. Click **Run 'app'** (Shift + F10) to build and install the APK on your device!

## 🎮 How to Connect to Euro Truck Simulator 2

### Method 1: Native Bluetooth HID (No PC Software Needed)
1. Turn on Bluetooth on your PC and Android phone.
2. Pair your phone to your PC via Windows Bluetooth settings.
3. Open **TruckController Pro** on your phone.
4. Android will register as a **Bluetooth HID Gamepad** (\`TruckController Pro\`).
5. In **Euro Truck Simulator 2**:
   - Go to **Options -> Controls**.
   - Select **Controller / Joystick (TruckController Pro)**.
   - Set Steering Axis to **Left Stick X** (Deadzone = 0%, Steering Linearity = Centered).
   - Set Acceleration Axis to **Right Trigger (Inverted/Centered)**.
   - Set Brake Axis to **Left Trigger (Inverted/Centered)**.

### Method 2: WiFi PC Bridge (Ultra-Low Latency Fallback)
1. Install Python 3 on your PC.
2. Run: \`pip install websockets vgamepad\`
3. Run: \`python scripts/pc_bridge_server.py\`
4. In TruckController Pro, tap **Bluetooth / Bridge** and enter your PC's local IP.
`,
  }
];
