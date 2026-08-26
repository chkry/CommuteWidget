package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextWindowTest {
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val morningStart = 420
    private val morningEnd = 600
    private val eveningStart = 1020
    private val eveningEnd = 1200

    private fun next(dayOfWeekIso: Int, minuteOfDay: Int, commuteDays: Set<Int> = weekdays): NextWindow? =
        nextWindow(
            dayOfWeekIso = dayOfWeekIso,
            minuteOfDay = minuteOfDay,
            commuteDays = commuteDays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )

    @Test
    fun beforeMorningStart_returnsMorningToday() {
        assertEquals(NextWindow(Direction.TO_WORK, morningStart), next(1, morningStart - 30))
    }

    @Test
    fun betweenWindows_returnsEveningToday() {
        assertEquals(NextWindow(Direction.TO_HOME, eveningStart), next(1, morningEnd + 10))
    }

    @Test
    fun afterEveningStart_stillReturnsEveningIfNotYetStarted() {
        // Not applicable when eveningStart already passed; covered by afterBothWindows below.
        assertEquals(NextWindow(Direction.TO_HOME, eveningStart), next(1, eveningStart - 1))
    }

    @Test
    fun afterBothWindowsToday_movesToNextEnabledDay() {
        assertEquals(NextWindow(Direction.TO_WORK, morningStart), next(1, eveningEnd + 1))
    }

    @Test
    fun fridayAfterBothWindows_wrapsToMonday() {
        assertEquals(NextWindow(Direction.TO_WORK, morningStart), next(5, eveningEnd + 1))
    }

    @Test
    fun dayNotEnabled_looksAheadToNextEnabledDay() {
        val days = setOf(1, 2, 4, 5) // Wednesday (3) excluded
        assertEquals(NextWindow(Direction.TO_WORK, morningStart), next(3, morningStart + 30, commuteDays = days))
    }

    @Test
    fun emptyCommuteDays_returnsNull() {
        assertNull(next(1, morningStart - 30, commuteDays = emptySet()))
    }

    @Test
    fun bothWindowsInvalid_returnsNull() {
        val result = nextWindow(
            dayOfWeekIso = 1,
            minuteOfDay = 500,
            commuteDays = weekdays,
            morningStart = 420,
            morningEnd = 420,
            eveningStart = 1200,
            eveningEnd = 1020,
        )
        assertNull(result)
    }

    @Test
    fun onlyEveningWindowValid_morningExcluded() {
        val result = nextWindow(
            dayOfWeekIso = 1,
            minuteOfDay = 0,
            commuteDays = weekdays,
            morningStart = 420,
            morningEnd = 420,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(NextWindow(Direction.TO_HOME, eveningStart), result)
    }
}
