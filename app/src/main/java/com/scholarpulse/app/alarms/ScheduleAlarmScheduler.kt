package com.scholarpulse.app.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.scholarpulse.app.db.ScheduleEntity
import java.util.Calendar

/**
 * Manages exact RTC_WAKEUP alarms for class timetable reminders and daily streak preservation.
 * Complies with Android 12+ (API 31) canScheduleExactAlarms requirements with graceful fallback.
 */
object ScheduleAlarmScheduler {

    private const val TAG = "ScheduleAlarmScheduler"

    const val ACTION_CLASS_ALARM = "com.scholarpulse.app.ACTION_CLASS_ALARM"
    const val ACTION_STREAK_ALARM = "com.scholarpulse.app.ACTION_STREAK_ALARM"

    const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    const val EXTRA_SUBJECT_ID = "extra_subject_id"
    const val EXTRA_SUBJECT_NAME = "extra_subject_name"
    const val EXTRA_START_TIME = "extra_start_time"
    const val EXTRA_END_TIME = "extra_end_time"
    const val EXTRA_DAY_OF_WEEK = "extra_day_of_week"

    const val STREAK_ALARM_REQUEST_CODE = 9999

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: true
        } else {
            true
        }
    }

    /**
     * Schedules a heads-up alarm 10 minutes prior to a scheduled class.
     * Repeats weekly by re-arming upon trigger in ScheduleAlarmReceiver.
     */
    fun scheduleClassAlarm(context: Context, schedule: ScheduleEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val triggerAtMillis = calculateClassReminderMillis(schedule.dayOfWeek, schedule.startTime)
        val requestCode = schedule.id.hashCode()

        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_CLASS_ALARM
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(EXTRA_SUBJECT_ID, schedule.subjectId)
            putExtra(EXTRA_SUBJECT_NAME, schedule.description ?: "Class")
            putExtra(EXTRA_START_TIME, schedule.startTime)
            putExtra(EXTRA_END_TIME, schedule.endTime)
            putExtra(EXTRA_DAY_OF_WEEK, schedule.dayOfWeek)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact class alarm for ${schedule.id} at $triggerAtMillis")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.w(TAG, "Exact alarm permission missing. Scheduled inexact alarm for ${schedule.id}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while scheduling exact alarm for ${schedule.id}", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fallback inexact alarm failed", fallbackEx)
            }
        }
    }

    /**
     * Schedules daily 8:00 PM streak preservation reminder.
     */
    fun scheduleStreakAlarm(context: Context, hour: Int = 20, minute: Int = 0) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now) || target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val triggerAtMillis = target.timeInMillis
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_STREAK_ALARM
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            STREAK_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact streak alarm at $triggerAtMillis")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule streak alarm", e)
        }
    }

    /**
     * Cancels an existing class alarm by schedule id.
     */
    fun cancelClassAlarm(context: Context, scheduleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_CLASS_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled class alarm for scheduleId: $scheduleId")
        }
    }

    /**
     * Re-arms all scheduled class reminders and the daily streak preservation alarm.
     */
    fun rearmAll(context: Context, schedules: List<ScheduleEntity>): Int {
        var armedCount = 0
        for (schedule in schedules) {
            try {
                scheduleClassAlarm(context, schedule)
                armedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to arm alarm for schedule ${schedule.id}", e)
            }
        }

        scheduleStreakAlarm(context)
        Log.i(TAG, "Re-armed $armedCount class alarms + daily streak reminder")
        return armedCount
    }

    /**
     * Computes the epoch millisecond trigger time 10 minutes prior to a weekly class.
     * dayOfWeek: 0 = Sunday, 1 = Monday ... 6 = Saturday (matching JavaScript / PostgreSQL convention).
     * startTime: "HH:mm".
     */
    fun calculateClassReminderMillis(dayOfWeek: Int, startTime: String): Long {
        val parts = startTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // In Calendar, SUNDAY = 1, MONDAY = 2 ... SATURDAY = 7
        val targetCalendarDay = (dayOfWeek % 7) + 1

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, targetCalendarDay)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // Subtract 10 minutes for upcoming warning
            add(Calendar.MINUTE, -10)
        }

        // If the 10-minute warning has already passed this week, advance 7 days
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 7)
        }

        return target.timeInMillis
    }
}
