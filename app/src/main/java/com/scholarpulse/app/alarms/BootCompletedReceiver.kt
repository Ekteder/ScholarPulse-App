package com.scholarpulse.app.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.scholarpulse.app.db.ScholarPulseDatabase
import com.scholarpulse.app.workers.OfflineSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered when the device finishes booting or app package is replaced.
 * Automatically restores all Room SQLite timetable alarms and schedules WorkManager sync.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        Log.i(TAG, "BootCompletedReceiver triggered with action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val database = ScholarPulseDatabase.getInstance(context)
                    val schedules = database.scheduleDao().getAllSchedulesSync()
                    val count = ScheduleAlarmScheduler.rearmAll(context, schedules)
                    Log.i(TAG, "Successfully re-armed $count timetable alarms after reboot.")

                    // Also ensure periodic background synchronization is scheduled
                    OfflineSyncWorker.schedulePeriodicSync(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-arm alarms on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
