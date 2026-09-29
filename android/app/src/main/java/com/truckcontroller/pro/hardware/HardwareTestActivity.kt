package com.truckcontroller.pro.hardware

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.truckcontroller.pro.R
import com.truckcontroller.pro.ToolActivity

/**
 * Base for a hardware test screen: header, "What it does / How to test" card, availability check and
 * runtime permission request, then [buildTest] fills the page.
 */
abstract class HardwareTestActivity : ToolActivity() {

    protected val accent = Color.parseColor("#2DD4BF")
    protected lateinit var page: LinearLayout
    protected lateinit var scroll: ScrollView
    protected val test: HardwareTest get() = HardwareTests.forClass(javaClass)
    /** True once the test UI is built (permissions granted, hardware present). */
    protected var started = false
        private set

    private var permissionCard: View? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missingPermissions().isEmpty()) startTest() else showPermissionCard(denied = true)
    }

    /** Runtime permissions the test needs before it can start. */
    protected open fun requiredPermissions(): List<String> = emptyList()
    /** Why the permissions are needed, shown on the permission card. */
    protected open val permissionReason = "This test needs a permission to read the hardware."

    protected abstract fun buildTest()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        page.addCard(aboutCard(test.what, test.how, accent))
        scroll = ScrollView(this).apply { addView(page) }
        setContentView(toolPage(test.title, test.icon, accent, scroll))

        if (!test.available(this)) {
            page.addCard(card().apply {
                addView(TextView(context).apply {
                    text = "Not on this phone"
                    textSize = 17f
                    setTextColor(Color.parseColor("#F87171"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = "This phone doesn't report a ${test.title.lowercase()} component, so it can't be tested."
                    textSize = 13f
                    setTextColor(color(R.color.slate_300))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            })
            return
        }
        if (missingPermissions().isEmpty()) startTest() else showPermissionCard(denied = false)
    }

    private fun missingPermissions() = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun startTest() {
        permissionCard?.let { page.removeView(it) }
        permissionCard = null
        if (started) return
        started = true
        buildTest()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startListening()
    }

    /** Start sensors / radios / callbacks; called while the screen is visible and the test is built. */
    protected open fun startListening() = Unit
    /** Stop everything started in [startListening]. */
    protected open fun stopListening() = Unit

    override fun onStart() {
        super.onStart()
        if (started) startListening()
    }

    override fun onStop() {
        super.onStop()
        if (started) stopListening()
    }

    private fun showPermissionCard(denied: Boolean) {
        permissionCard?.let { page.removeView(it) }
        permissionCard = card().apply {
            addView(TextView(context).apply {
                text = if (denied) "Permission denied" else "Permission needed"
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = permissionReason + if (denied) "\nIf Android no longer asks, allow it in the app settings." else ""
                textSize = 13f
                setTextColor(color(R.color.slate_300))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            addView(buttonRow(
                actionButton("Allow") { permissionLauncher.launch(requiredPermissions().toTypedArray()) },
                *if (denied) arrayOf(actionButton("App settings", filled = false) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }) else emptyArray(),
            ), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        page.addCard(permissionCard!!)
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted from the settings screen
        if (!started && permissionCard != null && missingPermissions().isEmpty()) startTest()
    }

    // ------------------------------------------------------------------
    // Shared widgets
    // ------------------------------------------------------------------

    protected fun LinearLayout.addCard(view: View) =
        addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

    protected fun actionButton(label: String, color: Int = accent, filled: Boolean = true, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(if (filled) Color.parseColor("#04201C") else color)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (filled) color else Color.parseColor("#1E293B"))
            }
            setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        }

    /** Buttons side by side with equal width. */
    protected fun buttonRow(vararg buttons: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    /** Grid of buttons, [columns] per row. */
    protected fun buttonGrid(columns: Int, buttons: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        buttons.chunked(columns).forEachIndexed { r, row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, b ->
                    addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (i > 0) marginStart = dp(8) })
                }
                repeat(columns - row.size) {
                    addView(View(context), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (r > 0) topMargin = dp(8) })
        }
    }

    /** Big status line, e.g. "3D fix" or "Tag detected". */
    protected fun statusText(text: String = "", size: Float = 20f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    }

    protected fun smallText(text: String = "") = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(R.color.slate_400))
        setLineSpacing(0f, 1.1f)
    }

    protected fun TextView.setYesNo(value: Boolean?) {
        text = when (value) { true -> "Yes"; false -> "No"; null -> "Unknown" }
        setTextColor(hex(when (value) { true -> "#34D399"; false -> "#94A3B8"; null -> "#64748B" }))
    }

    protected fun hex(color: String) = Color.parseColor(color)

    /** Signal quality colour for a 0–4 level. */
    protected fun levelColor(level: Int) = hex(when {
        level >= 3 -> "#34D399"; level == 2 -> "#FBBF24"; else -> "#F87171"
    })
}
