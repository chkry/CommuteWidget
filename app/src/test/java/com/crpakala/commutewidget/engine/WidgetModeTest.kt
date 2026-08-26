package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetModeTest {
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val morningStart = 420
    private val morningEnd = 600
    private val eveningStart = 1020
    private val eveningEnd = 1200

    private fun resolve(dayOfWeekIso: Int, minuteOfDay: Int, commuteDays: Set<Int> = weekdays): WidgetMode =
        resolveWidgetMode(
            dayOfWeekIso = dayOfWeekIso,
            minuteOfDay = minuteOfDay,
            commuteDays = commuteDays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )

    @Test
    fun beforeMorningWindow_isCalendar() {
        assertEquals(WidgetMode.Calendar, resolve(1, morningStart - 1))
    }

    @Test
    fun atMorningWindowStart_isCommuteToWork() {
        assertEquals(WidgetMode.Commute(Direction.TO_WORK), resolve(1, morningStart))
    }

    @Test
    fun insideMorningWindow_isCommuteToWork() {
        assertEquals(WidgetMode.Commute(Direction.TO_WORK), resolve(1, morningStart + 30))
    }

    @Test
    fun atMorningWindowEnd_isCalendar_endExclusive() {
        assertEquals(WidgetMode.Calendar, resolve(1, morningEnd))
    }

    @Test
    fun betweenWindows_isCalendar() {
        assertEquals(WidgetMode.Calendar, resolve(1, (morningEnd + eveningStart) / 2))
    }

    @Test
    fun insideEveningWindow_isCommuteToHome() {
        assertEquals(WidgetMode.Commute(Direction.TO_HOME), resolve(1, eveningStart + 30))
    }

    @Test
    fun atEveningWindowEnd_isCalendar_endExclusive() {
        assertEquals(WidgetMode.Calendar, resolve(1, eveningEnd))
    }

    @Test
    fun afterEveningWindow_isCalendar() {
        assertEquals(WidgetMode.Calendar, resolve(1, eveningEnd + 1))
    }

    @Test
    fun dayNotInCommuteDays_isCalendarEvenInsideWindowMinutes() {
        assertEquals(WidgetMode.Calendar, resolve(6, morningStart + 30, commuteDays = weekdays))
    }

    @Test
    fun emptyCommuteDays_alwaysCalendar() {
        assertEquals(WidgetMode.Calendar, resolve(1, morningStart + 30, commuteDays = emptySet()))
    }

    @Test
    fun overlappingWindows_morningWins() {
        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = 500,
            commuteDays = weekdays,
            morningStart = 420,
            morningEnd = 700,
            eveningStart = 450,
            eveningEnd = 1200,
        )
        assertEquals(WidgetMode.Commute(Direction.TO_WORK), mode)
    }

    @Test
    fun invalidMorningWindow_startEqualsEnd_isTreatedAsAbsent() {
        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = 420,
            commuteDays = weekdays,
            morningStart = 420,
            morningEnd = 420,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(WidgetMode.Calendar, mode)
    }

    @Test
    fun invalidEveningWindow_startAfterEnd_isTreatedAsAbsent() {
        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = 1100,
            commuteDays = weekdays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = 1200,
            eveningEnd = 1020,
        )
        assertEquals(WidgetMode.Calendar, mode)
    }
}
