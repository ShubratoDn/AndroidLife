package com.truckcontroller.pro.screentime

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

data class AppUsage(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val timeMs: Long,
    val opens: Int,
)

data class UsageSummary(
    val start: Long,
    val end: Long,
    val screenTimeMs: Long,
    val unlocks: Int,
    val firstUnlock: Long?,
    val longestSessionMs: Long,
    /** Screen time per bucket: 24 hours for a day, 7 days for a week. */
    val buckets: LongArray,
    val apps: List<AppUsage>,
)

/**
 * Computes screen time from Android's own usage event log (no background tracking needed).
 * Works from raw events so sessions crossing midnight, screen-off and apps left open are handled.
 */
class UsageAnalyzer(private val context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val pm = context.packageManager
    private val labelCache = HashMap<String, Pair<String, Drawable?>>()

    /** Home screens and system UI count as screen time but are not listed as apps. */
    private val hiddenPackages: Set<String> by lazy {
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        (homes + listOf("com.android.systemui", "android")).toSet()
    }

    companion object {
        /** Android keeps detailed events for about a week. */
        const val MAX_DAYS_BACK = 6
        private const val LOOKBACK_MS = 3 * 60 * 60 * 1000L // to know what was on at the window start

        fun hasAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun startOfDay(daysBack: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysBack)
        }.timeInMillis

        fun endOfDay(daysBack: Int): Long = startOfDay(daysBack - 1)
    }

    /** One day, [daysBack] 0 = today, with hourly buckets. */
    fun day(daysBack: Int): UsageSummary {
        val start = startOfDay(daysBack)
        return analyze(start, endOfDay(daysBack), LongArray(24)) { t -> ((t - start) / 3_600_000L).toInt() }
    }

    /** The 7 days ending with today, with one bucket per day. */
    fun week(): UsageSummary {
        val dayStarts = (MAX_DAYS_BACK downTo 0).map { startOfDay(it) }
        return analyze(dayStarts.first(), endOfDay(0), LongArray(7)) { t ->
            dayStarts.indexOfLast { t >= it }.coerceAtLeast(0)
        }
    }

    private fun analyze(start: Long, end: Long, buckets: LongArray, bucketOf: (Long) -> Int): UsageSummary {
        val now = System.currentTimeMillis()
        val windowEnd = min(end, now)
        val events = usm.queryEvents(start - LOOKBACK_MS, windowEnd)
        val event = UsageEvents.Event()

        var screenOnSince: Long? = null
        var sawScreenEvents = false
        var screenTime = 0L
        var longest = 0L
        var unlocks = 0
        var screenOns = 0
        var firstUnlock: Long? = null
        var currentApp: String? = null
        var appSince = 0L
        var lastOpened: String? = null
        val appTime = HashMap<String, Long>()
        val appOpens = HashMap<String, Int>()

        /** Adds [from, to] clipped to the window; returns the clipped length. */
        fun clip(from: Long, to: Long): Long = max(0L, min(to, windowEnd) - max(from, start))

        fun addScreen(from: Long, to: Long) {
            val len = clip(from, to)
            if (len <= 0) return
            screenTime += len
            longest = max(longest, len)
            // Spread across buckets (an evening session can cross an hour or midnight)
            var t = max(from, start)
            val stop = min(to, windowEnd)
            while (t < stop) {
                val b = bucketOf(t).coerceIn(0, buckets.lastIndex)
                val next = if (buckets.size == 24) start + (b + 1) * 3_600_000L else nextDayStart(t)
                val segEnd = min(stop, next)
                buckets[b] += segEnd - t
                t = segEnd
            }
        }

        fun closeApp(at: Long) {
            val app = currentApp ?: return
            val len = clip(appSince, at)
            if (len > 0) appTime[app] = (appTime[app] ?: 0L) + len
            currentApp = null
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val t = event.timeStamp
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    sawScreenEvents = true
                    if (screenOnSince == null) screenOnSince = t
                    if (t in start until windowEnd) screenOns++
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    sawScreenEvents = true
                    screenOnSince?.let { addScreen(it, t) }
                    screenOnSince = null
                    closeApp(t)
                    lastOpened = null
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    if (t in start until windowEnd) {
                        unlocks++
                        if (firstUnlock == null) firstUnlock = t
                    }
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val pkg = event.packageName
                    if (pkg != currentApp) {
                        closeApp(t)
                        currentApp = pkg
                        appSince = t
                    }
                    // An "open" = coming to this app from another app or from the home screen
                    if (pkg != lastOpened && t in start until windowEnd) appOpens[pkg] = (appOpens[pkg] ?: 0) + 1
                    lastOpened = pkg
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (event.packageName == currentApp) closeApp(t)
                }
            }
        }
        // Still on right now (or at the end of the window)
        screenOnSince?.let { addScreen(it, windowEnd) }
        closeApp(windowEnd)

        // Fallback for devices that do not log screen on/off: total of app time
        if (!sawScreenEvents) {
            screenTime = appTime.values.sum()
        }

        val apps = appTime.filter { (pkg, ms) -> ms >= 1000 && pkg !in hiddenPackages }
            .map { (pkg, ms) ->
                val (label, icon) = labelFor(pkg)
                AppUsage(pkg, label, icon, ms, appOpens[pkg] ?: 0)
            }
            .sortedByDescending { it.timeMs }

        // Phones without a lock screen log no unlocks: count screen-ons (pickups) instead
        return UsageSummary(start, end, screenTime, if (unlocks > 0) unlocks else screenOns, firstUnlock, longest, buckets, apps)
    }

    private fun nextDayStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun labelFor(pkg: String): Pair<String, Drawable?> = labelCache.getOrPut(pkg) {
        try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info)
        } catch (e: PackageManager.NameNotFoundException) {
            // Uninstalled app or not visible: show a readable package name
            pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase) to null
        }
    }
}
