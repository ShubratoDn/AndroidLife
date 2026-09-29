package com.truckcontroller.pro.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.truckcontroller.pro.model.ConnectionState
import com.truckcontroller.pro.model.ControllerState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android Bluetooth HID stack: the phone appears to the PC as one composite device with
 * four top-level collections, each with its own report ID:
 *
 * - 1: Keyboard (modifiers + 6-key rollover, Caps/Num/Scroll lock LEDs)
 * - 2: Mouse (5 buttons, relative X/Y, vertical wheel, horizontal pan)
 * - 3: Gamepad for Euro Truck Simulator 2 (steering, pedals, look, hat, 32 buttons)
 * - 4: Consumer control (volume / media keys)
 *
 * The service is a process-wide singleton ([get]) so the link survives switching screens.
 * All report state lives on a dedicated sender thread. Analog and mouse-motion updates are
 * coalesced so at most one report of each kind is sent every [MIN_REPORT_INTERVAL_MS].
 */
class BluetoothHidService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TruckBluetoothHid"

        const val REPORT_ID_KEYBOARD: Byte = 0x01
        const val REPORT_ID_MOUSE: Byte = 0x02
        const val REPORT_ID_JOYSTICK: Byte = 0x03
        const val REPORT_ID_CONSUMER: Byte = 0x04

        const val MOUSE_LEFT = 0x01
        const val MOUSE_RIGHT = 0x02
        const val MOUSE_MIDDLE = 0x04

        private const val GAMEPAD_REPORT_SIZE = 12
        private const val AXIS_CENTER = 128
        private const val PULSE_MS = 70L
        private const val CLICK_MS = 35L
        private const val TYPE_KEY_MS = 12L
        private const val MIN_REPORT_INTERVAL_MS = 8L // ~120 Hz
        private const val LOOK_HAT_THRESHOLD = 0.45f
        private const val PREFS = "bluetooth_hid"
        private const val KEY_LAST_HOST = "last_host_address"

        @Volatile private var instance: BluetoothHidService? = null

        fun get(context: Context): BluetoothHidService =
            instance ?: synchronized(this) {
                instance ?: BluetoothHidService(context.applicationContext).also { instance = it }
            }

        /**
         * Composite HID report descriptor. Changing it requires removing and re-pairing the phone
         * on the PC, because the host caches the descriptor at pairing time.
         */
        val HID_REPORT_DESCRIPTOR = byteArrayOf(
            // ===== Keyboard (report ID 1) =====
            0x05, 0x01,                    // USAGE_PAGE (Generic Desktop)
            0x09, 0x06,                    // USAGE (Keyboard)
            0xa1.toByte(), 0x01,           // COLLECTION (Application)
            0x85.toByte(), REPORT_ID_KEYBOARD,
            0x05, 0x07,                    //   USAGE_PAGE (Keyboard)
            0x19, 0xe0.toByte(),           //   USAGE_MINIMUM (Left Control)
            0x29, 0xe7.toByte(),           //   USAGE_MAXIMUM (Right GUI)
            0x15, 0x00,                    //   LOGICAL_MINIMUM (0)
            0x25, 0x01,                    //   LOGICAL_MAXIMUM (1)
            0x75, 0x01,                    //   REPORT_SIZE (1)
            0x95.toByte(), 0x08,           //   REPORT_COUNT (8)
            0x81.toByte(), 0x02,           //   INPUT (Data, Var, Abs) - modifier byte
            0x95.toByte(), 0x01,           //   REPORT_COUNT (1)
            0x75, 0x08,                    //   REPORT_SIZE (8)
            0x81.toByte(), 0x01,           //   INPUT (Cnst) - reserved byte
            0x95.toByte(), 0x05,           //   REPORT_COUNT (5)
            0x75, 0x01,                    //   REPORT_SIZE (1)
            0x05, 0x08,                    //   USAGE_PAGE (LEDs)
            0x19, 0x01,                    //   USAGE_MINIMUM (Num Lock)
            0x29, 0x05,                    //   USAGE_MAXIMUM (Kana)
            0x91.toByte(), 0x02,           //   OUTPUT (Data, Var, Abs) - LED report
            0x95.toByte(), 0x01,           //   REPORT_COUNT (1)
            0x75, 0x03,                    //   REPORT_SIZE (3)
            0x91.toByte(), 0x01,           //   OUTPUT (Cnst) - LED padding
            0x95.toByte(), 0x06,           //   REPORT_COUNT (6)
            0x75, 0x08,                    //   REPORT_SIZE (8)
            0x15, 0x00,                    //   LOGICAL_MINIMUM (0)
            0x26, 0xff.toByte(), 0x00,     //   LOGICAL_MAXIMUM (255)
            0x05, 0x07,                    //   USAGE_PAGE (Keyboard)
            0x19, 0x00,                    //   USAGE_MINIMUM (0)
            0x2a, 0xff.toByte(), 0x00,     //   USAGE_MAXIMUM (255)
            0x81.toByte(), 0x00,           //   INPUT (Data, Array) - 6 key codes
            0xc0.toByte(),                 // END_COLLECTION

            // ===== Mouse (report ID 2) =====
            0x05, 0x01,                    // USAGE_PAGE (Generic Desktop)
            0x09, 0x02,                    // USAGE (Mouse)
            0xa1.toByte(), 0x01,           // COLLECTION (Application)
            0x85.toByte(), REPORT_ID_MOUSE,
            0x09, 0x01,                    //   USAGE (Pointer)
            0xa1.toByte(), 0x00,           //   COLLECTION (Physical)
            0x05, 0x09,                    //     USAGE_PAGE (Button)
            0x19, 0x01,                    //     USAGE_MINIMUM (Button 1)
            0x29, 0x05,                    //     USAGE_MAXIMUM (Button 5)
            0x15, 0x00,                    //     LOGICAL_MINIMUM (0)
            0x25, 0x01,                    //     LOGICAL_MAXIMUM (1)
            0x95.toByte(), 0x05,           //     REPORT_COUNT (5)
            0x75, 0x01,                    //     REPORT_SIZE (1)
            0x81.toByte(), 0x02,           //     INPUT (Data, Var, Abs)
            0x95.toByte(), 0x01,           //     REPORT_COUNT (1)
            0x75, 0x03,                    //     REPORT_SIZE (3)
            0x81.toByte(), 0x01,           //     INPUT (Cnst) - padding
            0x05, 0x01,                    //     USAGE_PAGE (Generic Desktop)
            0x09, 0x30,                    //     USAGE (X)
            0x09, 0x31,                    //     USAGE (Y)
            0x09, 0x38,                    //     USAGE (Wheel)
            0x15, 0x81.toByte(),           //     LOGICAL_MINIMUM (-127)
            0x25, 0x7f,                    //     LOGICAL_MAXIMUM (127)
            0x75, 0x08,                    //     REPORT_SIZE (8)
            0x95.toByte(), 0x03,           //     REPORT_COUNT (3)
            0x81.toByte(), 0x06,           //     INPUT (Data, Var, Rel)
            0x05, 0x0c,                    //     USAGE_PAGE (Consumer)
            0x0a, 0x38, 0x02,              //     USAGE (AC Pan) - horizontal scroll
            0x15, 0x81.toByte(),           //     LOGICAL_MINIMUM (-127)
            0x25, 0x7f,                    //     LOGICAL_MAXIMUM (127)
            0x75, 0x08,                    //     REPORT_SIZE (8)
            0x95.toByte(), 0x01,           //     REPORT_COUNT (1)
            0x81.toByte(), 0x06,           //     INPUT (Data, Var, Rel)
            0xc0.toByte(),                 //   END_COLLECTION
            0xc0.toByte(),                 // END_COLLECTION

            // ===== ETS2 gamepad (report ID 3) =====
            // Every stick-type axis (X, Y, Rx, Ry) rests at its center. The pedals rest at zero, so they use
            // Slider / Dial: games never use those to drive the menu cursor.
            0x05, 0x01,                    // USAGE_PAGE (Generic Desktop)
            0x09, 0x05,                    // USAGE (Gamepad)
            0xa1.toByte(), 0x01,           // COLLECTION (Application)
            0x85.toByte(), REPORT_ID_JOYSTICK,
            0x09, 0x30,                    //   USAGE (X - Steering)
            0x16, 0x01, 0x80.toByte(),     //   LOGICAL_MINIMUM (-32767)
            0x26, 0xff.toByte(), 0x7f,     //   LOGICAL_MAXIMUM (32767)
            0x75, 0x10,                    //   REPORT_SIZE (16)
            0x95.toByte(), 0x01,           //   REPORT_COUNT (1)
            0x81.toByte(), 0x02,           //   INPUT (Data, Var, Abs)
            0x09, 0x31,                    //   USAGE (Y - unused, centered)
            0x09, 0x33,                    //   USAGE (Rx - Look X)
            0x09, 0x34,                    //   USAGE (Ry - Look Y)
            0x09, 0x36,                    //   USAGE (Slider - Throttle)
            0x09, 0x37,                    //   USAGE (Dial - Brake)
            0x15, 0x00,                    //   LOGICAL_MINIMUM (0)
            0x26, 0xff.toByte(), 0x00,     //   LOGICAL_MAXIMUM (255)
            0x75, 0x08,                    //   REPORT_SIZE (8)
            0x95.toByte(), 0x05,           //   REPORT_COUNT (5)
            0x81.toByte(), 0x02,           //   INPUT (Data, Var, Abs)
            0x09, 0x39,                    //   USAGE (Hat switch)
            0x15, 0x00,                    //   LOGICAL_MINIMUM (0)
            0x25, 0x07,                    //   LOGICAL_MAXIMUM (7)
            0x35, 0x00,                    //   PHYSICAL_MINIMUM (0)
            0x46, 0x3b, 0x01,              //   PHYSICAL_MAXIMUM (315)
            0x65, 0x14,                    //   UNIT (Eng Rot: Degrees)
            0x75, 0x04,                    //   REPORT_SIZE (4)
            0x95.toByte(), 0x01,           //   REPORT_COUNT (1)
            0x81.toByte(), 0x42,           //   INPUT (Data, Var, Abs, Null)
            0x65, 0x00,                    //   UNIT (None)
            0x75, 0x04,                    //   REPORT_SIZE (4)
            0x95.toByte(), 0x01,           //   REPORT_COUNT (1)
            0x81.toByte(), 0x03,           //   INPUT (Cnst) - padding
            0x05, 0x09,                    //   USAGE_PAGE (Button)
            0x19, 0x01,                    //   USAGE_MINIMUM (Button 1)
            0x29, 0x20,                    //   USAGE_MAXIMUM (Button 32)
            0x15, 0x00,                    //   LOGICAL_MINIMUM (0)
            0x25, 0x01,                    //   LOGICAL_MAXIMUM (1)
            0x75, 0x01,                    //   REPORT_SIZE (1)
            0x95.toByte(), 0x20,           //   REPORT_COUNT (32)
            0x81.toByte(), 0x02,           //   INPUT (Data, Var, Abs)
            0xc0.toByte(),                 // END_COLLECTION

            // ===== Consumer control (report ID 4) =====
            0x05, 0x0c,                    // USAGE_PAGE (Consumer)
            0x09, 0x01,                    // USAGE (Consumer Control)
            0xa1.toByte(), 0x01,           // COLLECTION (Application)
            0x85.toByte(), REPORT_ID_CONSUMER,
            0x15, 0x00,                    //   LOGICAL_MINIMUM (0)
            0x26, 0xff.toByte(), 0x03,     //   LOGICAL_MAXIMUM (1023)
            0x19, 0x00,                    //   USAGE_MINIMUM (0)
            0x2a, 0xff.toByte(), 0x03,     //   USAGE_MAXIMUM (1023)
            0x75, 0x10,                    //   REPORT_SIZE (16)
            0x95.toByte(), 0x01,           //   REPORT_COUNT (1)
            0x81.toByte(), 0x00,           //   INPUT (Data, Array)
            0xc0.toByte(),                 // END_COLLECTION
        )
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null

    var isRegistered = false
        private set
    var connectionState = ConnectionState.OFFLINE
        private set
    var connectedDeviceName: String? = null
        private set

    /** Keyboard LED bits from the host: 1 = Num Lock, 2 = Caps Lock, 4 = Scroll Lock. */
    @Volatile var keyboardLeds = 0
        private set

    /** True once a PC has connected before, so the app can reconnect to it automatically. */
    val hasKnownHost: Boolean
        get() = prefs.contains(KEY_LAST_HOST)

    private val connectionListeners = CopyOnWriteArraySet<(ConnectionState, String?) -> Unit>()
    private val ledListeners = CopyOnWriteArraySet<(Int) -> Unit>()

    /** Registers a listener invoked on the main thread whenever the host connection changes. */
    fun addConnectionListener(listener: (ConnectionState, String?) -> Unit) = connectionListeners.add(listener)
    fun removeConnectionListener(listener: (ConnectionState, String?) -> Unit) = connectionListeners.remove(listener)
    fun addLedListener(listener: (Int) -> Unit) = ledListeners.add(listener)
    fun removeLedListener(listener: (Int) -> Unit) = ledListeners.remove(listener)

    /** Number of HID reports successfully handed to the Bluetooth stack (for the status bar rate). */
    val reportsSent = AtomicInteger(0)

    // ---- Report state: only touched on the sender thread ----
    private val senderThread = HandlerThread("hid-sender").apply { start() }
    private val sender = Handler(senderThread.looper)
    @Volatile private var bootProtocol = false

    // Gamepad
    private var steering: Short = 0
    private var gas = 0
    private var brake = 0
    private var lookX = AXIS_CENTER
    private var lookY = AXIS_CENTER
    private var hat = 8
    private var heldButtons = 0
    private var pulseButtons = 0
    private var gamepadPending = false
    private var lastGamepadAt = 0L
    private val gamepadBuffer = ByteBuffer.allocate(GAMEPAD_REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val gamepadRunnable = Runnable {
        gamepadPending = false
        sendGamepadNow()
    }

    // Keyboard
    private var kbModifiers = 0
    private var kbKeys = IntArray(0)

    // Mouse
    private var mouseButtons = 0
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingWheel = 0
    private var pendingPan = 0
    private var mousePending = false
    private var lastMouseAt = 0L
    private var nextClickAt = 0L
    private val mouseRunnable = Runnable {
        mousePending = false
        flushMouse()
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                isRegistered = false
                connectedDevice = null
                updateConnection(ConnectionState.OFFLINE, null)
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            isRegistered = registered
            Log.d(TAG, "HID Application registered: $registered (plugged: ${pluggedDevice?.address})")
            if (registered && connectedDevice == null) {
                val host = pluggedDevice ?: lastHost()
                host?.let { connect(it) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            val name = safeName(device)
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    if (connectedDevice == null) updateConnection(ConnectionState.PAIRING, name)
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    device?.let { prefs.edit().putString(KEY_LAST_HOST, it.address).apply() }
                    Log.i(TAG, "Connected to host PC: $name (${device?.address})")
                    updateConnection(ConnectionState.CONNECTED, name)
                    sender.post { sendGamepadNow() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice == null || connectedDevice == device) {
                        connectedDevice = null
                        bootProtocol = false
                        sender.post { clearInputState() }
                        Log.i(TAG, "Disconnected from host PC")
                        updateConnection(ConnectionState.OFFLINE, null)
                    }
                }
            }
        }

        // Hosts poll the current input state during enumeration; answer with a real report
        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val hid = hidDevice ?: return
            if (device == null) return
            sender.post {
                try {
                    val payload = if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) when (id) {
                        REPORT_ID_KEYBOARD -> keyboardReport(kbModifiers, kbKeys)
                        REPORT_ID_MOUSE -> byteArrayOf(mouseButtons.toByte(), 0, 0, 0, 0)
                        REPORT_ID_JOYSTICK, 0.toByte() -> buildGamepadReport().copyOf()
                        REPORT_ID_CONSUMER -> byteArrayOf(0, 0)
                        else -> null
                    } else null
                    if (payload != null) {
                        hid.replyReport(device, type, if (id.toInt() == 0) REPORT_ID_JOYSTICK else id, payload)
                    } else {
                        hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing Bluetooth permission for replyReport", e)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == REPORT_ID_KEYBOARD) handleLeds(data)
            try {
                device?.let { hidDevice?.reportError(it, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permission for reportError", e)
            }
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            if (reportId == REPORT_ID_KEYBOARD) handleLeds(data)
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            bootProtocol = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
            Log.i(TAG, "Host set protocol: ${if (bootProtocol) "BOOT" else "REPORT"}")
        }
    }

    private fun handleLeds(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        // Some stacks include the report ID as the first byte
        val value = (if (data.size >= 2 && data[0] == REPORT_ID_KEYBOARD) data[1] else data[0]).toInt() and 0xff
        if (value == keyboardLeds) return
        keyboardLeds = value
        ledListeners.forEach { it(value) }
    }

    /**
     * Opens the HID Device profile proxy. Returns false if Bluetooth is missing or turned off.
     */
    @SuppressLint("MissingPermission")
    fun initialize(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        bluetoothAdapter = adapter
        if (hidDevice != null) return true
        return adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        // The PC-visible controller keeps its original name so existing pairings and ETS2 bindings still work
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TruckPad",
            "Keyboard, Mouse & ETS2 Controller",
            "Truck Simulators",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HID_REPORT_DESCRIPTOR
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, // Token rate
            9,   // Token bucket size
            0,   // Peak bandwidth
            11250, // Latency: 11.25 ms polling
            BluetoothHidDeviceAppQosSettings.MAX
        )
        try {
            hidDevice?.registerApp(sdp, null, qos, context.mainExecutor, hidCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission for registerApp", e)
        }
    }

    /** Paired devices, computers first, so the user can pick the PC to connect to. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> = try {
        bluetoothAdapter?.bondedDevices.orEmpty().sortedWith(
            compareByDescending<BluetoothDevice> {
                it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            }.thenBy { safeName(it) }
        )
    } catch (e: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        val hid = hidDevice ?: return false
        return try {
            updateConnection(ConnectionState.PAIRING, safeName(device))
            hid.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission for connect", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val device = connectedDevice ?: return
        releaseAllInput()
        resetGamepad()
        try {
            hidDevice?.disconnect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission for disconnect", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun safeName(device: BluetoothDevice?): String? = try {
        device?.name ?: device?.address
    } catch (e: SecurityException) {
        device?.address
    }

    private fun lastHost(): BluetoothDevice? {
        val address = prefs.getString(KEY_LAST_HOST, null) ?: return null
        return bondedDevices().firstOrNull { it.address == address }
    }

    private fun updateConnection(state: ConnectionState, name: String?) {
        connectionState = state
        connectedDeviceName = name
        connectionListeners.forEach { it(state, name) }
    }

    // ------------------------------------------------------------------
    // Gamepad (ETS2)
    // ------------------------------------------------------------------

    /**
     * Copies the analog inputs (steering, pedals, look) from [state] and schedules a report.
     * The retarder is sent as button presses only, so no axis rests off-center.
     */
    fun updateAxes(state: ControllerState) {
        val steeringRaw = (state.steeringNormalized * 32767f).roundToInt().coerceIn(-32767, 32767).toShort()
        val gasRaw = (state.gas * 2.55f).roundToInt().coerceIn(0, 255)
        val brakeRaw = (state.brake * 2.55f).roundToInt().coerceIn(0, 255)
        val lx = state.lookPanX
        val ly = state.lookPanY
        sender.post {
            steering = steeringRaw
            gas = gasRaw
            brake = brakeRaw
            lookX = ((lx + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
            lookY = ((ly + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
            hat = hatFor(lx, ly)
            scheduleGamepadSend()
        }
    }

    /** Returns every gamepad control to rest (used when leaving the ETS2 screen). */
    fun resetGamepad() {
        sender.post {
            steering = 0
            gas = 0
            brake = 0
            lookX = AXIS_CENTER
            lookY = AXIS_CENTER
            hat = 8
            heldButtons = 0
            sendGamepadNow()
        }
    }

    /** Sends a short press + release of a 1-based HID button (toggle-style actions). */
    fun pressButton(button: Int) {
        val bit = 1 shl (button - 1)
        sender.post {
            if (pulseButtons and bit != 0) {
                // Previous press of the same button is still down: queue this one after its release
                sender.postDelayed({ pressButton(button) }, PULSE_MS)
                return@post
            }
            pulseButtons = pulseButtons or bit
            sendGamepadNow()
            sender.postDelayed({
                pulseButtons = pulseButtons and bit.inv()
                sendGamepadNow()
            }, PULSE_MS)
        }
    }

    /** Holds or releases a 1-based HID button (horn, CB push-to-talk). */
    fun setButtonHeld(button: Int, held: Boolean) {
        val bit = 1 shl (button - 1)
        sender.post {
            heldButtons = if (held) heldButtons or bit else heldButtons and bit.inv()
            sendGamepadNow()
        }
    }

    private fun hatFor(x: Float, y: Float): Int {
        if (hypot(x, y) < LOOK_HAT_THRESHOLD) return 8
        // 0 = up, clockwise in 45° steps (screen Y grows downward)
        val degrees = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).let { if (it < 0) it + 360 else it }
        return ((degrees / 45.0).roundToInt()) % 8
    }

    private fun scheduleGamepadSend() {
        if (gamepadPending) return
        gamepadPending = true
        val wait = (lastGamepadAt + MIN_REPORT_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        sender.postDelayed(gamepadRunnable, wait)
    }

    private fun sendGamepadNow() {
        if (send(REPORT_ID_JOYSTICK, buildGamepadReport())) lastGamepadAt = SystemClock.uptimeMillis()
    }

    /** Serializes the gamepad state (sender thread only). Layout must match the descriptor. */
    private fun buildGamepadReport(): ByteArray {
        gamepadBuffer.clear()
        gamepadBuffer.putShort(steering)           // X
        gamepadBuffer.put(AXIS_CENTER.toByte())    // Y (unused, centered)
        gamepadBuffer.put(lookX.toByte())          // Rx
        gamepadBuffer.put(lookY.toByte())          // Ry
        gamepadBuffer.put(gas.toByte())            // Slider
        gamepadBuffer.put(brake.toByte())          // Dial
        gamepadBuffer.put(hat.toByte())            // 4-bit hat + 4-bit padding
        gamepadBuffer.putInt(heldButtons or pulseButtons)
        return gamepadBuffer.array()
    }

    // ------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------

    /**
     * Sets the complete keyboard state: [modifiers] is the HID modifier byte
     * (bit 0 = Left Ctrl … bit 7 = Right GUI), [keys] the pressed key usages (max 6 sent).
     */
    fun sendKeyboard(modifiers: Int, keys: List<Int>) {
        val snapshot = keys.take(6).toIntArray()
        sender.post {
            kbModifiers = modifiers and 0xff
            kbKeys = snapshot
            send(REPORT_ID_KEYBOARD, keyboardReport(kbModifiers, kbKeys))
        }
    }

    /** Presses and releases a key combination (e.g. Shift+F5), independent of held keys. */
    fun tapKeys(modifiers: Int, vararg keys: Int) {
        sender.post {
            send(REPORT_ID_KEYBOARD, keyboardReport(modifiers or kbModifiers, keys + kbKeys))
            sender.postDelayed({ send(REPORT_ID_KEYBOARD, keyboardReport(kbModifiers, kbKeys)) }, PULSE_MS)
        }
    }

    /**
     * Types [text] on the PC through the keyboard, one character at a time (US layout).
     * Characters that have no key on a US keyboard are skipped. Returns the number typed.
     */
    fun typeText(text: String): Int {
        val strokes = text.mapNotNull { usKeyFor(it) }
        sender.post {
            var at = SystemClock.uptimeMillis()
            strokes.forEach { (usage, shift) ->
                val mods = if (shift) 0x02 else 0
                sender.postAtTime({ send(REPORT_ID_KEYBOARD, keyboardReport(mods, intArrayOf(usage))) }, at)
                sender.postAtTime({ send(REPORT_ID_KEYBOARD, keyboardReport(kbModifiers, kbKeys)) }, at + TYPE_KEY_MS)
                at += TYPE_KEY_MS * 2
            }
        }
        return strokes.size
    }

    /** Key usage and whether Shift is needed for [c] on a US keyboard layout. */
    private fun usKeyFor(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> 0x04 + (c - 'a') to false
        in 'A'..'Z' -> 0x04 + (c - 'A') to true
        in '1'..'9' -> 0x1E + (c - '1') to false
        '0' -> 0x27 to false
        '\n' -> 0x28 to false
        '\t' -> 0x2B to false
        ' ' -> 0x2C to false
        else -> {
            val plain = "-=[]\\;'`,./"
            val shifted = "_+{}|:\"~<>?"
            val plainUsages = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
            val digitsShifted = "!@#$%^&*()"
            when {
                plain.indexOf(c) >= 0 -> plainUsages[plain.indexOf(c)] to false
                shifted.indexOf(c) >= 0 -> plainUsages[shifted.indexOf(c)] to true
                digitsShifted.indexOf(c) >= 0 -> (if (c == ')') 0x27 else 0x1E + digitsShifted.indexOf(c)) to true
                else -> null
            }
        }
    }

    private fun keyboardReport(modifiers: Int, keys: IntArray): ByteArray {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        keys.distinct().take(6).forEachIndexed { i, usage -> report[2 + i] = usage.toByte() }
        return report
    }

    // ------------------------------------------------------------------
    // Consumer (media) keys
    // ------------------------------------------------------------------

    /** Holds a consumer usage (e.g. Volume Up 0xE9); pass 0 to release. */
    fun setConsumer(usage: Int) {
        sender.post { send(REPORT_ID_CONSUMER, byteArrayOf((usage and 0xff).toByte(), (usage shr 8 and 0xff).toByte())) }
    }

    // ------------------------------------------------------------------
    // Mouse
    // ------------------------------------------------------------------

    /** Queues relative pointer motion; coalesced and split into ±127 steps. */
    fun moveMouse(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        sender.post {
            pendingDx += dx
            pendingDy += dy
            scheduleMouseSend()
        }
    }

    /** Queues wheel ticks: [vertical] > 0 scrolls up, [horizontal] > 0 scrolls right. */
    fun scrollMouse(vertical: Int, horizontal: Int) {
        if (vertical == 0 && horizontal == 0) return
        sender.post {
            pendingWheel += vertical
            pendingPan += horizontal
            scheduleMouseSend()
        }
    }

    /** Presses or releases mouse button bits ([MOUSE_LEFT], [MOUSE_RIGHT], [MOUSE_MIDDLE]). */
    fun setMouseButton(mask: Int, pressed: Boolean) {
        sender.post {
            mouseButtons = if (pressed) mouseButtons or mask else mouseButtons and mask.inv()
            flushMouse()
        }
    }

    /** Clicks a mouse button; consecutive calls are serialized so double-clicks arrive intact. */
    fun clickMouse(mask: Int) {
        sender.post {
            val now = SystemClock.uptimeMillis()
            val start = max(now, nextClickAt)
            sender.postAtTime({
                mouseButtons = mouseButtons or mask
                flushMouse()
            }, start)
            sender.postAtTime({
                mouseButtons = mouseButtons and mask.inv()
                flushMouse()
            }, start + CLICK_MS)
            nextClickAt = start + CLICK_MS * 2
        }
    }

    private fun scheduleMouseSend() {
        if (mousePending) return
        mousePending = true
        val wait = (lastMouseAt + MIN_REPORT_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        sender.postDelayed(mouseRunnable, wait)
    }

    /** Sends one mouse report with as much of the pending motion as fits (sender thread only). */
    private fun flushMouse() {
        val dx = pendingDx.coerceIn(-127, 127)
        val dy = pendingDy.coerceIn(-127, 127)
        val wheel = pendingWheel.coerceIn(-127, 127)
        val pan = pendingPan.coerceIn(-127, 127)
        pendingDx -= dx
        pendingDy -= dy
        pendingWheel -= wheel
        pendingPan -= pan
        send(REPORT_ID_MOUSE, byteArrayOf(mouseButtons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte(), pan.toByte()))
        lastMouseAt = SystemClock.uptimeMillis()
        if (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0 || pendingPan != 0) scheduleMouseSend()
    }

    // ------------------------------------------------------------------
    // Common
    // ------------------------------------------------------------------

    /** Releases every keyboard key, mouse button and media key (used when leaving an input screen). */
    fun releaseAllInput() {
        sender.post {
            val hadKeys = kbModifiers != 0 || kbKeys.isNotEmpty()
            val hadButtons = mouseButtons != 0
            clearInputState()
            if (hadKeys) send(REPORT_ID_KEYBOARD, keyboardReport(0, kbKeys))
            if (hadButtons) send(REPORT_ID_MOUSE, byteArrayOf(0, 0, 0, 0, 0))
            send(REPORT_ID_CONSUMER, byteArrayOf(0, 0))
        }
    }

    private fun clearInputState() {
        kbModifiers = 0
        kbKeys = IntArray(0)
        mouseButtons = 0
        pendingDx = 0
        pendingDy = 0
        pendingWheel = 0
        pendingPan = 0
    }

    @SuppressLint("MissingPermission")
    private fun send(reportId: Byte, payload: ByteArray): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        // Boot protocol has no report IDs; our reports would be misread, so stay silent
        if (bootProtocol) return false
        return try {
            hid.sendReport(device, reportId.toInt(), payload).also { if (it) reportsSent.incrementAndGet() }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission for sendReport", e)
            false
        }
    }
}
