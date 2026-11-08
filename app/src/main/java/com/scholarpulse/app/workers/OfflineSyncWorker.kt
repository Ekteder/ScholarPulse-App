package com.scholarpulse.app.workers

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scholarpulse.app.BuildConfig
import com.scholarpulse.app.auth.CookieSessionManager
import com.scholarpulse.app.bridge.NativeBridgeDispatcher
import com.scholarpulse.app.db.OfflineMutationEntity
import com.scholarpulse.app.db.ScholarPulseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Android WorkManager CoroutineWorker responsible for background offline synchronization.
 * Reads pending offline mutations from Room SQLite, bundles them into a unified BatchSyncPayload,
 * and executes an idempotent HTTP POST to /api/sync/batch with Cookie authentication.
 */
class OfflineSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OfflineSyncWorker"
        const val PERIODIC_SYNC_WORK_NAME = "scholarpulse_periodic_offline_sync"
        const val IMMEDIATE_SYNC_WORK_NAME = "scholarpulse_immediate_offline_sync"

        private val syncMutex = Mutex()

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        private data class UploadResult(
            val isSuccess: Boolean,
            val httpCode: Int,
            val responseBody: String?,
            val errorMessage: String? = null
        )

        /**
         * Schedules 15-minute periodic background synchronization constrained to CONNECTED network.
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<OfflineSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.d(TAG, "Periodic sync scheduled (every 15m when connected)")
        }

        /**
         * Enqueues an immediate one-time sync job (e.g. after session completion or network reconnection).
         */
        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateRequest = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_SYNC_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                immediateRequest
            )
            Log.d(TAG, "Immediate sync job enqueued")
        }

        /**
         * Opportunistic fast-path direct sync that executes immediately on an IO dispatcher.
         * Used by BroadcastReceivers and Foreground Services to achieve sub-second real-time sync
         * without waiting for WorkManager OS job-scheduling latency.
         * Protected by syncMutex to prevent race conditions and duplicate concurrent batch uploads.
         */
        suspend fun performSync(context: Context): Boolean = withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val database = ScholarPulseDatabase.getInstance(context)
                val mutationDao = database.offlineMutationDao()

                val pendingMutations = try {
                    mutationDao.getPendingMutations()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read pending mutations from Room DB", e)
                    return@withLock false
                }

                if (pendingMutations.isEmpty()) {
                    Log.d(TAG, "No pending mutations found in queue. Sync completed.")
                    return@withLock true
                }

                val cookieManager = CookieSessionManager(context)
                val sessionToken = cookieManager.getSessionToken()

                if (sessionToken.isNullOrBlank()) {
                    Log.w(TAG, "No active session cookie found. Postponing offline sync until authenticated.")
                    return@withLock false
                }

                val mutationIds = pendingMutations.map { it.id }
                mutationDao.markStatuses(mutationIds, "SYNCING")

                val clientDeviceId = getDeviceId(context)
                val clientTimestamp = Instant.now().toString()

                val studySessions = mutableListOf<StudySessionBatchItem>()
                val tasks = mutableListOf<TaskBatchItem>()
                val revisionRecords = mutableListOf<RevisionBatchItem>()
                var activeTimer: ActiveTimerBatchItem? = null

                for (mutation in pendingMutations) {
                    try {
                        when (mutation.actionType.uppercase()) {
                            "SESSION", "STUDY_SESSION" -> {
                                val item = json.decodeFromString<StudySessionBatchItem>(mutation.payloadJson)
                                studySessions.add(item)
                            }
                            "TASK" -> {
                                val item = json.decodeFromString<TaskBatchItem>(mutation.payloadJson)
                                tasks.add(item)
                            }
                            "REVISION" -> {
                                val item = json.decodeFromString<RevisionBatchItem>(mutation.payloadJson)
                                revisionRecords.add(item)
                            }
                            "TIMER", "ACTIVE_TIMER" -> {
                                val item = json.decodeFromString<ActiveTimerBatchItem>(mutation.payloadJson)
                                activeTimer = item
                            }
                            else -> {
                                Log.w(TAG, "Unknown actionType '${mutation.actionType}' for mutation ${mutation.id}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to deserialize payload for mutation ${mutation.id}: ${e.message}", e)
                        mutationDao.updateStatus(mutation.id, "DEAD", "Deserialization error: ${e.message}")
                    }
                }

                val payload = BatchSyncPayload(
                    clientDeviceId = clientDeviceId,
                    clientTimestamp = clientTimestamp,
                    studySessions = studySessions,
                    tasks = tasks,
                    revisionRecords = revisionRecords,
                    activeTimer = activeTimer
                )

                val primaryUrl = "${BuildConfig.WEB_APP_URL.trimEnd('/')}/api/sync/batch"
                val fallbackUrl = if (BuildConfig.DEBUG && !BuildConfig.WEB_APP_URL.contains("10.0.2.2")) {
                    "http://10.0.2.2:3000/api/sync/batch"
                } else {
                    null
                }

                var uploadResult = uploadBatch(primaryUrl, payload, sessionToken)
                if (!uploadResult.isSuccess && fallbackUrl != null) {
                    Log.w(TAG, "Primary sync upload failed (HTTP ${uploadResult.httpCode}). Attempting fallback to $fallbackUrl")
                    uploadResult = uploadBatch(fallbackUrl, payload, sessionToken)
                }

                if (uploadResult.isSuccess) {
                    val responseJson = uploadResult.responseBody
                    var parsedResponse: BatchSyncResponse? = null
                    if (!responseJson.isNullOrBlank()) {
                        try {
                            parsedResponse = json.decodeFromString<BatchSyncResponse>(responseJson)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to deserialize BatchSyncResponse: ${e.message}")
                        }
                    }

                    if (parsedResponse != null && parsedResponse.protocol == 2) {
                        Log.i(TAG, "Processing Protocol 2 sync response. Accepted: ${parsedResponse.accepted.size}, Rejected: ${parsedResponse.rejected.size}")
                        val acceptedKeys = parsedResponse.accepted.toSet()
                        val rejectedMap = parsedResponse.rejected.associate { it.key to it.reason }

                        for (mutation in pendingMutations) {
                            val action = mutation.actionType.uppercase()
                            if (action in listOf("TIMER", "ACTIVE_TIMER")) {
                                val timerRecon = parsedResponse.timer
                                if (timerRecon != null) {
                                    when (timerRecon.status) {
                                        "applied", "server_ahead" -> {
                                            mutationDao.deleteMutation(mutation.id)
                                        }
                                        "rejected" -> {
                                            val reason = timerRecon.reason ?: "Timer update rejected by server"
                                            mutationDao.updateStatus(mutation.id, "DEAD", reason)
                                            Log.e(TAG, "Timer mutation ${mutation.id} permanently rejected by server: $reason")
                                        }
                                        else -> {
                                            mutationDao.updateStatus(mutation.id, "PENDING", "Timer not processed in batch")
                                        }
                                    }
                                } else {
                                    mutationDao.deleteMutation(mutation.id)
                                }
                            } else {
                                val key = extractKey(mutation)
                                if (acceptedKeys.contains(key)) {
                                    mutationDao.deleteMutation(mutation.id)
                                } else if (rejectedMap.containsKey(key)) {
                                    val reason = rejectedMap[key] ?: "Permanent server rejection"
                                    mutationDao.updateStatus(mutation.id, "DEAD", reason)
                                    Log.e(TAG, "Mutation ${mutation.id} (key=$key) permanently rejected by server: $reason")
                                } else {
                                    mutationDao.updateStatus(mutation.id, "PENDING", "Not acknowledged in batch")
                                }
                            }
                        }

                        // Handle timer reconciliation if server is ahead
                        if (parsedResponse.timer?.status == "server_ahead" && parsedResponse.timer.serverTimer != null) {
                            Log.i(TAG, "Server timer is ahead. Reconciling local state...")
                            NativeBridgeDispatcher.activeDispatcher?.sendEvent("timer.reconcile", parsedResponse.timer.serverTimer)
                        }
                    } else {
                        // Protocol 1 legacy fallback
                        Log.i(TAG, "Legacy sync succeeded. Removing ${mutationIds.size} synced mutations.")
                        mutationDao.deleteMutations(mutationIds)
                    }

                    // Emit unsolicited sync.completed & workspace.refresh events to WebView
                    val syncStatsResult = buildJsonObject {
                        put("syncedMutationsCount", mutationIds.size)
                        put("studySessionsCount", studySessions.size)
                        put("tasksCount", tasks.size)
                        put("revisionRecordsCount", revisionRecords.size)
                        put("timestamp", clientTimestamp)
                    }
                    NativeBridgeDispatcher.activeDispatcher?.sendEvent("sync.completed", syncStatsResult)
                    NativeBridgeDispatcher.activeDispatcher?.sendEvent("workspace.refresh", null)

                    true
                } else {
                    Log.w(TAG, "Batch sync failed (HTTP ${uploadResult.httpCode}). Reverting ${mutationIds.size} mutations to FAILED for retry.")
                    for (id in mutationIds) {
                        mutationDao.updateStatus(id, "FAILED", "Network request failed: HTTP ${uploadResult.httpCode}")
                    }
                    false
                }
            }
        }

        private fun uploadBatch(
            urlString: String,
            payload: BatchSyncPayload,
            sessionToken: String
        ): UploadResult {
            return try {
                val url = URL(urlString)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cookie", "sp_session=$sessionToken")
                    setRequestProperty("User-Agent", "ScholarPulseAndroid/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("X-Sync-Protocol", "2")
                }

                val requestJson = json.encodeToString(payload)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(requestJson)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val isSuccess = responseCode in 200..299

                val responseBody = if (isSuccess) {
                    BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                        it.readText()
                    }
                } else {
                    try {
                        BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, Charsets.UTF_8)).use {
                            it.readText()
                        }
                    } catch (_: Exception) {
                        null
                    }
                }

                if (isSuccess) {
                    Log.d(TAG, "Upload to $urlString returned $responseCode: $responseBody")
                } else {
                    Log.e(TAG, "Upload to $urlString failed with HTTP $responseCode: $responseBody")
                }

                connection.disconnect()
                UploadResult(isSuccess = isSuccess, httpCode = responseCode, responseBody = responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during uploadBatch to $urlString: ${e.message}", e)
                UploadResult(isSuccess = false, httpCode = -1, responseBody = null, errorMessage = e.message)
            }
        }

        private fun extractKey(mutation: OfflineMutationEntity): String {
            return when (mutation.actionType.uppercase()) {
                "SESSION", "STUDY_SESSION" -> {
                    mutation.idempotencyKey
                }
                "TASK" -> {
                    try {
                        val item = json.decodeFromString<TaskBatchItem>(mutation.payloadJson)
                        item.idempotencyKey ?: item.id ?: item.clientTaskId.ifBlank { null } ?: mutation.idempotencyKey
                    } catch (_: Exception) {
                        mutation.idempotencyKey
                    }
                }
                "REVISION" -> {
                    try {
                        val item = json.decodeFromString<RevisionBatchItem>(mutation.payloadJson)
                        "${item.topicId}:${item.category}"
                    } catch (_: Exception) {
                        mutation.idempotencyKey
                    }
                }
                else -> mutation.idempotencyKey
            }
        }

        private fun getDeviceId(context: Context): String {
            return try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android_device"
            } catch (_: Exception) {
                "android_device"
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val success = performSync(applicationContext)
        if (success) Result.success() else Result.retry()
    }
}
