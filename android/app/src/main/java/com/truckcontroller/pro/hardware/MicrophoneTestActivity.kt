package com.truckcontroller.pro.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.os.Handler
import android.os.Looper
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.R
import com.truckcontroller.pro.sensors.SensorVisualView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Microphone test: pick a mic, live level meter + history, 5-second record and playback. */
class MicrophoneTestActivity : HardwareTestActivity() {

    private companion object {
        const val RATE = 44_100
        const val RECORD_SECONDS = 5
    }

    private lateinit var am: AudioManager
    private lateinit var chips: LinearLayout
    private lateinit var meter: LevelMeterView
    private lateinit var levelText: TextView
    private lateinit var history: SensorVisualView
    private lateinit var recordButton: TextView
    private lateinit var playButton: TextView
    private lateinit var recordInfo: TextView
    private var mics: List<AudioDeviceInfo?> = listOf(null)
    private var selected = 0
    private var peakHold = 0f

    @Volatile private var running = false
    @Volatile private var recording = false
    private var thread: Thread? = null
    private var clip: ShortArray? = null
    private var clipLength = 0
    private var player: AudioTrack? = null
    private val main = Handler(Looper.getMainLooper())

    override fun requiredPermissions() = listOf(Manifest.permission.RECORD_AUDIO)
    override val permissionReason = "Microphone permission is needed to measure sound. Recordings stay in memory and are never saved or sent."

    override fun buildTest() {
        am = getSystemService(AudioManager::class.java)
        mics = listOf<AudioDeviceInfo?>(null) + am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }

        page.addCard(titledCard("MICROPHONE", R.drawable.ic_mic, accent).apply {
            chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            levelText = statusText("—", 26f)
            addView(levelText, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
            meter = LevelMeterView(context)
            addView(meter, LinearLayout.LayoutParams(MATCH, dp(14)).apply { topMargin = dp(6) })
            history = SensorVisualView(context).apply { mode = SensorVisualView.Mode.GRAPH }
            addView(history, LinearLayout.LayoutParams(MATCH, dp(110)).apply { topMargin = dp(10) })
            addView(smallText("Level in dBFS: 0 is the loudest the mic can capture. Quiet room ≈ -60, speech ≈ -30, clap ≈ -10."))
        })
        buildChips()

        page.addCard(titledCard("RECORD & PLAY BACK", R.drawable.ic_circle_dot, accent).apply {
            recordInfo = smallText("Record $RECORD_SECONDS seconds, then play it back to hear the quality.")
            addView(recordInfo)
            recordButton = actionButton("Record", hex("#F87171")) { startRecording() }
            playButton = actionButton("Play back", filled = false) { play() }.apply { alpha = 0.4f; isEnabled = false }
            addView(buttonRow(recordButton, playButton), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        })

        page.addCard(titledCard("ALL MICROPHONES", R.drawable.ic_grid, accent).apply {
            val infos = runCatching { am.microphones }.getOrDefault(emptyList())
            infoRow("Microphones", "${infos.size.takeIf { it > 0 } ?: (mics.size - 1)}")
            infos.forEachIndexed { i, m ->
                val where = when (m.location) {
                    MicrophoneInfo.LOCATION_MAINBODY -> "Phone body"
                    MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "Movable part"
                    MicrophoneInfo.LOCATION_PERIPHERAL -> "Accessory"
                    else -> "Unknown"
                }
                val dir = when (m.directionality) {
                    MicrophoneInfo.DIRECTIONALITY_OMNI -> "omni"
                    MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
                    MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "bi-directional"
                    MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID, MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "directional"
                    else -> null
                }
                infoRow("Mic ${i + 1}" + (m.address.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                    listOfNotNull(where, dir).joinToString(", "))
            }
        })
    }

    private fun buildChips() {
        chips.removeAllViews()
        mics.forEachIndexed { i, d ->
            chips.addView(TextView(this).apply {
                text = micName(d, i)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    haptics.performButtonClickHaptic()
                    selected = i
                    styleChips()
                    restart()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        styleChips()
    }

    private fun styleChips() {
        for (i in 0 until chips.childCount) (chips.getChildAt(i) as TextView).apply {
            val on = i == selected
            setTextColor(if (on) hex("#04201C") else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (on) accent else hex("#1E293B"))
            }
        }
    }

    private fun micName(d: AudioDeviceInfo?, index: Int): String {
        if (d == null) return "Default"
        val address = d.address.takeIf { it.isNotBlank() && it != "0" }
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> address?.replaceFirstChar(Char::uppercase)?.let { "$it mic" } ?: "Built-in mic $index"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
            else -> "USB mic"
        }
    }

    override fun startListening() = startCapture()

    override fun stopListening() {
        stopCapture()
        player?.release()
        player = null
    }

    private fun restart() {
        stopCapture()
        startCapture()
    }

    @SuppressLint("MissingPermission")
    private fun newRecord(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE / 5))
        }.getOrNull() ?: return null
        if (r.state == AudioRecord.STATE_INITIALIZED) return r
        r.release()
        return null
    }

    private fun startCapture() {
        if (running) return
        // A specific mic is recorded unprocessed where possible so noise suppression doesn't hide faults
        val record = (if (selected > 0) newRecord(MediaRecorder.AudioSource.UNPROCESSED) else null)
            ?: newRecord(MediaRecorder.AudioSource.MIC)
        if (record == null) {
            levelText.text = "Microphone unavailable"
            return
        }
        mics.getOrNull(selected)?.let { record.preferredDevice = it }
        running = true
        thread = Thread {
            val buf = ShortArray(RATE / 20)
            runCatching { record.startRecording() }
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                var peak = 0
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    sum += (s * s).toDouble()
                    peak = max(peak, abs(s))
                }
                if (recording) appendClip(buf, n)
                val rms = sqrt(sum / n)
                val db = if (rms < 1) -90.0 else 20 * log10(rms / 32768.0)
                val peakDb = if (peak < 1) -90.0 else 20 * log10(peak / 32768.0)
                main.post { showLevel(db.toFloat(), peakDb.toFloat()) }
            }
            runCatching { record.stop() }
            record.release()
        }.apply { name = "mic-test"; start() }
    }

    private fun stopCapture() {
        running = false
        recording = false
        thread?.join(500)
        thread = null
    }

    private fun showLevel(db: Float, peakDb: Float) {
        val level = ((db + 70) / 70).coerceIn(0f, 1f)
        peakHold = max(peakHold * 0.97f, ((peakDb + 70) / 70).coerceIn(0f, 1f))
        meter.level = level
        meter.peak = peakHold
        levelText.text = String.format(Locale.US, "%.0f dBFS", db)
        levelText.setTextColor(hex(when { db > -12 -> "#F87171"; db > -40 -> "#34D399"; else -> "#94A3B8" }))
        history.pushHistory(floatArrayOf(db))
        if (recording) recordInfo.text = String.format(Locale.US, "Recording… %.1f s", clipLength.toFloat() / RATE)
    }

    @Synchronized
    private fun appendClip(buf: ShortArray, n: Int) {
        val c = clip ?: return
        val count = minOf(n, c.size - clipLength)
        System.arraycopy(buf, 0, c, clipLength, count)
        clipLength += count
        if (clipLength >= c.size) {
            recording = false
            main.post { finishRecording() }
        }
    }

    private fun startRecording() {
        if (!running) startCapture()
        player?.release()
        player = null
        synchronized(this) {
            clip = ShortArray(RATE * RECORD_SECONDS)
            clipLength = 0
        }
        recording = true
        recordButton.text = "Recording…"
        playButton.isEnabled = false
        playButton.alpha = 0.4f
    }

    private fun finishRecording() {
        recordButton.text = "Record again"
        recordInfo.text = "Recorded $RECORD_SECONDS s from ${micName(mics.getOrNull(selected), selected)}. Tap Play back."
        playButton.isEnabled = true
        playButton.alpha = 1f
    }

    private fun play() {
        val c = clip ?: return
        player?.release()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(clipLength * 2)
            .build()
        track.write(c, 0, clipLength)
        track.play()
        player = track
        recordInfo.text = "Playing back…"
        main.postDelayed({ if (player === track) recordInfo.text = "Done. Clear sound = working microphone." }, clipLength * 1000L / RATE)
    }
}
