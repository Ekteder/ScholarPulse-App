package com.scholarpulse.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test verifying timer tombstone lifecycle, anti-resurrection rules,
 * and state sync payload formatting.
 */
class TimerTombstoneSyncTest {

    class MockTimerStateStore {
        private var lastFinishedIdempotencyKey: String? = null
        private var pendingReviewJson: String? = null

        fun markSessionFinishedNatively(
            idempotencyKey: String,
            subjectId: String,
            topic: String,
            durationSeconds: Long,
            finishedAtEpochMs: Long = 1700000000000L
        ) {
            if (idempotencyKey.isBlank()) return
            val reviewPayload = buildJsonObject {
                put("idempotencyKey", idempotencyKey)
                put("subjectId", subjectId)
                put("topic", topic)
                put("durationSeconds", durationSeconds)
                put("finishedAt", finishedAtEpochMs)
            }.toString()

            lastFinishedIdempotencyKey = idempotencyKey
            pendingReviewJson = reviewPayload
        }

        fun isSessionTombstoned(idempotencyKey: String?): Boolean {
            if (idempotencyKey.isNullOrBlank()) return false
            return lastFinishedIdempotencyKey == idempotencyKey
        }

        fun clearPendingReview(idempotencyKey: String?) {
            pendingReviewJson = null
        }

        fun getSyncStateJson(): String {
            return buildJsonObject {
                if (lastFinishedIdempotencyKey != null) {
                    put("lastFinishedIdempotencyKey", lastFinishedIdempotencyKey!!)
                }
                if (pendingReviewJson != null) {
                    put("pendingReview", pendingReviewJson!!)
                }
            }.toString()
        }
    }

    @Test
    fun testTombstonePreventsResurrection() {
        val store = MockTimerStateStore()
        val sessionKey = "session_test_uuid_12345"

        // Initially not tombstoned
        assertFalse(store.isSessionTombstoned(sessionKey))

        // Finished natively in notification
        store.markSessionFinishedNatively(
            idempotencyKey = sessionKey,
            subjectId = "math-101",
            topic = "Calculus Derivatives",
            durationSeconds = 1500L
        )

        // Must be tombstoned now
        assertTrue(store.isSessionTombstoned(sessionKey))

        // Different session should not be tombstoned
        assertFalse(store.isSessionTombstoned("session_test_uuid_99999"))
    }

    @Test
    fun testSyncStatePayloadFormat() {
        val store = MockTimerStateStore()
        val sessionKey = "session_uuid_sync_test"

        store.markSessionFinishedNatively(
            idempotencyKey = sessionKey,
            subjectId = "chem-201",
            topic = "Organic Reactions",
            durationSeconds = 1800L
        )

        val syncJson = store.getSyncStateJson()
        val parsed = Json.parseToJsonElement(syncJson).jsonObject

        assertEquals(sessionKey, parsed["lastFinishedIdempotencyKey"]?.jsonPrimitive?.content)
        val pendingReviewStr = parsed["pendingReview"]?.jsonPrimitive?.content
        assertNotNull(pendingReviewStr)

        val reviewObj = Json.parseToJsonElement(pendingReviewStr!!).jsonObject
        assertEquals(sessionKey, reviewObj["idempotencyKey"]?.jsonPrimitive?.content)
        assertEquals("chem-201", reviewObj["subjectId"]?.jsonPrimitive?.content)
        assertEquals(1800L, reviewObj["durationSeconds"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun testPendingReviewCleared() {
        val store = MockTimerStateStore()
        val sessionKey = "session_to_clear"

        store.markSessionFinishedNatively(
            idempotencyKey = sessionKey,
            subjectId = "physics-101",
            topic = "Thermodynamics",
            durationSeconds = 1200L
        )

        // Clear review
        store.clearPendingReview(sessionKey)

        val syncJson = store.getSyncStateJson()
        val parsed = Json.parseToJsonElement(syncJson).jsonObject

        assertEquals(sessionKey, parsed["lastFinishedIdempotencyKey"]?.jsonPrimitive?.content)
        assertNull(parsed["pendingReview"])
    }
}
