package com.truckcontroller.pro

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.qr.QrDesign
import com.truckcontroller.pro.qr.QrRenderer
import com.truckcontroller.pro.transfer.FileTransfer
import com.truckcontroller.pro.transfer.FileTransfer.Access
import com.truckcontroller.pro.transfer.FileTransfer.Direction
import com.truckcontroller.pro.transfer.FileTransfer.ServerState
import com.truckcontroller.pro.transfer.Hub
import com.truckcontroller.pro.transfer.LiveShare
import java.util.Date

/**
 * File Transfer: start the phone's web server, then open the shown address in a browser on any
 * PC on the same Wi-Fi or hotspot. New browsers must be allowed here (matching a code shown on
 * the PC) and get an access level: send & receive only, shared folders, or all files. From here
 * the phone also sends files and text to connected PCs and receives theirs.
 */
class FileTransferActivity : ToolActivity() {

    companion object {
        private const val EXTRA_SHOW_LIVE = "show_live"
        /** How long a "share after turning on" request waits for the server. */
        private const val PENDING_LIVE_MS = 3 * 60_000L

        /** Opens this screen scrolled to Live view (Home's camera / screen tiles). */
        fun live(context: Context) = Intent(context, FileTransferActivity::class.java).putExtra(EXTRA_SHOW_LIVE, true)
    }

    private lateinit var scroll: ScrollView
    /** "camera" or "screen" to start once File Transfer is running. */
    private var pendingLive: String? = null
    private var pendingLiveAt = 0L

    private val accent = Color.parseColor("#38BDF8")
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var toggleButton: TextView
    private lateinit var pcCard: View
    private lateinit var addressBox: LinearLayout
    private lateinit var pinBox: LinearLayout
    private lateinit var pinText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var devicesBox: LinearLayout
    private lateinit var sendCard: View
    private lateinit var liveCard: View
    private lateinit var liveBox: LinearLayout
    private var liveKey = ""
    private lateinit var inboxBox: LinearLayout
    private lateinit var connectedText: TextView
    private lateinit var totalsText: TextView
    private lateinit var transfersBox: LinearLayout
    private lateinit var logBox: LinearLayout
    private lateinit var foldersBox: LinearLayout
    private lateinit var defaultAccessValue: TextView
    private lateinit var autoStopValue: TextView

    private var addressKey = ""
    private var devicesKey = ""
    private var inboxKey = ""
    private var transfersKey = ""
    private var logKey = ""
    private var approvalDialog: AlertDialog? = null
    private var approvalFor: String? = null
    /** Requests put off with "Later": not popped up again, still listed under Devices. */
    private val deferred = HashSet<String>()
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

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) FileTransfer.startCameraShare(this) else toast("Camera permission is needed to share the camera")
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                FileTransfer.startScreenShare(this, result.resultCode, data)
            } else {
                toast("Screen sharing cancelled")
            }
        }

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        // Keep read access while the PCs download, even if this screen closes
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        chooseRecipients("Send ${if (uris.size == 1) "1 file" else "${uris.size} files"} to") { ids ->
            val hub = FileTransfer.hub ?: return@chooseRecipients
            runCatching { hub.createPhoneShare(uris, ids) }
                .onSuccess { toast("Waiting for the PC to accept") }
                .onFailure { toast(it.message ?: "Couldn't send") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        page.addCard(serverCard())
        pcCard = pcCard()
        page.addCard(pcCard)
        page.addCard(devicesCard())
        sendCard = sendCard()
        page.addCard(sendCard)
        liveCard = liveCard()
        page.addCard(liveCard)
        page.addCard(activityCard())
        page.addCard(securityCard())
        page.addCard(helpCard())
        scroll = ScrollView(this).apply { addView(page) }
        setContentView(toolPage("File Transfer", R.drawable.ic_transfer, accent, scroll))
        if (intent.getBooleanExtra(EXTRA_SHOW_LIVE, false)) scrollToLive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_LIVE, false)) scrollToLive()
    }

    private fun scrollToLive() {
        scroll.postDelayed({ scroll.smoothScrollTo(0, (liveCard.top - dp(8)).coerceAtLeast(0)) }, 200)
    }

    override fun onResume() {
        super.onResume()
        if (startWhenAllowed) onStorageResult()
        renderSettings()
        handler.post(ticker)
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
    }

    /** While this screen is open, anything copied on the phone goes to the connected PCs. */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val hub = FileTransfer.hub ?: return@OnPrimaryClipChangedListener
        if (!FileTransfer.isRunning || hub.onlineDevices().isEmpty()) return@OnPrimaryClipChangedListener
        val text = phoneClipboard() ?: return@OnPrimaryClipChangedListener
        runCatching { hub.clipFromPhone(text) }.getOrNull()?.let { toast("Copied text sent to the PC") }
    }

    private fun phoneClipboard(): String? =
        getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }

    private fun sendClipboard() {
        val hub = FileTransfer.hub
        if (hub == null || hub.onlineDevices().isEmpty()) {
            toast("No PC connected yet. Open the address on a PC first.")
            return
        }
        val text = phoneClipboard() ?: return toast("The clipboard is empty")
        runCatching { hub.clipFromPhone(text) }
            .onSuccess { toast(if (it == null) "The PC already has this clipboard" else "Clipboard sent to the PC") }
            .onFailure { toast(it.message ?: "Couldn't send") }
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
        statusSub = hint("").apply { textSize = 13f; setTextColor(color(R.color.slate_300)) }
        toggleButton = button("", filled = true) { onToggle() }
        addView(statusTitle)
        addView(statusSub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(toggleButton, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) })
    }

    private fun pcCard() = titledCard("OPEN ON YOUR PC", R.drawable.ic_monitor, accent).apply {
        addView(hint("Type this address in a web browser on the PC, then allow it here when it asks. Tap the address to copy it.").apply {
            textSize = 13f
        })
        addressBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(addressBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        pinBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel("OR SIGN IN WITH PIN"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
            pinText = TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                textSize = 26f
                letterSpacing = 0.15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            addView(pinText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        addView(pinBox)
        qrImage = ImageView(context).apply { adjustViewBounds = true }
        addView(qrImage, LinearLayout.LayoutParams(dp(160), dp(160)).apply {
            topMargin = dp(16)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(hint("Tablet or another phone? Scan this code with its camera.").apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun devicesCard() = titledCard("DEVICES", R.drawable.ic_shield, accent).apply {
        devicesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(devicesBox)
    }

    private fun sendCard() = titledCard("SEND FROM THIS PHONE", R.drawable.ic_share, accent).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Send files", filled = true) { sendFiles() }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(button("Send text", filled = false) { sendText() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
            addView(button("Clipboard", filled = false) { sendClipboard() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        })
        addView(hint("Or tap Share › Send to PC in any app. Files from PCs are saved in Download/PhoneDeck.\n" +
            "Clipboard: text you copy while this screen is open goes to the PCs; otherwise use \"Send clipboard\" " +
            "in the notification or the Quick Settings tile. On the PC, press Ctrl + V on the Clipboard tab to send it here."),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        inboxBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(inboxBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }

    private fun activityCard() = titledCard("ACTIVITY", R.drawable.ic_history, accent).apply {
        connectedText = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        totalsText = hint("")
        transfersBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        logBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(connectedText)
        addView(totalsText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        addView(transfersBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        addView(sectionLabel("SECURITY LOG"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        addView(logBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }

    private fun securityCard() = titledCard("SECURITY", R.drawable.ic_lock, accent).apply {
        addView(settingRow("New devices get", "") { chooseDefaultAccess() }.also {
            defaultAccessValue = it.getChildAt(1) as TextView
        })
        addView(hint("Used when you allow from the notification or a PC signs in with the PIN. " +
            "In the approval dialog you can pick any level."))

        addView(sectionLabel("SHARED FOLDERS"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        addView(hint("Devices with \"Shared folders\" access see only these."))
        foldersBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(foldersBox)
        addView(button("Add folder", filled = false) { pickFolder() }, LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(8) })

        addView(switch("Allow PIN sign-in", FileTransfer.allowPin(context)) {
            FileTransfer.setAllowPin(this@FileTransferActivity, it)
            addressKey = ""
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        addView(hint("Off: every new device must be allowed on this phone."))

        addView(switch("Let PCs change the phone clipboard", FileTransfer.clipboardFromPc(context)) {
            FileTransfer.setClipboardFromPc(this@FileTransferActivity, it)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        addView(hint("Off: text pasted on a PC's Clipboard tab no longer reaches this phone."))

        addView(settingRow("Stop when idle", "") { chooseAutoStop() }.also {
            autoStopValue = it.getChildAt(1) as TextView
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        addView(hint("Turns File Transfer off after this long with no device connected."))

        addView(button("Forget remembered devices", filled = false) { forgetRemembered() },
            LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(14) })
    }

    private fun helpCard() = titledCard("HOW TO CONNECT", R.drawable.ic_info, accent).apply {
        addView(TextView(context).apply {
            text = "1. Put the PCs and this phone on the same network: the same Wi-Fi, this phone's hotspot, " +
                "or one PC's hotspot that the others join. Files move directly and don't use mobile data.\n" +
                "2. Tap Start.\n" +
                "3. On each PC, open the address above in a web browser and tap \"Ask for access\".\n" +
                "4. Here, check the code matches and tap Allow. New devices can only send and receive; " +
                "they can't see this phone's files unless you give them more access.\n" +
                "5. In the browser, pick a device and drop files to send them. Laptop to laptop goes through this phone.\n\n" +
                "Nothing is installed on the PCs. Keep the browser tab open on a PC that should receive files."
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
                    "Start to send files between this phone and PCs, or PC to PC, from a web browser."
                } else {
                    "PhoneDeck needs access to all files to save what PCs send and to share folders."
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

        val running = state == ServerState.RUNNING
        pcCard.visibility = if (running) View.VISIBLE else View.GONE
        sendCard.visibility = if (running) View.VISIBLE else View.GONE
        // Live view is always shown; sharing turns File Transfer on when needed
        renderLive()
        runPendingLive(state)
        if (running) renderAddresses()

        val hub = FileTransfer.hub
        renderDevices(hub)
        renderInbox(hub)
        renderApproval(hub)

        val online = hub?.onlineDevices().orEmpty()
        connectedText.text = when {
            !on -> "Server is off"
            online.isEmpty() -> "No device connected"
            online.size == 1 -> "1 device connected"
            else -> "${online.size} devices connected"
        }
        totalsText.text = "Sent ${formatBytes(FileTransfer.bytesSent.get())} · Received ${formatBytes(FileTransfer.bytesReceived.get())}"
        totalsText.visibility = if (on) View.VISIBLE else View.GONE
        renderTransfers()
        renderLog()
    }

    private fun renderAddresses() {
        val addresses = FileTransfer.addresses()
        val allowPin = FileTransfer.allowPin(this)
        val key = "$addresses|${FileTransfer.port}|${FileTransfer.pin}|$allowPin"
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
                background = rounded(Color.parseColor("#0C1622"), Color.parseColor("#1E3A52"))
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

        pinBox.visibility = if (allowPin) View.VISIBLE else View.GONE
        pinText.text = FileTransfer.pin.chunked(3).joinToString(" ")

        val first = addresses.firstOrNull()
        qrImage.visibility = if (first == null) View.GONE else View.VISIBLE
        if (first != null) {
            val qr = QrRenderer.encode(first.url(FileTransfer.port), withLogo = false)
            qrImage.setImageBitmap(QrRenderer.render(qr, QrDesign(), null, dp(160)))
        }
    }

    private fun renderDevices(hub: Hub?) {
        val requests = hub?.pendingRequests().orEmpty()
        val devices = hub?.devices().orEmpty()
        val key = requests.joinToString { it.id } + "|" +
            devices.joinToString { "${it.id}:${it.name}:${it.online}:${it.access}:${it.remembered}:${it.ip}" } + "|${hub != null}"
        if (key == devicesKey) return
        devicesKey = key
        devicesBox.removeAllViews()

        if (hub == null) {
            devicesBox.addView(hint("Start File Transfer to see and manage connected devices."))
            return
        }
        requests.forEach { request ->
            devicesBox.addView(itemRow(
                icon = R.drawable.ic_lock, iconColor = color(R.color.amber_400),
                title = "${request.name} wants to connect",
                subtitle = "${request.ip} · Code ${request.code}",
                highlight = true,
            ) { showApproval(request) })
        }
        if (devices.isEmpty() && requests.isEmpty()) {
            devicesBox.addView(hint("No devices yet. Open the address above on a PC."))
        }
        devices.forEach { device ->
            val status = if (device.online) "Online · ${device.ip}" else {
                if (device.lastSeenAt > 0) "Offline · last seen ${formatTime(device.lastSeenAt)}" else "Offline"
            }
            devicesBox.addView(itemRow(
                icon = platformIcon(device.platform),
                iconColor = if (device.online) color(R.color.emerald_400) else color(R.color.slate_500),
                title = device.name,
                subtitle = status + if (device.remembered) " · Remembered" else "",
                chip = device.access.title,
                chipColor = accessColor(device.access),
            ) { showDevice(device) })
        }
    }

    private fun renderInbox(hub: Hub?) {
        if (hub == null) return
        val shares = hub.shares().filter { it.fromId == Hub.PHONE_ID || it.recipients.containsKey(Hub.PHONE_ID) }.take(10)
        val texts = hub.texts().filter { t -> t.fromId == Hub.PHONE_ID || t.to.any { it.first == Hub.PHONE_ID } }.take(10)
        val key = shares.joinToString { s ->
            "${s.id}:${s.upload}:" + s.recipients.values.joinToString { "${it.state}${it.done / (256 * 1024)}" }
        } + "|" + texts.joinToString { it.id }
        if (key == inboxKey) return
        inboxKey = key
        inboxBox.removeAllViews()

        val items = shares.map { it.createdAt to it as Any } + texts.map { it.time to it as Any }
        items.sortedByDescending { it.first }.forEach { (_, item) ->
            when (item) {
                is Hub.Share -> inboxBox.addView(shareRow(hub, item))
                is Hub.TextMessage -> inboxBox.addView(textRow(item))
            }
        }
    }

    private fun shareRow(hub: Hub, share: Hub.Share): View {
        val incoming = share.fromId != Hub.PHONE_ID
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.parseColor("#0E141D"), Color.parseColor("#1E293B"))
        }
        box.addView(TextView(this).apply {
            text = if (incoming) "From ${share.fromName}" else "To ${share.recipients.values.joinToString { it.name }}"
            textSize = 12f
            setTextColor(color(R.color.slate_400))
        })
        box.addView(TextView(this).apply {
            text = "${share.title} · ${formatBytes(share.total)}"
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Color.WHITE)
        })
        if (incoming) {
            val me = share.recipients.getValue(Hub.PHONE_ID)
            when (me.state) {
                Hub.RecipientState.PENDING -> box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("Decline", filled = false) { hub.decline(share, Hub.PHONE_ID) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    addView(button("Accept", filled = true) { hub.accept(share, Hub.PHONE_ID) },
                        LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(10) })
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
                Hub.RecipientState.ACCEPTED, Hub.RecipientState.RECEIVING -> {
                    box.addView(progress(me.done, share.total, color(R.color.emerald_400)))
                    box.addView(hint(if (me.state == Hub.RecipientState.ACCEPTED) "Waiting for ${share.fromName} to upload…"
                        else "Receiving · ${percent(me.done, share.total)} %"))
                }
                Hub.RecipientState.DONE -> {
                    box.addView(hint("Saved in Download/PhoneDeck · tap to open").apply { setTextColor(color(R.color.emerald_400)) })
                    box.setOnClickListener { openReceivedFolder() }
                }
                else -> box.addView(hint(stateLabel(me.state, me.error)))
            }
        } else {
            share.recipients.values.forEach { r ->
                box.addView(hint("${r.name}: ${stateLabel(r.state, r.error)}" +
                    if (r.state == Hub.RecipientState.RECEIVING) " ${percent(r.done, share.total)} %" else ""))
            }
            if (!share.finished) {
                box.addView(button("Cancel", filled = false) { hub.cancelShare(share) },
                    LinearLayout.LayoutParams(MATCH, dp(38)).apply { topMargin = dp(8) })
            }
        }
        return box.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) } }
    }

    private fun textRow(message: Hub.TextMessage) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.parseColor("#0E141D"), Color.parseColor("#1E293B"))
        val incoming = message.fromId != Hub.PHONE_ID
        addView(TextView(context).apply {
            text = (if (incoming) "Text from ${message.fromName}" else "Text to ${message.to.joinToString { it.second }}") +
                " · ${formatTime(message.time)} · tap to copy"
            textSize = 12f
            setTextColor(color(R.color.slate_400))
        })
        addView(TextView(context).apply {
            text = message.text
            textSize = 14f
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
        })
        setOnClickListener {
            haptics.performButtonClickHaptic()
            copy("text", message.text)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun renderTransfers() {
        val transfers = FileTransfer.transfers.take(6)
        val key = transfers.joinToString { "${it.id}:${it.done / (64 * 1024)}:${it.finished}" }
        if (key == transfersKey) return
        transfersKey = key
        transfersBox.removeAllViews()
        transfers.forEach { t ->
            val (icon, tint, label) = when (t.direction) {
                Direction.TO_PHONE -> Triple(R.drawable.ic_arrow_left, color(R.color.emerald_400), "From ${t.peer}")
                Direction.FROM_PHONE -> Triple(R.drawable.ic_arrow_right, accent, "To ${t.peer}")
                Direction.RELAY -> Triple(R.drawable.ic_transfer, color(R.color.amber_400), t.peer)
            }
            transfersBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(icon)
                        setColorFilter(tint)
                    }, LinearLayout.LayoutParams(dp(16), dp(16)))
                    addView(TextView(context).apply {
                        text = t.name
                        textSize = 14f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
                })
                if (!t.finished) addView(progress(t.done, t.total, tint))
                addView(hint(when {
                    t.failed -> "$label · Stopped at ${formatBytes(t.done)}"
                    t.finished -> "$label · ${formatBytes(t.done)} · ${formatBytes(t.speed)}/s"
                    else -> "$label · ${percent(t.done, t.total)} % · ${formatBytes(t.done)} of ${formatBytes(t.total)} · ${formatBytes(t.speed)}/s"
                }).apply { if (t.failed) setTextColor(color(R.color.red_400)) },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
            })
        }
    }

    private fun renderLog() {
        val entries = FileTransfer.log.take(8)
        val key = entries.joinToString { "${it.time}${it.text}" }
        if (key == logKey) return
        logKey = key
        logBox.removeAllViews()
        if (entries.isEmpty()) logBox.addView(hint("Connections, approvals and deletions appear here."))
        entries.forEach { entry ->
            logBox.addView(TextView(this).apply {
                text = "${formatTime(entry.time)}  ${entry.text}"
                textSize = 12f
                setTextColor(if (entry.warning) color(R.color.amber_400) else color(R.color.slate_300))
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun renderSettings() {
        defaultAccessValue.text = FileTransfer.defaultAccess(this).title
        val minutes = FileTransfer.autoStopMinutes(this)
        autoStopValue.text = if (minutes == 0) "Never" else "After $minutes min"
        foldersBox.removeAllViews()
        val folders = FileTransfer.sharedFolders(this)
        if (folders.isEmpty()) foldersBox.addView(hint("No shared folders.").apply { setPadding(0, dp(6), 0, 0) })
        folders.forEach { folder ->
            foldersBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_file)
                    setColorFilter(color(R.color.amber_400))
                }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = folder.name
                        textSize = 14f
                        setTextColor(Color.WHITE)
                    })
                    addView(hint(displayPath(folder.path)))
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
                addView(SwitchMaterial(context).apply {
                    text = "Can edit"
                    textSize = 12f
                    setTextColor(color(R.color.slate_400))
                    isChecked = folder.writable
                    setOnCheckedChangeListener { _, checked ->
                        updateFolders { list -> list.map { if (it.path == folder.path) it.copy(writable = checked) else it } }
                    }
                })
                addView(headerButton(R.drawable.ic_close, "Remove") {
                    updateFolders { list -> list.filter { it.path != folder.path } }
                    renderSettings()
                }, LinearLayout.LayoutParams(dp(36), dp(34)).apply { marginStart = dp(4) })
            })
        }
    }

    private fun updateFolders(change: (List<FileTransfer.SharedFolder>) -> List<FileTransfer.SharedFolder>) {
        FileTransfer.setSharedFolders(this, change(FileTransfer.sharedFolders(this)))
    }

    // ------------------------------------------------------------------
    // Live view: camera and screen in the browser
    // ------------------------------------------------------------------

    private fun liveCard() = titledCard("LIVE VIEW", R.drawable.ic_eye, accent).apply {
        addView(hint("Show this phone's camera or screen in the browser of devices you allowed. " +
            "Nothing is shared until you start it here; open the Live tab in the browser to watch."))
        liveBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(liveBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }

    private fun renderLive() {
        val cam = LiveShare.camera
        val scr = LiveShare.screen
        val quality = LiveShare.quality(this)
        val serverOn = FileTransfer.isRunning
        val key = "$serverOn|$pendingLive|${cam.on}|${scr.on}|${LiveShare.lens}|${LiveShare.torch}|${LiveShare.hasTorch}|${LiveShare.rotation}|" +
            "${cam.viewerNames()}|${scr.viewerNames()}|$quality"
        if (key == liveKey) return
        liveKey = key
        liveBox.removeAllViews()

        fun watching(names: List<String>) = when (names.size) {
            0 -> "nobody watching yet"
            1 -> "${names[0]} watching"
            else -> "${names.size} watching: ${names.joinToString()}"
        }

        if (!serverOn) {
            liveBox.addView(hint(if (pendingLive != null) "Turning on File Transfer…"
                else "File Transfer is off. It turns on when you start sharing.").apply {
                setTextColor(color(R.color.amber_400))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        // Camera
        liveBox.addView(liveRow(
            "Camera",
            if (cam.on) "On · ${LiveShare.lens} camera · ${watching(cam.viewerNames())}" else "Off",
            cam.on,
        ))
        liveBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (!cam.on) {
                addView(button("Share camera", filled = true) { requestShare("camera") }, LinearLayout.LayoutParams(0, dp(42), 1f))
            } else {
                addView(button("Stop", filled = false) { FileTransfer.stopCameraShare(this@FileTransferActivity) }.apply {
                    setTextColor(color(R.color.red_400))
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(button("Switch", filled = false) { LiveShare.control?.invoke("camera", "lens") },
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
                if (LiveShare.hasTorch) {
                    addView(button(if (LiveShare.torch) "Light off" else "Light", filled = false) { LiveShare.control?.invoke("camera", "torch") },
                        LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
                }
                addView(button("Rotate", filled = false) { LiveShare.control?.invoke("camera", "rotate") },
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // Screen
        liveBox.addView(liveRow(
            "Screen",
            if (scr.on) "On · ${watching(scr.viewerNames())}" else "Off · Android asks you to confirm each time",
            scr.on,
        ), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        liveBox.addView(
            if (!scr.on) button("Share screen", filled = true) { requestShare("screen") }
            else button("Stop screen sharing", filled = false) { FileTransfer.stopScreenShare(this) }.apply {
                setTextColor(color(R.color.red_400))
            },
            LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(8) },
        )

        // Quality
        liveBox.addView(sectionLabel("PICTURE QUALITY"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        liveBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            LiveShare.Quality.entries.forEachIndexed { i, q ->
                addView(TextView(context).apply {
                    text = q.title
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    val on = q == quality
                    setTextColor(if (on) Color.parseColor("#062033") else color(R.color.slate_300))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (on) accent else Color.parseColor("#1E293B"))
                    }
                    setOnClickListener {
                        haptics.performButtonClickHaptic()
                        LiveShare.setQuality(this@FileTransferActivity, q)
                        liveKey = ""
                        if (LiveShare.sharing) toast("Applies the next time you start sharing")
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        liveBox.addView(hint(if (quality.original) {
            "Original: the screen at its own resolution and the camera at up to 4K, near-lossless. " +
                "About 10–15 frames a second and 5–10 MB/s per viewer: use 5 GHz Wi-Fi."
        } else {
            "Low saves data and battery; High is sharper; Original is full resolution."
        } + " Camera snapshots are full-resolution photos. Apps that block screenshots show black."),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun liveRow(title: String, status: String, on: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = if (on) "\u25CF $title" else title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (on) color(R.color.red_400) else Color.WHITE)
        })
        addView(hint(status))
    }

    /** Shares now, or offers to turn File Transfer on first and shares once it runs. */
    private fun requestShare(kind: String) {
        if (FileTransfer.isRunning) {
            if (kind == "camera") startCameraShare() else startScreenShare()
            return
        }
        val what = if (kind == "camera") "camera" else "screen"
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn on File Transfer?")
            .setMessage("Live view sends the $what to PC browsers through File Transfer. It turns on now, " +
                "then sharing starts. On the PC, open the address shown here and allow the PC when it asks.")
            .setPositiveButton("Turn on & share") { _, _ ->
                pendingLive = kind
                pendingLiveAt = android.os.SystemClock.elapsedRealtime()
                liveKey = ""
                FileTransfer.error = null
                if (FileTransfer.state == ServerState.OFF) onToggle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Starts the share asked for before File Transfer was on, once it is running. */
    private fun runPendingLive(state: ServerState) {
        val kind = pendingLive ?: return
        when {
            state == ServerState.RUNNING -> {
                pendingLive = null
                liveKey = ""
                if (kind == "camera") startCameraShare() else startScreenShare()
            }
            // Gave up (permission declined, port error) or took too long
            android.os.SystemClock.elapsedRealtime() - pendingLiveAt > PENDING_LIVE_MS ||
                (state == ServerState.OFF && FileTransfer.error != null && !startWhenAllowed) -> {
                pendingLive = null
                liveKey = ""
            }
        }
    }

    private fun startCameraShare() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            FileTransfer.startCameraShare(this)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScreenShare() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { screenCaptureLauncher.launch(manager.createScreenCaptureIntent()) }
            .onFailure { toast("Screen sharing isn't available on this phone") }
    }

    // ------------------------------------------------------------------
    // Approving devices
    // ------------------------------------------------------------------

    /** Opens the approval dialog for the oldest request, and closes it if answered elsewhere. */
    private fun renderApproval(hub: Hub?) {
        val current = approvalFor
        if (current != null) {
            val request = hub?.request(current)
            if (request == null || request.status != Hub.RequestStatus.PENDING) {
                approvalFor = null
                approvalDialog?.dismiss()
                approvalDialog = null
            }
            return
        }
        hub?.pendingRequests()?.firstOrNull { it.id !in deferred }?.let { showApproval(it) }
    }

    private fun showApproval(request: Hub.AccessRequest) {
        val hub = FileTransfer.hub ?: return
        if (approvalFor == request.id && approvalDialog?.isShowing == true) return
        approvalDialog?.dismiss()
        approvalFor = request.id

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        content.addView(TextView(this).apply {
            text = request.name
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        content.addView(hint("${request.ip} · wants to connect"))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.parseColor("#0C1622"), Color.parseColor("#1E3A52"))
            addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                text = request.code.chunked(1).joinToString(" ")
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
            })
            addView(hint("Allow only if the PC shows this code").apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        content.addView(sectionLabel("ACCESS"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        val group = accessGroup(FileTransfer.defaultAccess(this))
        content.addView(group)
        val remember = CheckBox(this).apply {
            text = "Remember this device for 30 days"
            setTextColor(color(R.color.slate_300))
        }
        content.addView(remember, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        approvalDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Allow this device?")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Allow") { _, _ ->
                hub.approve(request.id, checkedAccess(group), remember.isChecked)
            }
            .setNegativeButton("Deny") { _, _ -> hub.deny(request.id) }
            .setNeutralButton("Later", null)
            .setOnDismissListener {
                if (approvalFor == request.id) {
                    approvalFor = null
                    // "Later" keeps it pending; it stays under Devices until tapped
                    if (request.status == Hub.RequestStatus.PENDING) deferred += request.id
                }
            }
            .show()
    }

    private fun accessGroup(selected: Access) = RadioGroup(this).apply {
        Access.entries.forEachIndexed { i, access ->
            addView(RadioButton(context).apply {
                id = i + 1
                text = access.title
                setTextColor(if (access == Access.FULL) color(R.color.amber_400) else Color.WHITE)
                isChecked = access == selected
            })
            addView(hint(access.description).apply { setPadding(dp(32), 0, 0, dp(4)) })
        }
    }

    private fun checkedAccess(group: RadioGroup) =
        Access.entries.getOrElse(group.checkedRadioButtonId - 1) { Access.SEND }

    private fun showDevice(device: Hub.Device) {
        val hub = FileTransfer.hub ?: return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        content.addView(hint(if (device.online) "Online · ${device.ip}" else "Offline"))
        content.addView(sectionLabel("ACCESS"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        val group = accessGroup(device.access)
        content.addView(group)
        val remember = CheckBox(this).apply {
            text = "Remember this device"
            isChecked = device.remembered
            setTextColor(color(R.color.slate_300))
        }
        content.addView(remember)
        MaterialAlertDialogBuilder(this)
            .setTitle(device.name)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save") { _, _ ->
                hub.setAccess(device.id, checkedAccess(group))
                if (remember.isChecked != device.remembered) hub.setRemembered(device.id, remember.isChecked)
                devicesKey = ""
            }
            .setNegativeButton("Disconnect") { _, _ ->
                hub.remove(device.id)
                devicesKey = ""
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Sending from the phone
    // ------------------------------------------------------------------

    private fun sendFiles() {
        if (FileTransfer.hub?.onlineDevices().isNullOrEmpty()) {
            toast("No PC connected yet. Open the address on a PC first.")
            return
        }
        pickFilesLauncher.launch(arrayOf("*/*"))
    }

    private fun sendText() {
        if (FileTransfer.hub?.onlineDevices().isNullOrEmpty()) {
            toast("No PC connected yet. Open the address on a PC first.")
            return
        }
        val input = EditText(this).apply {
            hint = "Text or link"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 6
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Send text")
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(4), dp(20), 0)
                addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
            })
            .setPositiveButton("Next") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                chooseRecipients("Send text to") { ids ->
                    runCatching { FileTransfer.hub?.sendTextFromPhone(ids, text) }
                        .onSuccess { toast("Sent") }
                        .onFailure { toast(it.message ?: "Couldn't send") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseRecipients(title: String, onChosen: (List<String>) -> Unit) {
        val devices = FileTransfer.hub?.onlineDevices().orEmpty()
        if (devices.isEmpty()) {
            toast("No PC connected")
            return
        }
        val checked = BooleanArray(devices.size) { true }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMultiChoiceItems(devices.map { it.name }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Send") { _, _ ->
                val ids = devices.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.isNotEmpty()) onChosen(ids)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openReceivedFolder() {
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/PhoneDeck")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)) }
            .recoverCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            .onFailure { toast("Open Download/PhoneDeck in your file manager") }
    }

    // ------------------------------------------------------------------
    // Settings dialogs
    // ------------------------------------------------------------------

    private fun chooseDefaultAccess() {
        val current = FileTransfer.defaultAccess(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("New devices get")
            .setSingleChoiceItems(Access.entries.map { it.title }.toTypedArray(), current.ordinal) { dialog, which ->
                FileTransfer.setDefaultAccess(this, Access.entries[which])
                renderSettings()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseAutoStop() {
        val options = listOf(0, 15, 30, 60, 120)
        val labels = options.map { if (it == 0) "Never" else "After $it minutes" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop when idle")
            .setSingleChoiceItems(labels, options.indexOf(FileTransfer.autoStopMinutes(this)).coerceAtLeast(0)) { dialog, which ->
                FileTransfer.setAutoStopMinutes(this, options[which])
                renderSettings()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun forgetRemembered() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Forget remembered devices?")
            .setMessage("They are disconnected and must be allowed again next time.")
            .setPositiveButton("Forget") { _, _ ->
                val hub = FileTransfer.hub
                if (hub != null) hub.forgetAll()
                else FileTransfer.prefs(this).edit().remove(FileTransfer.KEY_REMEMBERED).apply()
                devicesKey = ""
                toast("Forgotten")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Simple folder browser over internal storage for choosing a shared folder. */
    private fun pickFolder() {
        if (!FileTransfer.hasStorageAccess(this)) {
            requestStorage()
            return
        }
        val root = Environment.getExternalStorageDirectory()
        var dir = root
        val pathText = hint("")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: AlertDialog

        fun show() {
            pathText.text = displayPath(dir.path)
            list.removeAllViews()
            if (dir != root) list.addView(folderEntry("..  Up") {
                dir = dir.parentFile ?: root
                show()
            })
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { child ->
                    list.addView(folderEntry(child.name) {
                        dir = child
                        show()
                    })
                }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(pathText)
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, dp(340)))
        }
        show()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Share a folder")
            .setView(content)
            .setPositiveButton("Share this folder") { _, _ ->
                if (dir == root) {
                    toast("Pick a folder inside storage, or give the device \"All files\" access")
                    return@setPositiveButton
                }
                updateFolders { it + FileTransfer.SharedFolder(dir.path, writable = false) }
                renderSettings()
                toast("Shared ${dir.name} (view-only)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun folderEntry(name: String, onClick: () -> Unit) = TextView(this).apply {
        text = name
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
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
            .setMessage("So PCs can save files to this phone and open the folders you share, turn on " +
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
    // Widgets and formatting
    // ------------------------------------------------------------------

    private fun itemRow(
        icon: Int, iconColor: Int, title: String, subtitle: String,
        chip: String? = null, chipColor: Int = accent, highlight: Boolean = false, onClick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = if (highlight) rounded(Color.parseColor("#2A1E08"), color(R.color.amber_400))
            else rounded(Color.parseColor("#0E141D"), Color.parseColor("#1E293B"))
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(iconColor)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
            })
            addView(hint(subtitle))
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        if (chip != null) addView(TextView(context).apply {
            text = chip
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(chipColor)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), chipColor)
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        setOnClickListener {
            haptics.performButtonClickHaptic()
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun settingRow(label: String, value: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(color(R.color.slate_300))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
        })
        setOnClickListener {
            haptics.performButtonClickHaptic()
            onClick()
        }
    }

    private fun progress(done: Long, total: Long, tint: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = if (total > 0) (done * 1000 / total).toInt() else 0
        progressTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(6) }
    }

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
        val stop = view.text == "Stop"
        view.setTextColor(when {
            filled -> Color.parseColor("#062033")
            stop -> color(R.color.red_400)
            else -> accent
        })
        view.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (filled) accent else Color.parseColor("#1E293B"))
        }
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun platformIcon(platform: String) = when (platform) {
        "android", "iphone" -> R.drawable.ic_smartphone
        else -> R.drawable.ic_monitor
    }

    private fun accessColor(access: Access) = when (access) {
        Access.SEND -> accent
        Access.FOLDERS -> color(R.color.emerald_400)
        Access.FULL -> color(R.color.amber_400)
    }

    private fun stateLabel(state: Hub.RecipientState, error: String?) = when (state) {
        Hub.RecipientState.PENDING -> "Waiting for answer"
        Hub.RecipientState.ACCEPTED -> "Accepted"
        Hub.RecipientState.RECEIVING -> "Receiving"
        Hub.RecipientState.DONE -> "Received"
        Hub.RecipientState.DECLINED -> "Declined"
        Hub.RecipientState.FAILED -> error ?: "Failed"
        Hub.RecipientState.CANCELLED -> "Cancelled"
    }

    private fun percent(done: Long, total: Long) = if (total > 0) (done * 100 / total).toInt() else 0

    private fun formatTime(time: Long): String = DateFormat.getTimeFormat(this).format(Date(time))

    private fun displayPath(path: String): String {
        val root = Environment.getExternalStorageDirectory().path
        return if (path.startsWith(root)) "Internal storage" + path.removePrefix(root) else path
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
