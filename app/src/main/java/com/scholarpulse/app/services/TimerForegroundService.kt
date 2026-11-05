package com.scholarpulse.app.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.scholarpulse.app.MainActivity
import com.scholarpulse.app.R
import com.scholarpulse.app.ScholarPulseApp
import com.scholarpulse.app.audio.NativeAudioEngine
import com.scholarpulse.app.audio.NativeHapticsEngine
import com.scholarpulse.app.bridge.NativeBridgeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale

/**
 * State machine status values for the study timer.
 */
enum class TimerStatus {
    IDLE,
    RUNNING,
    PAUSED,
    BREAK
}

/**
 * Operating mode: Stopwatch (unbounded) or Pomodoro (interval-based).
 */
enum class TimerMode {
    POMODORO,
    STOPWATCH
}

/**
 * Phase of a Pomodoro cycle.
 */
enum class PomodoroPhase {
    FOCUS,
    SHORT_BREAK,
    LONG_BREAK
}

/**
 * Un-killable Foreground Service managing academic study timer tracking.
 * Features:
 * - SpecialUse foreground service type with high-priority ongoing notification.
 * - PowerManager.PARTIAL_WAKE_LOCK with tag "ScholarPulse:StudyTimer" and 4-hour safety ceiling.
 * - High-precision timestamp reconciliation preventing OS clock drift.
 * - Automatic Pomodoro phase transitions with synthesized PCM chimes and haptic waveforms.
 * - Interactive notification controls routed via TimerActionReceiver.
 * - 1000ms timer.tick and timer.phaseCompleted bridge event dispatching.
 */
class TimerForegroundService : Service() {

    companion object {
        private const val TAG = "TimerForegroundService"

        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002

        // Broadcast / Service Actions
        const val ACTION_START = "com.scholarpulse.app.ACTION_START"
        const val ACTION_PAUSE = "com.scholarpulse.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.scholarpulse.app.ACTION_RESUME"
        const val ACTION_STOP = "com.scholarpulse.app.ACTION_STOP"
        const val ACTION_FINISH = "com.scholarpulse.app.ACTION_FINISH"
        const val ACTION_SKIP_PHASE = "com.scholarpulse.app.ACTION_SKIP_PHASE"
        const val ACTION_SKIP_BREAK = "com.scholarpulse.app.ACTION_SKIP_BREAK"
        const val ACTION_DISCARD = "com.scholarpulse.app.ACTION_DISCARD"

        // Intent Extras
        const val EXTRA_SUBJECT_ID = "extra_subject_id"
        const val EXTRA_SUBJECT_NAME = "extra_subject_name"
        const val EXTRA_TOPIC = "extra_topic"
        const val EXTRA_NOTES = "extra_notes"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TARGET_DURATION = "extra_target_duration"
        const val EXTRA_IDEMPOTENCY_KEY = "extra_idempotency_key"
        const val EXTRA_ACCUMULATED_SECONDS = "extra_accumulated_seconds"
        const val EXTRA_PHASE = "extra_phase"

        // Global singleton pointers for direct IPC and status inspection
        @Volatile
        var instance: TimerForegroundService? = null
            internal set

        @Volatile
        var dispatcher: NativeBridgeDispatcher? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    // Timer State
    var status: TimerStatus = TimerStatus.IDLE
        private set
    var mode: TimerMode = TimerMode.POMODORO
        private set
    var phase: PomodoroPhase = PomodoroPhase.FOCUS
        private set
    var previousPhase: PomodoroPhase = PomodoroPhase.FOCUS
        private set

    var subjectId: String = ""
        private set
    var subjectName: String = ""
        private set
    var topic: String = "Focused Study"
        private set
    var notes: String = ""
        private set
    var idempotencyKey: String = ""
        private set

    var targetDurationSeconds: Long = 25 * 60L
        private set
    var defaultFocusDurationSeconds: Long = 25 * 60L
        private set
    var completedPomodoros: Int = 0
        private set

    // Millisecond-precision timestamp reconciliation
    var accumulatedSeconds: Long = 0L
        private set
    var lastResumedAtMs: Long = 0L
        private set
    var startedAtIso: String = ""
        private set
    var lastResumedAtIso: String = ""
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Log.d(TAG, "TimerForegroundService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_FINISH, ACTION_STOP -> finishSession()
            ACTION_SKIP_PHASE, ACTION_SKIP_BREAK -> skipPhase()
            ACTION_DISCARD -> discardSession()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "TimerForegroundService destroyed")
        stopTicker()
        releaseWakeLock()
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    // =========================================================================
    // Core Timer State Machine Operations
    // =========================================================================

    private fun handleStart(intent: Intent?) {
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString()

        val newIdempotencyKey = intent?.getStringExtra(EXTRA_IDEMPOTENCY_KEY) ?: "timer_$nowMs"
        val passedAccumulated = intent?.getLongExtra(EXTRA_ACCUMULATED_SECONDS, 0L) ?: 0L
        val passedPhase = intent?.getStringExtra(EXTRA_PHASE) ?: "focus"

        // If the service is already actively running this exact timer session, do NOT reset the clock to 0!
        if (status == TimerStatus.RUNNING && idempotencyKey == newIdempotencyKey && idempotencyKey.isNotBlank()) {
            Log.d(TAG, "Timer is already actively running for idempotencyKey=$newIdempotencyKey. Updating metadata without resetting clock.")
            subjectName = intent?.getStringExtra(EXTRA_SUBJECT_NAME)?.ifBlank { subjectName } ?: subjectName
            topic = intent?.getStringExtra(EXTRA_TOPIC)?.ifBlank { topic } ?: topic
            updateNotification()
            return
        }

        subjectId = intent?.getStringExtra(EXTRA_SUBJECT_ID) ?: ""
        subjectName = intent?.getStringExtra(EXTRA_SUBJECT_NAME) ?: ""
        topic = intent?.getStringExtra(EXTRA_TOPIC)?.ifBlank { "Focused Study" } ?: "Focused Study"
        notes = intent?.getStringExtra(EXTRA_NOTES) ?: ""
        idempotencyKey = newIdempotencyKey

        val modeStr = intent?.getStringExtra(EXTRA_MODE) ?: "pomodoro"
        mode = if (modeStr.equals("stopwatch", ignoreCase = true)) {
            TimerMode.STOPWATCH
        } else {
            TimerMode.POMODORO
        }

        val targetDuration = intent?.getLongExtra(EXTRA_TARGET_DURATION, 25 * 60L) ?: (25 * 60L)
        targetDurationSeconds = if (targetDuration > 0) targetDuration else (25 * 60L)
        defaultFocusDurationSeconds = targetDurationSeconds

        phase = when (passedPhase.lowercase()) {
            "short_break" -> PomodoroPhase.SHORT_BREAK
            "long_break" -> PomodoroPhase.LONG_BREAK
            else -> PomodoroPhase.FOCUS
        }
        previousPhase = phase
        status = if (phase == PomodoroPhase.FOCUS) TimerStatus.RUNNING else TimerStatus.BREAK
        completedPomodoros = 0
        accumulatedSeconds = maxOf(0L, passedAccumulated)
        lastResumedAtMs = nowMs
        startedAtIso = nowIso
        lastResumedAtIso = nowIso

        acquireWakeLock()
        startForegroundServiceNotification()
        startTicker()
        dispatchTickEvent()
    }

    fun pause() {
        if (status != TimerStatus.RUNNING && status != TimerStatus.BREAK) return
        val currentElapsed = getElapsedSeconds()
        accumulatedSeconds = currentElapsed
        lastResumedAtMs = System.currentTimeMillis()
        lastResumedAtIso = Instant.ofEpochMilli(lastResumedAtMs).toString()
        status = TimerStatus.PAUSED

        releaseWakeLock()
        updateNotification()
        dispatchTickEvent()
        Log.d(TAG, "Timer paused at $accumulatedSeconds seconds")
    }

    fun resume() {
        if (status != TimerStatus.PAUSED) return
        lastResumedAtMs = System.currentTimeMillis()
        lastResumedAtIso = Instant.ofEpochMilli(lastResumedAtMs).toString()
        status = if (phase == PomodoroPhase.FOCUS) TimerStatus.RUNNING else TimerStatus.BREAK

        acquireWakeLock()
        updateNotification()
        dispatchTickEvent()
        Log.d(TAG, "Timer resumed at $accumulatedSeconds seconds")
    }

    fun finishSession() {
        Log.d(TAG, "Finishing timer session")
        val finalSeconds = getElapsedSeconds()
        status = TimerStatus.IDLE
        stopTicker()
        releaseWakeLock()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun discardSession() {
        Log.d(TAG, "Discarding timer session")
        status = TimerStatus.IDLE
        stopTicker()
        releaseWakeLock()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun skipPhase() {
        if (mode != TimerMode.POMODORO) return

        val nowMs = System.currentTimeMillis()
        lastResumedAtMs = nowMs
        lastResumedAtIso = Instant.ofEpochMilli(nowMs).toString()
        accumulatedSeconds = 0L

        if (phase == PomodoroPhase.FOCUS) {
            previousPhase = PomodoroPhase.FOCUS
            phase = PomodoroPhase.SHORT_BREAK
            status = TimerStatus.BREAK
            targetDurationSeconds = 5 * 60L
            Log.d(TAG, "Skipped from FOCUS to SHORT_BREAK")
        } else {
            previousPhase = phase
            phase = PomodoroPhase.FOCUS
            status = TimerStatus.RUNNING
            targetDurationSeconds = defaultFocusDurationSeconds
            Log.d(TAG, "Skipped from BREAK to FOCUS")
        }

        updateNotification()
        dispatchTickEvent()
    }

    // =========================================================================
    // Elapsed Seconds & Timing Calculations
    // =========================================================================

    fun getElapsedSeconds(): Long {
        if (status != TimerStatus.RUNNING && status != TimerStatus.BREAK) {
            return accumulatedSeconds
        }
        val delta = (System.currentTimeMillis() - lastResumedAtMs) / 1000L
        return accumulatedSeconds + maxOf(0L, delta)
    }

    fun getRemainingSeconds(): Long {
        if (mode == TimerMode.STOPWATCH) return 0L
        val elapsed = getElapsedSeconds()
        return maxOf(0L, targetDurationSeconds - elapsed)
    }

    // =========================================================================
    // High-Precision Ticker & Automatic Pomodoro Completion
    // =========================================================================

    private fun startTicker() {
        stopTicker()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                onTick()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun onTick() {
        if (status != TimerStatus.RUNNING && status != TimerStatus.BREAK) return

        val elapsed = getElapsedSeconds()

        // Check if Pomodoro interval has elapsed
        if (mode == TimerMode.POMODORO && elapsed >= targetDurationSeconds) {
            handlePomodoroPhaseCompleted()
        }

        dispatchTickEvent()
    }

    private fun handlePomodoroPhaseCompleted() {
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString()

        if (phase == PomodoroPhase.FOCUS) {
            completedPomodoros++
            val completedPhaseName = "focus"
            val isLongBreak = (completedPomodoros % 4 == 0)
            val nextPhase = if (isLongBreak) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
            val breakDuration = if (isLongBreak) (15 * 60L) else (5 * 60L)

            previousPhase = PomodoroPhase.FOCUS
            phase = nextPhase
            status = TimerStatus.BREAK
            targetDurationSeconds = breakDuration
            accumulatedSeconds = 0L
            lastResumedAtMs = nowMs
            lastResumedAtIso = nowIso

            // 1. Play native harmonic chime & haptic
            NativeAudioEngine.playChime(applicationContext, "focus_end")
            NativeHapticsEngine.vibrate(applicationContext, "success")

            // 2. Post heads-up alert notification
            val breakLabel = if (isLongBreak) "Long Break (15 min)" else "Short Break (5 min)"
            postCompletionAlert(
                title = "Pomodoro Focus Complete!",
                message = "Great work on \"$topic\". Time for a $breakLabel rest."
            )

            // 3. Dispatch unsolicited bridge event
            dispatchPhaseCompletedEvent(
                completedPhase = completedPhaseName,
                nextPhase = if (isLongBreak) "long_break" else "short_break",
                completedCount = completedPomodoros,
                nextDuration = breakDuration
            )
        } else {
            val completedPhaseName = if (phase == PomodoroPhase.LONG_BREAK) "long_break" else "short_break"
            previousPhase = phase
            phase = PomodoroPhase.FOCUS
            status = TimerStatus.RUNNING
            targetDurationSeconds = defaultFocusDurationSeconds
            accumulatedSeconds = 0L
            lastResumedAtMs = nowMs
            lastResumedAtIso = nowIso

            // 1. Play return chime & haptic
            NativeAudioEngine.playChime(applicationContext, "break_end")
            NativeHapticsEngine.vibrate(applicationContext, "warning")

            // 2. Post heads-up alert notification
            postCompletionAlert(
                title = "Break Finished!",
                message = "Ready to jump back into \"$topic\"?"
            )

            // 3. Dispatch unsolicited bridge event
            dispatchPhaseCompletedEvent(
                completedPhase = completedPhaseName,
                nextPhase = "focus",
                completedCount = completedPomodoros,
                nextDuration = targetDurationSeconds
            )
        }

        updateNotification()
    }

    // =========================================================================
    // WakeLock Management (4-Hour Safety Ceiling)
    // =========================================================================

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ScholarPulse:StudyTimer"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4-hour safety ceiling
            }
            Log.d(TAG, "WakeLock acquired: ScholarPulse:StudyTimer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire PARTIAL_WAKE_LOCK", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    // =========================================================================
    // Notification & Interactive Action Controls
    // =========================================================================

    private fun startForegroundServiceNotification() {
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                0
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val elapsed = getElapsedSeconds()
        val remaining = getRemainingSeconds()

        val isRunning = (status == TimerStatus.RUNNING || status == TimerStatus.BREAK)
        val isPaused = (status == TimerStatus.PAUSED)

        val subjectHeader = if (subjectName.isNotBlank()) subjectName else "ScholarPulse"
        val topicBody = if (topic.isNotBlank()) topic else "Study Session"

        val subText = when {
            mode == TimerMode.POMODORO && phase == PomodoroPhase.FOCUS -> "Pomodoro #${completedPomodoros + 1}"
            mode == TimerMode.POMODORO -> "Break"
            else -> "Stopwatch"
        }

        val title = when {
            isPaused -> "Paused (${formatDuration(elapsed)}) • $subjectHeader"
            mode == TimerMode.STOPWATCH -> "Stopwatch • $subjectHeader"
            phase == PomodoroPhase.FOCUS -> "Focus: $subjectHeader"
            phase == PomodoroPhase.SHORT_BREAK -> "Short Break • $subjectHeader"
            phase == PomodoroPhase.LONG_BREAK -> "Long Break • $subjectHeader"
            else -> subjectHeader
        }

        val contentText = topicBody

        // Tapping notification body launches MainActivity directly to timer tab
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://scholarpulsev2.vercel.app?tab=timer")
            putExtra("tab", "timer")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, ScholarPulseApp.CHANNEL_ID_TIMER)
            .setSmallIcon(R.drawable.ic_stat_scholarpulse)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isRunning)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // ---------------------------------------------------------------------
        // Custom RemoteViews Matching the Floating Mini-Player Bar
        // Collapsed View: Subject name + Big Chronometer + Actions (no "Focus" text)
        // Expanded View: Full details with Subject, Topic, Phase pill, and Chronometer
        // ---------------------------------------------------------------------
        val collapsedViews = RemoteViews(packageName, R.layout.notification_timer_collapsed)
        val expandedViews = RemoteViews(packageName, R.layout.notification_timer_expanded)

        val phaseLabel = when {
            mode == TimerMode.STOPWATCH -> "Stopwatch"
            phase == PomodoroPhase.FOCUS -> "Focus"
            phase == PomodoroPhase.SHORT_BREAK -> "Short Break"
            phase == PomodoroPhase.LONG_BREAK -> "Long Break"
            else -> "Study"
        }

        val statusBadgeText = when {
            isPaused -> "PAUSED"
            phase == PomodoroPhase.FOCUS -> "FOCUSING"
            else -> "ON BREAK"
        }

        // Configure Collapsed RemoteViews (Clean subject name, plenty of space, no truncated "Focus")
        collapsedViews.setTextViewText(R.id.notif_subject, subjectHeader)
        collapsedViews.setOnClickPendingIntent(R.id.notif_root, contentPendingIntent)
        collapsedViews.setOnClickPendingIntent(R.id.btn_notif_finish, createActionPendingIntent(ACTION_FINISH, 102))

        // Configure Expanded RemoteViews (Full details: Subject, Topic, Phase pill "Focus", Status badge)
        expandedViews.setTextViewText(R.id.notif_big_subject, subjectHeader)
        expandedViews.setTextViewText(R.id.notif_big_topic, topicBody)
        expandedViews.setTextViewText(R.id.notif_big_phase_pill, phaseLabel)
        expandedViews.setTextViewText(R.id.notif_big_status_badge, statusBadgeText)
        expandedViews.setOnClickPendingIntent(R.id.notif_big_root, contentPendingIntent)
        expandedViews.setOnClickPendingIntent(R.id.btn_big_finish, createActionPendingIntent(ACTION_FINISH, 102))

        if (isRunning) {
            val isCountDown = (mode == TimerMode.POMODORO)
            val baseElapsedRealtime = if (isCountDown) {
                SystemClock.elapsedRealtime() + (remaining * 1000L)
            } else {
                SystemClock.elapsedRealtime() - (elapsed * 1000L)
            }

            // Collapsed Live Chronometer & Actions
            collapsedViews.setViewVisibility(R.id.notif_chronometer, View.VISIBLE)
            collapsedViews.setViewVisibility(R.id.notif_paused_time, View.GONE)
            collapsedViews.setChronometer(R.id.notif_chronometer, baseElapsedRealtime, null, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                collapsedViews.setChronometerCountDown(R.id.notif_chronometer, isCountDown)
            }
            collapsedViews.setViewVisibility(R.id.btn_notif_pause, View.VISIBLE)
            collapsedViews.setViewVisibility(R.id.btn_notif_resume, View.GONE)
            val pauseAction = if (phase == PomodoroPhase.FOCUS) ACTION_PAUSE else ACTION_SKIP_PHASE
            collapsedViews.setOnClickPendingIntent(R.id.btn_notif_pause, createActionPendingIntent(pauseAction, 101))

            // Expanded Live Chronometer & Actions
            expandedViews.setViewVisibility(R.id.notif_big_chronometer, View.VISIBLE)
            expandedViews.setViewVisibility(R.id.notif_big_paused_time, View.GONE)
            expandedViews.setChronometer(R.id.notif_big_chronometer, baseElapsedRealtime, null, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                expandedViews.setChronometerCountDown(R.id.notif_big_chronometer, isCountDown)
            }
            expandedViews.setViewVisibility(R.id.btn_big_pause, View.VISIBLE)
            expandedViews.setViewVisibility(R.id.btn_big_resume, View.GONE)
            expandedViews.setOnClickPendingIntent(R.id.btn_big_pause, createActionPendingIntent(pauseAction, 101))

            // System header fallback when ticking
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
            if (mode == TimerMode.POMODORO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setChronometerCountDown(true)
                }
                val targetEndMs = System.currentTimeMillis() + (remaining * 1000L)
                builder.setWhen(targetEndMs)
            } else {
                builder.setChronometerCountDown(false)
                val startBaseMs = System.currentTimeMillis() - (elapsed * 1000L)
                builder.setWhen(startBaseMs)
            }
        } else {
            val pausedFormatted = formatDuration(elapsed)

            // Collapsed Paused State
            collapsedViews.setViewVisibility(R.id.notif_chronometer, View.GONE)
            collapsedViews.setViewVisibility(R.id.notif_paused_time, View.VISIBLE)
            collapsedViews.setTextViewText(R.id.notif_paused_time, "$pausedFormatted (Paused)")
            collapsedViews.setViewVisibility(R.id.btn_notif_pause, View.GONE)
            collapsedViews.setViewVisibility(R.id.btn_notif_resume, View.VISIBLE)
            collapsedViews.setOnClickPendingIntent(R.id.btn_notif_resume, createActionPendingIntent(ACTION_RESUME, 105))

            // Expanded Paused State
            expandedViews.setViewVisibility(R.id.notif_big_chronometer, View.GONE)
            expandedViews.setViewVisibility(R.id.notif_big_paused_time, View.VISIBLE)
            expandedViews.setTextViewText(R.id.notif_big_paused_time, pausedFormatted)
            expandedViews.setViewVisibility(R.id.btn_big_pause, View.GONE)
            expandedViews.setViewVisibility(R.id.btn_big_resume, View.VISIBLE)
            expandedViews.setOnClickPendingIntent(R.id.btn_big_resume, createActionPendingIntent(ACTION_RESUME, 105))

            // System header fallback when paused
            builder.setUsesChronometer(false)
            builder.setShowWhen(false)
        }

        // Apply DecoratedCustomViewStyle with custom collapsed & expanded views
        builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedViews)
            .setCustomBigContentView(expandedViews)

        return builder.build()
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TimerActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postCompletionAlert(title: String, message: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://scholarpulsev2.vercel.app?tab=timer")
            putExtra("tab", "timer")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            ALERT_NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, ScholarPulseApp.CHANNEL_ID_TIMER_ALERTS)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            notificationManager.notify(ALERT_NOTIFICATION_ID, alertNotification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post alert notification", e)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val s = maxOf(0L, seconds)
        val hrs = s / 3600
        val mins = (s % 3600) / 60
        val secs = s % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    // =========================================================================
    // Bridge Event Dispatches (timer.tick and timer.phaseCompleted)
    // =========================================================================

    private fun dispatchTickEvent() {
        val elapsed = getElapsedSeconds()
        val remaining = getRemainingSeconds()

        val phaseStr = when (phase) {
            PomodoroPhase.FOCUS -> "focus"
            PomodoroPhase.SHORT_BREAK -> "short_break"
            PomodoroPhase.LONG_BREAK -> "long_break"
        }

        val statusStr = when (status) {
            TimerStatus.RUNNING -> "running"
            TimerStatus.BREAK -> "running" // Web context expects "running" for active break ticker
            TimerStatus.PAUSED -> "paused"
            TimerStatus.IDLE -> "idle"
        }

        val payload = buildJsonObject {
            put("elapsedSeconds", elapsed)
            put("remainingSeconds", remaining)
            put("phase", phaseStr)
            put("status", statusStr)
        }

        val activeBridge = dispatcher ?: NativeBridgeDispatcher.activeDispatcher
        activeBridge?.sendEvent("timer.tick", payload)
    }

    private fun dispatchPhaseCompletedEvent(
        completedPhase: String,
        nextPhase: String,
        completedCount: Int,
        nextDuration: Long
    ) {
        val payload = buildJsonObject {
            put("completedPhase", completedPhase)
            put("nextPhase", nextPhase)
            put("completedPomodoros", completedCount)
            put("targetDurationSeconds", nextDuration)
        }

        val activeBridge = dispatcher ?: NativeBridgeDispatcher.activeDispatcher
        activeBridge?.sendEvent("timer.phaseCompleted", payload)
    }
}
