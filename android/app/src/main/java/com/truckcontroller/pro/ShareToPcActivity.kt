package com.truckcontroller.pro

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.IntentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.truckcontroller.pro.transfer.FileTransfer
import com.truckcontroller.pro.transfer.Hub

/**
 * "Send to PC" in the system share sheet: sends the shared files or text to browsers connected to
 * File Transfer. Stays open while the PCs download, because the sharing app's permission to read
 * the files lasts only as long as this screen.
 */
class ShareToPcActivity : ToolActivity() {

    private val accent = Color.parseColor("#38BDF8")
    private val handler = Handler(Looper.getMainLooper())

    private var uris: List<Uri> = emptyList()
    private var sharedText: String? = null
    private val selected = LinkedHashSet<String>()
    private val seen = HashSet<String>()
    private var share: Hub.Share? = null

    private lateinit var status: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var actionButton: TextView
    private var devicesKey = ""

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) uris = listOf(stream) else sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            Intent.ACTION_SEND_MULTIPLE ->
                uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }
        if (uris.isEmpty() && sharedText.isNullOrBlank()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        page.addView(titledCard("SENDING", R.drawable.ic_share, accent).apply {
            addView(TextView(context).apply {
                text = if (uris.isNotEmpty()) {
                    if (uris.size == 1) "1 file" else "${uris.size} files"
                } else sharedText
                textSize = 16f
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        page.addView(titledCard("SEND TO", R.drawable.ic_monitor, accent).apply {
            status = TextView(context).apply {
                textSize = 13f
                setTextColor(color(R.color.slate_300))
            }
            devicesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            actionButton = TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { haptics.performButtonClickHaptic(); onAction() }
            }
            addView(status)
            addView(devicesBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            addView(actionButton, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        setContentView(toolPage("Send to PC", R.drawable.ic_transfer, accent, ScrollView(this).apply { addView(page) }))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun finish() {
        handler.removeCallbacks(ticker)
        super.finish()
    }

    private fun render() {
        val current = share
        if (current != null) return renderProgress(current)
        val hub = FileTransfer.hub
        if (!FileTransfer.isRunning || hub == null) {
            status.text = if (FileTransfer.state == FileTransfer.ServerState.STARTING) "Starting File Transfer…"
                else "File Transfer is off. Start it, then open its address on the PC."
            setButton(if (FileTransfer.state == FileTransfer.ServerState.STARTING) "Starting…" else "Start File Transfer", enabled = true)
            devicesBox.removeAllViews()
            devicesKey = ""
            return
        }
        val devices = hub.onlineDevices()
        devices.filter { seen.add(it.id) }.forEach { selected += it.id }
        selected.retainAll(devices.map { it.id }.toSet())
        val address = FileTransfer.addresses().firstOrNull()?.url(FileTransfer.port)
        val waiting = hub.pendingRequests()
        status.text = when {
            waiting.isNotEmpty() -> "${waiting[0].name} is asking to connect. Tap here to allow it in File Transfer."
            devices.isEmpty() -> "No PC connected yet. Open ${address ?: "the File Transfer address"} in a browser on the PC and allow it."
            else -> "Choose who gets it:"
        }
        status.setOnClickListener(if (waiting.isNotEmpty()) View.OnClickListener {
            startActivity(Intent(this, FileTransferActivity::class.java))
        } else null)

        val key = devices.joinToString { "${it.id}:${it.name}" } + selected.joinToString()
        if (key != devicesKey) {
            devicesKey = key
            devicesBox.removeAllViews()
            devices.forEach { device ->
                devicesBox.addView(CheckBox(this).apply {
                    text = device.name
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    isChecked = device.id in selected
                    buttonTintList = ColorStateList.valueOf(accent)
                    setOnCheckedChangeListener { _, checked -> if (checked) selected += device.id else selected -= device.id }
                })
            }
        }
        setButton("Send", enabled = selected.isNotEmpty())
    }

    private fun renderProgress(share: Hub.Share) {
        devicesBox.removeAllViews()
        devicesKey = ""
        status.text = if (share.finished) "Finished" else "Keep this screen open until the PCs finish downloading."
        share.recipients.values.forEach { r ->
            devicesBox.addView(TextView(this).apply {
                text = "${r.name}: " + when (r.state) {
                    Hub.RecipientState.PENDING -> "waiting for them to accept"
                    Hub.RecipientState.ACCEPTED -> "accepted"
                    Hub.RecipientState.RECEIVING -> "downloading · ${if (share.total > 0) r.done * 100 / share.total else 0} %"
                    Hub.RecipientState.DONE -> "received"
                    Hub.RecipientState.DECLINED -> "declined"
                    Hub.RecipientState.FAILED -> r.error ?: "failed"
                    Hub.RecipientState.CANCELLED -> "cancelled"
                }
                textSize = 14f
                setTextColor(if (r.state == Hub.RecipientState.DONE) color(R.color.emerald_400) else Color.WHITE)
                setPadding(0, dp(6), 0, 0)
            })
            if (r.state == Hub.RecipientState.RECEIVING) {
                devicesBox.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (share.total > 0) (r.done * 1000 / share.total).toInt() else 0
                    progressTintList = ColorStateList.valueOf(accent)
                }, LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(4) })
            }
        }
        setButton(if (share.finished) "Done" else "Cancel", enabled = true, filled = share.finished)
    }

    private fun onAction() {
        val current = share
        if (current != null) {
            if (current.finished) finish() else confirmLeave()
            return
        }
        val hub = FileTransfer.hub
        if (!FileTransfer.isRunning || hub == null) {
            if (FileTransfer.state == FileTransfer.ServerState.STARTING) return
            if (FileTransfer.hasStorageAccess(this)) FileTransfer.start(this)
            else startActivity(Intent(this, FileTransferActivity::class.java))
            return
        }
        val ids = selected.toList()
        if (ids.isEmpty()) return
        val text = sharedText
        if (text != null) {
            runCatching { hub.sendTextFromPhone(ids, text) }
                .onSuccess { Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show(); finish() }
                .onFailure { Toast.makeText(this, it.message ?: "Couldn't send", Toast.LENGTH_LONG).show() }
            return
        }
        runCatching { hub.createPhoneShare(uris, ids) }
            .onSuccess { share = it }
            .onFailure { Toast.makeText(this, it.message ?: "Couldn't send", Toast.LENGTH_LONG).show() }
    }

    private fun confirmLeave() {
        val current = share
        if (current == null || current.finished) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop sending?")
            .setMessage("PCs that haven't finished downloading won't get the files.")
            .setPositiveButton("Stop") { _, _ ->
                FileTransfer.hub?.cancelShare(current)
                finish()
            }
            .setNegativeButton("Keep sending", null)
            .show()
    }

    private fun setButton(label: String, enabled: Boolean, filled: Boolean = true) {
        actionButton.text = label
        actionButton.isEnabled = enabled
        actionButton.alpha = if (enabled) 1f else 0.45f
        actionButton.setTextColor(if (filled) Color.parseColor("#062033") else color(R.color.red_400))
        actionButton.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (filled) accent else Color.parseColor("#1E293B"))
        }
    }
}
