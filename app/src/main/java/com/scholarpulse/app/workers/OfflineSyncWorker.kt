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

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

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
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = ScholarPulseDatabase.getInstance(applicationContext)
        val mutationDao = database.offlineMutationDao()

        val pendingMutations = try {
            mutationDao.getPendingMutations()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read pending mutations from Room DB", e)
            return@withContext Result.retry()
        }

        if (pendingMutations.isEmpty()) {
            Log.d(TAG, "No pending mutations found in queue. Sync completed.")
            return@withContext Result.success()
        }

        val cookieManager = CookieSessionManager(applicationContext)
        val sessionToken = cookieManager.getSessionToken()

        if (sessionToken.isNullOrBlank()) {
            Log.w(TAG, "No active session cookie found. Postponing offline sync until authenticated.")
            return@withContext Result.retry()
        }

        val mutationIds = pendingMutations.map { it.id }
        mutationDao.markStatuses(mutationIds, "SYNCING")

        val clientDeviceId = getDeviceId(applicationContext)
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
                mutationDao.updateStatus(mutation.id, "FAILED", "Deserialization error: ${e.message}")
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

        val syncSuccess = uploadBatch(primaryUrl, payload, sessionToken) ||
                (fallbackUrl != null && uploadBatch(fallbackUrl, payload, sessionToken))

        if (syncSuccess) {
            Log.i(TAG, "Batch sync succeeded. Removing ${mutationIds.size} synced mutations.")
            mutationDao.deleteMutations(mutationIds)

            // Emit unsolicited sync.completed event to WebView if available
            val syncStatsResult = buildJsonObject {
                put("syncedMutationsCount", mutationIds.size)
                put("studySessionsCount", studySessions.size)
                put("tasksCount", tasks.size)
                put("revisionRecordsCount", revisionRecords.size)
                put("timestamp", clientTimestamp)
            }
            NativeBridgeDispatcher.activeDispatcher?.sendEvent("sync.completed", syncStatsResult)
            NativeBridgeDispatcher.activeDispatcher?.sendEvent("workspace.refresh", null)

            Result.success()
        } else {
            Log.w(TAG, "Batch sync failed. Reverting ${mutationIds.size} mutations to FAILED for retry.")
            for (id in mutationIds) {
                mutationDao.updateStatus(id, "FAILED", "Network request failed")
            }
            Result.retry()
        }
    }

    private fun uploadBatch(
        urlString: String,
        payload: BatchSyncPayload,
        sessionToken: String
    ): Boolean {
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
            }

            val requestJson = json.encodeToString(payload)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestJson)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val isSuccess = responseCode in 200..299

            if (isSuccess) {
                val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                Log.d(TAG, "Upload to $urlString returned $responseCode: $responseBody")
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, Charsets.UTF_8)).use {
                        it.readText()
                    }
                } catch (_: Exception) {
                    "No error body"
                }
                Log.e(TAG, "Upload to $urlString failed with HTTP $responseCode: $errorBody")
            }

            connection.disconnect()
            isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Exception during uploadBatch to $urlString: ${e.message}", e)
            false
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
