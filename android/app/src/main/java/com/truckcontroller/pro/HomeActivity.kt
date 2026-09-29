package com.truckcontroller.pro

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.input.InputMode
import com.truckcontroller.pro.model.ConnectionState
import com.truckcontroller.pro.ui.ActionTile

/**
 * Landing page: pick the ETS2 controller or one of the keyboard & mouse modes.
 * Handles first-run Bluetooth permission and pairing so every mode starts connected.
 */
class HomeActivity : HidActivity() {

    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var connectionPill: LinearLayout
    private lateinit var connectionDot: View
    private lateinit var tvConnection: TextView
    private lateinit var tvPairPc: TextView

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

        setFullscreen(true)
        ensureBluetooth()
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
