package com.truckcontroller.pro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.bluetooth.BluetoothHidService
import com.truckcontroller.pro.model.ConnectionState
import com.truckcontroller.pro.ui.BluetoothDialog
import com.truckcontroller.pro.ui.showBluetoothDialog
import kotlin.math.roundToInt

/**
 * Base screen for everything that talks to the PC: Bluetooth permissions, enabling Bluetooth,
 * the pairing dialog and the shared connection pill. Fullscreen comes from [BaseActivity].
 */
abstract class HidActivity : BaseActivity() {

    protected lateinit var hidService: BluetoothHidService
    private var bluetoothDialog: BluetoothDialog? = null

    private val bluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startBluetoothHid()
            } else {
                Toast.makeText(this, "Bluetooth permission is required to connect to the PC", Toast.LENGTH_LONG).show()
                onConnectionChanged()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startBluetoothHid() }

    // Makes the phone visible so the PC can find and pair with it
    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val connectionListener: (ConnectionState, String?) -> Unit = { _, _ ->
        onConnectionChanged()
        bluetoothDialog?.let { if (it.dialog.isShowing) it.refresh() }
    }

    /** Called on the main thread when the link state changes; subclasses re-render. */
    protected open fun onConnectionChanged() = Unit

    /** Whether this screen should open the pairing dialog on first run. */
    protected open val offerPairingOnFirstRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hidService = BluetoothHidService.get(this)
    }

    /** Call once the content view exists. */
    protected fun ensureBluetooth() {
        if (hasBluetoothPermissions()) startBluetoothHid() else permissionLauncher.launch(bluetoothPermissions)
    }

    override fun onStart() {
        super.onStart()
        hidService.addConnectionListener(connectionListener)
        onConnectionChanged()
    }

    override fun onStop() {
        super.onStop()
        hidService.removeConnectionListener(connectionListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothDialog?.dialog?.dismiss()
    }

    protected fun hasBluetoothPermissions(): Boolean = bluetoothPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startBluetoothHid() {
        if (!hidService.initialize()) {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter != null && !adapter.isEnabled && hasBluetoothPermissions()) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                Toast.makeText(this, "Bluetooth is unavailable on this device", Toast.LENGTH_LONG).show()
            }
            onConnectionChanged()
            return
        }
        onConnectionChanged()
        if (offerPairingOnFirstRun && !hidService.hasKnownHost) {
            window.decorView.postDelayed({ if (!isFinishing) openBluetoothDialog() }, 800)
        }
    }

    private fun makeDiscoverable() {
        if (!hasBluetoothPermissions()) {
            permissionLauncher.launch(bluetoothPermissions)
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        discoverableLauncher.launch(intent)
    }

    protected fun openBluetoothDialog() {
        if (!hasBluetoothPermissions()) {
            permissionLauncher.launch(bluetoothPermissions)
            return
        }
        if (bluetoothDialog?.dialog?.isShowing == true) return
        bluetoothDialog = showBluetoothDialog(hidService, ::makeDiscoverable)
    }

    // ------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------

    protected fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    protected fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    /** Builds text where each part can have its own (bold) color. */
    protected fun spans(vararg parts: Pair<String, Int?>): CharSequence {
        val builder = SpannableStringBuilder()
        parts.forEach { (text, colorRes) ->
            val start = builder.length
            builder.append(text)
            if (colorRes != null) {
                builder.setSpan(ForegroundColorSpan(color(colorRes)), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return builder
    }

    /** Renders the shared connection pill (dot + label) and returns whether the PC is connected. */
    protected fun renderConnectionPill(pill: LinearLayout, dot: View, label: TextView): Boolean {
        val state = hidService.connectionState
        val (text, fg, dotColor) = when (state) {
            ConnectionState.CONNECTED -> Triple(
                "Connected: ${hidService.connectedDeviceName ?: "PC"}", R.color.emerald_400, R.color.emerald_400
            )
            ConnectionState.PAIRING -> Triple("Pairing…", R.color.amber_400, R.color.amber_400)
            ConnectionState.OFFLINE -> Triple("Offline", R.color.slate_400, R.color.slate_500)
        }
        label.text = text
        label.setTextColor(color(fg))
        dot.backgroundTintList = ColorStateList.valueOf(color(dotColor))
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            val c = color(fg)
            setColor(if (state == ConnectionState.OFFLINE) Color.parseColor("#CC1E293B")
                else Color.argb(38, Color.red(c), Color.green(c), Color.blue(c)))
            setStroke(dp(1), Color.argb(77, Color.red(c), Color.green(c), Color.blue(c)))
        }
        return state == ConnectionState.CONNECTED
    }
}
