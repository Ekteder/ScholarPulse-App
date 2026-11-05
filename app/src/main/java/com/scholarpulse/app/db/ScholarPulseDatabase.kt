package com.scholarpulse.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ActiveTimerEntity::class,
        SubjectEntity::class,
        ScheduleEntity::class,
        TaskEntity::class,
        RevisionRecordEntity::class,
        OfflineMutationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ScholarPulseDatabase : RoomDatabase() {

    abstract fun activeTimerDao(): ActiveTimerDao
    abstract fun subjectDao(): SubjectDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun taskDao(): TaskDao
    abstract fun revisionDao(): RevisionDao
    abstract fun offlineMutationDao(): OfflineMutationDao

    companion object {
        private const val DATABASE_NAME = "scholarpulse.db"

        @Volatile
        private var instance: ScholarPulseDatabase? = null

        fun getInstance(context: Context): ScholarPulseDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScholarPulseDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
