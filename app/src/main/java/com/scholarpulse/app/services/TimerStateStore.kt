package com.scholarpulse.app.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Thread-safe persistent store for timer synchronization and tombstone lifecycle management.
 * Guarantees that sessions finished natively in the notification shade cannot be resurrected
 * by stale WebView React state upon app resume.
 */
class TimerStateStore(context: Context) {

    companion object {
        private const val TAG = "TimerStateStore"
        private const val PREFS_NAME = "scholarpulse_timer_sync_prefs"
        private const val KEY_LAST_FINISHED_IDEMPOTENCY_KEY = "last_finished_idempotency_key"
        private const val KEY_PENDING_REVIEW_JSON = "pending_review_json"
        private const val KEY_PENDING_REVIEW_IDEMPOTENCY_KEY = "pending_review_idempotency_key"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records a finished session tombstone and stores pending review metadata for the web app.
     */
    fun markSessionFinishedNatively(
        idempotencyKey: String,
        subjectId: String,
        topic: String,
        durationSeconds: Long,
        finishedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        if (idempotencyKey.isBlank()) return

        val reviewPayload = buildJsonObject {
            put("idempotencyKey", idempotencyKey)
            put("subjectId", subjectId)
            put("topic", topic)
            put("durationSeconds", durationSeconds)
            put("finishedAt", finishedAtEpochMs)
        }.toString()

        prefs.edit()
            .putString(KEY_LAST_FINISHED_IDEMPOTENCY_KEY, idempotencyKey)
            .putString(KEY_PENDING_REVIEW_JSON, reviewPayload)
            .putString(KEY_PENDING_REVIEW_IDEMPOTENCY_KEY, idempotencyKey)
            .apply()

        Log.i(TAG, "Tombstoned timer session: $idempotencyKey (duration: ${durationSeconds}s)")
    }

    /**
     * Returns whether the given idempotencyKey has been marked finished/tombstoned natively.
     */
    fun isSessionTombstoned(idempotencyKey: String?): Boolean {
        if (idempotencyKey.isNullOrBlank()) return false
        val lastFinished = prefs.getString(KEY_LAST_FINISHED_IDEMPOTENCY_KEY, null)
        return lastFinished == idempotencyKey
    }

    fun getLastFinishedIdempotencyKey(): String? {
        return prefs.getString(KEY_LAST_FINISHED_IDEMPOTENCY_KEY, null)
    }

    fun getPendingReview(): String? {
        return prefs.getString(KEY_PENDING_REVIEW_JSON, null)
    }

    fun clearPendingReview(idempotencyKey: String?) {
        val currentKey = prefs.getString(KEY_PENDING_REVIEW_IDEMPOTENCY_KEY, null)
        if (idempotencyKey == null || currentKey == idempotencyKey) {
            prefs.edit()
                .remove(KEY_PENDING_REVIEW_JSON)
                .remove(KEY_PENDING_REVIEW_IDEMPOTENCY_KEY)
                .apply()
            Log.d(TAG, "Cleared pending review for $idempotencyKey")
        }
    }

    /**
     * Synchronous JSON snapshot returned directly to WebView bridge.
     */
    fun getSyncStateJson(): String {
        val lastFinished = getLastFinishedIdempotencyKey()
        val pendingReview = getPendingReview()

        return buildJsonObject {
            if (lastFinished != null) {
                put("lastFinishedIdempotencyKey", lastFinished)
            }
            if (pendingReview != null) {
                put("pendingReview", pendingReview)
            }
        }.toString()
    }
}
