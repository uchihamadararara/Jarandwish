package com.jarvis.assistant.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService

/**
 * Fires when a reminder alarm goes off, and re-arms alarms after reboot.
 * Does not touch WebSocket or audio code. It only (1) posts a backup notification
 * and (2) asks JarvisConversationService to speak the reminder.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ReminderManager.rescheduleAll(context)
            }
            else -> {
                val id = intent.getIntExtra(ReminderManager.EXTRA_REMINDER_ID, -1)
                if (id < 0) return
                val reminder = ReminderManager.onFired(context, id) ?: return

                // 1. Backup notification (so it is never missed, even if voice fails)
                showNotification(context, reminder)

                // 2. Ask the service to speak it with the mic left on
                try {
                    val svc = Intent(context, JarvisConversationService::class.java).apply {
                        action = ACTION_REMIND
                        putExtra(EXTRA_REMINDER_TEXT, reminder.message)
                    }
                    ContextCompat.startForegroundService(context, svc)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start service for reminder: ${e.message}", e)
                }
            }
        }
    }

    private fun showNotification(context: Context, reminder: Reminder) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val tap = PendingIntent.getActivity(
                context, reminder.id, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Reminder")
                .setContentText(reminder.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_BASE_ID + reminder.id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Reminder notification failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        private const val CHANNEL_ID = "jarvis_reminders"
        private const val NOTIF_BASE_ID = 7000

        /** Sent to JarvisConversationService (added to the service in a later step). */
        const val ACTION_REMIND = "com.jarvis.assistant.REMIND"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
    }
}
