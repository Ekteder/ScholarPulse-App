package com.scholarpulse.app.bridge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import com.scholarpulse.app.BuildConfig
import com.scholarpulse.app.MainActivity
import com.scholarpulse.app.audio.NativeAudioEngine
import com.scholarpulse.app.audio.NativeHapticsEngine
import com.scholarpulse.app.auth.CookieSessionManager
import com.scholarpulse.app.auth.CredentialManagerHelper
import com.scholarpulse.app.auth.GoogleSignInResponseDto
import com.scholarpulse.app.auth.GoogleSignInResult
import com.scholarpulse.app.auth.GoogleTokenExchanger
import com.scholarpulse.app.auth.SyncSessionParams
import com.scholarpulse.app.auth.TokenExchangeResult
import com.scholarpulse.app.services.PomodoroPhase
import com.scholarpulse.app.services.TimerForegroundService
import com.scholarpulse.app.services.TimerMode
import com.scholarpulse.app.services.TimerStatus
import com.scholarpulse.app.services.TimerStateStore
import com.scholarpulse.app.alarms.ScheduleAlarmScheduler
import com.scholarpulse.app.db.ScheduleEntity
import com.scholarpulse.app.db.ScholarPulseDatabase
import com.scholarpulse.app.db.OfflineMutationEntity
import com.scholarpulse.app.services.AppFirebaseMessagingService
import com.scholarpulse.app.workers.OfflineSyncWorker
import com.scholarpulse.app.workers.StudySessionBatchItem
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant

class NativeBridgeDispatcher(
    val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NativeBridgeDispatcher"

        @Volatile
        var activeDispatcher: NativeBridgeDispatcher? = null
            internal set
    }

    private val tag = TAG
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webViewRef: WebView? = null

    init {
        activeDispatcher = this
        TimerForegroundService.dispatcher = this
    }

    var currentInsets = SafeAreaInsets()
    var onThemeChange: ((isDark: Boolean) -> Unit)? = null
    var onWebReady: (() -> Unit)? = null

    // Auth dependencies wired from Activity
    var activityProvider: (() -> Activity?)? = null
    var credentialManagerHelper: CredentialManagerHelper? = null
    var tokenExchanger: GoogleTokenExchanger = GoogleTokenExchanger()
    var cookieSessionManager: CookieSessionManager? = null
    @Volatile
    private var isAuthInProgress = false

    private val timerStateStore = TimerStateStore(context)

    fun getTimerSyncState(): String = timerStateStore.getSyncStateJson()

    fun acknowledgePendingReview(idempotencyKey: String?) {
        timerStateStore.clearPendingReview(idempotencyKey)
    }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val allowedOrigins: Set<String> = buildSet {
        add("https://scholarpulsev2.vercel.app")
        add("https://appassets.androidplatform.net")
        if (BuildConfig.DEBUG) {
            add("http://localhost:3000")
            add("http://10.0.2.2:3000")
            add("http://127.0.0.1:3000")
        }
    }

    fun attachWebView(webView: WebView) {
        this.webViewRef = webView
    }

    fun isOriginAllowed(originUrl: String): Boolean {
        return try {
            val uri = URI(originUrl)
            val normalizedOrigin = "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
            allowedOrigins.any { it.equals(normalizedOrigin, ignoreCase = true) || it.equals(originUrl.trimEnd('/'), ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    fun handleRawMessage(
        messagePayload: String,
        sourceOrigin: String,
        replyProxy: JavaScriptReplyProxy? = null
    ) {
        if (!isOriginAllowed(sourceOrigin)) {
            val errorResponse = BridgeResponse(
                id = "unknown",
                success = false,
                error = BridgeError(
                    code = BridgeErrorCodes.E_UNAUTHORIZED_ORIGIN,
                    message = "Origin '$sourceOrigin' is not authorized to access ScholarPulse Native Bridge."
                )
            )
            sendResponse(errorResponse, replyProxy)
            return
        }

        try {
            val request = json.decodeFromString<BridgeRequest>(messagePayload)
            dispatchRequest(request, replyProxy)
        } catch (e: Exception) {
            val errorResponse = BridgeResponse(
                id = "malformed",
                success = false,
                error = BridgeError(
                    code = BridgeErrorCodes.E_INVALID_PARAMS,
                    message = "Malformed JSON-RPC request: ${e.message}"
                )
            )
            sendResponse(errorResponse, replyProxy)
        }
    }

    private fun dispatchRequest(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        when (request.method) {
            "system.getDeviceInfo", "device.getInfo" -> handleGetDeviceInfo(request, replyProxy)
            "system.triggerHaptic", "haptics.vibrate" -> handleTriggerHaptic(request, replyProxy)
            "system.updateTheme" -> handleUpdateTheme(request, replyProxy)
            "system.notifyReady" -> handleNotifyReady(request, replyProxy)

            // Auth subsystem
            "auth.googleSignIn" -> handleGoogleSignIn(request, replyProxy)
            "auth.signOut" -> handleSignOut(request, replyProxy)
            "auth.syncSessionCookie" -> handleSyncSessionCookie(request, replyProxy)

            // Foreground Study Timer subsystem (Phase 5)
            "timer.start" -> handleTimerStart(request, replyProxy)
            "timer.pause" -> handleTimerPause(request, replyProxy)
            "timer.resume" -> handleTimerResume(request, replyProxy)
            "timer.stop" -> handleTimerStop(request, replyProxy)
            "timer.skipPhase" -> handleTimerSkipPhase(request, replyProxy)
            "timer.getStatus" -> handleTimerGetStatus(request, replyProxy)
            "timer.getSyncState" -> handleTimerGetSyncState(request, replyProxy)
            "timer.ackReview" -> handleTimerAckReview(request, replyProxy)
            "timer.onRemoteSync", "timer.syncRemoteState" -> handleTimerRemoteSync(request, replyProxy)

            // Native PCM Audio Synthesis (Phase 5)
            "audio.playChime" -> handleAudioPlayChime(request, replyProxy)

            // Schedule Alarms & Batch Offline Sync (Phase 6)
            "alarms.sync", "notifications.syncScheduleAlarms" -> handleSyncScheduleAlarms(request, replyProxy)
            "sync.triggerBatch" -> handleTriggerBatchSync(request, replyProxy)
            "fcm.getToken", "notifications.getToken", "notifications.getFcmToken" -> handleGetFcmToken(request, replyProxy)
            "notifications.getStatus" -> handleNotificationGetStatus(request, replyProxy)
            "notifications.requestPermission" -> handleNotificationRequestPermission(request, replyProxy)
            "notifications.openSettings" -> handleNotificationOpenSettings(request, replyProxy)
            else -> {
                sendResponse(
                    BridgeResponse(
                        id = request.id,
                        success = false,
                        error = BridgeError(
                            code = BridgeErrorCodes.E_METHOD_NOT_FOUND,
                            message = "Method '${request.method}' is not implemented in native bridge."
                        )
                    ),
                    replyProxy
                )
            }
        }
    }

    fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_android"
        } catch (_: Exception) {
            "unknown_android"
        }
    }

    fun triggerGoogleSignIn(onComplete: ((Boolean) -> Unit)? = null) {
        val dummyRequestId = "native_${System.currentTimeMillis()}"
        val request = BridgeRequest(id = dummyRequestId, method = "auth.googleSignIn")
        handleGoogleSignIn(request, replyProxy = null, onComplete = onComplete)
    }

    // =========================================================================
    // Foreground Study Timer Handlers (Phase 5)
    // =========================================================================

    private fun handleTimerStart(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val params = try {
            request.params?.let { json.decodeFromJsonElement<StartTimerParams>(it) } ?: StartTimerParams()
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse StartTimerParams, falling back to default", e)
            StartTimerParams()
        }

        // GUARDRAIL: Refuse to resurrect a session that was already finished natively in the notification
        if (timerStateStore.isSessionTombstoned(params.idempotencyKey)) {
            Log.w(tag, "Refusing to start/resurrect tombstoned timer session: ${params.idempotencyKey}")
            val eventPayload = buildJsonObject {
                put("action", "finish")
                put("source", "native_tombstone")
                put("idempotencyKey", params.idempotencyKey)
            }
            sendEvent("timer.actionTriggered", eventPayload)

            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = false,
                    error = BridgeError(
                        code = "E_SESSION_TOMBSTONED",
                        message = "Timer session ${params.idempotencyKey} was already finished natively."
                    )
                ),
                replyProxy
            )
            return
        }

        val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
            action = TimerForegroundService.ACTION_START
            putExtra(TimerForegroundService.EXTRA_SUBJECT_ID, params.subjectId)
            putExtra(TimerForegroundService.EXTRA_SUBJECT_NAME, params.subjectName ?: "")
            putExtra(TimerForegroundService.EXTRA_TOPIC, params.topic)
            putExtra(TimerForegroundService.EXTRA_NOTES, params.notes ?: "")
            putExtra(TimerForegroundService.EXTRA_MODE, params.mode)
            putExtra(TimerForegroundService.EXTRA_TARGET_DURATION, params.targetDurationSeconds.toLong())
            putExtra(TimerForegroundService.EXTRA_IDEMPOTENCY_KEY, params.idempotencyKey)
            putExtra(TimerForegroundService.EXTRA_ACCUMULATED_SECONDS, params.accumulatedSeconds)
            putExtra(TimerForegroundService.EXTRA_PHASE, params.phase)
        }

        // Request runtime notification permission on Android 13+ if not already granted
        (activityProvider?.invoke() as? MainActivity)?.requestNotificationPermissionIfNeeded()

        try {
            Log.i(tag, "Starting TimerForegroundService: mode=${params.mode}, duration=${params.targetDurationSeconds}s, topic=${params.topic}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start TimerForegroundService", e)
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = false,
                    error = BridgeError(
                        code = BridgeErrorCodes.E_SERVICE_UNAVAILABLE,
                        message = "Failed to start native timer service: ${e.message}"
                    )
                ),
                replyProxy
            )
            return
        }

        val nowIso = Instant.now().toString()
        val result = buildJsonObject {
            put("started", true)
            put("status", "running")
            put("timer", buildJsonObject {
                put("status", "running")
                put("startedAt", nowIso)
                put("accumulatedSeconds", 0)
            })
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = result
            ),
            replyProxy
        )
    }

    private fun handleTimerPause(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val service = TimerForegroundService.instance
        val accumulated = service?.getElapsedSeconds() ?: 0L

        if (service != null) {
            service.pause()
        } else {
            val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
                action = TimerForegroundService.ACTION_PAUSE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to route ACTION_PAUSE to service", e)
            }
        }

        val nowIso = Instant.now().toString()
        val result = buildJsonObject {
            put("paused", true)
            put("status", "paused")
            put("accumulatedSeconds", accumulated)
            put("pausedAt", nowIso)
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = result
            ),
            replyProxy
        )
    }

    private fun handleTimerResume(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val service = TimerForegroundService.instance
        if (service != null) {
            service.resume()
        } else {
            val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
                action = TimerForegroundService.ACTION_RESUME
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to route ACTION_RESUME to service", e)
            }
        }

        val nowIso = Instant.now().toString()
        val result = buildJsonObject {
            put("resumed", true)
            put("status", "running")
            put("lastResumedAt", nowIso)
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = result
            ),
            replyProxy
        )
    }

    private fun handleTimerRemoteSync(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val params = try {
            request.params?.let { json.decodeFromJsonElement<RemoteTimerSyncParams>(it) }
        } catch (_: Exception) {
            null
        }

        if (params != null) {
            val accumulated = params.accumulatedSeconds ?: (params.remainingMs?.let { 0L } ?: -1L)
            TimerForegroundService.dispatchRemoteSync(
                context = context,
                action = params.action,
                accumulatedSeconds = accumulated,
                updatedAt = params.updatedAt ?: System.currentTimeMillis(),
                originDeviceId = params.originDeviceId.orEmpty(),
                expectedIdempotencyKey = params.idempotencyKey.orEmpty()
            )
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = buildJsonObject { put("synced", true) }
            ),
            replyProxy
        )
    }

    private fun handleTimerStop(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val params = try {
            request.params?.let { json.decodeFromJsonElement<StopTimerParams>(it) }
        } catch (_: Exception) {
            null
        }

        val service = TimerForegroundService.instance
        val finalSeconds = service?.getElapsedSeconds() ?: 0L
        val isDiscard = params?.discard == true

        var queuedOffline = false
        var sessionId: String? = null

        if (service != null) {
            if (!isDiscard && finalSeconds > 0) {
                val sId = if (service.idempotencyKey.isNotBlank()) {
                    service.idempotencyKey
                } else {
                    "session_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
                }
                sessionId = sId
                val sessionItem = StudySessionBatchItem(
                    clientSessionId = sId,
                    idempotencyKey = sId,
                    subjectId = service.subjectId.ifBlank { "00000000-0000-0000-0000-000000000000" },
                    startTime = service.startedAtIso ?: Instant.now().minusSeconds(finalSeconds).toString(),
                    endTime = Instant.now().toString(),
                    durationSeconds = finalSeconds.toInt(),
                    topic = service.topic.ifBlank { "General Study" },
                    notes = params?.notes ?: service.notes,
                    productivityRating = params?.productivityRating ?: 4,
                    sessionType = "timer"
                )

                scope.launch(Dispatchers.IO) {
                    try {
                        val database = ScholarPulseDatabase.getInstance(context)
                        val mutation = OfflineMutationEntity(
                            actionType = "SESSION",
                            payloadJson = json.encodeToString(sessionItem),
                            idempotencyKey = sessionItem.idempotencyKey
                        )
                        database.offlineMutationDao().enqueueMutation(mutation)
                        OfflineSyncWorker.enqueueImmediateSync(context)
                        Log.d(tag, "Enqueued completed study session into Room offline mutations: $sId")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to persist completed study session", e)
                    }
                }
                queuedOffline = true
            }

            if (service.idempotencyKey.isNotBlank()) {
                timerStateStore.markSessionFinishedNatively(
                    idempotencyKey = service.idempotencyKey,
                    subjectId = service.subjectId,
                    topic = service.topic,
                    durationSeconds = finalSeconds
                )
            }

            if (isDiscard) {
                service.discardSession()
            } else {
                service.finishSession()
            }
        } else {
            val action = if (isDiscard) {
                TimerForegroundService.ACTION_DISCARD
            } else {
                TimerForegroundService.ACTION_FINISH
            }
            val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
                this.action = action
            }
            try {
                context.startService(serviceIntent)
            } catch (_: Exception) {}
        }

        val result = buildJsonObject {
            put("stopped", true)
            put("status", "idle")
            put("finalDurationSeconds", finalSeconds)
            put("queuedOffline", queuedOffline)
            if (sessionId != null) {
                put("sessionId", sessionId)
            }
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = result
            ),
            replyProxy
        )
    }

    private fun handleTimerSkipPhase(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val service = TimerForegroundService.instance
        if (service != null) {
            service.skipPhase()
            val prevPhase = when (service.previousPhase) {
                PomodoroPhase.FOCUS -> "focus"
                PomodoroPhase.SHORT_BREAK -> "short_break"
                PomodoroPhase.LONG_BREAK -> "long_break"
            }
            val newPhase = when (service.phase) {
                PomodoroPhase.FOCUS -> "focus"
                PomodoroPhase.SHORT_BREAK -> "short_break"
                PomodoroPhase.LONG_BREAK -> "long_break"
            }
            val targetSeconds = service.targetDurationSeconds

            val result = buildJsonObject {
                put("success", true)
                put("previousPhase", prevPhase)
                put("newPhase", newPhase)
                put("targetDurationSeconds", targetSeconds)
            }
            sendResponse(BridgeResponse(id = request.id, success = true, result = result), replyProxy)
        } else {
            val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
                action = TimerForegroundService.ACTION_SKIP_PHASE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (_: Exception) {}

            val result = buildJsonObject {
                put("success", true)
                put("previousPhase", "focus")
                put("newPhase", "short_break")
                put("targetDurationSeconds", 300)
            }
            sendResponse(BridgeResponse(id = request.id, success = true, result = result), replyProxy)
        }
    }

    private fun handleTimerGetStatus(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val service = TimerForegroundService.instance
        val result = if (service != null) {
            val statusStr = when (service.status) {
                TimerStatus.RUNNING -> "running"
                TimerStatus.BREAK -> "running"
                TimerStatus.PAUSED -> "paused"
                TimerStatus.IDLE -> "idle"
            }
            val phaseStr = when (service.phase) {
                PomodoroPhase.FOCUS -> "focus"
                PomodoroPhase.SHORT_BREAK -> "short_break"
                PomodoroPhase.LONG_BREAK -> "long_break"
            }
            val modeStr = when (service.mode) {
                TimerMode.POMODORO -> "pomodoro"
                TimerMode.STOPWATCH -> "stopwatch"
            }

            buildJsonObject {
                put("status", statusStr)
                put("phase", phaseStr)
                put("mode", modeStr)
                put("subjectId", service.subjectId)
                put("subjectName", service.subjectName)
                put("topic", service.topic)
                put("elapsedSeconds", service.getElapsedSeconds())
                put("remainingSeconds", service.getRemainingSeconds())
                put("targetDurationSeconds", service.targetDurationSeconds)
                put("completedPomodoros", service.completedPomodoros)
                put("startedAt", service.startedAtIso)
                put("lastResumedAt", service.lastResumedAtIso)
            }
        } else {
            buildJsonObject {
                put("status", "idle")
                put("phase", "focus")
                put("mode", "pomodoro")
                put("subjectId", "")
                put("topic", "")
                put("elapsedSeconds", 0)
                put("remainingSeconds", 0)
                put("targetDurationSeconds", 1500)
                put("completedPomodoros", 0)
            }
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = result
            ),
            replyProxy
        )
    }

    private fun handleTimerGetSyncState(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val rawJson = timerStateStore.getSyncStateJson()
        val result = try {
            json.parseToJsonElement(rawJson)
        } catch (_: Exception) {
            buildJsonObject {}
        }
        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = result
            ),
            replyProxy
        )
    }

    private fun handleTimerAckReview(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val key = try {
            val paramsObj = request.params as? kotlinx.serialization.json.JsonObject
            paramsObj?.get("idempotencyKey")?.let {
                if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
            }
        } catch (_: Exception) {
            null
        }
        timerStateStore.clearPendingReview(key)
        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = buildJsonObject { put("acknowledged", true) }
            ),
            replyProxy
        )
    }

    // =========================================================================
    // Native PCM Audio Synthesis Handler (Phase 5)
    // =========================================================================

    private fun handleAudioPlayChime(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val type = try {
            request.params?.let { json.decodeFromJsonElement<AudioChimeParams>(it).type } ?: "focus_end"
        } catch (_: Exception) {
            "focus_end"
        }

        NativeAudioEngine.playChime(context, type)

        val result = buildJsonObject {
            put("played", true)
            put("type", type)
        }
        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = result
            ),
            replyProxy
        )
    }

    // =========================================================================
    // Native Google Sign-In & Cookie Session Handlers
    // =========================================================================

    private fun handleGoogleSignIn(
        request: BridgeRequest,
        replyProxy: JavaScriptReplyProxy?,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val activity = activityProvider?.invoke()
        if (activity == null) {
            Log.e(tag, "Google Sign-In failed: Activity context unavailable")
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = false,
                    error = BridgeError(
                        code = BridgeErrorCodes.E_SERVICE_UNAVAILABLE,
                        message = "Activity context is not available for Google Sign-In"
                    )
                ),
                replyProxy
            )
            onComplete?.invoke(false)
            return
        }

        if (isAuthInProgress) {
            Log.w(tag, "Google Sign-In already in progress. Ignoring duplicate trigger.")
            return
        }
        isAuthInProgress = true

        val helper = credentialManagerHelper ?: CredentialManagerHelper(activity)
        val cookieManager = cookieSessionManager ?: CookieSessionManager(context)

        scope.launch {
            try {
                Log.e(tag, "STEP A: Initiating CredentialManagerHelper.signIn on Activity")
                when (val signInResult = helper.signIn(activity)) {
                    is GoogleSignInResult.Failure -> {
                        Log.e(tag, "STEP B FAIL: Google Sign-In cancelled or failed: ${signInResult.errorCode} - ${signInResult.message}")
                        sendResponse(
                            BridgeResponse(
                                id = request.id,
                                success = false,
                                error = BridgeError(
                                    code = signInResult.errorCode,
                                    message = signInResult.message
                                )
                            ),
                            replyProxy
                        )
                        // Show brief Toast on device and reload page to reset React button state
                        mainHandler.post {
                            try {
                                android.widget.Toast.makeText(
                                    context,
                                    "Google Sign-In: ${signInResult.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                webViewRef?.reload()
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to reload webview after auth failure", e)
                            }
                        }
                        onComplete?.invoke(false)
                    }
                    is GoogleSignInResult.Success -> {
                        Log.e(tag, "STEP B SUCCESS: Google ID token obtained! Exchanging with backend /api/auth/google-native")
                        val deviceId = getDeviceId()
                        when (val exchangeResult = tokenExchanger.exchangeToken(
                            idToken = signInResult.idToken,
                            deviceId = deviceId,
                            deviceModel = Build.MODEL,
                            appVersion = BuildConfig.VERSION_NAME
                        )) {
                            is TokenExchangeResult.Failure -> {
                                Log.e(tag, "STEP C FAIL: Backend token exchange failed: ${exchangeResult.errorCode} - ${exchangeResult.message}")
                                sendResponse(
                                    BridgeResponse(
                                        id = request.id,
                                        success = false,
                                        error = BridgeError(
                                            code = exchangeResult.errorCode,
                                            message = exchangeResult.message
                                        )
                                    ),
                                    replyProxy
                                )
                                mainHandler.post {
                                    try {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Login Error: ${exchangeResult.message}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                        webViewRef?.reload()
                                    } catch (_: Exception) {}
                                }
                                onComplete?.invoke(false)
                            }
                            is TokenExchangeResult.Success -> {
                                Log.e(tag, "STEP C SUCCESS: Backend token exchange succeeded! Syncing session cookie into CookieManager.")
                                // 1. Synchronize cookie into Android CookieManager
                                cookieManager.syncSessionCookie(
                                    sessionToken = exchangeResult.sessionToken,
                                    rawHeader = exchangeResult.rawCookieHeader
                                )

                                // Auto-register FCM device token for authenticated user
                                AppFirebaseMessagingService.getCachedToken(context)?.let { fcmToken ->
                                    scope.launch(Dispatchers.IO) {
                                        AppFirebaseMessagingService.registerDeviceToken(context, fcmToken)
                                    }
                                }

                                // 2. Respond to the RPC request
                                val responseDto = GoogleSignInResponseDto(
                                    authenticated = true,
                                    sessionToken = exchangeResult.sessionToken,
                                    user = exchangeResult.user
                                )
                                sendResponse(
                                    BridgeResponse(
                                        id = request.id,
                                        success = true,
                                        result = json.encodeToJsonElement(responseDto)
                                    ),
                                    replyProxy
                                )

                                // 3. Emit unsolicited event auth.sessionChanged
                                val sessionEventPayload = buildJsonObject {
                                    put("authenticated", true)
                                    put("user", json.encodeToJsonElement(exchangeResult.user))
                                }
                                sendEvent("auth.sessionChanged", sessionEventPayload)

                                // 4. Navigate WebView to root URL to render authenticated dashboard
                                mainHandler.postDelayed({
                                    try {
                                        Log.e(tag, "Navigating WebView to ${BuildConfig.WEB_APP_URL} to render dashboard")
                                        val welcomeName = exchangeResult.user?.name ?: "Student"
                                        android.widget.Toast.makeText(
                                            context,
                                            "Welcome back, $welcomeName!",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        webViewRef?.loadUrl(BuildConfig.WEB_APP_URL)
                                    } catch (e: Exception) {
                                        Log.e(tag, "Failed to load dashboard URL after sign-in", e)
                                    }
                                }, 300L)

                                onComplete?.invoke(true)
                            }
                        }
                    }
                }
            } finally {
                isAuthInProgress = false
            }
        }
    }

    private fun handleSignOut(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val cookieManager = cookieSessionManager ?: CookieSessionManager(context)
        scope.launch {
            Log.d(tag, "Signing out: clearing cookies and CredentialManager state")
            cookieManager.clearSession()

            // 1. Respond to RPC caller
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = true,
                    result = json.encodeToJsonElement(mapOf("success" to true))
                ),
                replyProxy
            )

            // 2. Emit unsolicited event auth.sessionChanged with authenticated = false
            val sessionEventPayload = buildJsonObject {
                put("authenticated", false)
                put("user", JsonNull)
            }
            sendEvent("auth.sessionChanged", sessionEventPayload)

            // 3. Reload WebView on main thread
            mainHandler.postDelayed({
                try {
                    webViewRef?.reload()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to reload WebView after sign-out", e)
                }
            }, 250L)
        }
    }

    private fun handleSyncSessionCookie(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val params = try {
            request.params?.let { json.decodeFromJsonElement<SyncSessionParams>(it) }
        } catch (e: Exception) {
            Log.w(tag, "Malformed SyncSessionParams", e)
            null
        }

        val token = params?.getEffectiveToken()
        if (token == null) {
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = false,
                    error = BridgeError(
                        code = BridgeErrorCodes.E_INVALID_PARAMS,
                        message = "Missing or empty 'token' / 'sessionToken' in auth.syncSessionCookie"
                    )
                ),
                replyProxy
            )
            return
        }

        val cookieManager = cookieSessionManager ?: CookieSessionManager(context)
        cookieManager.syncSessionCookie(token, params.domain)

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = json.encodeToJsonElement(mapOf("synced" to true))
            ),
            replyProxy
        )
    }

    private fun handleGetDeviceInfo(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val deviceId = getDeviceId()

        val hasNotificationPermission = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: true
        } else {
            true
        }

        val result = DeviceInfoResult(
            platform = "android",
            osVersion = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            deviceId = deviceId,
            fcmToken = AppFirebaseMessagingService.getCachedToken(context),
            hasNotificationPermission = hasNotificationPermission,
            hasExactAlarmPermission = hasExactAlarmPermission,
            safeAreaInsets = currentInsets
        )

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = json.encodeToJsonElement(result)
            ),
            replyProxy
        )
    }

    private fun handleTriggerHaptic(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val style = try {
            request.params?.let { json.decodeFromJsonElement<HapticParams>(it).style } ?: "light"
        } catch (_: Exception) {
            "light"
        }

        val success = NativeHapticsEngine.vibrate(context, style)

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = json.encodeToJsonElement(mapOf("success" to success, "triggered" to success, "style" to style))
            ),
            replyProxy
        )
    }

    private fun handleUpdateTheme(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val themeParam = try {
            request.params?.let { json.decodeFromJsonElement<UpdateThemeParams>(it).theme } ?: "system"
        } catch (_: Exception) {
            "system"
        }

        Log.i(tag, "handleUpdateTheme received request: themeParam=$themeParam")

        mainHandler.post {
            val isDark = themeParam == "dark"
            Log.i(tag, "Posting onThemeChange isDark=$isDark")
            onThemeChange?.invoke(isDark)
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = json.encodeToJsonElement(mapOf("theme" to themeParam))
            ),
            replyProxy
        )
    }

    private fun handleNotifyReady(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        mainHandler.post {
            onWebReady?.invoke()
        }

        sendResponse(
            BridgeResponse(
                id = request.id,
                success = true,
                result = json.encodeToJsonElement(mapOf("ready" to true))
            ),
            replyProxy
        )
    }

    private fun handleSyncScheduleAlarms(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val params = try {
            request.params?.let { json.decodeFromJsonElement<SyncScheduleAlarmsParams>(it) }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse SyncScheduleAlarmsParams", e)
            null
        }

        if (params == null) {
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = false,
                    error = BridgeError(
                        code = BridgeErrorCodes.E_INVALID_PARAMS,
                        message = "Missing or invalid 'schedules' payload for notifications.syncScheduleAlarms"
                    )
                ),
                replyProxy
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val database = ScholarPulseDatabase.getInstance(context)
                val scheduleEntities = params.schedules.map { item ->
                    ScheduleEntity(
                        id = item.id,
                        userId = "",
                        subjectId = item.subjectId,
                        dayOfWeek = item.dayOfWeek,
                        startTime = item.startTime,
                        endTime = item.endTime ?: "",
                        description = item.subjectName ?: item.description,
                        updatedAt = System.currentTimeMillis()
                    )
                }

                val scheduleDao = database.scheduleDao()
                scheduleDao.clearAll()
                if (scheduleEntities.isNotEmpty()) {
                    scheduleDao.upsertSchedules(scheduleEntities)
                }

                val armedCount = ScheduleAlarmScheduler.rearmAll(context, scheduleEntities)
                Log.i(tag, "notifications.syncScheduleAlarms: armed $armedCount exact alarms")

                val result = buildJsonObject {
                    put("success", true)
                    put("armedAlarmCount", armedCount)
                    put("scheduledCount", armedCount)
                }

                sendResponse(
                    BridgeResponse(
                        id = request.id,
                        success = true,
                        result = result
                    ),
                    replyProxy
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to sync schedule alarms", e)
                sendResponse(
                    BridgeResponse(
                        id = request.id,
                        success = false,
                        error = BridgeError(
                            code = BridgeErrorCodes.E_INTERNAL_ERROR,
                            message = "Failed to sync schedule alarms: ${e.message}"
                        )
                    ),
                    replyProxy
                )
            }
        }
    }

    private fun handleTriggerBatchSync(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        try {
            OfflineSyncWorker.enqueueImmediateSync(context)
            Log.d(tag, "sync.triggerBatch: immediate offline sync enqueued via WorkManager")

            val result = buildJsonObject {
                put("success", true)
                put("enqueued", true)
            }

            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = true,
                    result = result
                ),
                replyProxy
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to enqueue immediate batch sync", e)
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = false,
                    error = BridgeError(
                        code = BridgeErrorCodes.E_SERVICE_UNAVAILABLE,
                        message = "Failed to enqueue sync: ${e.message}"
                    )
                ),
                replyProxy
            )
        }
    }

    private fun handleGetFcmToken(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val cachedToken = AppFirebaseMessagingService.getCachedToken(context)
        if (!cachedToken.isNullOrBlank()) {
            val result = buildJsonObject {
                put("success", true)
                put("fcmToken", cachedToken)
            }
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = true,
                    result = result
                ),
                replyProxy
            )
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && !task.result.isNullOrBlank()) {
                    val token = task.result
                    AppFirebaseMessagingService.cacheToken(context, token)
                    scope.launch(Dispatchers.IO) {
                        AppFirebaseMessagingService.registerDeviceToken(context, token)
                    }

                    val result = buildJsonObject {
                        put("success", true)
                        put("fcmToken", token)
                    }
                    sendResponse(
                        BridgeResponse(
                            id = request.id,
                            success = true,
                            result = result
                        ),
                        replyProxy
                    )
                } else {
                    Log.w(tag, "FirebaseMessaging.getInstance().token failed", task.exception)
                    val result = buildJsonObject {
                        put("success", true)
                        put("fcmToken", "")
                    }
                    sendResponse(
                        BridgeResponse(
                            id = request.id,
                            success = true,
                            result = result
                        ),
                        replyProxy
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to retrieve FCM token: ${e.message}", e)
            val result = buildJsonObject {
                put("success", true)
                put("fcmToken", "")
            }
            sendResponse(
                BridgeResponse(
                    id = request.id,
                    success = true,
                    result = result
                ),
                replyProxy
            )
        }
    }

    private fun handleNotificationGetStatus(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        val result = buildJsonObject {
            put("success", true)
            put("granted", hasPermission)
            put("status", if (hasPermission) "GRANTED" else "DENIED")
        }
        sendResponse(BridgeResponse(id = request.id, success = true, result = result), replyProxy)
    }

    private fun handleNotificationRequestPermission(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        mainHandler.post {
            (activityProvider?.invoke() as? MainActivity)?.requestNotificationPermissionIfNeeded()
        }
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        val result = buildJsonObject {
            put("success", true)
            put("requested", true)
            put("granted", hasPermission)
        }
        sendResponse(BridgeResponse(id = request.id, success = true, result = result), replyProxy)
    }

    private fun handleNotificationOpenSettings(request: BridgeRequest, replyProxy: JavaScriptReplyProxy?) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            sendResponse(BridgeResponse(id = request.id, success = true, result = buildJsonObject { put("success", true) }), replyProxy)
        } catch (e: Exception) {
            Log.e(tag, "Failed to open notification settings", e)
            sendResponse(BridgeResponse(id = request.id, success = false, error = BridgeError(code = BridgeErrorCodes.E_INTERNAL_ERROR, message = e.message ?: "Failed to open settings")), replyProxy)
        }
    }

    fun sendResponse(response: BridgeResponse, replyProxy: JavaScriptReplyProxy? = null) {
        val payload = json.encodeToString(response)
        mainHandler.post {
            try {
                if (replyProxy != null) {
                    replyProxy.postMessage(payload)
                } else {
                    webViewRef?.evaluateJavascript(
                        "window.__SCHOLARPULSE_NATIVE_DISPATCHER__ && window.__SCHOLARPULSE_NATIVE_DISPATCHER__($payload);",
                        null
                    )
                }
            } catch (_: Exception) {
                // WebView might be navigating or destroyed
            }
        }
    }

    fun sendEvent(eventName: String, payload: JsonElement? = null) {
        val event = NativeEvent(
            event = eventName,
            payload = payload,
            timestamp = System.currentTimeMillis()
        )
        val eventJson = json.encodeToString(event)
        mainHandler.post {
            try {
                webViewRef?.evaluateJavascript(
                    "window.__SCHOLARPULSE_NATIVE_DISPATCHER__ && window.__SCHOLARPULSE_NATIVE_DISPATCHER__($eventJson);",
                    null
                )
            } catch (_: Exception) {}
        }
    }
}
