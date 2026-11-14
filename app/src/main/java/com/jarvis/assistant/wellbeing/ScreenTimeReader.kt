package com.jarvis.assistant.wellbeing

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.util.Calendar

/** Reads today's real screen-on time from the system (needs the one-time "Usage access" permission). */
object ScreenTimeReader {

    // UsageEvents.Event constants (API 28+); numeric so older compile stubs cannot break the build.
    private const val SCREEN_INTERACTIVE = 15
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val USER_INTERACTION = 7

    fun hasPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Milliseconds the screen has been on today, or 0 if it cannot be read. */
    fun usedTodayMillis(context: Context): Long {
        if (!hasPermission(context)) return 0L
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = startOfToday()
            val end = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) screenOnFromEvents(context, usm, start, end)
            else foregroundFallback(usm, start, end)
        } catch (_: Exception) {
            0L
        }
    }

    private fun screenOnFromEvents(context: Context, usm: UsageStatsManager, start: Long, end: Long): Long {
        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        var onSince = -1L
        var total = 0L
        var sawScreenEvent = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                SCREEN_INTERACTIVE -> {
                    sawScreenEvent = true
                    if (onSince < 0) onSince = e.timeStamp
                }
                SCREEN_NON_INTERACTIVE -> {
                    if (onSince >= 0) {
                        total += e.timeStamp - onSince
                    } else if (!sawScreenEvent) {
                        total += e.timeStamp - start // screen was already on at midnight
                    }
                    sawScreenEvent = true
                    onSince = -1L
                }
            }
        }
        if (onSince >= 0) total += end - onSince
        if (!sawScreenEvent) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) total = end - start
        }
        return total.coerceAtLeast(0L)
    }

    private fun foregroundFallback(usm: UsageStatsManager, start: Long, end: Long): Long {
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: return 0L
        return stats.sumOf { it.totalTimeInForeground }
    }

    /** True if the system saw a user interaction within the last [windowMs]. */
    fun recentUserInteraction(context: Context, windowMs: Long): Boolean {
        if (!hasPermission(context)) return false
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - windowMs, end)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == USER_INTERACTION) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
