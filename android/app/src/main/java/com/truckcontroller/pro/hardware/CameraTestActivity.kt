package com.truckcontroller.pro.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.util.SizeF
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.truckcontroller.pro.R
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Camera test: specs of every camera (incl. hidden physical lenses) and a live preview with focus + zoom. */
@OptIn(ExperimentalCamera2Interop::class)
class CameraTestActivity : HardwareTestActivity() {

    private lateinit var cm: CameraManager
    private lateinit var previewView: PreviewView
    private lateinit var chips: LinearLayout
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomText: TextView
    private lateinit var specs: LinearLayout
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraInfos: List<CameraInfo> = emptyList()
    private var selected = 0

    override fun requiredPermissions() = listOf(Manifest.permission.CAMERA)
    override val permissionReason = "Camera permission is needed to show the live preview. Nothing is recorded or saved."

    @SuppressLint("ClickableViewAccessibility")
    override fun buildTest() {
        cm = getSystemService(CameraManager::class.java)
        page.addCard(card().apply {
            setPadding(dp(8), dp(8), dp(8), dp(10))
            chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            previewView = PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                        val point = meteringPointFactory.createPoint(e.x, e.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                    true
                }
            }
            addView(FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
            }, LinearLayout.LayoutParams(MATCH, dp(380)).apply { topMargin = dp(8) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                zoomText = smallText("1.0×").apply { minWidth = dp(48) }
                addView(zoomText)
                zoomBar = SeekBar(context).apply {
                    max = 100
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                            if (fromUser) camera?.cameraControl?.setLinearZoom(p / 100f)
                        }
                        override fun onStartTrackingTouch(s: SeekBar) = Unit
                        override fun onStopTrackingTouch(s: SeekBar) = Unit
                    })
                }
                addView(zoomBar, LinearLayout.LayoutParams(0, WRAP, 1f))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            addView(smallText("Tap the picture to focus. Drag the slider to zoom (below 1× switches to the ultra-wide lens on many phones)."))
        })

        specs = titledCard("SELECTED CAMERA", R.drawable.ic_camera, accent)
        page.addCard(specs)
        page.addCard(allCamerasCard())
    }

    override fun startListening() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            cameraInfos = p.availableCameraInfos.sortedBy { if (it.lensFacing == CameraSelector.LENS_FACING_BACK) 0 else 1 }
            buildChips()
            bind(selected.coerceIn(0, (cameraInfos.size - 1).coerceAtLeast(0)))
        }, mainExecutor)
    }

    override fun stopListening() {
        provider?.unbindAll()
        camera = null
    }

    private fun buildChips() {
        chips.removeAllViews()
        cameraInfos.forEachIndexed { i, info ->
            val id = Camera2CameraInfo.from(info).cameraId
            val c = cm.getCameraCharacteristics(id)
            chips.addView(TextView(this).apply {
                text = "${facingName(c)} · ${megapixels(c)}"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { haptics.performButtonClickHaptic(); bind(i) }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
    }

    private fun bind(index: Int) {
        val p = provider ?: return
        val info = cameraInfos.getOrNull(index) ?: return
        selected = index
        for (i in 0 until chips.childCount) {
            (chips.getChildAt(i) as TextView).apply {
                val on = i == index
                setTextColor(if (on) hex("#04201C") else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (on) accent else hex("#1E293B"))
                }
            }
        }
        val id = Camera2CameraInfo.from(info).cameraId
        val selector = CameraSelector.Builder().addCameraFilter { list ->
            list.filter { Camera2CameraInfo.from(it).cameraId == id }
        }.build()
        p.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        camera = runCatching { p.bindToLifecycle(this, selector, preview) }.getOrNull()
        camera?.cameraInfo?.zoomState?.observe(this) { z ->
            zoomText.text = String.format(Locale.US, "%.1f×", z.zoomRatio)
            zoomBar.progress = (z.linearZoom * 100).roundToInt()
        }
        showSpecs(id)
    }

    private fun showSpecs(id: String) {
        while (specs.childCount > 1) specs.removeViewAt(1)
        val c = cm.getCameraCharacteristics(id)
        specsRows(c).forEach { (k, v) -> specs.infoRow(k, v) }
        camera?.cameraInfo?.zoomState?.value?.let {
            specs.infoRow("Zoom range", String.format(Locale.US, "%.1f× – %.0f×", it.minZoomRatio, it.maxZoomRatio))
        }
    }

    private fun allCamerasCard() = titledCard("ALL CAMERAS & LENSES", R.drawable.ic_grid, accent).apply {
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val physical = ids.flatMap { id -> runCatching { cm.getCameraCharacteristics(id).physicalCameraIds }.getOrDefault(emptySet()) }
            .filter { it !in ids }.distinct()
        (ids + physical).forEach { id ->
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: return@forEach
            addView(TextView(context).apply {
                text = "${facingName(c)} · ${lensType(c)}" + if (id in physical) " (inside a multi-camera)" else ""
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(2))
            })
            addView(smallText(specsRows(c).filter { it.first in SUMMARY }.joinToString(" · ") { it.second }))
        }
        if (ids.isEmpty()) addView(smallText("No cameras reported."))
    }

    private fun specsRows(c: CameraCharacteristics): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Position" to facingName(c)
        rows += "Lens" to lensType(c)
        rows += "Resolution" to megapixels(c)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val eq = equivalentFocal(c)
        if (focal != null) rows += "Focal length" to String.format(Locale.US, "%.2f mm", focal) + (eq?.let { " (≈ $it mm)" } ?: "")
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let {
            rows += "Aperture" to String.format(Locale.US, "f/%.1f", it)
        }
        c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { rows += "Sensor size" to sensorSize(it) }
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        rows += "Autofocus" to if (minFocus > 0f) "Yes (closest ${String.format(Locale.US, "%.0f", 100 / minFocus)} cm)" else "Fixed focus"
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        rows += "Optical stabilization" to if (ois) "Yes" else "No"
        rows += "Flash" to if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "Yes" else "No"
        val maxFps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper }
        if (maxFps != null) rows += "Max preview fps" to "$maxFps"
        val video = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)?.maxByOrNull { it.width * it.height }
        if (video != null) rows += "Max video size" to when {
            video.width >= 7680 -> "8K"; video.width >= 3840 -> "4K"; video.width >= 1920 -> "1080p"; else -> "${video.width}×${video.height}"
        } + " (${video.width}×${video.height})"
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        rows += "RAW photos" to if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) "Yes" else "No"
        rows += "Manual controls" to if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps) "Yes" else "No"
        rows += "Camera2 support" to when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
            else -> "—"
        }
        return rows
    }

    private fun facingName(c: CameraCharacteristics) = when (c.get(CameraCharacteristics.LENS_FACING)) {
        CameraMetadata.LENS_FACING_BACK -> "Back"
        CameraMetadata.LENS_FACING_FRONT -> "Front"
        else -> "External"
    }

    private fun megapixels(c: CameraCharacteristics): String {
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return "—"
        val mp = size.width.toLong() * size.height / 1_000_000.0
        return String.format(Locale.US, if (mp >= 10) "%.0f MP" else "%.1f MP", mp)
    }

    /** 35 mm-equivalent focal length from the sensor diagonal. */
    private fun equivalentFocal(c: CameraCharacteristics): Int? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = sqrt(s.width * s.width + s.height * s.height)
        if (diag <= 0f) return null
        return (focal * 43.27f / diag).roundToInt()
    }

    private fun lensType(c: CameraCharacteristics): String {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) return "Multi-camera"
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps) return "Depth"
        val eq = equivalentFocal(c) ?: return "Camera"
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) return "Selfie"
        return when {
            eq < 20 -> "Ultra-wide"; eq < 35 -> "Wide (main)"; eq < 45 -> "Standard"; eq < 80 -> "Telephoto 2–3×"
            else -> "Telephoto ${(eq / 26f).roundToInt()}×"
        }
    }

    private fun sensorSize(s: SizeF): String {
        val diagMm = sqrt(s.width * s.width + s.height * s.height)
        return String.format(Locale.US, "%.1f × %.1f mm (1/%.2f\")", s.width, s.height, 16f / diagMm)
    }

    private companion object {
        val SUMMARY = setOf("Resolution", "Focal length", "Aperture", "Autofocus", "Optical stabilization")
    }
}
