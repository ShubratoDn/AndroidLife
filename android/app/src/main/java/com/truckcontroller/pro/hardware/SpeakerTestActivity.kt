package com.truckcontroller.pro.hardware

import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Speaker test: tones through loudspeaker, left/right, earpiece, sweeps; live list of audio outputs. */
class SpeakerTestActivity : HardwareTestActivity() {

    private companion object {
        const val RATE = 44_100
        /** Tags the auto-stop callback so it can be cancelled without touching device callbacks. */
        val STOP_TOKEN = Any()
    }

    private enum class Route { DEFAULT, SPEAKER, EARPIECE }

    private lateinit var am: AudioManager
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var outputs: LinearLayout
    private var track: AudioTrack? = null
    private var communicationMode = false
    private val handler = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = showOutputs()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = showOutputs()
    }

    override fun buildTest() {
        am = getSystemService(AudioManager::class.java)
        page.addCard(card().apply {
            status = statusText("Ready")
            statusSub = smallText()
            addView(status)
            addView(statusSub)
        })

        page.addCard(titledCard("LOUDSPEAKER", R.drawable.ic_speaker, accent).apply {
            addView(buttonGrid(2, listOf(
                actionButton("Speaker 440 Hz") { play("Loudspeaker · 440 Hz", Route.SPEAKER) { tone(440.0, 440.0, 2.0) } },
                actionButton("Stereo", filled = false) { play("Stereo · both sides", Route.DEFAULT) { tone(440.0, 440.0, 2.0) } },
                actionButton("Left", filled = false) { play("Left channel only", Route.DEFAULT) { tone(523.0, 523.0, 2.0, channel = 1) } },
                actionButton("Right", filled = false) { play("Right channel only", Route.DEFAULT) { tone(659.0, 659.0, 2.0, channel = 2) } },
                actionButton("Bass 80 Hz", filled = false) { play("Low tone · 80 Hz", Route.SPEAKER) { tone(80.0, 80.0, 2.0) } },
                actionButton("Treble 10 kHz", filled = false) { play("High tone · 10 kHz", Route.SPEAKER) { tone(10_000.0, 10_000.0, 2.0) } },
                actionButton("Sweep 50 Hz–16 kHz", filled = false) { play("Frequency sweep · listen for rattles", Route.SPEAKER) { tone(50.0, 16_000.0, 6.0) } },
                actionButton("Stop", hex("#F87171"), filled = false) { stop() },
            )))
            addView(smallText("Left/Right only sound different on phones with stereo speakers, or with headphones."),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        })

        page.addCard(titledCard("EARPIECE (CALL SPEAKER)", R.drawable.ic_phone, accent).apply {
            addView(smallText("Hold the top of the phone to your ear. The tone should come only from the earpiece."))
            addView(buttonRow(
                actionButton("Earpiece tone") { play("Earpiece · 440 Hz", Route.EARPIECE) { tone(440.0, 440.0, 3.0) } },
                actionButton("Earpiece sweep", filled = false) { play("Earpiece sweep", Route.EARPIECE) { tone(200.0, 4_000.0, 4.0) } },
            ), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        })

        outputs = titledCard("AUDIO OUTPUTS", R.drawable.ic_headphones, accent)
        page.addCard(outputs)
    }

    override fun startListening() {
        am.registerAudioDeviceCallback(deviceCallback, handler)
        showOutputs()
    }

    override fun stopListening() {
        stop()
        am.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun showOutputs() {
        while (outputs.childCount > 1) outputs.removeViewAt(1)
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val jack = devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        outputs.infoRow("Headphone jack", if (jack) "Connected ✓" else "Nothing plugged in")
            .setTextColor(if (jack) hex("#34D399") else Color.WHITE)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        outputs.infoRow("Media volume", "${vol * 100 / max.coerceAtLeast(1)}%")
            .setTextColor(if (vol == 0) hex("#F87171") else Color.WHITE)
        devices.mapNotNull { d -> outputName(d.type)?.let { it to d } }.distinctBy { it.first }.forEach { (name, d) ->
            val detail = listOfNotNull(
                d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL },
                d.channelCounts.maxOrNull()?.let { if (it >= 2) "stereo" else "mono" },
            ).joinToString(" · ")
            outputs.infoRow(name, detail.ifBlank { "Available" })
        }
    }

    private fun outputName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loudspeaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset (calls)"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE audio"
        else -> null
    }

    /** Stereo PCM: sine (or exponential sweep) from [f0] to [f1] Hz; channel 0 = both, 1 = left, 2 = right. */
    private fun tone(f0: Double, f1: Double, seconds: Double, channel: Int = 0): ShortArray {
        val n = (RATE * seconds).toInt()
        val out = ShortArray(n * 2)
        val fade = RATE / 50
        var phase = 0.0
        val k = ln(f1 / f0) / n
        for (i in 0 until n) {
            val f = if (f0 == f1) f0 else f0 * exp(k * i)
            phase += 2 * PI * f / RATE
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            val s = (sin(phase) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
            out[i * 2] = if (channel == 2) 0 else s
            out[i * 2 + 1] = if (channel == 1) 0 else s
        }
        return out
    }

    private fun play(label: String, route: Route, pcm: () -> ShortArray) {
        stop()
        val data = pcm()
        val earpiece = route == Route.EARPIECE
        if (earpiece) enterCallMode()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(if (earpiece) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        if (route == Route.SPEAKER) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { t.preferredDevice = it }
        }
        t.write(data, 0, data.size)
        t.play()
        track = t
        status.text = label
        status.setTextColor(accent)
        statusSub.text = if (am.getStreamVolume(if (earpiece) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC) == 0)
            "Volume is at 0 — turn it up" else "Playing…"
        handler.postDelayed({ if (track === t) stop() }, STOP_TOKEN, data.size / 2 * 1000L / RATE + 150)
    }

    private fun enterCallMode() {
        communicationMode = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
    }

    private fun stop() {
        handler.removeCallbacksAndMessages(STOP_TOKEN)
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        if (communicationMode) {
            communicationMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        }
        if (::status.isInitialized && status.text != "Ready") {
            status.text = "Ready"
            status.setTextColor(Color.WHITE)
            statusSub.text = "Did you hear it clearly, without crackling or buzzing?"
        }
    }
}
