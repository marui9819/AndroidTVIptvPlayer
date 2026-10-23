package com.tvplayer.app.util

import org.junit.Assert.*
import org.junit.Test

class SleepTimerStateTest {
    @Test fun validatesHourAndMinuteInputWithoutOverflow() {
        assertNull(SleepTimerState.durationMs(0, 0))
        assertNull(SleepTimerState.durationMs(-1, 10))
        assertNull(SleepTimerState.durationMs(0, 60))
        assertNull(SleepTimerState.durationMs(24, 0))
        assertNull(SleepTimerState.durationMs(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(60_000L, SleepTimerState.durationMs(0, 1))
        assertEquals(5_400_000L, SleepTimerState.durationMs(1, 30))
        assertEquals(86_340_000L, SleepTimerState.durationMs(23, 59))
    }

    @Test fun timerSurvivesNavigationAndExpiresOnlyAtDeadline() {
        val timer = SleepTimerState("one", 1_000L, 61_000L, "boot-a")
        assertTrue(timer.isValid(10_000L, "boot-a"))
        assertEquals(51_000L, timer.remainingMs(10_000L))
        assertFalse(timer.isDue(60_999L))
        assertTrue(timer.isDue(61_000L))
        assertEquals(0L, timer.remainingMs(90_000L))
    }

    @Test fun rebootAndCancelledTimerCannotCloseAnotherSession() {
        val timer = SleepTimerState("one", 1_000L, 61_000L, "boot-a")
        assertFalse(timer.isValid(2_000L, "boot-b"))
        assertFalse(timer.isValid(999L, "boot-a"))
        assertFalse(SleepTimerState().isDue(Long.MAX_VALUE))
        assertFalse(SleepTimerState().isValid(2_000L, "boot-a"))
        assertFalse(SleepTimerState("bad", 5L, 4L).isValid(10L, ""))
    }
}
