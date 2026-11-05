package com.scholarpulse.app

import com.scholarpulse.app.services.PomodoroPhase
import com.scholarpulse.app.services.TimerMode
import com.scholarpulse.app.services.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test verifying timer state calculation, threshold detection, and Pomodoro phase transitions.
 * References: TRD Section 6.2, QA Plan Section 3.1 & 6.1.
 */
class TimerStateCalculationTest {

    /**
     * Pure test simulator representing TimerForegroundService's mathematical state reconciliation.
     */
    class TimerCalculationEngine(
        var status: TimerStatus = TimerStatus.RUNNING,
        var mode: TimerMode = TimerMode.POMODORO,
        var phase: PomodoroPhase = PomodoroPhase.FOCUS,
        var targetDurationSeconds: Long = 25 * 60L,
        var defaultFocusDurationSeconds: Long = 25 * 60L,
        var accumulatedSeconds: Long = 0L,
        var lastResumedAtMs: Long = 0L,
        var completedPomodoros: Int = 0
    ) {
        fun calculateElapsed(currentMs: Long): Long {
            if (status != TimerStatus.RUNNING && status != TimerStatus.BREAK) {
                return accumulatedSeconds
            }
            val delta = (currentMs - lastResumedAtMs) / 1000L
            return accumulatedSeconds + maxOf(0L, delta)
        }

        fun calculateRemaining(currentMs: Long): Long {
            if (mode == TimerMode.STOPWATCH) return 0L
            val elapsed = calculateElapsed(currentMs)
            return maxOf(0L, targetDurationSeconds - elapsed)
        }

        fun isTargetThresholdReached(currentMs: Long): Boolean {
            if (mode == TimerMode.STOPWATCH) return false
            return calculateElapsed(currentMs) >= targetDurationSeconds
        }

        fun pause(pauseAtMs: Long) {
            accumulatedSeconds = calculateElapsed(pauseAtMs)
            lastResumedAtMs = pauseAtMs
            status = TimerStatus.PAUSED
        }

        fun resume(resumeAtMs: Long) {
            lastResumedAtMs = resumeAtMs
            status = if (phase == PomodoroPhase.FOCUS) TimerStatus.RUNNING else TimerStatus.BREAK
        }

        fun completePomodoroPhase(transitionAtMs: Long): PomodoroPhase {
            if (phase == PomodoroPhase.FOCUS) {
                completedPomodoros++
                val isLongBreak = (completedPomodoros % 4 == 0)
                phase = if (isLongBreak) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
                status = TimerStatus.BREAK
                targetDurationSeconds = if (isLongBreak) (15 * 60L) else (5 * 60L)
            } else {
                phase = PomodoroPhase.FOCUS
                status = TimerStatus.RUNNING
                targetDurationSeconds = defaultFocusDurationSeconds
            }
            accumulatedSeconds = 0L
            lastResumedAtMs = transitionAtMs
            return phase
        }
    }

    @Test
    fun testElapsedTimeAcrossRunningPausedAndResumedStates() {
        val baseTimeMs = 1_000_000_000L
        val engine = TimerCalculationEngine(
            status = TimerStatus.RUNNING,
            accumulatedSeconds = 0L,
            lastResumedAtMs = baseTimeMs
        )

        // 1. Running state after 300 seconds (5 minutes)
        val elapsedRunning = engine.calculateElapsed(baseTimeMs + 300_000L)
        assertEquals(300L, elapsedRunning)

        // 2. Pause at 300 seconds
        engine.pause(baseTimeMs + 300_000L)
        assertEquals(TimerStatus.PAUSED, engine.status)
        assertEquals(300L, engine.accumulatedSeconds)

        // Elapsed time while paused should NOT advance even 10 minutes later
        val elapsedWhilePaused = engine.calculateElapsed(baseTimeMs + 900_000L)
        assertEquals(300L, elapsedWhilePaused)

        // 3. Resume at 900 seconds
        engine.resume(baseTimeMs + 900_000L)
        assertEquals(TimerStatus.RUNNING, engine.status)

        // 4. Running again for another 120 seconds (total 420 seconds)
        val elapsedAfterResume = engine.calculateElapsed(baseTimeMs + 900_000L + 120_000L)
        assertEquals(420L, elapsedAfterResume)
    }

    @Test
    fun testPomodoroTargetDurationThresholdDetection() {
        val baseTimeMs = 2_000_000_000L
        val targetSeconds = 25 * 60L // 1500 seconds
        val engine = TimerCalculationEngine(
            targetDurationSeconds = targetSeconds,
            lastResumedAtMs = baseTimeMs
        )

        // Before threshold (1499s)
        assertFalse(engine.isTargetThresholdReached(baseTimeMs + 1499_000L))
        assertEquals(1L, engine.calculateRemaining(baseTimeMs + 1499_000L))

        // Exactly at threshold (1500s)
        assertTrue(engine.isTargetThresholdReached(baseTimeMs + 1500_000L))
        assertEquals(0L, engine.calculateRemaining(baseTimeMs + 1500_000L))

        // Beyond threshold (1505s)
        assertTrue(engine.isTargetThresholdReached(baseTimeMs + 1505_000L))
        assertEquals(0L, engine.calculateRemaining(baseTimeMs + 1505_000L))
    }

    @Test
    fun testPomodoroPhaseTransitionsAcrossFullCycles() {
        val baseTimeMs = 3_000_000_000L
        val engine = TimerCalculationEngine(
            phase = PomodoroPhase.FOCUS,
            defaultFocusDurationSeconds = 25 * 60L,
            lastResumedAtMs = baseTimeMs
        )

        // Cycle 1: Focus -> Short Break
        var nextPhase = engine.completePomodoroPhase(baseTimeMs + 1500_000L)
        assertEquals(PomodoroPhase.SHORT_BREAK, nextPhase)
        assertEquals(1, engine.completedPomodoros)
        assertEquals(5 * 60L, engine.targetDurationSeconds)
        assertEquals(TimerStatus.BREAK, engine.status)

        // Break 1 complete -> Focus
        nextPhase = engine.completePomodoroPhase(baseTimeMs + 1800_000L)
        assertEquals(PomodoroPhase.FOCUS, nextPhase)
        assertEquals(25 * 60L, engine.targetDurationSeconds)
        assertEquals(TimerStatus.RUNNING, engine.status)

        // Cycle 2: Focus -> Short Break
        nextPhase = engine.completePomodoroPhase(baseTimeMs + 3300_000L)
        assertEquals(PomodoroPhase.SHORT_BREAK, nextPhase)
        assertEquals(2, engine.completedPomodoros)
        assertEquals(5 * 60L, engine.targetDurationSeconds)

        // Break 2 complete -> Focus
        nextPhase = engine.completePomodoroPhase(baseTimeMs + 3600_000L)
        assertEquals(PomodoroPhase.FOCUS, nextPhase)

        // Cycle 3: Focus -> Short Break
        nextPhase = engine.completePomodoroPhase(baseTimeMs + 5100_000L)
        assertEquals(PomodoroPhase.SHORT_BREAK, nextPhase)
        assertEquals(3, engine.completedPomodoros)
        assertEquals(5 * 60L, engine.targetDurationSeconds)

        // Break 3 complete -> Focus
        nextPhase = engine.completePomodoroPhase(baseTimeMs + 5400_000L)
        assertEquals(PomodoroPhase.FOCUS, nextPhase)

        // Cycle 4: 4th completed Pomodoro MUST trigger Long Break (15 min)
        nextPhase = engine.completePomodoroPhase(baseTimeMs + 6900_000L)
        assertEquals(PomodoroPhase.LONG_BREAK, nextPhase)
        assertEquals(4, engine.completedPomodoros)
        assertEquals(15 * 60L, engine.targetDurationSeconds)
        assertEquals(TimerStatus.BREAK, engine.status)

        // Long break complete -> back to Focus
        nextPhase = engine.completePomodoroPhase(baseTimeMs + 7800_000L)
        assertEquals(PomodoroPhase.FOCUS, nextPhase)
        assertEquals(25 * 60L, engine.targetDurationSeconds)
    }

    @Test
    fun testStopwatchUnboundedAccumulation() {
        val baseTimeMs = 4_000_000_000L
        val engine = TimerCalculationEngine(
            mode = TimerMode.STOPWATCH,
            status = TimerStatus.RUNNING,
            lastResumedAtMs = baseTimeMs
        )

        // Running for 3600 seconds (1 hour)
        val elapsedOneHour = engine.calculateElapsed(baseTimeMs + 3600_000L)
        assertEquals(3600L, elapsedOneHour)
        // Stopwatch has no remaining duration and never triggers threshold
        assertEquals(0L, engine.calculateRemaining(baseTimeMs + 3600_000L))
        assertFalse(engine.isTargetThresholdReached(baseTimeMs + 3600_000L))

        // Running for 4 hours (14400 seconds)
        val elapsedFourHours = engine.calculateElapsed(baseTimeMs + 14400_000L)
        assertEquals(14400L, elapsedFourHours)
        assertFalse(engine.isTargetThresholdReached(baseTimeMs + 14400_000L))
    }
}
