package com.truckcontroller.pro.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural cockpit sound effects (no audio assets): clicks, gear clunks, air brake hiss,
 * blinker relay, looping air horn and an RPM-following diesel idle.
 */
class SoundEngine {

    companion object {
        private const val TAG = "TruckSoundEngine"
        private const val SAMPLE_RATE = 22050
        // Loop buffers are 0.5 s long, so every tone that is a multiple of 2 Hz loops seamlessly
        private const val LOOP_SAMPLES = SAMPLE_RATE / 2
    }

    var enabled = true
        set(value) {
            field = value
            if (!value) {
                stopHorn()
                stopEngine()
            }
        }

    var volume = 0.8f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    var muted = false
        set(value) {
            field = value
            applyVolume()
        }

    private val click = oneShot(synthClick(1000.0, 0.025))
    private val clickLow = oneShot(synthClick(600.0, 0.03))
    private val gearShift = oneShot(synthGearShift())
    private val airBrake = oneShot(synthAirBrake())
    private val tick = oneShot(synthClick(2200.0, 0.012))
    private val tock = oneShot(synthClick(1600.0, 0.012))
    private val squelch = oneShot(synthNoiseBurst(0.16))
    private val starter = oneShot(synthStarter())
    private val horn = loop(synthHorn())
    private val engine = loop(synthEngine())

    private val allTracks = listOfNotNull(click, clickLow, gearShift, airBrake, tick, tock, squelch, starter, horn, engine)
    private var engineOn = false

    init {
        applyVolume()
    }

    fun playClick() = play(click)
    fun playRetarderClick() = play(clickLow)
    fun playGearShift() = play(gearShift)
    fun playAirBrake() = play(airBrake)
    fun playTurnSignal(tock: Boolean) = play(if (tock) this.tock else tick)
    fun playCbSquelch() = play(squelch)

    fun startHorn() {
        if (!enabled) return
        restartLoop(horn)
    }

    fun stopHorn() {
        horn?.let { runCatching { it.pause() } }
    }

    fun startEngine() {
        if (!enabled) return
        engineOn = true
        play(starter)
        engine?.let { runCatching { it.playbackRate = SAMPLE_RATE } }
        restartLoop(engine)
    }

    fun stopEngine() {
        engineOn = false
        engine?.let { runCatching { it.pause() } }
    }

    /** Pitch-shifts the idle loop between 650 rpm (1.0x) and ~2100 rpm (2.0x). */
    fun updateEngineRpm(rpm: Float) {
        if (!engineOn) return
        val ratio = (rpm / 650f).coerceIn(1f, 2f)
        engine?.let { runCatching { it.playbackRate = (SAMPLE_RATE * ratio).toInt() } }
    }

    fun release() {
        allTracks.forEach { runCatching { it.release() } }
    }

    private fun play(track: AudioTrack?) {
        if (!enabled || track == null) return
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    /** Rewinds a looping track and re-arms its infinite loop before playing. */
    private fun restartLoop(track: AudioTrack?) {
        if (track == null) return
        runCatching {
            track.pause()
            track.playbackHeadPosition = 0
            track.setLoopPoints(0, LOOP_SAMPLES, -1)
            track.play()
        }.onFailure { Log.w(TAG, "Unable to restart loop", it) }
    }

    private fun applyVolume() {
        val v = if (muted) 0f else volume
        allTracks.forEach { runCatching { it.setVolume(v) } }
    }

    // ---- Track creation ----

    private fun oneShot(samples: ShortArray): AudioTrack? = createTrack(samples, loop = false)
    private fun loop(samples: ShortArray): AudioTrack? = createTrack(samples, loop = true)

    private fun createTrack(samples: ShortArray, loop: Boolean): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .apply {
                write(samples, 0, samples.size)
                if (loop) setLoopPoints(0, samples.size, -1)
            }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to create audio track", e)
        null
    }

    // ---- Synthesis ----

    private fun toPcm(buffer: DoubleArray): ShortArray {
        val peak = buffer.maxOf { abs(it) }.coerceAtLeast(1e-6)
        val gain = 0.9 / peak
        return ShortArray(buffer.size) { (buffer[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun synthClick(freq: Double, seconds: Double): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt()
        return toPcm(DoubleArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            sin(2 * PI * freq * t) * exp(-t * 180)
        })
    }

    private fun synthGearShift(): ShortArray {
        val n = (SAMPLE_RATE * 0.16).toInt()
        val rnd = Random(7)
        return toPcm(DoubleArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val first = if (t < 0.05) (sin(2 * PI * 140 * t) + rnd.nextDouble(-0.6, 0.6)) * exp(-t * 60) else 0.0
            val t2 = t - 0.07
            val second = if (t2 > 0) (sin(2 * PI * 95 * t2) + rnd.nextDouble(-0.8, 0.8)) * exp(-t2 * 45) else 0.0
            first * 0.7 + second
        })
    }

    private fun synthAirBrake(): ShortArray {
        val n = (SAMPLE_RATE * 0.55).toInt()
        val rnd = Random(3)
        var lp = 0.0
        return toPcm(DoubleArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val noise = rnd.nextDouble(-1.0, 1.0)
            lp += (noise - lp) * 0.55 // soft low-pass, leaves a bright hiss
            val attack = (t / 0.015).coerceAtMost(1.0)
            (noise - lp * 0.6) * attack * exp(-t * 6)
        })
    }

    private fun synthNoiseBurst(seconds: Double): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt()
        val rnd = Random(11)
        return toPcm(DoubleArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            rnd.nextDouble(-1.0, 1.0) * exp(-t * 14)
        })
    }

    private fun synthStarter(): ShortArray {
        val n = (SAMPLE_RATE * 0.8).toInt()
        val rnd = Random(5)
        return toPcm(DoubleArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val crank = 0.5 + 0.5 * sin(2 * PI * 9 * t)
            val body = sin(2 * PI * 55 * t) + 0.5 * sin(2 * PI * 110 * t) + rnd.nextDouble(-0.4, 0.4)
            body * crank * (1 - t / 0.8)
        })
    }

    private fun synthHorn(): ShortArray {
        val buffer = DoubleArray(LOOP_SAMPLES) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0
            // Dual trumpet chord with a few harmonics for a brassy edge
            for (h in 1..4) {
                v += sin(2 * PI * 310 * h * t) / h
                v += sin(2 * PI * 370 * h * t) / h
            }
            v
        }
        return toPcm(buffer)
    }

    private fun synthEngine(): ShortArray {
        val buffer = DoubleArray(LOOP_SAMPLES) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            // 6-cylinder diesel idle: 32 Hz firing pulse with harmonics
            val firing = 0.6 + 0.4 * sin(2 * PI * 32 * t)
            (sin(2 * PI * 32 * t) + 0.6 * sin(2 * PI * 64 * t) + 0.3 * sin(2 * PI * 96 * t) +
                0.15 * sin(2 * PI * 192 * t)) * firing
        }
        return toPcm(buffer)
    }
}
