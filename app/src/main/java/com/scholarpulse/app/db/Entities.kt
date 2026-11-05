package com.scholarpulse.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "active_timer")
data class ActiveTimerEntity(
    @PrimaryKey val userId: String,
    val subjectId: String,
    val topic: String,
    val notes: String? = null,
    val mode: String = "pomodoro",
    val targetDurationSeconds: Int = 1500,
    val accumulatedSeconds: Int = 0,
    val status: String = "running",
    val phase: String = "focus",
    val startedAt: Long = System.currentTimeMillis(),
    val lastResumedAt: Long = System.currentTimeMillis(),
    val completedPomodoros: Int = 0,
    val stateVersion: Int = 1,
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val color: String = "#3B82F6",
    val dailyTargetMinutes: Int = 0,
    val weeklyTargetMinutes: Int = 0,
    val isArchived: Boolean = false,
    val notes: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val subjectId: String,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String,
    val description: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val subjectId: String,
    val title: String,
    val description: String? = null,
    val dueDate: String,
    val priority: String = "medium",
    val status: String = "pending",
    val completedAt: String? = null,
    val idempotencyKey: String? = null,
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "revision_records")
data class RevisionRecordEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val subjectId: String,
    val topicId: String,
    val category: String,
    val activityDate: String? = null,
    val isCompleted: Boolean = true,
    val notes: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "offline_mutations",
    indices = [Index(value = ["idempotencyKey"], unique = true)]
)
data class OfflineMutationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val actionType: String,
    val payloadJson: String,
    val idempotencyKey: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val status: String = "PENDING",
    val errorMessage: String? = null
)
