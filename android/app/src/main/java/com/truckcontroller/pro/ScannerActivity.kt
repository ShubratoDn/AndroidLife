package com.truckcontroller.pro

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.truckcontroller.pro.bluetooth.BluetoothHidService
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.model.ConnectionState
import com.truckcontroller.pro.scan.CodeType
import com.truckcontroller.pro.scan.ScanHistory
import com.truckcontroller.pro.scan.ScannedCode
import com.truckcontroller.pro.scan.ScannerOverlayView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import zxingcpp.BarcodeReader

/**
 * QR code and barcode scanner (camera or gallery image) with smart actions for links, Wi-Fi,
 * contacts, phone numbers and more, plus "Type on PC" through PhoneDeck's Bluetooth keyboard.
 */
class ScannerActivity : BaseActivity() {

    private lateinit var haptics: HapticFeedbackHelper
    private lateinit var preview: PreviewView
    private lateinit var overlay: ScannerOverlayView
    private lateinit var tvHint: TextView
    private lateinit var btnTorch: ImageButton
    private lateinit var analysisExecutor: ExecutorService
    private val reader = BarcodeReader().apply {
        options.tryHarder = true
        options.tryRotate = true
        options.tryInvert = true
    }
    private var camera: Camera? = null
    private var torchOn = false

    /** True while a result is shown, so the camera does not keep firing. */
    @Volatile private var paused = false
    private var resultDialog: AlertDialog? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else tvHint.text = "Camera permission is needed to scan.\nYou can still scan an image from the gallery."
        }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scanImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = HapticFeedbackHelper(this)
        analysisExecutor = Executors.newSingleThreadExecutor()
        setContentView(buildScreen())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resultDialog?.dismiss()
        analysisExecutor.shutdown()
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    if (!paused) {
                        val hit = reader.read(image).firstOrNull { !it.text.isNullOrEmpty() }
                        if (hit != null) {
                            paused = true
                            val code = ScannedCode(hit.text!!, prettyFormat(hit.format.name), System.currentTimeMillis())
                            runOnUiThread { onCodeFound(code) }
                        }
                    }
                } catch (e: Exception) {
                    // A bad frame is simply skipped
                } finally {
                    image.close()
                }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
                btnTorch.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
                tvHint.text = "Point the camera at a QR code or barcode"
            } catch (e: Exception) {
                tvHint.text = "Camera is unavailable.\nYou can still scan an image from the gallery."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnTorch.setImageResource(if (torchOn) R.drawable.ic_flash_off else R.drawable.ic_flash)
        btnTorch.setColorFilter(color(if (torchOn) R.color.amber_400 else R.color.slate_300))
    }

    private fun scanImage(uri: Uri) {
        paused = true
        analysisExecutor.execute {
            val code = runCatching {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > 2400) {
                        val scale = 2400f / longest
                        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }
                }.copy(Bitmap.Config.ARGB_8888, false)
                reader.read(bitmap).firstOrNull { !it.text.isNullOrEmpty() }?.let {
                    ScannedCode(it.text!!, prettyFormat(it.format.name), System.currentTimeMillis())
                }
            }.getOrNull()
            runOnUiThread {
                if (code != null) {
                    onCodeFound(code)
                } else {
                    paused = false
                    Toast.makeText(this, "No QR code or barcode found in that image", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun prettyFormat(name: String): String = when (name) {
        "QR_CODE" -> "QR Code"
        "MICRO_QR_CODE" -> "Micro QR"
        "DATA_MATRIX" -> "Data Matrix"
        "EAN_13" -> "EAN-13"
        "EAN_8" -> "EAN-8"
        "UPC_A" -> "UPC-A"
        "UPC_E" -> "UPC-E"
        "CODE_128" -> "Code 128"
        "CODE_39" -> "Code 39"
        "CODE_93" -> "Code 93"
        "PDF_417" -> "PDF417"
        else -> name.lowercase(Locale.US).split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    private fun onCodeFound(code: ScannedCode) {
        overlay.found = true
        haptics.performGearShiftHaptic()
        ScanHistory.add(this, code)
        showResult(code)
    }

    private fun resumeScanning() {
        overlay.found = false
        paused = false
    }

    private fun showResult(code: ScannedCode) {
        resultDialog?.dismiss()
        // Dismissing the previous dialog resumes scanning; keep it paused while this one is shown
        paused = true
        val type = code.type
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(4))
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(iconFor(type))
                setColorFilter(color(R.color.accent))
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(TextView(context).apply {
                text = type.label
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
            addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                text = code.format
                textSize = 11f
            })
        })

        // Readable summary for structured codes, then the raw content
        val summary = summaryFor(code)
        if (summary != null) {
            content.addView(TextView(this).apply {
                text = summary
                setTextColor(color(R.color.slate_300))
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, 0)
            })
        }
        content.addView(TextView(this, null, 0, R.style.Cockpit_Mono).apply {
            text = code.text
            textSize = if (summary != null) 11f else 14f
            setTextColor(color(if (summary != null) R.color.slate_500 else R.color.slate_300))
            setTextIsSelectable(true)
            maxLines = 8
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(10), 0, dp(10))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        primaryActions(code).forEach { (label, icon, action) -> actions.addView(actionButton(label, icon, true, action)) }
        actions.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Copy", R.drawable.ic_copy, false) { copy(code.text) }, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(actionButton("Share", R.drawable.ic_share, false) { share(code.text) },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(8) })
        actions.addView(actionButton("Type on PC", R.drawable.ic_keyboard, false) { typeOnPc(code.text) },
            LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(8) })
        content.addView(actions)

        resultDialog = MaterialAlertDialogBuilder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Scan again", null)
            .setOnDismissListener { resumeScanning() }
            .show()
    }

    private fun summaryFor(code: ScannedCode): String? = when (code.type) {
        CodeType.WIFI -> {
            val f = code.wifi
            buildString {
                append("Network: ").append(f["S"] ?: "?")
                append("\nPassword: ").append(f["P"]?.takeIf { it.isNotEmpty() } ?: "(none)")
                append("\nSecurity: ").append(f["T"]?.takeIf { it.isNotEmpty() } ?: "Open")
                if (f["H"] == "true") append(" · hidden network")
            }
        }
        CodeType.CONTACT -> contactFields(code.text).entries.joinToString("\n") { "${it.key}: ${it.value}" }.ifEmpty { null }
        else -> null
    }

    private data class Action(val label: String, @DrawableRes val icon: Int, val run: () -> Unit)

    private fun primaryActions(code: ScannedCode): List<Action> {
        val t = code.text.trim()
        return when (code.type) {
            CodeType.URL -> listOf(Action("Open link", R.drawable.ic_open) {
                open(Intent(Intent.ACTION_VIEW, Uri.parse(if (t.contains("://")) t else "https://$t")))
            })
            CodeType.WIFI -> listOfNotNull(
                code.wifi["P"]?.takeIf { it.isNotEmpty() }?.let { pass -> Action("Copy password", R.drawable.ic_copy) { copy(pass) } },
                Action("Open Wi-Fi settings", R.drawable.ic_wifi) {
                    open(Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
                },
            )
            CodeType.EMAIL -> listOf(Action("Send email", R.drawable.ic_mail) {
                val lower = t.lowercase(Locale.US)
                val uri = when {
                    lower.startsWith("mailto:") -> Uri.parse(t)
                    lower.startsWith("matmsg:") -> {
                        val f = ScannedCode.parseFields(t.substring(7))
                        Uri.parse("mailto:${f["TO"].orEmpty()}?subject=${Uri.encode(f["SUB"].orEmpty())}&body=${Uri.encode(f["BODY"].orEmpty())}")
                    }
                    else -> Uri.parse("mailto:$t")
                }
                open(Intent(Intent.ACTION_SENDTO, uri))
            })
            CodeType.PHONE -> listOf(Action("Call", R.drawable.ic_phone) { open(Intent(Intent.ACTION_DIAL, Uri.parse(t))) })
            CodeType.SMS -> listOf(Action("Send SMS", R.drawable.ic_sms) {
                // SMSTO:number:message
                val parts = t.substringAfter(':').split(':', limit = 2)
                open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${parts[0]}")).putExtra("sms_body", parts.getOrElse(1) { "" }))
            })
            CodeType.GEO -> listOf(Action("Open map", R.drawable.ic_map_pin) { open(Intent(Intent.ACTION_VIEW, Uri.parse(t))) })
            CodeType.CONTACT -> listOf(Action("Add contact", R.drawable.ic_contact) {
                val f = contactFields(t)
                open(Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    f["Name"]?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
                    f["Phone"]?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                    f["Email"]?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                    f["Company"]?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
                })
            })
            CodeType.PRODUCT -> listOf(Action("Search product", R.drawable.ic_cart) {
                open(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", t))
            })
            CodeType.TEXT -> emptyList()
        }
    }

    /** Name / phone / email / company from a vCard or MECARD. */
    private fun contactFields(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (text.lowercase(Locale.US).startsWith("mecard:")) {
            val f = ScannedCode.parseFields(text.substring(7))
            f["N"]?.let { out["Name"] = it.replace(",", " ").trim() }
            f["TEL"]?.let { out["Phone"] = it }
            f["EMAIL"]?.let { out["Email"] = it }
            f["ORG"]?.let { out["Company"] = it }
            return out
        }
        text.lines().forEach { line ->
            val key = line.substringBefore(':').substringBefore(';').uppercase(Locale.US)
            val value = line.substringAfter(':', "").trim()
            if (value.isEmpty()) return@forEach
            when (key) {
                "FN" -> out["Name"] = value
                "N" -> if ("Name" !in out) out["Name"] = value.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
                "TEL" -> if ("Phone" !in out) out["Phone"] = value
                "EMAIL" -> if ("Email" !in out) out["Email"] = value
                "ORG" -> out["Company"] = value.replace(';', ' ').trim()
            }
        }
        return out
    }

    private fun iconFor(type: CodeType) = when (type) {
        CodeType.URL -> R.drawable.ic_open
        CodeType.WIFI -> R.drawable.ic_wifi
        CodeType.EMAIL -> R.drawable.ic_mail
        CodeType.PHONE -> R.drawable.ic_phone
        CodeType.SMS -> R.drawable.ic_sms
        CodeType.GEO -> R.drawable.ic_map_pin
        CodeType.CONTACT -> R.drawable.ic_contact
        CodeType.PRODUCT -> R.drawable.ic_cart
        CodeType.TEXT -> R.drawable.ic_type
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Scanned code", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(text: String) {
        open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }

    /** Types the code on the connected PC through PhoneDeck's Bluetooth keyboard. */
    private fun typeOnPc(text: String) {
        val hid = BluetoothHidService.get(this)
        if (hid.connectionState != ConnectionState.CONNECTED) {
            Toast.makeText(this, "Connect to your PC first (Pair PC on the home screen)", Toast.LENGTH_LONG).show()
            return
        }
        val typed = hid.typeText(text)
        val skipped = text.length - typed
        Toast.makeText(
            this,
            if (skipped > 0) "Typing $typed characters ($skipped not on a US keyboard were skipped)" else "Typing on PC…",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    private fun showHistory() {
        paused = true
        val scans = ScanHistory.all(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        var dialog: AlertDialog? = null
        if (scans.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No scans yet."
                setTextColor(color(R.color.slate_400))
            })
        } else {
            list.addView(TextView(this, null, 0, R.style.Cockpit_Mono).apply {
                text = "Tap to open · long-press to delete"
                textSize = 10f
                setPadding(0, 0, 0, dp(6))
            })
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        scans.forEach { scan ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_step_button)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(ImageView(context).apply {
                    setImageResource(iconFor(scan.type))
                    setColorFilter(color(R.color.accent))
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = scan.text.lineSequence().first().take(80)
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        maxLines = 1
                    })
                    addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                        text = "${scan.type.label} · ${scan.format} · ${dateFormat.format(Date(scan.scannedAt))}"
                        textSize = 10f
                    })
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
                setOnClickListener {
                    dialog?.setOnDismissListener(null)
                    dialog?.dismiss()
                    showResult(scan)
                }
                setOnLongClickListener {
                    ScanHistory.delete(this@ScannerActivity, scan)
                    dialog?.setOnDismissListener(null)
                    dialog?.dismiss()
                    showHistory()
                    true
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Scan history")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Close", null)
            .setOnDismissListener { resumeScanning() }
        if (scans.isNotEmpty()) builder.setNeutralButton("Clear all") { _, _ -> ScanHistory.clear(this) }
        dialog = builder.show()
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildScreen(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        preview = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        this@ScannerActivity.overlay = ScannerOverlayView(context)
        addView(this@ScannerActivity.overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.argb(170, 11, 14, 20))
            addView(headerButton(R.drawable.ic_home, "Home") { finish() }, LinearLayout.LayoutParams(dp(38), dp(34)))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_qr)
                setColorFilter(color(R.color.accent))
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
            addView(TextView(context).apply {
                text = "Scanner"
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
            btnTorch = headerButton(R.drawable.ic_flash, "Flashlight") { toggleTorch() }.apply { visibility = View.GONE }
            addView(btnTorch, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(6) })
            val fullscreenButton = ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon)
            addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(6) })
            bindFullscreenButton(fullscreenButton) { haptics.performButtonClickHaptic() }
        }
        addView(header, FrameLayout.LayoutParams(MATCH, dp(50), Gravity.TOP))

        // Bottom: hint and buttons
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            tvHint = TextView(context).apply {
                text = "Starting camera…"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
            }
            addView(tvHint, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pillButton("Gallery", R.drawable.ic_image) { pickImage.launch("image/*") })
                addView(pillButton("History", R.drawable.ic_history) { showHistory() },
                    LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(12) })
                addView(pillButton("Create", R.drawable.ic_qr) {
                    startActivity(Intent(this@ScannerActivity, QrGeneratorActivity::class.java))
                }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { topMargin = dp(14) })
        }
        addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    private fun headerButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) =
        ImageButton(this, null, 0, R.style.Cockpit_HeaderIcon).apply {
            setImageResource(icon)
            setColorFilter(color(R.color.slate_300))
            contentDescription = description
            setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        }

    private fun pillButton(label: String, @DrawableRes icon: Int, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_header_icon)
        setPadding(dp(16), 0, dp(18), 0)
        isClickable = true
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(color(R.color.slate_300))
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(44))
    }

    private fun actionButton(label: String, @DrawableRes icon: Int, primary: Boolean, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(if (primary) R.drawable.bg_orange_button else R.drawable.bg_step_button)
            isClickable = true
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(if (primary) Color.WHITE else color(R.color.slate_300))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(8) }
        }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
