package com.scholarpulse.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.scholarpulse.app.bridge.NativeBridgeDispatcher
import com.scholarpulse.app.db.OfflineMutationEntity
import com.scholarpulse.app.db.ScholarPulseDatabase
import com.scholarpulse.app.workers.OfflineSyncWorker
import com.scholarpulse.app.workers.StudySessionBatchItem
import com.scholarpulse.app.workers.ActiveTimerBatchItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * BroadcastReceiver intercepting notification action clicks from the persistent study timer.
 * Routes commands directly to TimerForegroundService and emits `timer.actionTriggered`
 * to the WebView NativeBridgeDispatcher so the web React context synchronizes in real time.
 *
 * Implements native background sovereignty:
 * - When Finish [ ⏹ ] is tapped, persists the completed session to Room SQLite,
 *   marks the session tombstoned in TimerStateStore, and triggers WorkManager sync.
 * - Guarantees zero reliance on the suspended WebView to record or terminate the session.
 * - Eliminates ForegroundServiceDidNotStartInTimeException by avoiding startForegroundService()
 *   for terminal teardown actions.
 */
class TimerActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimerActionReceiver"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.d(TAG, "Notification action received: $action")

        val service = TimerForegroundService.instance

        // =========================================================================
        // Terminal Finish & Save Action [ ⏹ ]
        // =========================================================================
        if (action == TimerForegroundService.ACTION_FINISH || action == TimerForegroundService.ACTION_STOP) {
            val pendingResult = goAsync()
            val stateStore = TimerStateStore(context)

            // 1. Capture snapshot in-memory synchronously BEFORE stopping the service
            val snapshot = service?.captureSnapshot()

            // 2. IMMEDIATELY dismiss notification & stop foreground service on Main thread (0ms perceived latency!)
            if (service != null) {
                service.finishSession()
            } else {
                context.stopService(Intent(context, TimerForegroundService::class.java))
            }

            // 3. Complete persistence, offline queueing, and cloud sync asynchronously in the background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (snapshot != null) {
                        val duration = snapshot.elapsedSeconds
                        val key = snapshot.idempotencyKey.ifBlank { "timer_${System.currentTimeMillis()}" }

                        // a. Mark tombstone natively so WebView can NEVER resurrect this timer
                        stateStore.markSessionFinishedNatively(
                            idempotencyKey = key,
                            subjectId = snapshot.subjectId,
                            topic = snapshot.topic,
                            durationSeconds = duration
                        )

                        // b. If session duration is >= 30 seconds, persist offline mutation to Room
                        if (duration >= 30) {
                            val sessionItem = StudySessionBatchItem(
                                clientSessionId = key,
                                idempotencyKey = key,
                                subjectId = snapshot.subjectId.ifBlank { "00000000-0000-0000-0000-000000000000" },
                                startTime = snapshot.startedAtIso,
                                endTime = Instant.now().toString(),
                                durationSeconds = duration.toInt(),
                                topic = snapshot.topic.ifBlank { "General Study" },
                                notes = snapshot.notes,
                                productivityRating = 4,
                                sessionType = "timer"
                            )

                            try {
                                val db = ScholarPulseDatabase.getInstance(context)
                                db.offlineMutationDao().clearPendingTimerMutations()
                                val mutation = OfflineMutationEntity(
                                    actionType = "SESSION",
                                    payloadJson = json.encodeToString(sessionItem),
                                    idempotencyKey = sessionItem.idempotencyKey
                                )
                                db.offlineMutationDao().enqueueMutation(mutation)
                                Log.i(TAG, "Enqueued offline mutation for completed study session: ${sessionItem.idempotencyKey}")

                                // c. Enqueue immediate WorkManager background sync & trigger opportunistic fast-path
                                OfflineSyncWorker.enqueueImmediateSync(context)
                                try {
                                    OfflineSyncWorker.performSync(context)
                                } catch (syncErr: Exception) {
                                    Log.d(TAG, "Direct opportunistic sync deferred to WorkManager: ${syncErr.message}")
                                }
                            } catch (dbErr: Exception) {
                                Log.e(TAG, "Failed to enqueue offline session mutation in Room", dbErr)
                            }
                        } else {
                            Log.d(TAG, "Session duration ($duration s) < 30s threshold; discarded without saving.")
                        }
                    }

                    // d. Best-effort dispatch event to WebView if awake
                    // Send action = "reconcile" with alreadySaved = true so the WebView clears local timer state
                    // and refreshes workspace WITHOUT re-executing finishTimerSession() and creating a duplicate session.
                    val activeBridge = TimerForegroundService.dispatcher ?: NativeBridgeDispatcher.activeDispatcher
                    val eventPayload = buildJsonObject {
                        put("action", "reconcile")
                        put("source", "notification")
                        put("alreadySaved", true)
                        if (snapshot != null) {
                            put("idempotencyKey", snapshot.idempotencyKey)
                        }
                    }
                    activeBridge?.sendEvent("timer.actionTriggered", eventPayload)
                    activeBridge?.sendEvent("workspace.refresh", null)
                    Log.d(TAG, "Dispatched timer.actionTriggered (reconcile) to bridge")
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling notification finish action in background", e)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        // =========================================================================
        // Terminal Discard Action
        // =========================================================================
        if (action == TimerForegroundService.ACTION_DISCARD) {
            val key = service?.idempotencyKey
            if (!key.isNullOrBlank()) {
                TimerStateStore(context).markSessionFinishedNatively(
                    idempotencyKey = key,
                    subjectId = service.subjectId,
                    topic = service.topic,
                    durationSeconds = service.getElapsedSeconds()
                )
            }
            if (service != null) {
                service.discardSession()
            } else {
                context.stopService(Intent(context, TimerForegroundService::class.java))
            }
            val activeBridge = TimerForegroundService.dispatcher ?: NativeBridgeDispatcher.activeDispatcher
            activeBridge?.sendEvent("timer.actionTriggered", buildJsonObject {
                put("action", "discard")
                put("source", "notification")
            })
            return
        }

        // =========================================================================
        // Non-Terminal Interactive Actions: PAUSE, RESUME, SKIP_PHASE
        // =========================================================================
        if (service != null && (action == TimerForegroundService.ACTION_PAUSE || action == TimerForegroundService.ACTION_RESUME)) {
            val isPaused = (action == TimerForegroundService.ACTION_PAUSE)
            if (isPaused) service.pause() else service.resume()

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val key = service.idempotencyKey
                    if (key.isNotBlank()) {
                        val timerItem = ActiveTimerBatchItem(
                            idempotencyKey = key,
                            subjectId = service.subjectId.ifBlank { "00000000-0000-0000-0000-000000000000" },
                            topic = service.topic.ifBlank { "Focused Study" },
                            notes = service.notes,
                            mode = if (service.mode == TimerMode.POMODORO) "pomodoro" else "stopwatch",
                            phase = if (service.phase == PomodoroPhase.FOCUS) "focus" else "short_break",
                            status = if (isPaused) "paused" else "running",
                            startedAt = if (service.startedAtIso.isNotBlank()) service.startedAtIso else Instant.now().toString(),
                            lastResumedAt = if (service.lastResumedAtIso.isNotBlank()) service.lastResumedAtIso else Instant.now().toString(),
                            accumulatedSeconds = service.accumulatedSeconds.toInt(),
                            totalFocusSeconds = service.accumulatedSeconds.toInt(),
                            targetDurationSeconds = service.targetDurationSeconds.toInt(),
                            completedPomodoros = service.completedPomodoros,
                            stateVersion = ((System.currentTimeMillis() / 1000L).toInt())
                        )

                        val db = ScholarPulseDatabase.getInstance(context)
                        val mutation = OfflineMutationEntity(
                            actionType = "TIMER",
                            payloadJson = json.encodeToString(timerItem),
                            idempotencyKey = "active_timer_$key"
                        )
                        db.offlineMutationDao().enqueueMutation(mutation)
                        Log.i(TAG, "Enqueued active timer mutation ($action) to Room: $key")
                        OfflineSyncWorker.enqueueImmediateSync(context)
                        try {
                            OfflineSyncWorker.performSync(context)
                        } catch (syncErr: Exception) {
                            Log.d(TAG, "Direct opportunistic sync deferred to WorkManager: ${syncErr.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist timer state mutation ($action)", e)
                } finally {
                    pendingResult.finish()
                }
            }
        } else if (service != null) {
            when (action) {
                TimerForegroundService.ACTION_SKIP_PHASE, TimerForegroundService.ACTION_SKIP_BREAK -> service.skipPhase()
            }
        } else {
            // Service not running in memory; route via Service Intent
            val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
                this.action = action
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service for action $action", e)
            }
        }

        // Map notification action to web bridge contract and dispatch
        val bridgeAction = when (action) {
            TimerForegroundService.ACTION_PAUSE -> "pause"
            TimerForegroundService.ACTION_RESUME -> "resume"
            TimerForegroundService.ACTION_SKIP_PHASE, TimerForegroundService.ACTION_SKIP_BREAK -> "skipPhase"
            else -> action
        }

        val eventPayload = buildJsonObject {
            put("action", bridgeAction)
            put("source", "notification")
        }

        val activeBridge = TimerForegroundService.dispatcher ?: NativeBridgeDispatcher.activeDispatcher
        activeBridge?.sendEvent("timer.actionTriggered", eventPayload)
        Log.d(TAG, "Dispatched timer.actionTriggered to bridge: $bridgeAction")
    }
}
