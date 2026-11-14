package com.jarvis.assistant.wellbeing

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All phone-wellbeing settings and small counters, kept in one SharedPreferences file. */
object WellbeingPrefs {
    private const val FILE = "jarvis_wellbeing"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // Switches (both OFF until the user turns them on)
    fun masterEnabled(c: Context) = sp(c).getBoolean("master", false)
    fun setMasterEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("master", v).apply()
    fun chatEnabled(c: Context) = sp(c).getBoolean("chat", false)
    fun setChatEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("chat", v).apply()

    // Limits (minutes, 0 = no limit)
    fun dailyLimitMin(c: Context) = sp(c).getInt("daily_limit", 0)
    fun setDailyLimitMin(c: Context, v: Int) = sp(c).edit().putInt("daily_limit", v.coerceIn(0, 1440)).apply()

    fun todayLimitMin(c: Context): Int {
        val p = sp(c)
        return if (p.getString("today_limit_date", "") == today()) p.getInt("today_limit", 0) else 0
    }
    fun setTodayLimitMin(c: Context, v: Int) =
        sp(c).edit().putString("today_limit_date", today()).putInt("today_limit", v.coerceIn(0, 1440)).apply()

    /** "Today only" limit wins over the daily one. */
    fun effectiveLimitMin(c: Context): Int = todayLimitMin(c).let { if (it > 0) it else dailyLimitMin(c) }

    // Bedtime: minute of day (0 = 12:00 AM). The reminder window runs from bedtime until 05:00.
    fun bedtimeEnabled(c: Context) = sp(c).getBoolean("bedtime_on", true)
    fun setBedtimeEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("bedtime_on", v).apply()
    fun bedtimeMinute(c: Context) = sp(c).getInt("bedtime_min", 0)
    fun setBedtimeMinute(c: Context, v: Int) = sp(c).edit().putInt("bedtime_min", ((v % 1440) + 1440) % 1440).apply()

    // Daily counters
    fun locksToday(c: Context): Int {
        val p = sp(c)
        return if (p.getString("locks_date", "") == today()) p.getInt("locks", 0) else 0
    }
    fun incLocksToday(c: Context) =
        sp(c).edit().putString("locks_date", today()).putInt("locks", locksToday(c) + 1).apply()

    fun chatsToday(c: Context): Int {
        val p = sp(c)
        return if (p.getString("chats_date", "") == today()) p.getInt("chats", 0) else 0
    }
    fun incChatsToday(c: Context) =
        sp(c).edit().putString("chats_date", today()).putInt("chats", chatsToday(c) + 1).apply()

    // Timestamps (epoch ms)
    fun cooldownUntil(c: Context) = sp(c).getLong("cooldown_until", 0L)
    fun setCooldownUntil(c: Context, v: Long) = sp(c).edit().putLong("cooldown_until", v).apply()
    fun noLockUntil(c: Context) = sp(c).getLong("no_lock_until", 0L)
    fun setNoLockUntil(c: Context, v: Long) = sp(c).edit().putLong("no_lock_until", v).apply()
    fun nextChatAt(c: Context) = sp(c).getLong("next_chat_at", 0L)
    fun setNextChatAt(c: Context, v: Long) = sp(c).edit().putLong("next_chat_at", v).apply()
}
