package com.scholarpulse.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveTimerDao {
    @Query("SELECT * FROM active_timer WHERE userId = :userId LIMIT 1")
    suspend fun getActiveTimer(userId: String): ActiveTimerEntity?

    @Query("SELECT * FROM active_timer WHERE userId = :userId LIMIT 1")
    fun observeActiveTimer(userId: String): Flow<ActiveTimerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveTimer(timer: ActiveTimerEntity)

    @Query("DELETE FROM active_timer WHERE userId = :userId")
    suspend fun deleteActiveTimer(userId: String)

    @Query("DELETE FROM active_timer")
    suspend fun clearAll()
}

@Dao
interface SubjectDao {
    @Query("SELECT * FROM subjects WHERE userId = :userId AND isArchived = 0 ORDER BY name ASC")
    fun getSubjects(userId: String): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects WHERE userId = :userId AND isArchived = 0 ORDER BY name ASC")
    suspend fun getSubjectsSync(userId: String): List<SubjectEntity>

    @Query("SELECT * FROM subjects WHERE id = :id LIMIT 1")
    suspend fun getSubjectById(id: String): SubjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubjects(subjects: List<SubjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubject(subject: SubjectEntity)

    @Query("DELETE FROM subjects WHERE id = :id")
    suspend fun deleteSubject(id: String)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE userId = :userId ORDER BY dayOfWeek, startTime ASC")
    fun getSchedules(userId: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY dayOfWeek, startTime ASC")
    suspend fun getAllSchedulesSync(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE dayOfWeek = :dayOfWeek ORDER BY startTime ASC")
    suspend fun getSchedulesForDay(dayOfWeek: Int): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedules(schedules: List<ScheduleEntity>)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteSchedule(id: String)

    @Query("DELETE FROM schedules")
    suspend fun clearAll()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE userId = :userId ORDER BY dueDate ASC")
    fun getTasks(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)
}

@Dao
interface RevisionDao {
    @Query("SELECT * FROM revision_records WHERE userId = :userId ORDER BY activityDate DESC")
    fun getRevisionRecords(userId: String): Flow<List<RevisionRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRevisionRecords(records: List<RevisionRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRevisionRecord(record: RevisionRecordEntity)

    @Query("DELETE FROM revision_records WHERE id = :id")
    suspend fun deleteRevisionRecord(id: String)
}

@Dao
interface OfflineMutationDao {
    @Query("SELECT * FROM offline_mutations WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAtMs ASC")
    suspend fun getPendingMutations(): List<OfflineMutationEntity>

    @Query("SELECT * FROM offline_mutations WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAtMs ASC")
    fun observePendingMutations(): Flow<List<OfflineMutationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueMutation(mutation: OfflineMutationEntity)

    @Query("UPDATE offline_mutations SET status = :status, retryCount = retryCount + 1, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("UPDATE offline_mutations SET status = :status WHERE id IN (:ids)")
    suspend fun markStatuses(ids: List<String>, status: String)

    @Query("DELETE FROM offline_mutations WHERE id = :id")
    suspend fun deleteMutation(id: String)

    @Query("DELETE FROM offline_mutations WHERE id IN (:ids)")
    suspend fun deleteMutations(ids: List<String>)

    @Query("DELETE FROM offline_mutations WHERE status = 'SYNCED'")
    suspend fun pruneSynced()
}
