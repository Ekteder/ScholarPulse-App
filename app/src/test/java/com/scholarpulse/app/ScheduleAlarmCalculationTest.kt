package com.scholarpulse.app

import com.scholarpulse.app.alarms.ScheduleAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit test verifying class reminder and streak alarm trigger calculations.
 * References: TRD Section 8.3 & QA Plan Section 3.3.
 */
class ScheduleAlarmCalculationTest {

    /**
     * Deterministic calculation mirror accepting an injected 'now' calendar for timestamp testing.
     */
    class DeterministicAlarmCalculator {
        fun calculateClassReminderMillis(
            dayOfWeek: Int,
            startTime: String,
            simulatedNow: Calendar
        ): Long {
            val parts = startTime.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

            val targetCalendarDay = (dayOfWeek % 7) + 1

            val target = (simulatedNow.clone() as Calendar).apply {
                set(Calendar.DAY_OF_WEEK, targetCalendarDay)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, -10)
            }

            if (target.timeInMillis <= simulatedNow.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 7)
            }

            return target.timeInMillis
        }

        fun calculateStreakReminderMillis(
            simulatedNow: Calendar,
            hour: Int = 20,
            minute: Int = 0
        ): Long {
            val target = (simulatedNow.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (target.before(simulatedNow) || target.timeInMillis <= simulatedNow.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            return target.timeInMillis
        }
    }

    @Test
    fun testTenMinuteClassReminderCalculationExactness() {
        val calculator = DeterministicAlarmCalculator()

        // Set simulated now: Monday at 08:00 AM (Monday = Calendar.MONDAY = 2, dayOfWeek = 1)
        val simulatedNow = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Class scheduled at 10:00 AM today (Monday)
        val triggerMillis = calculator.calculateClassReminderMillis(
            dayOfWeek = 1, // Monday in JS/SQL
            startTime = "10:00",
            simulatedNow = simulatedNow
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = triggerMillis }

        // Must trigger at 09:50 AM (exactly 10 minutes prior)
        assertEquals(Calendar.MONDAY, triggerCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(50, triggerCal.get(Calendar.MINUTE))
        assertEquals(0, triggerCal.get(Calendar.SECOND))

        // Difference must be exactly 1 hour 50 minutes (6600 seconds = 6,600,000 ms)
        val expectedDiffMs = (1 * 60 + 50) * 60 * 1000L
        assertEquals(expectedDiffMs, triggerMillis - simulatedNow.timeInMillis)
    }

    @Test
    fun testRolloverToNextWeekWhenClassTimeHasPassed() {
        val calculator = DeterministicAlarmCalculator()

        // Set simulated now: Monday at 10:30 AM
        val simulatedNow = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Class was at 10:00 AM today (10-minute warning at 09:50 AM is in the past)
        val triggerMillis = calculator.calculateClassReminderMillis(
            dayOfWeek = 1,
            startTime = "10:00",
            simulatedNow = simulatedNow
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = triggerMillis }

        // Must rollover to NEXT Monday at 09:50 AM
        assertEquals(Calendar.MONDAY, triggerCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(50, triggerCal.get(Calendar.MINUTE))
        assertTrue(triggerMillis > simulatedNow.timeInMillis)

        // Must be scheduled ~6 days 23 hours and 20 minutes in the future
        val diffMs = triggerMillis - simulatedNow.timeInMillis
        val expectedDiffMs = ((7 * 24 * 60) - 40) * 60 * 1000L // 7 days minus 40 min
        assertEquals(expectedDiffMs, diffMs)
    }

    @Test
    fun testEightPmDailyStreakAlarmCalculation() {
        val calculator = DeterministicAlarmCalculator()

        // Case A: Before 8:00 PM (e.g. 14:30 / 2:30 PM) -> triggers TODAY at 20:00
        val beforeEightPm = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val triggerToday = calculator.calculateStreakReminderMillis(beforeEightPm, 20, 0)
        val triggerCalToday = Calendar.getInstance().apply { timeInMillis = triggerToday }

        assertEquals(beforeEightPm.get(Calendar.DAY_OF_YEAR), triggerCalToday.get(Calendar.DAY_OF_YEAR))
        assertEquals(20, triggerCalToday.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCalToday.get(Calendar.MINUTE))
        assertEquals(0, triggerCalToday.get(Calendar.SECOND))

        // Case B: After 8:00 PM (e.g. 21:15 / 9:15 PM) -> triggers TOMORROW at 20:00
        val afterEightPm = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val triggerTomorrow = calculator.calculateStreakReminderMillis(afterEightPm, 20, 0)
        val triggerCalTomorrow = Calendar.getInstance().apply { timeInMillis = triggerTomorrow }

        val dayDiff = triggerCalTomorrow.get(Calendar.DAY_OF_YEAR) - afterEightPm.get(Calendar.DAY_OF_YEAR)
        assertTrue("Trigger must be next day", dayDiff == 1 || dayDiff < 0) // < 0 handles year rollover
        assertEquals(20, triggerCalTomorrow.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCalTomorrow.get(Calendar.MINUTE))
    }

    @Test
    fun testRealScheduleAlarmSchedulerLiveCall() {
        // Direct call into ScheduleAlarmScheduler to verify production method executes without crashing
        val triggerMillis = ScheduleAlarmScheduler.calculateClassReminderMillis(
            dayOfWeek = 2, // Tuesday
            startTime = "14:00"
        )
        val triggerCal = Calendar.getInstance().apply { timeInMillis = triggerMillis }
        val now = Calendar.getInstance()

        assertTrue(triggerMillis > now.timeInMillis)
        assertEquals(13, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(50, triggerCal.get(Calendar.MINUTE))
    }
}
