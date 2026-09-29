package com.truckcontroller.pro

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.switchmaterial.SwitchMaterial
import com.truckcontroller.pro.haptics.HapticFeedbackHelper
import com.truckcontroller.pro.qr.Barcode1D
import com.truckcontroller.pro.qr.BarcodeFormat
import com.truckcontroller.pro.qr.ColorChoice
import com.truckcontroller.pro.qr.EyeShape
import com.truckcontroller.pro.qr.LogoMode
import com.truckcontroller.pro.qr.ModuleShape
import com.truckcontroller.pro.qr.QrContentType
import com.truckcontroller.pro.qr.QrDesign
import com.truckcontroller.pro.qr.QrPresets
import com.truckcontroller.pro.qr.QrRenderer
import io.nayuki.qrcodegen.DataTooLongException
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import zxingcpp.BarcodeReader

/**
 * QR code and barcode generator. QR codes: text, links, Wi-Fi, contacts, phone, email, SMS and
 * locations in many designs (shapes, eyes, colours, gradients, logo, frame). Barcodes: Code 128,
 * EAN-13, EAN-8, UPC-A and Code 39. Every result is test-scanned so the user knows it reads.
 * Save to the gallery or share as a PNG.
 */
class QrGeneratorActivity : BaseActivity() {

    companion object {
        private const val PREVIEW_PX = 900
        private const val EXPORT_PX = 1400

        /** Optional: open in barcode mode ("barcode") with a format name and text prefilled. */
        const val EXTRA_KIND = "kind"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_TEXT = "text"
    }

    private lateinit var haptics: HapticFeedbackHelper
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // Checks what the user entered; standard (not "full ASCII") Code 39 so % $ / + stay literal
    private val reader = BarcodeReader().apply { options.tryCode39ExtendedMode = false }

    // State
    private var type = QrContentType.TEXT
    private val values = HashMap<QrContentType, HashMap<String, String>>()
    private var design = QrDesign()
    private var customLogo: Bitmap? = null
    private val appLogo: Bitmap by lazy { ContextCompat.getDrawable(this, R.drawable.ic_logo)!!.toBitmap(256, 256) }
    private var currentText: String? = null
    private var renderVersion = 0

    // Barcode mode
    private var barcodeMode = false
    private var barcodeFormat = BarcodeFormat.CODE_128
    private val barcodeValues = hashMapOf(
        BarcodeFormat.CODE_128 to "TRUCKPAD-2026",
        BarcodeFormat.EAN_13 to "590123412345",
        BarcodeFormat.EAN_8 to "9638507",
        BarcodeFormat.UPC_A to "03600029145",
        BarcodeFormat.CODE_39 to "TRUCK 42",
    )
    private var showBarcodeText = true
    private lateinit var qrSections: LinearLayout
    private lateinit var qrExtras: LinearLayout
    private lateinit var barcodeSections: LinearLayout
    private lateinit var barcodeInput: EditText
    private val modeChips = HashMap<Boolean, TextView>()
    private val formatChips = HashMap<BarcodeFormat, TextView>()

    // Views
    private lateinit var preview: ImageView
    private lateinit var badge: TextView
    private lateinit var form: LinearLayout
    private lateinit var frameInput: EditText
    private lateinit var frameSwitch: SwitchMaterial
    private val typeChips = HashMap<QrContentType, TextView>()
    private val moduleChips = HashMap<ModuleShape, TextView>()
    private val eyeChips = HashMap<EyeShape, TextView>()
    private val logoChips = HashMap<LogoMode, TextView>()
    private val colorSwatches = HashMap<ColorChoice, View>()
    private val eyeSwatches = HashMap<ColorChoice?, View>()
    private val bgSwatches = HashMap<ColorChoice, View>()

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            if (customLogo == null) setDesign(design.copy(logo = LogoMode.NONE))
            return@registerForActivityResult
        }
        worker.execute {
            val bmp = runCatching { decodeScaled(uri, 512) }.getOrNull()
            runOnUiThread {
                if (bmp != null) {
                    customLogo = bmp
                    setDesign(design.copy(logo = LogoMode.CUSTOM))
                } else {
                    Toast.makeText(this, "Could not open that image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveToGallery() else Toast.makeText(this, "Storage permission is needed to save", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        haptics = HapticFeedbackHelper(this)
        values[QrContentType.TEXT] = hashMapOf("text" to "Hello from PhoneDeck")
        intent.getStringExtra(EXTRA_FORMAT)?.let { name ->
            BarcodeFormat.entries.firstOrNull { it.name == name }?.let { barcodeFormat = it }
        }
        intent.getStringExtra(EXTRA_TEXT)?.let { barcodeValues[barcodeFormat] = it }
        setContentView(buildScreen())
        selectType(QrContentType.TEXT)
        setDesign(design)
        setMode(intent.getStringExtra(EXTRA_KIND) == "barcode")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun logoBitmap(d: QrDesign) = when (d.logo) {
        LogoMode.NONE -> null
        LogoMode.APP -> appLogo
        LogoMode.CUSTOM -> customLogo
    }

    private fun regenerate() {
        if (!::barcodeSections.isInitialized) return
        if (barcodeMode) {
            val code = Barcode1D.encode(barcodeFormat, barcodeValues[barcodeFormat].orEmpty())
            if (code == null) {
                currentText = null
                preview.setImageBitmap(null)
                setBadge(Barcode1D.error ?: "Invalid value", R.color.amber_400)
                return
            }
            currentText = code.humanText
            val bitmap = Barcode1D.render(code, design.color, design.color2, design.background, showBarcodeText, PREVIEW_PX)
            preview.setImageBitmap(bitmap)
            verifySoon(bitmap, code.humanText)
            return
        }
        val text = type.build(values.getOrPut(type) { HashMap() })
        currentText = text
        if (text == null) {
            preview.setImageBitmap(null)
            setBadge("Fill in the details above", R.color.slate_400)
            return
        }
        val bitmap = try {
            val logo = logoBitmap(design)
            QrRenderer.render(QrRenderer.encode(text, logo != null), design, logo, PREVIEW_PX)
        } catch (e: DataTooLongException) {
            preview.setImageBitmap(null)
            setBadge("Too much content for one QR code", R.color.red_400)
            currentText = null
            return
        }
        preview.setImageBitmap(bitmap)
        verifySoon(bitmap, text)
    }

    /** Test-decodes the design so the user knows it still scans (after typing pauses). */
    private fun verifySoon(bitmap: Bitmap, text: String) {
        val version = ++renderVersion
        setBadge("Checking…", R.color.slate_400)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val bg = design.background
            worker.execute {
                // Transparent codes are checked on the background they will most likely be shown on
                val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply {
                    drawColor(if (bg == Color.TRANSPARENT) Color.WHITE else bg)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                val ok = runCatching { reader.read(flat).any { it.text == text } }.getOrDefault(false)
                runOnUiThread {
                    if (version != renderVersion) return@runOnUiThread
                    if (ok) setBadge("✓ Scannable", R.color.emerald_400)
                    else setBadge("⚠ May not scan — use more contrast or a smaller logo", R.color.amber_400)
                }
            }
        }, 350)
    }

    private fun setBadge(text: String, @ColorRes colorRes: Int) {
        badge.text = text
        badge.setTextColor(color(colorRes))
    }

    // ------------------------------------------------------------------
    // State changes
    // ------------------------------------------------------------------

    private fun setMode(barcode: Boolean) {
        barcodeMode = barcode
        modeChips.forEach { (k, v) -> styleChip(v, k == barcode) }
        qrSections.visibility = if (barcode) View.GONE else View.VISIBLE
        qrExtras.visibility = qrSections.visibility
        barcodeSections.visibility = if (barcode) View.VISIBLE else View.GONE
        if (barcode) selectFormat(barcodeFormat) else regenerate()
    }

    private fun selectFormat(f: BarcodeFormat) {
        barcodeFormat = f
        formatChips.forEach { (k, v) -> styleChip(v, k == f) }
        barcodeInput.hint = f.hint
        barcodeInput.inputType = when (f) {
            BarcodeFormat.CODE_128 -> android.text.InputType.TYPE_CLASS_TEXT
            BarcodeFormat.CODE_39 -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            else -> android.text.InputType.TYPE_CLASS_NUMBER
        }
        val value = barcodeValues[f].orEmpty()
        if (barcodeInput.text.toString() != value) barcodeInput.setText(value)
        barcodeInput.setSelection(barcodeInput.text.length)
        regenerate()
    }

    private fun selectType(t: QrContentType) {
        type = t
        typeChips.forEach { (k, v) -> styleChip(v, k == t) }
        buildForm()
        regenerate()
    }

    private fun setDesign(d: QrDesign) {
        design = d
        moduleChips.forEach { (k, v) -> styleChip(v, k == d.module) }
        eyeChips.forEach { (k, v) -> styleChip(v, k == d.eye) }
        logoChips.forEach { (k, v) -> styleChip(v, k == d.logo) }
        colorSwatches.forEach { (k, v) -> styleSwatch(v, k.color == d.color && k.color2 == d.color2) }
        eyeSwatches.forEach { (k, v) -> styleSwatch(v, k?.color == d.eyeColor) }
        bgSwatches.forEach { (k, v) -> styleSwatch(v, k.color == d.background) }
        if (::frameSwitch.isInitialized) {
            frameSwitch.setOnCheckedChangeListener(null)
            frameSwitch.isChecked = d.frameText != null
            frameSwitch.setOnCheckedChangeListener { _, checked -> onFrameToggled(checked) }
            frameInput.visibility = if (d.frameText != null) View.VISIBLE else View.GONE
            // Only sync from presets; never rewrite the box while the user is typing in it
            if (d.frameText != null && !frameInput.hasFocus() && frameInput.text.toString().trim() != d.frameText.trim()) {
                frameInput.setText(d.frameText)
            }
        }
        regenerate()
    }

    private fun onFrameToggled(checked: Boolean) {
        val text = frameInput.text.toString().ifBlank { "SCAN ME" }
        setDesign(design.copy(frameText = if (checked) text else null))
    }

    // ------------------------------------------------------------------
    // Save / share
    // ------------------------------------------------------------------

    private fun exportBitmap(): Bitmap? {
        val text = currentText ?: run {
            Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show()
            return null
        }
        if (barcodeMode) {
            val code = Barcode1D.encode(barcodeFormat, barcodeValues[barcodeFormat].orEmpty()) ?: return null
            return Barcode1D.render(code, design.color, design.color2, design.background, showBarcodeText, EXPORT_PX)
        }
        val logo = logoBitmap(design)
        return QrRenderer.render(QrRenderer.encode(text, logo != null), design, logo, EXPORT_PX)
    }

    private fun saveToGallery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val bitmap = exportBitmap() ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,
                "PhoneDeck-${if (barcodeMode) "Barcode" else "QR"}-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhoneDeck")
            }
        }
        val ok = runCatching {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        Toast.makeText(this, if (ok) "Saved to Pictures/PhoneDeck" else "Could not save the image", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val bitmap = exportBitmap() ?: return
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, if (barcodeMode) "barcode.png" else "qr-code.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            if (barcodeMode) "Share barcode" else "Share QR code"
        ))
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.cockpit_bg))
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, dp(46)))
        root.addView(View(this).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(MATCH, dp(1)))

        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#1A2130"))
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            badge = TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Save", R.drawable.ic_image, true) { saveToGallery() }, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(actionButton("Share", R.drawable.ic_share, false) { share() },
                LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(10) })
        }
        val controls = buildControls()
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (portrait) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(24))
                previewPanel.addView(preview, LinearLayout.LayoutParams(dp(280), dp(280)))
                previewPanel.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
                addView(previewPanel, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(actions, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(12) })
                addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            root.addView(ScrollView(this).apply { addView(column) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(8), dp(12))
                previewPanel.addView(preview, LinearLayout.LayoutParams(MATCH, 0, 1f))
                previewPanel.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
                addView(previewPanel, LinearLayout.LayoutParams(MATCH, 0, 1f))
                addView(actions, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(10) })
            }
            val right = ScrollView(this).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), 0, dp(16), dp(24))
                    addView(controls)
                })
            }
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))
                addView(right, LinearLayout.LayoutParams(0, MATCH, 1.3f))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(color(R.color.header_bg))
        addView(ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon).apply {
            setImageResource(R.drawable.ic_home)
            setColorFilter(color(R.color.slate_300))
            contentDescription = "Home"
            setOnClickListener { haptics.performButtonClickHaptic(); finish() }
        }, LinearLayout.LayoutParams(dp(38), dp(34)))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_qr)
            setColorFilter(color(R.color.accent))
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
        addView(TextView(context).apply {
            text = "QR Generator"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
        val fullscreenButton = ImageButton(context, null, 0, R.style.Cockpit_HeaderIcon)
        addView(fullscreenButton, LinearLayout.LayoutParams(dp(38), dp(34)))
        bindFullscreenButton(fullscreenButton) { haptics.performButtonClickHaptic() }
    }

    private fun buildControls(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(sectionLabel("CREATE"))
        addView(chipRow(listOf(
            chip("QR code", R.drawable.ic_qr) { haptics.performButtonClickHaptic(); setMode(false) }.also { modeChips[false] = it },
            chip("Barcode", R.drawable.ic_cart) { haptics.performButtonClickHaptic(); setMode(true) }.also { modeChips[true] = it },
        )))

        // Barcode: format + value
        barcodeSections = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel("FORMAT"))
            addView(chipRow(BarcodeFormat.entries.map { f ->
                chip(f.label) { haptics.performButtonClickHaptic(); selectFormat(f) }.also { formatChips[f] = it }
            }))
            addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                text = "Value"
                textSize = 11f
                setPadding(0, dp(10), 0, 0)
            })
            barcodeInput = EditText(context).apply {
                isSingleLine = true
                setTextColor(Color.WHITE)
                setHintTextColor(color(R.color.slate_600))
                addTextChangedListener(afterChange { text ->
                    barcodeValues[barcodeFormat] = text
                    regenerate()
                })
            }
            addView(barcodeInput, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(SwitchMaterial(context).apply {
                text = "Show the value under the bars"
                isChecked = showBarcodeText
                setTextColor(color(R.color.slate_300))
                setOnCheckedChangeListener { _, checked -> showBarcodeText = checked; regenerate() }
            })
            visibility = View.GONE
        }
        addView(barcodeSections)

        // QR code only: content, designs, shapes, eyes, logo, frame
        qrSections = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(qrSections)
        qrSections.buildQrContentAndStyle()

        // Shared: colours and background
        addView(sectionLabel("COLOR"))
        addView(chipRow(QrPresets.COLORS.map { c ->
            swatch(c) { setDesign(design.copy(color = c.color, color2 = c.color2)) }.also { colorSwatches[c] = it }
        }))
        addView(sectionLabel("BACKGROUND"))
        addView(chipRow(QrPresets.BACKGROUNDS.map { c ->
            swatch(c) { setDesign(design.copy(background = c.color)) }.also { bgSwatches[c] = it }
        }))
        // QR code only, after the colours: eye colour, logo, frame
        qrExtras = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(qrExtras)
        qrExtras.buildQrExtras()
    }

    private fun LinearLayout.buildQrContentAndStyle() {
        val context = this@QrGeneratorActivity
        // Content
        addView(sectionLabel("CONTENT"))
        addView(chipRow(QrContentType.entries.map { t ->
            chip(t.label, t.icon) { haptics.performButtonClickHaptic(); selectType(t) }.also { typeChips[t] = it }
        }))
        form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(form)

        // Ready-made designs
        addView(sectionLabel("DESIGNS"))
        val sample = QrRenderer.encode("PhoneDeck", withLogo = true)
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                QrPresets.DESIGNS.forEach { (name, preset) ->
                    val logo = if (preset.logo == LogoMode.APP) appLogo else null
                    val thumb = QrRenderer.render(sample, preset, logo, 220)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true
                        setOnClickListener {
                            haptics.performButtonClickHaptic()
                            setDesign(preset.copy(logo = if (preset.logo == LogoMode.CUSTOM && customLogo == null) LogoMode.NONE else preset.logo))
                        }
                        addView(ImageView(context).apply {
                            setImageBitmap(thumb)
                            background = GradientDrawable().apply {
                                cornerRadius = dp(10).toFloat()
                                setColor(Color.parseColor("#1A2130"))
                            }
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }, LinearLayout.LayoutParams(dp(78), dp(78)))
                        addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                            text = name
                            textSize = 10f
                        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(10) })
                }
            })
        })

        // Custom design
        addView(sectionLabel("SHAPE"))
        addView(chipRow(ModuleShape.entries.map { m ->
            chip(m.label) { haptics.performButtonClickHaptic(); setDesign(design.copy(module = m)) }.also { moduleChips[m] = it }
        }))
        addView(sectionLabel("EYES"))
        addView(chipRow(EyeShape.entries.map { e ->
            chip(e.label) { haptics.performButtonClickHaptic(); setDesign(design.copy(eye = e)) }.also { eyeChips[e] = it }
        }))
    }

    /** QR-only options shown after the shared colour sections. */
    private fun LinearLayout.buildQrExtras() {
        val context = this@QrGeneratorActivity
        addView(sectionLabel("EYE COLOR"))
        addView(chipRow(QrPresets.EYE_COLORS.map { c ->
            (if (c == null) swatchAuto { setDesign(design.copy(eyeColor = null)) } else swatch(c) { setDesign(design.copy(eyeColor = c.color)) })
                .also { eyeSwatches[c] = it }
        }))
        addView(sectionLabel("LOGO"))
        addView(chipRow(LogoMode.entries.map { l ->
            chip(l.label) {
                haptics.performButtonClickHaptic()
                if (l == LogoMode.CUSTOM) pickLogo.launch("image/*") else setDesign(design.copy(logo = l))
            }.also { logoChips[l] = it }
        }))
        addView(sectionLabel("FRAME"))
        frameSwitch = SwitchMaterial(context).apply {
            text = "Add a caption frame"
            setTextColor(color(R.color.slate_300))
        }
        addView(frameSwitch)
        frameInput = EditText(context).apply {
            hint = "Caption, e.g. SCAN ME"
            setText("SCAN ME")
            setTextColor(Color.WHITE)
            setHintTextColor(color(R.color.slate_600))
            isSingleLine = true
            visibility = View.GONE
            addTextChangedListener(afterChange { text ->
                if (design.frameText != null && text.isNotBlank()) setDesign(design.copy(frameText = text))
            })
        }
        addView(frameInput, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildForm() {
        form.removeAllViews()
        val v = values.getOrPut(type) { HashMap() }
        type.fields.forEach { field ->
            form.addView(TextView(this, null, 0, R.style.Cockpit_Mono).apply {
                text = field.label
                textSize = 11f
                setPadding(0, dp(10), 0, 0)
            })
            if (field.options != null) {
                if (v[field.key] == null) v[field.key] = field.options.first()
                val chips = HashMap<String, TextView>()
                form.addView(chipRow(field.options.map { option ->
                    chip(option) {
                        v[field.key] = option
                        chips.forEach { (k, c) -> styleChip(c, k == option) }
                        regenerate()
                    }.also { chips[option] = it; styleChip(it, v[field.key] == option) }
                }))
            } else {
                form.addView(EditText(this).apply {
                    setText(v[field.key].orEmpty())
                    hint = field.hint
                    inputType = field.inputType or if (field.multiLine) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                    isSingleLine = !field.multiLine
                    if (field.multiLine) maxLines = 5
                    setTextColor(Color.WHITE)
                    setHintTextColor(color(R.color.slate_600))
                    addTextChangedListener(afterChange { text ->
                        v[field.key] = text
                        regenerate()
                    })
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }

    // ------------------------------------------------------------------
    // Small widgets
    // ------------------------------------------------------------------

    private fun sectionLabel(text: String) = TextView(this, null, 0, R.style.Cockpit_Mono).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.2f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.slate_500))
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun chipRow(children: List<View>): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            children.forEach { addView(it, (it.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(8) }) }
        })
    }

    private fun chip(label: String, @DrawableRes icon: Int? = null, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setTypeface(typeface, Typeface.BOLD)
            if (icon != null) {
                val d = ContextCompat.getDrawable(context, icon)!!.mutate()
                d.setBounds(0, 0, dp(16), dp(16))
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = dp(6)
            }
            styleChip(this, false)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(8) }
        }

    private fun styleChip(chip: TextView, selected: Boolean) {
        val accent = color(R.color.accent)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.parseColor("#151A24"))
            setStroke(dp(1), if (selected) accent else Color.parseColor("#2A3445"))
        }
        val fg = if (selected) accent else color(R.color.slate_300)
        chip.setTextColor(fg)
        chip.compoundDrawables[0]?.setTint(fg)
    }

    private fun swatch(choice: ColorChoice, onClick: () -> Unit): View = View(this).apply {
        tag = choice
        contentDescription = choice.name
        setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        styleSwatch(this, false)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }
    }

    private fun swatchAuto(onClick: () -> Unit): View = TextView(this).apply {
        text = "Auto"
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.slate_300))
        tag = "auto"
        setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        styleSwatch(this, false)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(36)).apply { marginEnd = dp(10) }
    }

    private fun styleSwatch(view: View, selected: Boolean) {
        val choice = view.tag as? ColorChoice
        view.background = GradientDrawable().apply {
            if (choice == null) {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#151A24"))
            } else {
                shape = GradientDrawable.OVAL
                when {
                    choice.color == Color.TRANSPARENT -> setColor(Color.TRANSPARENT)
                    choice.color2 != null -> {
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(choice.color, choice.color2)
                    }
                    else -> setColor(choice.color)
                }
            }
            when {
                selected -> setStroke(dp(3), color(R.color.accent))
                choice?.color == Color.TRANSPARENT -> setStroke(dp(2), Color.parseColor("#64748B"), dp(4).toFloat(), dp(3).toFloat())
                else -> setStroke(dp(1), Color.parseColor("#334155"))
            }
        }
        if (view is TextView) view.setTextColor(if (selected) color(R.color.accent) else color(R.color.slate_300))
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
        }

    private fun afterChange(block: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
    }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
