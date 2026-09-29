package com.truckcontroller.pro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.dimmer.NightScreen
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.input.InputMode
import com.truckcontroller.pro.model.ConnectionState
import com.truckcontroller.pro.ui.ActionTile

/**
 * Landing page: pick the ETS2 controller or one of the keyboard & mouse modes.
 * Handles first-run Bluetooth permission and pairing so every mode starts connected.
 */
class HomeActivity : HidActivity() {

    companion object {
        /** Set by the Quick Settings tile when Night Screen needs the app (e.g. for permissions). */
        const val EXTRA_OPEN_NIGHT_SCREEN = "open_night_screen"
    }

    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var connectionPill: LinearLayout
    private lateinit var connectionDot: View
    private lateinit var tvConnection: TextView
    private lateinit var tvPairPc: TextView
    private lateinit var nightTile: ActionTile
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

    override val offerPairingOnFirstRun = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        haptics = HapticFeedbackHelper(this)

        connectionPill = findViewById(R.id.connectionPill)
        connectionDot = findViewById(R.id.connectionDot)
        tvConnection = findViewById(R.id.tvConnection)
        tvPairPc = findViewById(R.id.tvPairPc)
        findViewById<TextView>(R.id.tvTitle).text = spans("Truck" to null, "Pad" to R.color.accent)

        val openBluetooth = View.OnClickListener {
            haptics.performButtonClickHaptic()
            openBluetoothDialog()
        }
        connectionPill.setOnClickListener(openBluetooth)
        findViewById<View>(R.id.btnPairPc).setOnClickListener(openBluetooth)

        // Game controller
        findViewById<FrameLayout>(R.id.gameSlot).addView(
            ActionTile(this).apply {
                title = "ETS2 Truck Controller"
                subtitle = "Steering wheel · pedals · shifter · cockpit switches"
                setIcon(R.drawable.ic_truck)
                accentColor = color(R.color.accent)
                primary = true
                setOnClickListener { open(Intent(this@HomeActivity, MainActivity::class.java)) }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Keyboard & mouse modes: 3 on the first row, 2 on the second
        val rows = listOf(findViewById<LinearLayout>(R.id.inputRow1), findViewById(R.id.inputRow2))
        InputMode.entries.forEachIndexed { index, mode ->
            val row = rows[if (index < 3) 0 else 1]
            row.addView(
                ActionTile(this).apply {
                    title = mode.title
                    subtitle = mode.subtitle
                    setIcon(mode.icon)
                    accentColor = color(mode.accent)
                    setOnClickListener { open(InputActivity.intent(this@HomeActivity, mode)) }
                },
                // Landscape rows are horizontal (tiles side by side); portrait rows stack them
                if (row.orientation == LinearLayout.HORIZONTAL) {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (row.childCount > 0) marginStart = dp(10)
                    }
                } else {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                        if (row.childCount > 0 || index >= 3) topMargin = dp(8)
                    }
                }
            )
        }

        // Night Screen dimmer shares the second row
        val nightRow = rows[1]
        nightTile = ActionTile(this).apply {
            title = "Night Screen"
            setIcon(R.drawable.ic_moon)
            accentColor = color(R.color.amber_400)
            setOnClickListener {
                haptics.performButtonClickHaptic()
                showNightScreenDialog()
            }
        }
        nightRow.addView(
            nightTile,
            if (nightRow.orientation == LinearLayout.HORIZONTAL) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(10) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
            }
        )

        setFullscreen(true)
        ensureBluetooth()
        if (intent.getBooleanExtra(EXTRA_OPEN_NIGHT_SCREEN, false)) showNightScreenDialog()
    }

    override fun onResume() {
        super.onResume()
        renderNightTile()
    }

    private fun renderNightTile() {
        nightTile.subtitle = if (NightScreen.isRunning) {
            "On · dimmed ${NightScreen.level(this)} %"
        } else {
            "Screen dimmer · adjustable from notifications"
        }
        nightTile.isActive = NightScreen.isRunning
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
                override fun onStopTrackingTouch(sb: SeekBar?) = renderNightTile()
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
            .setOnDismissListener { renderNightTile() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            if (NightScreen.isRunning) {
                NightScreen.stop(this)
                (button as TextView).text = "Turn on"
            } else {
                startNightScreen()
                (button as TextView).text = "Turn off"
            }
            button.postDelayed({ renderNightTile() }, 300)
        }
        nightDialog = dialog
    }

    /** Checks the overlay (and, on Android 13+, notification) permission, then starts the dimmer. */
    private fun startNightScreen(askNotifications: Boolean = true) {
        if (!NightScreen.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow display over other apps")
                .setMessage("Night Screen draws a dark layer above all apps. On the next screen, turn on " +
                    "\"Allow display over other apps\" for TruckPad, then come back.")
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
        window.decorView.postDelayed({ renderNightTile() }, 300)
    }

    private fun open(intent: Intent) {
        haptics.performButtonClickHaptic()
        startActivity(intent)
    }

    override fun onConnectionChanged() {
        if (!::connectionPill.isInitialized) return
        renderConnectionPill(connectionPill, connectionDot, tvConnection)
        tvPairPc.text = if (hidService.connectionState == ConnectionState.CONNECTED) "PC LINKED" else "PAIR PC"
    }
}
