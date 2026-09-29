package com.truckcontroller.pro.qr

import android.net.Uri
import android.text.InputType
import androidx.annotation.DrawableRes
import com.truckcontroller.pro.R

/** One input box of a content form. [options] turns it into a choice (e.g. Wi-Fi security). */
data class QrField(
    val key: String,
    val label: String,
    val inputType: Int = InputType.TYPE_CLASS_TEXT,
    val hint: String = "",
    val multiLine: Boolean = false,
    val options: List<String>? = null,
)

/** What the QR code encodes, and how its form values become the standard QR text format. */
enum class QrContentType(val label: String, @DrawableRes val icon: Int, val fields: List<QrField>) {
    TEXT("Text", R.drawable.ic_type, listOf(
        QrField("text", "Text", hint = "Any text", multiLine = true),
    )),
    LINK("Link", R.drawable.ic_open, listOf(
        QrField("url", "Website", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "https://example.com"),
    )),
    WIFI("Wi-Fi", R.drawable.ic_wifi, listOf(
        QrField("ssid", "Network name (SSID)"),
        QrField("password", "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        QrField("security", "Security", options = listOf("WPA/WPA2/WPA3", "WEP", "None")),
        QrField("hidden", "Hidden network", options = listOf("No", "Yes")),
    )),
    CONTACT("Contact", R.drawable.ic_contact, listOf(
        QrField("name", "Name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS),
        QrField("phone", "Phone", InputType.TYPE_CLASS_PHONE),
        QrField("email", "Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        QrField("company", "Company"),
        QrField("website", "Website", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
    )),
    PHONE("Phone", R.drawable.ic_phone, listOf(
        QrField("phone", "Phone number", InputType.TYPE_CLASS_PHONE, "+8801…"),
    )),
    EMAIL("Email", R.drawable.ic_mail, listOf(
        QrField("to", "To", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        QrField("subject", "Subject"),
        QrField("body", "Message", multiLine = true),
    )),
    SMS("SMS", R.drawable.ic_sms, listOf(
        QrField("phone", "Phone number", InputType.TYPE_CLASS_PHONE),
        QrField("message", "Message", multiLine = true),
    )),
    LOCATION("Location", R.drawable.ic_map_pin, listOf(
        QrField("lat", "Latitude", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, "23.8103"),
        QrField("lng", "Longitude", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, "90.4125"),
    ));

    /** Builds the text to encode, or null while the required fields are empty. */
    fun build(v: Map<String, String>): String? {
        fun f(key: String) = v[key].orEmpty().trim()
        return when (this) {
            TEXT -> v["text"].orEmpty().ifBlank { null }
            LINK -> f("url").ifEmpty { null }?.let { if (it.contains("://")) it else "https://$it" }
            WIFI -> f("ssid").ifEmpty { null }?.let { ssid ->
                val security = when (v["security"]) { "WEP" -> "WEP"; "None" -> "nopass"; else -> "WPA" }
                buildString {
                    append("WIFI:T:").append(security).append(";S:").append(escape(ssid)).append(';')
                    if (security != "nopass") append("P:").append(escape(v["password"].orEmpty())).append(';')
                    if (v["hidden"] == "Yes") append("H:true;")
                    append(';')
                }
            }
            CONTACT -> if (f("name").isEmpty() && f("phone").isEmpty() && f("email").isEmpty()) null else buildString {
                append("BEGIN:VCARD\nVERSION:3.0\n")
                if (f("name").isNotEmpty()) append("FN:").append(f("name")).append('\n')
                if (f("phone").isNotEmpty()) append("TEL:").append(f("phone")).append('\n')
                if (f("email").isNotEmpty()) append("EMAIL:").append(f("email")).append('\n')
                if (f("company").isNotEmpty()) append("ORG:").append(f("company")).append('\n')
                if (f("website").isNotEmpty()) append("URL:").append(f("website")).append('\n')
                append("END:VCARD")
            }
            PHONE -> f("phone").ifEmpty { null }?.let { "tel:$it" }
            EMAIL -> f("to").ifEmpty { null }?.let { to ->
                val query = listOfNotNull(
                    f("subject").takeIf { it.isNotEmpty() }?.let { "subject=${Uri.encode(it)}" },
                    f("body").takeIf { it.isNotEmpty() }?.let { "body=${Uri.encode(it)}" },
                ).joinToString("&")
                "mailto:$to" + if (query.isNotEmpty()) "?$query" else ""
            }
            SMS -> f("phone").ifEmpty { null }?.let { "SMSTO:$it:${v["message"].orEmpty()}" }
            LOCATION -> {
                val lat = f("lat").toDoubleOrNull()
                val lng = f("lng").toDoubleOrNull()
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) null else "geo:$lat,$lng"
            }
        }
    }

    /** Wi-Fi QR fields escape \ ; , : and ". */
    private fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace(":", "\\:").replace("\"", "\\\"")
}
