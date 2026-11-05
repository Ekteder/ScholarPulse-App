package com.scholarpulse.app

import com.scholarpulse.app.workers.ActiveTimerBatchItem
import com.scholarpulse.app.workers.BatchSyncPayload
import com.scholarpulse.app.workers.RevisionBatchItem
import com.scholarpulse.app.workers.StudySessionBatchItem
import com.scholarpulse.app.workers.TaskBatchItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test verifying JSON serialization and deserialization of BatchSyncPayload models.
 * Confirms exact field naming contract compliance with v2/src/lib/validations/sync.ts.
 */
class BatchSyncPayloadTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Test
    fun testStudySessionBatchItemSerializationContract() {
        val sessionItem = StudySessionBatchItem(
            clientSessionId = "client-uuid-1",
            idempotencyKey = "timer_1730790000_abc123_final",
            subjectId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
            startTime = "2026-11-05T08:00:00.000Z",
            endTime = "2026-11-05T08:25:00.000Z",
            durationSeconds = 1500,
            topic = "Organic Chemistry",
            notes = "Completed reaction mechanisms review",
            productivityRating = 5,
            sessionType = "timer"
        )

        val encoded = json.encodeToString(sessionItem)
        val decoded = json.decodeFromString<StudySessionBatchItem>(encoded)

        assertEquals(sessionItem, decoded)

        // Verify JSON fields match Zod StudySessionBatchItemSchema
        val parsedJson = json.parseToJsonElement(encoded).jsonObject
        assertEquals("client-uuid-1", parsedJson["clientSessionId"]?.jsonPrimitive?.content)
        assertEquals("timer_1730790000_abc123_final", parsedJson["idempotencyKey"]?.jsonPrimitive?.content)
        assertEquals("a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d", parsedJson["subjectId"]?.jsonPrimitive?.content)
        assertEquals("2026-11-05T08:00:00.000Z", parsedJson["startTime"]?.jsonPrimitive?.content)
        assertEquals("2026-11-05T08:25:00.000Z", parsedJson["endTime"]?.jsonPrimitive?.content)
        assertEquals("1500", parsedJson["durationSeconds"]?.jsonPrimitive?.content)
        assertEquals("Organic Chemistry", parsedJson["topic"]?.jsonPrimitive?.content)
        assertEquals("5", parsedJson["productivityRating"]?.jsonPrimitive?.content)
        assertEquals("timer", parsedJson["sessionType"]?.jsonPrimitive?.content)
    }

    @Test
    fun testTaskBatchItemSerializationContract() {
        val taskItem = TaskBatchItem(
            id = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            clientTaskId = "task-local-1",
            idempotencyKey = "task_sync_key_101",
            subjectId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
            title = "Solve Exercise 4.2",
            description = "Problems 1 through 15",
            dueDate = "2026-11-06T23:59:59.000Z",
            priority = "high",
            status = "completed",
            completedAt = "2026-11-05T10:00:00.000Z",
            version = 2
        )

        val encoded = json.encodeToString(taskItem)
        val decoded = json.decodeFromString<TaskBatchItem>(encoded)

        assertEquals(taskItem, decoded)

        val parsedJson = json.parseToJsonElement(encoded).jsonObject
        assertEquals("Solve Exercise 4.2", parsedJson["title"]?.jsonPrimitive?.content)
        assertEquals("high", parsedJson["priority"]?.jsonPrimitive?.content)
        assertEquals("completed", parsedJson["status"]?.jsonPrimitive?.content)
        assertEquals("2", parsedJson["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun testRevisionBatchItemSerializationContract() {
        val revisionItem = RevisionBatchItem(
            subjectId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
            topicId = "b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e",
            category = "cq_solve",
            activityDate = "2026-11-05",
            isCompleted = true,
            bookName = "Halliday & Resnick Fundamentals of Physics",
            pagesRead = 20,
            questionsSolved = 12,
            correctCount = 11,
            notes = "Kinematics chapter",
            clientUpdatedAt = "2026-11-05T10:15:00.000Z"
        )

        val encoded = json.encodeToString(revisionItem)
        val decoded = json.decodeFromString<RevisionBatchItem>(encoded)

        assertEquals(revisionItem, decoded)

        val parsedJson = json.parseToJsonElement(encoded).jsonObject
        assertEquals("cq_solve", parsedJson["category"]?.jsonPrimitive?.content)
        assertEquals("2026-11-05", parsedJson["activityDate"]?.jsonPrimitive?.content)
        assertEquals("12", parsedJson["questionsSolved"]?.jsonPrimitive?.content)
        assertEquals("11", parsedJson["correctCount"]?.jsonPrimitive?.content)
    }

    @Test
    fun testActiveTimerBatchItemSerializationContract() {
        val activeTimerItem = ActiveTimerBatchItem(
            idempotencyKey = "active_timer_sync_999",
            subjectId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
            topic = "Thermodynamics",
            notes = "Carnot cycle entropy derivations",
            mode = "pomodoro",
            phase = "focus",
            status = "running",
            startedAt = "2026-11-05T09:00:00.000Z",
            lastResumedAt = "2026-11-05T09:00:00.000Z",
            accumulatedSeconds = 600,
            totalFocusSeconds = 600,
            targetDurationSeconds = 1500,
            completedPomodoros = 1,
            stateVersion = 1
        )

        val encoded = json.encodeToString(activeTimerItem)
        val decoded = json.decodeFromString<ActiveTimerBatchItem>(encoded)

        assertEquals(activeTimerItem, decoded)

        val parsedJson = json.parseToJsonElement(encoded).jsonObject
        assertEquals("pomodoro", parsedJson["mode"]?.jsonPrimitive?.content)
        assertEquals("focus", parsedJson["phase"]?.jsonPrimitive?.content)
        assertEquals("running", parsedJson["status"]?.jsonPrimitive?.content)
        assertEquals("600", parsedJson["accumulatedSeconds"]?.jsonPrimitive?.content)
        assertEquals("1500", parsedJson["targetDurationSeconds"]?.jsonPrimitive?.content)
    }

    @Test
    fun testFullBatchSyncPayloadRootStructure() {
        val payload = BatchSyncPayload(
            clientDeviceId = "android-device-pixel8-abc12345",
            clientTimestamp = "2026-11-05T10:30:00.000Z",
            studySessions = listOf(
                StudySessionBatchItem(
                    idempotencyKey = "session-1",
                    subjectId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
                    startTime = "2026-11-05T09:00:00Z",
                    endTime = "2026-11-05T09:25:00Z"
                )
            ),
            tasks = listOf(
                TaskBatchItem(
                    subjectId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
                    title = "Test Task",
                    dueDate = "2026-11-06T00:00:00Z"
                )
            ),
            revisionRecords = emptyList(),
            activeTimer = null
        )

        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<BatchSyncPayload>(encoded)

        assertEquals(payload.clientDeviceId, decoded.clientDeviceId)
        assertEquals(payload.clientTimestamp, decoded.clientTimestamp)
        assertEquals(1, decoded.studySessions.size)
        assertEquals(1, decoded.tasks.size)
        assertTrue(decoded.revisionRecords.isEmpty())
        assertEquals(null, decoded.activeTimer)

        val parsedJson = json.parseToJsonElement(encoded).jsonObject
        assertTrue(parsedJson.containsKey("clientDeviceId"))
        assertTrue(parsedJson.containsKey("clientTimestamp"))
        assertTrue(parsedJson.containsKey("studySessions"))
        assertTrue(parsedJson.containsKey("tasks"))
        assertTrue(parsedJson.containsKey("revisionRecords"))
    }
}
