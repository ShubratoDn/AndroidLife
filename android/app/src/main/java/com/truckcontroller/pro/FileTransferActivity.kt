package com.truckcontroller.pro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.qr.QrDesign
import com.truckcontroller.pro.qr.QrRenderer
import com.truckcontroller.pro.transfer.FileTransfer
import com.truckcontroller.pro.transfer.FileTransfer.ServerState

/**
 * File Transfer: start the phone's web server, then open the shown address in any browser on a
 * PC connected to the same Wi-Fi or hotspot. Shows the address, PIN, connected browsers and live
 * transfers; the server keeps running in the background until stopped.
 */
class FileTransferActivity : ToolActivity() {

    private val accent = Color.parseColor("#38BDF8")
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var toggleButton: TextView
    private lateinit var pcCard: View
    private lateinit var addressBox: LinearLayout
    private lateinit var pinText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var connectedText: TextView
    private lateinit var totalsText: TextView
    private lateinit var transfersBox: LinearLayout
    private var addressKey = ""
    private var transfersKey = ""
    /** Start as soon as the user comes back from granting access. */
    private var startWhenAllowed = false

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onStorageResult() }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { FileTransfer.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        page.addCard(serverCard())
        pcCard = pcCard()
        page.addCard(pcCard)
        page.addCard(activityCard())
        page.addCard(optionsCard())
        page.addCard(helpCard())
        setContentView(toolPage("File Transfer", R.drawable.ic_transfer, accent, ScrollView(this).apply { addView(page) }))
    }

    override fun onResume() {
        super.onResume()
        if (startWhenAllowed) onStorageResult()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun LinearLayout.addCard(view: View) =
        addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    private fun serverCard() = titledCard("SERVER", R.drawable.ic_power, accent).apply {
        statusTitle = TextView(context).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        statusSub = TextView(context).apply {
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(color(R.color.slate_300))
        }
        toggleButton = button("", filled = true) { onToggle() }
        addView(statusTitle)
        addView(statusSub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(toggleButton, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) })
    }

    private fun pcCard() = titledCard("OPEN ON YOUR PC", R.drawable.ic_monitor, accent).apply {
        addView(TextView(context).apply {
            text = "Type this address in Chrome, Edge or Firefox on the PC. Tap it to copy."
            textSize = 13f
            setTextColor(color(R.color.slate_400))
        })
        addressBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(addressBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        addView(sectionLabel("PIN"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        pinText = TextView(context, null, 0, R.style.Cockpit_Mono).apply {
            textSize = 30f
            letterSpacing = 0.15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        addView(pinText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        qrImage = ImageView(context).apply { adjustViewBounds = true }
        addView(qrImage, LinearLayout.LayoutParams(dp(170), dp(170)).apply {
            topMargin = dp(16)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(TextView(context).apply {
            text = "Tablet or another phone? Scan this code with its camera."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.slate_400))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun activityCard() = titledCard("ACTIVITY", R.drawable.ic_history, accent).apply {
        connectedText = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        totalsText = TextView(context).apply {
            textSize = 12f
            setTextColor(color(R.color.slate_400))
        }
        transfersBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(connectedText)
        addView(totalsText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        addView(transfersBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun optionsCard() = titledCard("OPTIONS", R.drawable.ic_sliders_horizontal, accent).apply {
        addView(switch("Require PIN", FileTransfer.requirePin(context)) {
            FileTransfer.setRequirePin(this@FileTransferActivity, it)
            addressKey = ""
        })
        addView(hint("Anyone on the same network could open your files without it. Keep it on for shared or public Wi-Fi."))
        addView(switch("Read-only", FileTransfer.readOnly(context)) {
            FileTransfer.setReadOnly(this@FileTransferActivity, it)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(hint("The PC can browse and download, but can't upload, rename or delete."))
    }

    private fun helpCard() = titledCard("HOW TO CONNECT", R.drawable.ic_info, accent).apply {
        addView(TextView(context).apply {
            text = "1. Put the PC and this phone on the same Wi-Fi. No router? Turn on this phone's hotspot " +
                "and connect the PC to it (or the other way round). Files move directly between the two " +
                "devices and don't use mobile data.\n" +
                "2. Tap Start.\n" +
                "3. On the PC, open the address above in a web browser and enter the PIN.\n" +
                "4. Drag files onto the page to send them to the phone, or select files and folders to download them.\n\n" +
                "Nothing needs to be installed on the PC. Guest Wi-Fi networks often stop devices from " +
                "reaching each other; use the hotspot instead."
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setTextColor(color(R.color.slate_300))
        })
    }

    // ------------------------------------------------------------------
    // Live state
    // ------------------------------------------------------------------

    private fun render() {
        val state = FileTransfer.state
        val storageOk = FileTransfer.hasStorageAccess(this)
        when (state) {
            ServerState.RUNNING -> {
                statusTitle.text = "Running"
                statusTitle.setTextColor(color(R.color.emerald_400))
                statusSub.text = "Keeps running in the background. Stop it when you're done, or from the notification."
            }
            ServerState.STARTING -> {
                statusTitle.text = "Starting…"
                statusTitle.setTextColor(color(R.color.amber_400))
                statusSub.text = ""
            }
            ServerState.OFF -> {
                statusTitle.text = "Off"
                statusTitle.setTextColor(color(R.color.slate_300))
                statusSub.text = FileTransfer.error ?: if (storageOk) {
                    "Start to open this phone's files from a PC's web browser."
                } else {
                    "PhoneDeck needs access to all files so the PC can browse and save them."
                }
            }
        }
        val on = state != ServerState.OFF
        toggleButton.text = when {
            on -> "Stop"
            !storageOk -> "Allow access to files"
            else -> "Start"
        }
        styleButton(toggleButton, filled = !on)

        pcCard.visibility = if (state == ServerState.RUNNING) View.VISIBLE else View.GONE
        if (state == ServerState.RUNNING) renderAddresses()

        val clients = FileTransfer.connectedClients()
        connectedText.text = when {
            !on -> "Server is off"
            clients.isEmpty() -> "No browser connected yet"
            clients.size == 1 -> "1 browser connected · ${clients[0]}"
            else -> "${clients.size} browsers connected · ${clients.joinToString(", ")}"
        }
        totalsText.text = "Sent ${formatBytes(FileTransfer.bytesSent.get())} · Received ${formatBytes(FileTransfer.bytesReceived.get())}"
        totalsText.visibility = if (on) View.VISIBLE else View.GONE
        renderTransfers()
    }

    private fun renderAddresses() {
        val addresses = FileTransfer.addresses()
        val requirePin = FileTransfer.requirePin(this)
        val key = "$addresses|${FileTransfer.port}|${FileTransfer.pin}|$requirePin"
        if (key == addressKey) return
        addressKey = key

        addressBox.removeAllViews()
        if (addresses.isEmpty()) {
            addressBox.addView(TextView(this).apply {
                text = "This phone isn't on a Wi-Fi network. Connect to Wi-Fi, or turn on the hotspot and connect the PC to it."
                textSize = 14f
                setTextColor(color(R.color.amber_400))
                setPadding(0, dp(6), 0, 0)
            })
        }
        addresses.forEach { address ->
            val url = address.url(FileTransfer.port)
            addressBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#0C1622"))
                    setStroke(dp(1), Color.parseColor("#1E3A52"))
                }
                addView(sectionLabel(address.label.uppercase()).apply { setTextColor(accent) })
                addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                    text = url.removePrefix("http://")
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
                setOnClickListener {
                    haptics.performButtonClickHaptic()
                    copy("address", url)
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }

        pinText.text = if (requirePin) FileTransfer.pin.chunked(3).joinToString(" ") else "Off"
        pinText.textSize = if (requirePin) 30f else 18f

        val first = addresses.firstOrNull()
        qrImage.visibility = if (first == null) View.GONE else View.VISIBLE
        if (first != null) {
            val qr = QrRenderer.encode(first.url(FileTransfer.port), withLogo = false)
            qrImage.setImageBitmap(QrRenderer.render(qr, QrDesign(), null, dp(170)))
        }
    }

    private fun renderTransfers() {
        val transfers = FileTransfer.transfers.take(8)
        val key = transfers.joinToString { "${it.id}:${it.done}:${it.finished}" }
        if (key == transfersKey) return
        transfersKey = key
        transfersBox.removeAllViews()
        transfers.forEach { t ->
            transfersBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(if (t.upload) R.drawable.ic_arrow_left else R.drawable.ic_arrow_right)
                        setColorFilter(if (t.upload) color(R.color.emerald_400) else accent)
                        contentDescription = if (t.upload) "From PC" else "To PC"
                    }, LinearLayout.LayoutParams(dp(16), dp(16)))
                    addView(TextView(context).apply {
                        text = t.name
                        textSize = 14f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
                })
                if (!t.finished) {
                    addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = if (t.total > 0) (t.done * 1000 / t.total).toInt() else 0
                        progressTintList = ColorStateList.valueOf(if (t.upload) color(R.color.emerald_400) else accent)
                    }, LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(6) })
                }
                addView(TextView(context).apply {
                    val direction = if (t.upload) "From PC" else "To PC"
                    text = when {
                        t.failed -> "$direction · Stopped at ${formatBytes(t.done)}"
                        t.finished -> "$direction · ${formatBytes(t.done)} · ${formatBytes(t.speed)}/s"
                        else -> "$direction · ${if (t.total > 0) t.done * 100 / t.total else 0} % · " +
                            "${formatBytes(t.done)} of ${formatBytes(t.total)} · ${formatBytes(t.speed)}/s"
                    }
                    textSize = 12f
                    setTextColor(if (t.failed) color(R.color.red_400) else color(R.color.slate_400))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
            })
        }
    }

    // ------------------------------------------------------------------
    // Start / stop and permissions
    // ------------------------------------------------------------------

    private fun onToggle() {
        if (FileTransfer.state != ServerState.OFF) {
            FileTransfer.stop(this)
            return
        }
        if (!FileTransfer.hasStorageAccess(this)) {
            requestStorage()
            return
        }
        start()
    }

    private fun start() {
        startWhenAllowed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        FileTransfer.start(this)
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow access to all files")
            .setMessage("So the PC can browse your folders and save files to them, turn on " +
                "\"Allow access to manage all files\" for PhoneDeck on the next screen, then come back.")
            .setPositiveButton("Open settings") { _, _ ->
                startWhenAllowed = true
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }
                    .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onStorageResult() {
        if (FileTransfer.hasStorageAccess(this)) {
            FileTransfer.error = null
            start()
        } else {
            startWhenAllowed = false
        }
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(R.color.slate_400))
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchMaterial(this).apply {
        text = label
        isChecked = checked
        textSize = 14f
        setTextColor(color(R.color.slate_300))
        setOnCheckedChangeListener { _, value -> haptics.performButtonClickHaptic(); onChange(value) }
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        styleButton(this, filled)
        setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
    }

    private fun styleButton(view: TextView, filled: Boolean) {
        view.setTextColor(if (filled) Color.parseColor("#062033") else color(R.color.red_400))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (filled) accent else Color.parseColor("#1E293B"))
        }
    }
}
