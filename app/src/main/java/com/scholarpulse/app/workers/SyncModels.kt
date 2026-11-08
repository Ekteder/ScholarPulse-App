package com.scholarpulse.app.workers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StudySessionBatchItem(
    val clientSessionId: String = "",
    val idempotencyKey: String,
    val subjectId: String,
    val startTime: String,
    val endTime: String,
    val durationSeconds: Int? = null,
    val topic: String = "General Study",
    val notes: String = "",
    val productivityRating: Int = 4,
    val sessionType: String = "timer"
)

@Serializable
data class TaskBatchItem(
    val id: String? = null,
    val clientTaskId: String = "",
    val idempotencyKey: String? = null,
    val subjectId: String,
    val title: String,
    val description: String = "",
    val dueDate: String,
    val priority: String = "medium",
    val status: String = "pending",
    val completedAt: String? = null,
    val version: Int = 1
)

@Serializable
data class RevisionBatchItem(
    val subjectId: String,
    val topicId: String,
    val category: String,
    val activityDate: String? = null,
    val isCompleted: Boolean = true,
    val bookName: String? = null,
    val pagesRead: Int? = null,
    val questionsSolved: Int? = null,
    val correctCount: Int? = null,
    val notes: String? = null,
    val clientUpdatedAt: String? = null
)

@Serializable
data class ActiveTimerBatchItem(
    val idempotencyKey: String? = null,
    val subjectId: String,
    val topic: String = "Focused Study",
    val notes: String = "",
    val mode: String = "stopwatch",
    val phase: String = "focus",
    val status: String = "running",
    val startedAt: String,
    val lastResumedAt: String,
    val accumulatedSeconds: Int = 0,
    val totalFocusSeconds: Int = 0,
    val targetDurationSeconds: Int = 1500,
    val completedPomodoros: Int = 0,
    val stateVersion: Int = 1
)

@Serializable
data class BatchSyncPayload(
    val clientDeviceId: String,
    val clientTimestamp: String,
    val studySessions: List<StudySessionBatchItem> = emptyList(),
    val tasks: List<TaskBatchItem> = emptyList(),
    val revisionRecords: List<RevisionBatchItem> = emptyList(),
    val activeTimer: ActiveTimerBatchItem? = null
)

@Serializable
data class RejectedItem(
    val key: String,
    val reason: String
)

@Serializable
data class TimerReconciliation(
    val status: String = "none",
    val reason: String? = null,
    val serverTimer: JsonElement? = null
)

@Serializable
data class BatchSyncResponse(
    val success: Boolean = false,
    val protocol: Int? = null,
    val accepted: List<String> = emptyList(),
    val rejected: List<RejectedItem> = emptyList(),
    val timer: TimerReconciliation? = null
)
