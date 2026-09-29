package com.truckcontroller.pro

import android.Manifest
import android.app.usage.StorageStatsManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.truckcontroller.pro.screentime.UsageAnalyzer
import java.util.concurrent.Executors

/**
 * Storage Analyzer: used / free space by category (apps, images, videos, audio, system & other),
 * the largest media files (open or delete) and the largest apps (open their settings to clear cache).
 */
class StorageActivity : ToolActivity() {

    private data class Category(val name: String, val bytes: Long, val color: Int, val icon: Int)
    private data class BigFile(val uri: Uri, val name: String, val bytes: Long, val kind: String, val icon: Int)
    private data class BigApp(val pkg: String, val label: String, val icon: Drawable?, val bytes: Long)
    private data class Result(
        val total: Long, val free: Long, val categories: List<Category>,
        val files: List<BigFile>, val apps: List<BigApp>?, val mediaAllowed: Boolean,
    )

    private val accent = Color.parseColor("#F472B6")
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout

    private val mediaPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { analyze() }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            analyze()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(toolPage("Storage Analyzer", R.drawable.ic_storage, accent, ScrollView(this).apply { addView(list) },
            R.drawable.ic_settings to { runCatching { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } }))
        if (!hasMediaAccess()) permissionLauncher.launch(mediaPermissions)
    }

    override fun onResume() {
        super.onResume()
        analyze()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun hasMediaAccess() = mediaPermissions.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------
    // Analysis (background thread)
    // ------------------------------------------------------------------

    private fun analyze() {
        list.removeAllViews()
        list.addView(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply {
                text = "Analyzing storage…"
                setTextColor(color(R.color.slate_300))
                textSize = 14f
            })
        })
        worker.execute {
            val result = runCatching { compute() }.getOrNull()
            runOnUiThread { if (!isFinishing && result != null) render(result) }
        }
    }

    private fun compute(): Result {
        val stats = getSystemService(StorageStatsManager::class.java)
        val total = stats.getTotalBytes(StorageManager.UUID_DEFAULT)
        val free = stats.getFreeBytes(StorageManager.UUID_DEFAULT)
        val usageAccess = UsageAnalyzer.hasAccess(this)
        val mediaAllowed = hasMediaAccess()

        val appsBytes = if (usageAccess) runCatching {
            val s = stats.queryStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
            s.appBytes + s.dataBytes + s.cacheBytes - s.externalCacheBytes
        }.getOrNull() else null

        val images = if (mediaAllowed) mediaSize(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) else 0L
        val videos = if (mediaAllowed) mediaSize(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) else 0L
        val audio = if (mediaAllowed) mediaSize(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) else 0L
        val used = total - free
        val other = (used - (appsBytes ?: 0L) - images - videos - audio).coerceAtLeast(0)

        val categories = listOfNotNull(
            appsBytes?.let { Category("Apps", it, Color.parseColor("#818CF8"), R.drawable.ic_package) },
            Category("Images", images, Color.parseColor("#22C55E"), R.drawable.ic_image).takeIf { mediaAllowed },
            Category("Videos", videos, Color.parseColor("#EF4444"), R.drawable.ic_film).takeIf { mediaAllowed },
            Category("Audio", audio, Color.parseColor("#F59E0B"), R.drawable.ic_music).takeIf { mediaAllowed },
            Category(if (appsBytes == null) "Apps, system & other" else "System & other", other,
                Color.parseColor("#64748B"), R.drawable.ic_cpu),
        )

        val files = if (mediaAllowed) largestFiles() else emptyList()
        val apps = if (usageAccess) largestApps(stats) else null
        return Result(total, free, categories, files, apps, mediaAllowed)
    }

    private fun mediaSize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
            var sum = 0L
            while (c.moveToNext()) sum += c.getLong(0)
            sum
        } ?: 0L
    }.getOrDefault(0L)

    private fun largestFiles(): List<BigFile> {
        val out = mutableListOf<BigFile>()
        listOf(
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Image", R.drawable.ic_image),
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", R.drawable.ic_film),
            Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", R.drawable.ic_music),
        ).forEach { (uri, kind, icon) ->
            runCatching {
                contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                    null, null, "${MediaStore.MediaColumns.SIZE} DESC"
                )?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n < 15) {
                        out += BigFile(ContentUris.withAppendedId(uri, c.getLong(0)), c.getString(1) ?: "Unnamed", c.getLong(2), kind, icon)
                        n++
                    }
                }
            }
        }
        return out.sortedByDescending { it.bytes }.take(15)
    }

    private fun largestApps(stats: StorageStatsManager): List<BigApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
        return pkgs.mapNotNull { pkg ->
            runCatching {
                val s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                val info = packageManager.getApplicationInfo(pkg, 0)
                BigApp(pkg, packageManager.getApplicationLabel(info).toString(), packageManager.getApplicationIcon(info),
                    s.appBytes + s.dataBytes + s.cacheBytes)
            }.getOrNull()
        }.sortedByDescending { it.bytes }.take(15)
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render(r: Result) {
        list.removeAllViews()
        val used = r.total - r.free

        // Overview: used / total, stacked bar and legend
        list.addView(card().apply {
            addView(sectionLabel("INTERNAL STORAGE"))
            addView(TextView(context).apply {
                text = "${formatBytes(used)} used"
                setTextColor(Color.WHITE)
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                text = "${formatBytes(r.free)} free of ${formatBytes(r.total)} · ${used * 100 / r.total.coerceAtLeast(1)}% full"
                textSize = 12f
                setTextColor(color(R.color.slate_400))
            })
            addView(stackedBar(r), LinearLayout.LayoutParams(MATCH, dp(16)).apply { topMargin = dp(14) })
            (r.categories + Category("Free", r.free, Color.parseColor("#1E293B"), R.drawable.ic_storage)).forEach { cat ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                    addView(View(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cat.color) }
                    }, LinearLayout.LayoutParams(dp(10), dp(10)))
                    addView(ImageView(context).apply {
                        setImageResource(cat.icon)
                        setColorFilter(color(R.color.slate_400))
                    }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(10) })
                    addView(TextView(context).apply {
                        text = cat.name
                        setTextColor(color(R.color.slate_300))
                        textSize = 14f
                    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
                    addView(TextView(context).apply {
                        text = formatBytes(cat.bytes)
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                })
            }
        })

        // Missing permissions: explain what extra detail they unlock
        if (!r.mediaAllowed) {
            list.addView(actionCard("See photos, videos and music sizes", "Allow access to media files") {
                permissionLauncher.launch(mediaPermissions)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        if (r.apps == null) {
            list.addView(actionCard("See how much space each app uses", "Allow usage access") {
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }

        // Largest files
        if (r.files.isNotEmpty()) {
            list.addView(titledCard("LARGEST FILES · TAP TO OPEN, LONG-PRESS TO DELETE", R.drawable.ic_file, accent).apply {
                r.files.forEach { f ->
                    addView(itemRow(f.name, "${f.kind} · ${formatBytes(f.bytes)}", null, f.icon,
                        onClick = { openFile(f.uri) }, onLongClick = { deleteFile(f) }))
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }

        // Largest apps
        if (!r.apps.isNullOrEmpty()) {
            list.addView(titledCard("LARGEST APPS · TAP TO CLEAR CACHE", R.drawable.ic_package, accent).apply {
                r.apps.forEach { a ->
                    addView(itemRow(a.label, formatBytes(a.bytes), a.icon, R.drawable.ic_package, onClick = {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                        }
                    }))
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
    }

    private fun stackedBar(r: Result): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#1E293B")) }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        r.categories.forEach { cat ->
            val weight = cat.bytes.toFloat() / r.total.coerceAtLeast(1)
            if (weight > 0.002f) addView(View(context).apply { setBackgroundColor(cat.color) }, LinearLayout.LayoutParams(0, MATCH, weight))
        }
        val freeWeight = r.free.toFloat() / r.total.coerceAtLeast(1)
        addView(View(context), LinearLayout.LayoutParams(0, MATCH, freeWeight))
    }

    private fun actionCard(title: String, button: String, onClick: () -> Unit) = card().apply {
        addView(TextView(context).apply {
            text = title
            setTextColor(color(R.color.slate_300))
            textSize = 14f
        })
        addView(TextView(context).apply {
            text = button
            setTextColor(accent)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { haptics.performButtonClickHaptic(); onClick() }
        })
    }

    private fun itemRow(
        title: String, subtitle: String, iconDrawable: Drawable?, iconRes: Int,
        onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        setOnClickListener { onClick() }
        onLongClick?.let { l -> setOnLongClickListener { l(); true } }
        addView(ImageView(context).apply {
            if (iconDrawable != null) setImageDrawable(iconDrawable) else {
                setImageResource(iconRes)
                setColorFilter(color(R.color.slate_400))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#1A2130")) }
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(context, null, 0, R.style.Cockpit_Mono).apply {
                text = subtitle
                textSize = 11f
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
    }

    private fun openFile(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    /** Deletes through Android's own confirmation dialog (Android 11+). */
    private fun deleteFile(f: BigFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(f.uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            Toast.makeText(this, "Open the file in your gallery or files app to delete it", Toast.LENGTH_LONG).show()
        }
    }
}
