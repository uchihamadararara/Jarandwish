package com.jarvis.assistant.wellbeing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import java.util.Calendar
import kotlin.random.Random

/**
 * Phone wellbeing brain. Lives inside JarvisConversationService's process and is fully event-driven:
 * nothing is scheduled while the screen is off, and there is no polling.
 *
 * One chain at a time:  IDLE -> PROMPTED -> GRACE -> (WARNED) -> IDLE  (+ cooldown)
 * Safety: never locks during a call / camera use / navigation, at most MAX_LOCKS_PER_DAY locks,
 * cooldown after each chain, and no re-lock for 10 minutes after the user unlocks again.
 * Any failure ends the chain without locking.
 */
object WellbeingController {

    const val ACTION_WELLBEING = "com.jarvis.assistant.WELLBEING"
    const val EXTRA_TEXT = "wellbeing_text"
    const val EXTRA_KIND = "wellbeing_kind"
    const val KIND_LIMIT = "limit"
    const val KIND_BEDTIME = "bedtime"
    const val KIND_CHAT = "chat"
    const val KIND_SECOND = "second"

    private const val TAG = "Wellbeing"

    private const val GRACE_MS = 5 * 60_000L
    private const val COOLDOWN_MS = 30 * 60_000L
    private const val NO_LOCK_AFTER_UNLOCK_MS = 10 * 60_000L
    private const val MAX_LOCKS_PER_DAY = 3
    private const val MIN_DELAY_MS = 15_000L
    private const val BEDTIME_SETTLE_MS = 20_000L
    private const val BUSY_RETRY_MS = 10 * 60_000L
    private const val SESSION_RETRY_MS = 5 * 60_000L
    private const val PROMPT_TIMEOUT_MS = 90_000L
    private const val SECOND_LOCK_DELAY_MS = 15_000L
    private const val CHAT_GAP_MIN_MS = 90 * 60_000L
    private const val CHAT_GAP_MAX_MS = 150 * 60_000L
    private const val CHAT_AFTER_UNLOCK_MS = 3 * 60_000L
    private const val MAX_CHATS_PER_DAY = 4
    private const val BEDTIME_WINDOW_END_MIN = 5 * 60
    private const val CHAT_QUIET_START_MIN = 23 * 60
    private const val CHAT_QUIET_END_MIN = 8 * 60

    private enum class Stage { IDLE, PROMPTED, GRACE, WARNED }

    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var started = false
    private var stage = Stage.IDLE
    private var gracePostpones = 0
    private var lockedByUs = false

    private val limitRunnable = Runnable { trigger(KIND_LIMIT) }
    private val bedtimeRunnable = Runnable { trigger(KIND_BEDTIME) }
    private val chatRunnable = Runnable { trigger(KIND_CHAT) }
    private val graceRunnable = Runnable { recheck() }
    private val lockRunnable = Runnable { finishSecondWarning() }
    private val promptTimeoutRunnable = Runnable { onPromptTimeout() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> cancelTimers()
                Intent.ACTION_SCREEN_ON -> evaluate()
                Intent.ACTION_USER_PRESENT -> {
                    if (lockedByUs) {
                        lockedByUs = false
                        WellbeingPrefs.setNoLockUntil(context, System.currentTimeMillis() + NO_LOCK_AFTER_UNLOCK_MS)
                    }
                    evaluate()
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun start(context: Context) {
        handler.post {
            if (started) return@post
            val ctx = context.applicationContext
            appCtx = ctx
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ctx.registerReceiver(screenReceiver, filter)
                }
                PhoneActivity.registerCamera(ctx)
                started = true
                evaluate()
            } catch (e: Exception) {
                Log.e(TAG, "start failed: ${e.message}", e)
            }
        }
    }

    fun stop() {
        handler.post {
            val ctx = appCtx ?: return@post
            cancelTimers()
            handler.removeCallbacks(graceRunnable)
            handler.removeCallbacks(lockRunnable)
            handler.removeCallbacks(promptTimeoutRunnable)
            stage = Stage.IDLE
            if (started) {
                try {
                    ctx.unregisterReceiver(screenReceiver)
                } catch (_: Exception) {
                }
                PhoneActivity.unregisterCamera(ctx)
            }
            started = false
        }
    }

    /** Call after a setting changed (settings screen / voice) so timers are recomputed. */
    fun refresh() {
        handler.post { if (started) evaluate() }
    }

    // ---------------------------------------------------------------- scheduling

    private fun cancelTimers() {
        handler.removeCallbacks(limitRunnable)
        handler.removeCallbacks(bedtimeRunnable)
        handler.removeCallbacks(chatRunnable)
    }

    private fun reschedule(runnable: Runnable, delayMs: Long) {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, maxOf(delayMs, MIN_DELAY_MS))
    }

    private fun evaluate() {
        val ctx = appCtx ?: return
        cancelTimers()
        if (stage != Stage.IDLE) return
        if (!PhoneActivity.isScreenOnUnlocked(ctx)) return
        val now = System.currentTimeMillis()

        if (WellbeingPrefs.masterEnabled(ctx)) {
            val limitMin = WellbeingPrefs.effectiveLimitMin(ctx)
            if (limitMin > 0 && ScreenTimeReader.hasPermission(ctx)) {
                val remaining = limitMin * 60_000L - ScreenTimeReader.usedTodayMillis(ctx)
                reschedule(limitRunnable, remaining)
            }
            if (WellbeingPrefs.bedtimeEnabled(ctx)) {
                val delay = if (inBedtimeWindow(ctx, now)) BEDTIME_SETTLE_MS else msUntilBedtime(ctx, now)
                reschedule(bedtimeRunnable, delay)
            }
        }
        if (WellbeingPrefs.chatEnabled(ctx)) {
            var next = WellbeingPrefs.nextChatAt(ctx)
            if (next <= 0L) {
                next = now + randomChatGap()
                WellbeingPrefs.setNextChatAt(ctx, next)
            }
            reschedule(chatRunnable, if (next <= now) CHAT_AFTER_UNLOCK_MS else next - now)
        }
    }

    private fun minuteOfDay(now: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun inBedtimeWindow(ctx: Context, now: Long): Boolean {
        val start = WellbeingPrefs.bedtimeMinute(ctx)
        val m = minuteOfDay(now)
        return if (start < BEDTIME_WINDOW_END_MIN) m in start until BEDTIME_WINDOW_END_MIN
        else m >= start || m < BEDTIME_WINDOW_END_MIN
    }

    private fun msUntilBedtime(ctx: Context, now: Long): Long {
        val start = WellbeingPrefs.bedtimeMinute(ctx)
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, start / 60)
            set(Calendar.MINUTE, start % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis - now
    }

    private fun randomChatGap(): Long = Random.nextLong(CHAT_GAP_MIN_MS, CHAT_GAP_MAX_MS)

    private fun inChatQuietHours(now: Long): Boolean {
        val m = minuteOfDay(now)
        return m >= CHAT_QUIET_START_MIN || m < CHAT_QUIET_END_MIN
    }

    // ---------------------------------------------------------------- triggers

    private fun trigger(kind: String) {
        val ctx = appCtx ?: return
        try {
            if (stage != Stage.IDLE) return
            val chat = kind == KIND_CHAT
            if (chat) {
                if (!WellbeingPrefs.chatEnabled(ctx)) return
            } else if (!WellbeingPrefs.masterEnabled(ctx)) return
            if (!PhoneActivity.isScreenOnUnlocked(ctx)) return
            val now = System.currentTimeMillis()
            val runnable = when (kind) {
                KIND_LIMIT -> limitRunnable
                KIND_BEDTIME -> bedtimeRunnable
                else -> chatRunnable
            }

            if (chat) {
                if (WellbeingPrefs.chatsToday(ctx) >= MAX_CHATS_PER_DAY || inChatQuietHours(now)) {
                    WellbeingPrefs.setNextChatAt(ctx, now + randomChatGap())
                    evaluate()
                    return
                }
            } else {
                val cooldown = WellbeingPrefs.cooldownUntil(ctx)
                if (now < cooldown) {
                    reschedule(runnable, cooldown - now)
                    return
                }
                // make sure the reason still holds
                if (kind == KIND_LIMIT) {
                    val lim = WellbeingPrefs.effectiveLimitMin(ctx)
                    if (lim <= 0 || ScreenTimeReader.usedTodayMillis(ctx) < lim * 60_000L) {
                        evaluate()
                        return
                    }
                }
                if (kind == KIND_BEDTIME && !inBedtimeWindow(ctx, now)) {
                    evaluate()
                    return
                }
            }

            if (PhoneActivity.isCallOrBusy(ctx, false)) {
                reschedule(runnable, BUSY_RETRY_MS)
                return
            }
            if (JarvisConversationService.isSessionOn.value) {
                reschedule(runnable, SESSION_RETRY_MS)
                return
            }

            if (chat) {
                WellbeingPrefs.incChatsToday(ctx)
                WellbeingPrefs.setNextChatAt(ctx, now + randomChatGap())
                sendToService(ctx, kind, buildPrompt(ctx, kind))
                return
            }
            stage = Stage.PROMPTED
            handler.removeCallbacks(promptTimeoutRunnable)
            handler.postDelayed(promptTimeoutRunnable, PROMPT_TIMEOUT_MS)
            sendToService(ctx, kind, buildPrompt(ctx, kind))
        } catch (e: Exception) {
            Log.e(TAG, "trigger($kind) failed: ${e.message}", e)
            stage = Stage.IDLE
        }
    }

    private fun onPromptTimeout() {
        // Jarvis never got to talk (connection failed etc.): end quietly, never lock.
        val ctx = appCtx ?: return
        if (stage == Stage.PROMPTED) endChain(ctx)
    }

    // ---------------------------------------------------------------- results from the conversation

    /** Boss agreed (or did not answer): give him 5 minutes, then check for real. */
    fun onAgreed() {
        handler.post {
            if (stage != Stage.PROMPTED) return@post
            handler.removeCallbacks(promptTimeoutRunnable)
            stage = Stage.GRACE
            gracePostpones = 0
            handler.removeCallbacks(graceRunnable)
            handler.postDelayed(graceRunnable, GRACE_MS)
        }
    }

    fun onNoResponse() = onAgreed()

    /** Boss refused: Jarvis already warned him, lock the screen now (if it is safe). */
    fun onRefused() {
        handler.post {
            val ctx = appCtx ?: return@post
            if (stage != Stage.PROMPTED) return@post
            handler.removeCallbacks(promptTimeoutRunnable)
            tryLock(ctx)
            endChain(ctx)
            endSession(ctx)
        }
    }

    private fun recheck() {
        val ctx = appCtx
        if (ctx == null || stage != Stage.GRACE) {
            stage = Stage.IDLE
            return
        }
        try {
            if (!WellbeingPrefs.masterEnabled(ctx) || !PhoneActivity.isScreenOnUnlocked(ctx)) {
                endChain(ctx)
                return
            }
            if (PhoneActivity.isCallOrBusy(ctx, false) || JarvisConversationService.isSessionOn.value) {
                if (gracePostpones < 2) {
                    gracePostpones++
                    handler.postDelayed(graceRunnable, BUSY_RETRY_MS)
                } else {
                    endChain(ctx)
                }
                return
            }
            if (!PhoneActivity.isUsingPhone(ctx)) {
                endChain(ctx) // he really put the phone down
                return
            }
            // Still using it after agreeing: warn once more, then lock.
            stage = Stage.WARNED
            sendToService(ctx, KIND_SECOND, buildPrompt(ctx, KIND_SECOND))
            handler.removeCallbacks(lockRunnable)
            handler.postDelayed(lockRunnable, SECOND_LOCK_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "recheck failed: ${e.message}", e)
            endChain(ctx)
        }
    }

    private fun finishSecondWarning() {
        val ctx = appCtx ?: run {
            stage = Stage.IDLE
            return
        }
        tryLock(ctx)
        endChain(ctx)
        endSession(ctx)
    }

    private fun endChain(ctx: Context) {
        stage = Stage.IDLE
        handler.removeCallbacks(promptTimeoutRunnable)
        handler.removeCallbacks(graceRunnable)
        handler.removeCallbacks(lockRunnable)
        val until = System.currentTimeMillis() + COOLDOWN_MS
        if (until > WellbeingPrefs.cooldownUntil(ctx)) WellbeingPrefs.setCooldownUntil(ctx, until)
        evaluate()
    }

    // ---------------------------------------------------------------- locking

    fun canLockNow(ctx: Context): Boolean {
        if (!WellbeingPrefs.masterEnabled(ctx)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (JarvisAccessibilityService.instance == null) return false
        if (System.currentTimeMillis() < WellbeingPrefs.noLockUntil(ctx)) return false
        if (WellbeingPrefs.locksToday(ctx) >= MAX_LOCKS_PER_DAY) return false
        return true
    }

    /** Locks the screen (lock only, never power-off). Returns true only if it really locked. */
    private fun tryLock(ctx: Context): Boolean {
        try {
            if (!canLockNow(ctx)) return false
            if (!PhoneActivity.isScreenOnUnlocked(ctx)) return false
            // A call that started meanwhile always wins. (Own voice session may set "communication" mode.)
            if (PhoneActivity.isCallOrBusy(ctx, JarvisConversationService.isSessionOn.value)) {
                Log.i(TAG, "Lock skipped: call/camera/navigation active")
                return false
            }
            val svc = JarvisAccessibilityService.instance ?: return false
            @Suppress("NewApi")
            val ok = svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            if (ok) {
                lockedByUs = true
                WellbeingPrefs.incLocksToday(ctx)
                Log.i(TAG, "Screen locked (locks today: ${WellbeingPrefs.locksToday(ctx)})")
            }
            return ok
        } catch (e: Exception) {
            Log.e(TAG, "tryLock failed: ${e.message}", e)
            return false
        }
    }

    // ---------------------------------------------------------------- talking

    private fun endSession(ctx: Context) {
        try {
            val i = Intent(ctx, JarvisConversationService::class.java).apply {
                action = JarvisConversationService.ACTION_ENTER_STANDBY
            }
            ContextCompat.startForegroundService(ctx, i)
        } catch (e: Exception) {
            Log.w(TAG, "endSession failed: ${e.message}")
        }
    }

    private fun sendToService(ctx: Context, kind: String, text: String) {
        try {
            val i = Intent(ctx, JarvisConversationService::class.java).apply {
                action = ACTION_WELLBEING
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_KIND, kind)
            }
            ContextCompat.startForegroundService(ctx, i)
        } catch (e: Exception) {
            Log.e(TAG, "sendToService failed: ${e.message}", e)
            if (kind != KIND_CHAT) endChain(ctx)
        }
    }

    private fun buildPrompt(ctx: Context, kind: String): String {
        val canLock = canLockNow(ctx)
        val decision = if (canLock) {
            "When Boss replies: if he agrees (ok, theek hai, haan, thik hai), say one short warm line and then say exactly: Wellbeing agreed. " +
                "If he refuses or says no, say in a slightly angry tone: Aap nahi karoge to main kar dungi. and then say exactly: Wellbeing refused."
        } else {
            "When Boss replies: if he agrees (ok, theek hai, haan, thik hai), say one short warm line and then say exactly: Wellbeing agreed. " +
                "If he refuses, gently ask once more to take a break, and do not say Wellbeing agreed or Wellbeing refused."
        }
        val never = " Never say the words Wellbeing agreed or Wellbeing refused at any other time."
        return when (kind) {
            KIND_LIMIT -> {
                val usedMin = (ScreenTimeReader.usedTodayMillis(ctx) / 60_000L).toInt()
                val limit = WellbeingPrefs.effectiveLimitMin(ctx)
                val line = if (WellbeingPrefs.todayLimitMin(ctx) > 0) {
                    "Boss, phone off kar de thodi der ke liye."
                } else {
                    "Boss, aapne aaj phone ka kuch jyada hi istemal kar liya hai, thoda rest kar lijiye."
                }
                "[PHONE WELLBEING - SYSTEM MESSAGE, not spoken by Boss] Boss has used his phone for $usedMin minutes today and reached his limit of $limit minutes. " +
                    "Start speaking now. In Hinglish, in a caring tone, say in one or two short sentences: \"$line\" Then stop and listen for his reply. $decision$never"
            }
            KIND_BEDTIME ->
                "[PHONE WELLBEING - SYSTEM MESSAGE, not spoken by Boss] It is very late at night and Boss is still using his phone. " +
                    "Start speaking now. In Hinglish, in a caring tone, say in one or two short sentences: \"Boss, bahut raat ho gayi hai, ab aapko so jaana chahiye.\" Then stop and listen for his reply. $decision$never"
            KIND_SECOND ->
                "[PHONE WELLBEING - SYSTEM MESSAGE, not spoken by Boss] Boss said he would rest but is still using his phone after 5 minutes. " +
                    "Start speaking now. In Hinglish, in one or two short sentences, tell him you already warned him and " +
                    (if (canLock) "you are locking the screen now." else "he really should rest now.") +
                    " Do not ask a question. Do not say Wellbeing agreed or Wellbeing refused."
            else ->
                "[CASUAL CHECK-IN - SYSTEM MESSAGE, not spoken by Boss] Start a short, friendly Hinglish chat with Boss now: one or two sentences, " +
                    "for example ask how he is doing or share one tiny interesting thought. Keep it natural and listen for his reply."
        }
    }

    // ---------------------------------------------------------------- status for the system prompt

    fun promptSummary(ctx: Context): String {
        return try {
            val perm = ScreenTimeReader.hasPermission(ctx)
            val used = if (perm) (ScreenTimeReader.usedTodayMillis(ctx) / 60_000L).toString() + " minutes" else "unknown (usage access not granted)"
            val bed = WellbeingPrefs.bedtimeMinute(ctx)
            val h24 = bed / 60
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            val bedText = "%d:%02d %s".format(h12, bed % 60, if (h24 < 12) "AM" else "PM")
            "\n\n[PHONE WELLBEING STATUS]\n" +
                "Wellbeing: " + (if (WellbeingPrefs.masterEnabled(ctx)) "on" else "off") +
                " | Random chats: " + (if (WellbeingPrefs.chatEnabled(ctx)) "on" else "off") + "\n" +
                "Screen time today: " + used + " (at session start)\n" +
                "Daily limit: " + (WellbeingPrefs.dailyLimitMin(ctx).let { if (it > 0) "$it minutes" else "none" }) +
                " | Today's limit: " + (WellbeingPrefs.todayLimitMin(ctx).let { if (it > 0) "$it minutes" else "none" }) + "\n" +
                "Bedtime reminder: " + (if (WellbeingPrefs.bedtimeEnabled(ctx)) bedText else "off") + "\n"
        } catch (_: Exception) {
            ""
        }
    }
}
