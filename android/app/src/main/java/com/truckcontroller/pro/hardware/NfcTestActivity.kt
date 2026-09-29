package com.truckcontroller.pro.hardware

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.R
import java.nio.charset.Charset

/** NFC test: adapter status, card-emulation support, and a reader that decodes any card or tag. */
class NfcTestActivity : HardwareTestActivity() {

    private var nfc: NfcAdapter? = null
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var settingsButton: TextView
    private lateinit var tagCard: LinearLayout
    private var tagsRead = 0

    override fun buildTest() {
        nfc = NfcAdapter.getDefaultAdapter(this)
        page.addCard(card().apply {
            status = statusText()
            statusSub = smallText()
            addView(status)
            addView(statusSub)
            settingsButton = actionButton("Turn on NFC") { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            addView(buttonRow(settingsButton), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        })

        page.addCard(titledCard("CAPABILITIES", R.drawable.ic_nfc, accent).apply {
            infoRow("Tap to pay (card emulation)")
                .setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
            infoRow("FeliCa emulation (Japan)")
                .setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val secure = nfc?.isSecureNfcSupported == true
                infoRow("Secure NFC").setYesNo(secure)
                if (secure) infoRow("Secure NFC enabled").setYesNo(nfc?.isSecureNfcEnabled)
            }
        })

        tagCard = titledCard("LAST CARD / TAG", R.drawable.ic_radar, accent)
        tagCard.addView(smallText("Nothing read yet."))
        page.addCard(tagCard)
    }

    override fun onResume() {
        super.onResume()
        if (!started) return
        showStatus()
        runCatching {
            nfc?.enableReaderMode(this, { tag -> runOnUiThread { showTag(tag) } },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE, null)
        }
    }

    override fun onPause() {
        super.onPause()
        if (started) runCatching { nfc?.disableReaderMode(this) }
    }

    private fun showStatus() {
        val on = nfc?.isEnabled == true
        status.text = if (!on) "NFC is off" else if (tagsRead == 0) "Ready — hold a card to the back" else "Card read ✓ ($tagsRead)"
        status.setTextColor(hex(if (!on) "#FBBF24" else "#34D399"))
        statusSub.text = if (on) "Keep the card still for a second. Metal cases can block NFC." else "Turn NFC on to read cards and tags."
        settingsButton.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun showTag(tag: Tag) {
        tagsRead++
        haptics.performButtonClickHaptic()
        showStatus()
        while (tagCard.childCount > 1) tagCard.removeViewAt(1)
        tagCard.infoRow("ID (UID)", tag.id.joinToString(":") { "%02X".format(it) })
        tagCard.infoRow("Technologies", tag.techList.joinToString(", ") { it.substringAfterLast('.') })
        tagCard.infoRow("Type", tagType(tag))
        NfcA.get(tag)?.let { tagCard.infoRow("ATQA / SAK", "${it.atqa.joinToString("") { b -> "%02X".format(b) }} / %02X".format(it.sak)) }
        runCatching { MifareClassic.get(tag) }.getOrNull()?.let {
            tagCard.infoRow("Memory", "${it.size} bytes · ${it.sectorCount} sectors")
        }
        IsoDep.get(tag)?.let { tagCard.infoRow("Max transfer", "${it.maxTransceiveLength} bytes") }

        val ndef = Ndef.get(tag)
        if (ndef == null) {
            tagCard.infoRow("NDEF data", "None (payment/ID cards are protected)")
            return
        }
        tagCard.infoRow("NDEF type", ndef.type.substringAfterLast('.'))
        tagCard.infoRow("Capacity", "${ndef.maxSize} bytes")
        tagCard.infoRow("Writable").setYesNo(ndef.isWritable)
        val message: NdefMessage? = ndef.cachedNdefMessage
        if (message == null || message.records.isEmpty()) {
            tagCard.infoRow("Records", "Empty tag")
            return
        }
        message.records.forEachIndexed { i, r -> tagCard.infoRow("Record ${i + 1}", describe(r)).setTextColor(Color.WHITE) }
    }

    private fun tagType(tag: Tag): String {
        val techs = tag.techList.map { it.substringAfterLast('.') }
        runCatching { MifareClassic.get(tag) }.getOrNull()?.let {
            return "MIFARE Classic " + when (it.size) { MifareClassic.SIZE_MINI -> "Mini"; MifareClassic.SIZE_1K -> "1K"
                MifareClassic.SIZE_2K -> "2K"; MifareClassic.SIZE_4K -> "4K"; else -> "" }
        }
        MifareUltralight.get(tag)?.let {
            return if (it.type == MifareUltralight.TYPE_ULTRALIGHT_C) "MIFARE Ultralight C" else "MIFARE Ultralight / NTAG"
        }
        return when {
            "IsoDep" in techs -> "Smart card (ISO 14443-4): bank, transit or ID card"
            "NfcF" in techs -> "FeliCa"
            "NfcV" in techs -> "ISO 15693 (vicinity tag)"
            "NfcB" in techs -> "ISO 14443-B"
            "NfcBarcode" in techs -> "NFC barcode"
            else -> "ISO 14443-A tag"
        }
    }

    private fun describe(r: NdefRecord): String {
        r.toUri()?.let { return "Link: $it" }
        if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) && r.payload.isNotEmpty()) {
            val status = r.payload[0].toInt()
            val langLength = status and 0x3F
            val charset = if (status and 0x80 != 0) Charset.forName("UTF-16") else Charsets.UTF_8
            return "Text: " + String(r.payload, 1 + langLength, r.payload.size - 1 - langLength, charset)
        }
        if (r.tnf == NdefRecord.TNF_MIME_MEDIA) return "Data (${String(r.type, Charsets.US_ASCII)}), ${r.payload.size} bytes"
        return "${r.payload.size} bytes"
    }
}
