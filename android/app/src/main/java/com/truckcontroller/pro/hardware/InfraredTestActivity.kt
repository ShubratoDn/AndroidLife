package com.truckcontroller.pro.hardware

import android.hardware.ConsumerIrManager
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.R
import java.util.Locale

/** Infrared blaster test: carrier frequency range and real TV power codes (NEC, Samsung, Sony). */
class InfraredTestActivity : HardwareTestActivity() {

    private lateinit var ir: ConsumerIrManager
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    @Volatile private var sending = false

    override fun buildTest() {
        ir = getSystemService(ConsumerIrManager::class.java)
        page.addCard(card().apply {
            status = statusText("Ready")
            statusSub = smallText("Point the top edge of the phone at the TV, 1–3 m away.")
            addView(status)
            addView(statusSub)
        })

        page.addCard(titledCard("SEND A CODE", R.drawable.ic_radio, accent).apply {
            addView(buttonGrid(2, listOf(
                actionButton("Samsung TV power") { send("Samsung TV power", 38_000, samsung(0xE0E040BFL)) },
                actionButton("LG TV power", filled = false) { send("LG TV power", 38_000, nec(0x20DF10EFL)) },
                actionButton("Sony TV power", filled = false) { send("Sony TV power", 40_000, sony(command = 21, device = 1)) },
                actionButton("NEC power (generic)", filled = false) { send("NEC power", 38_000, nec(0x00FF02FDL)) },
            )))
            addView(buttonRow(actionButton("Camera check (1 s of flashes)", filled = false) {
                send("Flashing for the camera check", 38_000, IntArray(20) { 50_000 })
            }), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            addView(smallText("Camera check: look at the top of this phone through another phone's camera. " +
                "The IR LED should blink purple-white."), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        })

        page.addCard(titledCard("TRANSMITTER", R.drawable.ic_info, accent).apply {
            infoRow("IR emitter").setYesNo(ir.hasIrEmitter())
            val ranges = runCatching { ir.carrierFrequencies }.getOrNull().orEmpty()
            if (ranges.isEmpty()) infoRow("Carrier frequencies", "Not reported")
            ranges.forEachIndexed { i, r ->
                infoRow(if (ranges.size == 1) "Carrier frequency" else "Range ${i + 1}",
                    if (r.minFrequency == r.maxFrequency) khz(r.minFrequency) else "${khz(r.minFrequency)} – ${khz(r.maxFrequency)}")
            }
            addView(smallText("Most TV and AC remotes use 36–40 kHz."))
        })
    }

    private fun khz(hz: Int) = String.format(Locale.US, "%.1f kHz", hz / 1000f)

    private fun send(label: String, frequency: Int, pattern: IntArray) {
        if (sending) return
        sending = true
        status.text = "Sending…"
        status.setTextColor(hex("#FBBF24"))
        statusSub.text = label
        Thread {
            val result = runCatching { ir.transmit(frequency, pattern) }
            runOnUiThread {
                sending = false
                if (result.isSuccess) {
                    status.text = "Sent ✓"
                    status.setTextColor(hex("#34D399"))
                    statusSub.text = "$label. If the TV didn't react, it may use another brand's code."
                } else {
                    status.text = "Failed to send"
                    status.setTextColor(hex("#F87171"))
                    statusSub.text = result.exceptionOrNull()?.message ?: "The IR transmitter reported an error."
                }
            }
        }.start()
    }

    /** NEC: 9 ms mark, 4.5 ms space, 32 bits (560 µs mark; 560 / 1690 µs space), stop mark. */
    private fun nec(code: Long) = pulseDistance(9000, 4500, code)

    /** Samsung32: like NEC with a 4.5 ms / 4.5 ms header. */
    private fun samsung(code: Long) = pulseDistance(4500, 4500, code)

    private fun pulseDistance(headerMark: Int, headerSpace: Int, code: Long): IntArray {
        val out = mutableListOf(headerMark, headerSpace)
        for (bit in 31 downTo 0) {
            out += 560
            out += if ((code shr bit) and 1L == 1L) 1690 else 560
        }
        out += 560
        return out.toIntArray()
    }

    /** Sony SIRC 12-bit: 2.4 ms header, 1.2 ms / 0.6 ms marks, 0.6 ms spaces, LSB first, sent 3 times. */
    private fun sony(command: Int, device: Int): IntArray {
        val frame = mutableListOf(2400, 600)
        val bits = command or (device shl 7)
        for (i in 0 until 12) {
            frame += if ((bits shr i) and 1 == 1) 1200 else 600
            frame += 600
        }
        val frameLength = frame.sum()
        frame[frame.size - 1] = 600 + (45_000 - frameLength).coerceAtLeast(0)
        return (frame + frame + frame).toIntArray()
    }
}
