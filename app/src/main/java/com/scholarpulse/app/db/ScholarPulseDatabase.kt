package com.scholarpulse.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActiveTimerEntity::class,
        SubjectEntity::class,
        ScheduleEntity::class,
        TaskEntity::class,
        RevisionRecordEntity::class,
        OfflineMutationEntity::class
    ],
    version = 2,
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_mutations ADD COLUMN updatedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE offline_mutations SET updatedAtMs = createdAtMs")
            }
        }

        @Volatile
        private var instance: ScholarPulseDatabase? = null

        fun getInstance(context: Context): ScholarPulseDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScholarPulseDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
