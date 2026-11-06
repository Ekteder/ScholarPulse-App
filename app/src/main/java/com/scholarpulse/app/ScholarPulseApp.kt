package com.scholarpulse.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ScholarPulseApp : Application() {

    companion object {
        const val CHANNEL_ID_TIMER = "scholarpulse_timer_channel"
        const val CHANNEL_ID_TIMER_LEGACY = "study_timer_channel"
        const val CHANNEL_ID_TIMER_ALERTS = "scholarpulse_timer_alerts"
        const val CHANNEL_ID_ALARMS = "schedule_alarms_channel"
        const val CHANNEL_ID_STREAKS = "streak_alerts_channel"
        const val CHANNEL_ID_ANNOUNCEMENTS = "announcements_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        com.scholarpulse.app.workers.OfflineSyncWorker.schedulePeriodicSync(this)
        subscribeToGlobalAnnouncements()
    }

    private fun subscribeToGlobalAnnouncements() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("announcements_global")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        android.util.Log.i("ScholarPulseApp", "Subscribed to announcements_global topic successfully")
                    } else {
                        android.util.Log.w("ScholarPulseApp", "Failed to subscribe to announcements_global", task.exception)
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("ScholarPulseApp", "Error subscribing to FCM topic", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 1. Ongoing Study Timer (Silent ticker to avoid spamming notification sounds)
            val timerChannel = NotificationChannel(
                CHANNEL_ID_TIMER,
                "Study Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for active Pomodoro and stopwatch study sessions"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val timerLegacyChannel = NotificationChannel(
                CHANNEL_ID_TIMER_LEGACY,
                "Study Timer (Legacy)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Legacy notification channel for active study sessions"
                setShowBadge(false)
            }

            // 2. Timer Completion Alerts (Heads-up chime notification when session or break completes)
            val timerAlertsChannel = NotificationChannel(
                CHANNEL_ID_TIMER_ALERTS,
                "Study Timer Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Chime and heads-up alerts when study focus or break intervals complete"
                enableVibration(true)
                setShowBadge(true)
            }

            // 3. Schedule & Class Reminders (Heads-up alert)
            val alarmChannel = NotificationChannel(
                CHANNEL_ID_ALARMS,
                "Class & Revision Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "10-minute alerts before scheduled classes and revision sessions"
                enableVibration(true)
                setShowBadge(true)
            }

            // 4. Streak & Goal Alerts
            val streakChannel = NotificationChannel(
                CHANNEL_ID_STREAKS,
                "Streak & Goal Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily 8:00 PM streak preservation reminders"
                setShowBadge(true)
            }

            // 5. Remote FCM Announcements
            val announcementsChannel = NotificationChannel(
                CHANNEL_ID_ANNOUNCEMENTS,
                "ScholarPulse Announcements",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Important updates, system notices, and cloud announcements"
                setShowBadge(true)
            }

            notificationManager?.createNotificationChannels(
                listOf(
                    timerChannel,
                    timerLegacyChannel,
                    timerAlertsChannel,
                    alarmChannel,
                    streakChannel,
                    announcementsChannel
                )
            )
        }
    }
}
