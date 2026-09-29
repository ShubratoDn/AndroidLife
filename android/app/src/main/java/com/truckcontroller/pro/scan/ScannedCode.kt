package com.truckcontroller.pro.scan

import android.content.Context
import android.util.Patterns
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What a scanned code contains, so the result can offer the right action. */
enum class CodeType(val label: String) {
    URL("Link"),
    WIFI("Wi-Fi network"),
    EMAIL("Email"),
    PHONE("Phone number"),
    SMS("SMS"),
    GEO("Location"),
    CONTACT("Contact"),
    PRODUCT("Product barcode"),
    TEXT("Text"),
}

data class ScannedCode(
    val text: String,
    val format: String,     // e.g. "QR Code", "EAN-13"
    val scannedAt: Long,
) {
    val type: CodeType get() = detectType(text, format)

    /** Wi-Fi QR fields: "WIFI:T:WPA;S:MyNet;P:secret;;" */
    val wifi: Map<String, String> get() = if (type == CodeType.WIFI) parseFields(text.removePrefix("WIFI:").removePrefix("wifi:")) else emptyMap()

    companion object {
        fun detectType(text: String, format: String): CodeType {
            val t = text.trim()
            val lower = t.lowercase(Locale.US)
            return when {
                lower.startsWith("wifi:") -> CodeType.WIFI
                lower.startsWith("mailto:") || lower.startsWith("matmsg:") ||
                    (Patterns.EMAIL_ADDRESS.matcher(t).matches()) -> CodeType.EMAIL
                lower.startsWith("tel:") -> CodeType.PHONE
                lower.startsWith("smsto:") || lower.startsWith("sms:") -> CodeType.SMS
                lower.startsWith("geo:") -> CodeType.GEO
                lower.startsWith("begin:vcard") || lower.startsWith("mecard:") -> CodeType.CONTACT
                lower.startsWith("http://") || lower.startsWith("https://") ||
                    (!t.contains(' ') && Patterns.WEB_URL.matcher(t).matches() && t.contains('.')) -> CodeType.URL
                format.startsWith("EAN") || format.startsWith("UPC") -> CodeType.PRODUCT
                else -> CodeType.TEXT
            }
        }

        /** Parses "K:value;K:value;" with backslash escapes (Wi-Fi / MECARD style). */
        fun parseFields(body: String): Map<String, String> {
            val fields = LinkedHashMap<String, String>()
            var key = StringBuilder()
            var value = StringBuilder()
            var inValue = false
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length -> {
                        (if (inValue) value else key).append(body[i + 1])
                        i++
                    }
                    !inValue && c == ':' -> inValue = true
                    inValue && c == ';' -> {
                        if (key.isNotEmpty()) fields[key.toString().uppercase(Locale.US)] = value.toString()
                        key = StringBuilder()
                        value = StringBuilder()
                        inValue = false
                    }
                    else -> (if (inValue) value else key).append(c)
                }
                i++
            }
            if (inValue && key.isNotEmpty()) fields[key.toString().uppercase(Locale.US)] = value.toString()
            return fields
        }
    }
}

/** Last scans, newest first, kept in app storage. */
object ScanHistory {

    private const val PREFS = "scan_history"
    private const val KEY = "scans"
    private const val MAX_SCANS = 200

    fun all(context: Context): List<ScannedCode> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                array.getJSONObject(i).run { ScannedCode(getString("text"), getString("format"), getLong("at")) }
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, code: ScannedCode) {
        // The same code scanned again moves to the top instead of being duplicated
        save(context, (listOf(code) + all(context).filterNot { it.text == code.text }).take(MAX_SCANS))
    }

    fun delete(context: Context, code: ScannedCode) = save(context, all(context).filterNot { it.scannedAt == code.scannedAt })

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, scans: List<ScannedCode>) {
        val array = JSONArray()
        scans.forEach { s -> array.put(JSONObject().put("text", s.text).put("format", s.format).put("at", s.scannedAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
