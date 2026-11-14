package com.jarvis.assistant.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Reminder(
    val id: Int,
    val message: String,
    val triggerAt: Long,
    val repeat: String, // "once" or "daily"
    val enabled: Boolean = true
)

/**
 * Stores reminders and schedules them with AlarmManager.
 * Fully self-contained: does not touch WebSocket or audio code.
 */
object ReminderManager {

    private const val PREFS = "jarvis_reminders"
    private const val KEY_LIST = "list"
    const val EXTRA_REMINDER_ID = "reminder_id"

    /** Accepts "yyyy-MM-dd HH:mm" or "yyyy-MM-dd HH:mm:ss" in device local time. */
    fun parseLocalTime(text: String): Long? {
        val t = text.trim().replace('T', ' ')
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US)
                f.isLenient = false
                return f.parse(t)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Returns the saved reminder, or null if a one-time reminder is already in the past. */
    @Synchronized
    fun add(context: Context, message: String, triggerAt: Long, repeat: String): Reminder? {
        val isDaily = repeat.equals("daily", ignoreCase = true)
        var at = triggerAt
        val now = System.currentTimeMillis()
        if (at <= now) {
            if (!isDaily) return null
            at = rollForwardDaily(at, now)
        }
        val list = load(context).toMutableList()
        val id = ((list.maxOfOrNull { it.id } ?: 0) + 1)
        val r = Reminder(id, message.trim(), at, if (isDaily) "daily" else "once")
        list.add(r)
        save(context, list)
        schedule(context, r)
        return r
    }

    @Synchronized
    fun list(context: Context): List<Reminder> = load(context)

    @Synchronized
    fun remove(context: Context, id: Int) {
        cancelAlarm(context, id)
        save(context, load(context).filter { it.id != id })
    }

    /** Called when an alarm fires. Returns the reminder to speak; reschedules it if daily, drops it if once. */
    @Synchronized
    fun onFired(context: Context, id: Int): Reminder? {
        val list = load(context).toMutableList()
        val r = list.firstOrNull { it.id == id } ?: return null
        if (!r.enabled) return null
        if (r.repeat == "daily") {
            val next = r.copy(triggerAt = rollForwardDaily(r.triggerAt, System.currentTimeMillis()))
            list[list.indexOf(r)] = next
            save(context, list)
            schedule(context, next)
        } else {
            list.remove(r)
            save(context, list)
        }
        return r
    }

    /** Re-arms every alarm (after phone reboot). Missed one-time reminders are dropped. */
    @Synchronized
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val kept = mutableListOf<Reminder>()
        for (r in load(context)) {
            if (!r.enabled) {
                kept.add(r) // paused: keep it, but never schedule it
            } else if (r.repeat == "daily") {
                kept.add(r.copy(triggerAt = if (r.triggerAt <= now) rollForwardDaily(r.triggerAt, now) else r.triggerAt))
            } else if (r.triggerAt > now) {
                kept.add(r)
            }
        }
        save(context, kept)
        kept.filter { it.enabled }.forEach { schedule(context, it) }
    }

    /** Pause (false) or resume (true) a reminder. Returns false if it cannot be resumed (one-time reminder already in the past). */
    @Synchronized
    fun setEnabled(context: Context, id: Int, enabled: Boolean): Boolean {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val r = list[idx]
        if (!enabled) {
            cancelAlarm(context, id)
            list[idx] = r.copy(enabled = false)
            save(context, list)
            return true
        }
        val now = System.currentTimeMillis()
        val at = if (r.triggerAt <= now) {
            if (r.repeat == "daily") rollForwardDaily(r.triggerAt, now) else return false
        } else {
            r.triggerAt
        }
        val next = r.copy(triggerAt = at, enabled = true)
        list[idx] = next
        save(context, list)
        schedule(context, next)
        return true
    }

    /** Reminders whose text matches the query (exact match wins, otherwise "contains" either way). */
    @Synchronized
    fun findMatches(context: Context, query: String): List<Reminder> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return emptyList()
        val all = load(context)
        val exact = all.filter { it.message.trim().lowercase(Locale.ROOT) == q }
        if (exact.isNotEmpty()) return exact
        return all.filter {
            val m = it.message.lowercase(Locale.ROOT)
            m.contains(q) || q.contains(m)
        }
    }

    @Synchronized
    fun clearAll(context: Context) {
        load(context).forEach { cancelAlarm(context, it.id) }
        save(context, emptyList())
    }

    /** Text block appended to Gemini's system prompt so it can read the list out loud. */
    @Synchronized
    fun promptSummary(context: Context): String {
        val items = load(context).sortedBy { it.triggerAt }
        val fmt = SimpleDateFormat("EEE, d MMM yyyy, h:mm a", Locale.ENGLISH)
        val body = if (items.isEmpty()) {
            "(no reminders saved)"
        } else {
            items.joinToString("\n") {
                "- " + it.message + " | " + fmt.format(java.util.Date(it.triggerAt)) + " | " + it.repeat +
                    " | " + (if (it.enabled) "active" else "paused")
            }
        }
        return "\n\n[YOUR CURRENT REMINDERS]\nSnapshot from when this session started (reminders you set or changed since then are not listed; remember those yourself).\n" + body + "\n"
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    // ------------------------------------------------------------------

    private fun rollForwardDaily(from: Long, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = from }
        while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, r.id)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        } else {
            // Exact-alarm permission not granted: still fire, just not to-the-second.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        }
    }

    private fun cancelAlarm(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, id))
    }

    private fun load(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Reminder(o.getInt("id"), o.getString("message"), o.getLong("at"), o.getString("repeat"), o.optBoolean("enabled", true))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("message", it.message).put("at", it.triggerAt).put("repeat", it.repeat).put("enabled", it.enabled))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
