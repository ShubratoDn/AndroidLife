package com.truckcontroller.pro.hardware

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.truckcontroller.pro.R

/** Flashlight test: torch on/off per flash LED, brightness level (Android 13+), strobe and SOS. */
class FlashlightTestActivity : HardwareTestActivity() {

    private lateinit var cm: CameraManager
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var toggle: TextView
    private lateinit var strobeText: TextView
    private var flashIds: List<String> = emptyList()
    private var selectedId: String? = null
    private var torchOn = false
    private var maxLevel = 1
    private var level = 1
    private var strobeHz = 0
    private var sos = false
    private val handler = Handler(Looper.getMainLooper())

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == selectedId && strobeHz == 0 && !sos) { torchOn = enabled; render() }
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == selectedId) { statusSub.text = "In use by another app (camera open?)" }
        }
    }

    override fun buildTest() {
        cm = getSystemService(CameraManager::class.java)
        flashIds = cm.cameraIdList.filter {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        selectedId = flashIds.firstOrNull()

        page.addCard(card().apply {
            status = statusText()
            statusSub = smallText()
            addView(status)
            addView(statusSub)
            toggle = actionButton("Turn on") { stopPatterns(); setTorch(!torchOn) }
            addView(buttonRow(toggle), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            if (flashIds.size > 1) {
                addView(buttonRow(*flashIds.map { id ->
                    actionButton(facing(id), filled = false) {
                        setTorch(false)
                        selectedId = id
                        readLevels()
                        render()
                    }
                }.toTypedArray()), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }
        })

        readLevels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxLevel > 1) {
            page.addCard(titledCard("BRIGHTNESS", R.drawable.ic_sun, accent).apply {
                val label = smallText()
                addView(label)
                addView(SeekBar(context).apply {
                    max = maxLevel - 1
                    progress = level - 1
                    label.text = "Level $level of $maxLevel"
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                            level = p + 1
                            label.text = "Level $level of $maxLevel"
                            if (torchOn) setTorch(true)
                        }
                        override fun onStartTrackingTouch(s: SeekBar) = Unit
                        override fun onStopTrackingTouch(s: SeekBar) = Unit
                    })
                })
            })
        }

        page.addCard(titledCard("STROBE & SOS", R.drawable.ic_zap, accent).apply {
            strobeText = smallText("Off")
            addView(strobeText)
            addView(SeekBar(context).apply {
                max = 15
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        sos = false
                        strobeHz = p
                        strobeText.text = if (p == 0) "Off" else "$p flashes per second"
                        handler.removeCallbacksAndMessages(null)
                        if (p > 0) strobe(true) else setTorch(false)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) = Unit
                    override fun onStopTrackingTouch(s: SeekBar) = Unit
                })
            })
            addView(buttonRow(actionButton("SOS", hex("#F87171"), filled = false) {
                stopPatterns()
                sos = true
                sosStep(0)
            }, actionButton("Stop", filled = false) { stopPatterns(); setTorch(false) }),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        })

        page.addCard(titledCard("FLASH LEDs", R.drawable.ic_flash, accent).apply {
            infoRow("Flash units", "${flashIds.size}")
            flashIds.forEach { id -> infoRow(facing(id), if (levelsFor(id) > 1) "Dimmable (${levelsFor(id)} levels)" else "On / off") }
        })
    }

    override fun startListening() {
        cm.registerTorchCallback(torchCallback, handler)
        render()
    }

    override fun stopListening() {
        stopPatterns()
        setTorch(false)
        cm.unregisterTorchCallback(torchCallback)
    }

    private fun readLevels() {
        maxLevel = selectedId?.let { levelsFor(it) } ?: 1
        level = maxLevel
    }

    private fun levelsFor(id: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 else 1

    private fun setTorch(on: Boolean) {
        val id = selectedId ?: return
        runCatching {
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxLevel > 1) cm.turnOnTorchWithStrengthLevel(id, level)
            else cm.setTorchMode(id, on)
        }.onFailure { statusSub.text = "Flash is busy (close other camera apps)" }
        torchOn = on
        render()
    }

    private fun strobe(on: Boolean) {
        if (strobeHz == 0) return
        setTorch(on)
        handler.postDelayed({ strobe(!on) }, 500L / strobeHz)
    }

    private fun sosStep(i: Int) {
        if (!sos) return
        // · · · — — — · · ·  (1 unit = 200 ms)
        val pattern = intArrayOf(1, 1, 1, 1, 1, 3, 3, 1, 3, 1, 3, 3, 1, 1, 1, 1, 1, 7)
        val on = i % 2 == 0
        setTorch(on)
        handler.postDelayed({ sosStep((i + 1) % pattern.size) }, pattern[i] * 200L)
    }

    private fun stopPatterns() {
        handler.removeCallbacksAndMessages(null)
        strobeHz = 0
        sos = false
        if (::strobeText.isInitialized) strobeText.text = "Off"
    }

    private fun render() {
        if (selectedId == null) {
            status.text = "No flash found"
            status.setTextColor(hex("#F87171"))
            return
        }
        status.text = when {
            sos -> "SOS"; strobeHz > 0 -> "Strobe"; torchOn -> "Flashlight on"; else -> "Flashlight off"
        }
        status.setTextColor(if (torchOn || sos || strobeHz > 0) hex("#FBBF24") else Color.WHITE)
        statusSub.text = "${facing(selectedId!!)} flash" + if (maxLevel > 1) " · brightness $level / $maxLevel" else ""
        toggle.text = if (torchOn && strobeHz == 0 && !sos) "Turn off" else "Turn on"
        (toggle.background as? GradientDrawable)?.setColor(if (torchOn) hex("#FBBF24") else accent)
    }

    private fun facing(id: String) = when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
        CameraMetadata.LENS_FACING_FRONT -> "Front"
        CameraMetadata.LENS_FACING_BACK -> "Back"
        else -> "External"
    }
}
