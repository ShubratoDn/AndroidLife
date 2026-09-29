package com.truckcontroller.pro.hardware

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import com.truckcontroller.pro.R

/** Fingerprint & face test: available biometrics, enrolment status and a live verification. */
class BiometricTestActivity : HardwareTestActivity() {

    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private val rows = HashMap<String, TextView>()
    private var succeeded = 0
    private var rejected = 0
    private var cancel: CancellationSignal? = null

    override fun buildTest() {
        page.addCard(card().apply {
            status = statusText("Ready to verify")
            statusSub = smallText("Tap a button below, then use your fingerprint or face.")
            addView(status)
            addView(statusSub)
            val buttons = mutableListOf(actionButton("Verify") { verify(strong = false) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) buttons += actionButton("Fingerprint only", filled = false) { verify(strong = true) }
            addView(buttonRow(*buttons.toTypedArray()), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(buttonRow(actionButton("Biometric settings", filled = false) {
                startActivity(Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Settings.ACTION_BIOMETRIC_ENROLL else Settings.ACTION_SECURITY_SETTINGS))
            }), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        })

        page.addCard(titledCard("RESULTS", R.drawable.ic_activity, accent).apply {
            rows["Recognised"] = infoRow("Recognised", "0")
            rows["Rejected"] = infoRow("Not recognised", "0")
            rows["Method"] = infoRow("Last method", "—")
        })

        page.addCard(titledCard("HARDWARE", R.drawable.ic_fingerprint, accent).apply {
            infoRow("Fingerprint sensor").setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                infoRow("Face unlock").setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_FACE))
                infoRow("Iris scanner").setYesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_IRIS))
            }
            infoRow("Screen lock set").setYesNo(getSystemService(KeyguardManager::class.java)?.isDeviceSecure)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bm = getSystemService(BiometricManager::class.java)
                infoRow("Strong biometrics", statusName(bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)))
                infoRow("Any biometrics", statusName(bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                infoRow("Biometrics", statusName(getSystemService(BiometricManager::class.java).canAuthenticate()))
            }
            addView(smallText("Strong = secure enough for payments (usually fingerprint). Face unlock on many phones is weak (camera only)."))
        })
    }

    override fun stopListening() {
        cancel?.cancel()
        cancel = null
    }

    private fun verify(strong: Boolean) {
        cancel?.cancel()
        val signal = CancellationSignal().also { cancel = it }
        val builder = BiometricPrompt.Builder(this)
            .setTitle("Biometric test")
            .setSubtitle(if (strong) "Touch the fingerprint sensor" else "Use your fingerprint or face")
            .setNegativeButton("Cancel", mainExecutor) { _, _ -> showResult("Cancelled", "#94A3B8", "") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                if (strong) BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setConfirmationRequired(false)
        status.text = "Waiting…"
        status.setTextColor(hex("#FBBF24"))
        runCatching {
            builder.build().authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    succeeded++
                    val method = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) when (result.authenticationType) {
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC -> "Biometric"
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL -> "PIN / pattern"
                        else -> "Unknown"
                    } else "Biometric"
                    rows["Method"]?.text = method
                    showResult("Recognised ✓", "#34D399", "The sensor works and recognised you.")
                }

                override fun onAuthenticationFailed() {
                    rejected++
                    showResult("Not recognised", "#F87171", "Try again, or test with an unregistered finger.")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val (title, color) = when (errorCode) {
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> "Cancelled" to "#94A3B8"
                        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> "Locked — too many attempts" to "#F87171"
                        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> "Nothing enrolled" to "#FBBF24"
                        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT -> "No biometric hardware" to "#F87171"
                        else -> "Error" to "#F87171"
                    }
                    showResult(title, color, errString.toString())
                }
            })
        }.onFailure { showResult("Can't start", "#F87171", it.message ?: "") }
    }

    private fun showResult(title: String, color: String, detail: String) {
        status.text = title
        status.setTextColor(hex(color))
        statusSub.text = detail
        rows["Recognised"]?.text = "$succeeded"
        rows["Rejected"]?.text = "$rejected"
    }

    private fun statusName(code: Int) = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "Ready (set up)"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Not set up"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Unavailable"
        15 -> "Security update needed"
        else -> "Unknown"
    }
}
