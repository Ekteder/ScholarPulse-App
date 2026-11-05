package com.scholarpulse.app.alarms

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scholarpulse.app.MainActivity
import com.scholarpulse.app.R
import com.scholarpulse.app.ScholarPulseApp
import com.scholarpulse.app.db.ScheduleEntity

/**
 * BroadcastReceiver triggered by AlarmManager exact RTC_WAKEUP alarms.
 * Displays high-priority heads-up notifications for upcoming classes and daily streak alerts.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
        private const val STREAK_NOTIFICATION_ID = 9001
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        Log.d(TAG, "onReceive triggered with action: $action")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "Notification permission is not granted. Suppressing alert.")
            return
        }

        when (action) {
            ScheduleAlarmScheduler.ACTION_CLASS_ALARM -> {
                val scheduleId = intent.getStringExtra(ScheduleAlarmScheduler.EXTRA_SCHEDULE_ID) ?: "class_reminder"
                val subjectId = intent.getStringExtra(ScheduleAlarmScheduler.EXTRA_SUBJECT_ID) ?: ""
                val subjectName = intent.getStringExtra(ScheduleAlarmScheduler.EXTRA_SUBJECT_NAME) ?: "Academic Class"
                val startTime = intent.getStringExtra(ScheduleAlarmScheduler.EXTRA_START_TIME) ?: "upcoming"
                val endTime = intent.getStringExtra(ScheduleAlarmScheduler.EXTRA_END_TIME) ?: ""
                val dayOfWeek = intent.getIntExtra(ScheduleAlarmScheduler.EXTRA_DAY_OF_WEEK, 1)

                val openScheduleIntent = Intent(context, MainActivity::class.java).apply {
                    this.action = Intent.ACTION_VIEW
                    data = Uri.parse("https://scholarpulsev2.vercel.app?tab=schedule")
                    putExtra("tab", "schedule")
                    putExtra("targetId", scheduleId)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                val contentPendingIntent = PendingIntent.getActivity(
                    context,
                    scheduleId.hashCode(),
                    openScheduleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, ScholarPulseApp.CHANNEL_ID_ALARMS)
                    .setSmallIcon(R.drawable.ic_splash_logo)
                    .setContentTitle("⏰ Upcoming Class in 10 minutes: $subjectName")
                    .setContentText("Class begins at $startTime. Get your notes and focus ready!")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(contentPendingIntent)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build()

                notificationManager.notify(scheduleId.hashCode(), notification)
                Log.i(TAG, "Dispatched class alert notification for schedule: $scheduleId ($subjectName)")

                // Automatically re-arm for next week's occurrence
                val nextSchedule = ScheduleEntity(
                    id = scheduleId,
                    userId = "",
                    subjectId = subjectId,
                    dayOfWeek = dayOfWeek,
                    startTime = startTime,
                    endTime = endTime,
                    description = subjectName
                )
                ScheduleAlarmScheduler.scheduleClassAlarm(context, nextSchedule)
            }

            ScheduleAlarmScheduler.ACTION_STREAK_ALARM -> {
                val openTimerIntent = Intent(context, MainActivity::class.java).apply {
                    this.action = Intent.ACTION_VIEW
                    data = Uri.parse("https://scholarpulsev2.vercel.app?tab=timer")
                    putExtra("tab", "timer")
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                val contentPendingIntent = PendingIntent.getActivity(
                    context,
                    STREAK_NOTIFICATION_ID,
                    openTimerIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, ScholarPulseApp.CHANNEL_ID_STREAKS)
                    .setSmallIcon(R.drawable.ic_splash_logo)
                    .setContentTitle("🔥 Protect Your Daily Study Streak!")
                    .setContentText("Log your study session before midnight to keep your streak alive.")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(contentPendingIntent)
                    .build()

                notificationManager.notify(STREAK_NOTIFICATION_ID, notification)
                Log.i(TAG, "Dispatched daily streak preservation notification")

                // Re-arm for tomorrow at 20:00
                ScheduleAlarmScheduler.scheduleStreakAlarm(context)
            }
        }
    }
}
