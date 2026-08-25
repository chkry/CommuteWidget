package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class DirectionDecisionTest {
    private val switchAt2pm = 14 * 60

    @Test
    fun weekdayMorningIsToWork() {
        assertEquals(Direction.TO_WORK, decideDirection(DayOfWeek.MONDAY, 8 * 60, switchAt2pm))
        assertEquals(Direction.TO_WORK, decideDirection(DayOfWeek.FRIDAY, 0, switchAt2pm))
        assertEquals(Direction.TO_WORK, decideDirection(DayOfWeek.WEDNESDAY, switchAt2pm - 1, switchAt2pm))
    }

    @Test
    fun weekdayAfterSwitchIsToHome() {
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.MONDAY, switchAt2pm + 1, switchAt2pm))
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.FRIDAY, 17 * 60, switchAt2pm))
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.THURSDAY, 23 * 60 + 59, switchAt2pm))
    }

    @Test
    fun exactlyAtSwitchMinuteIsToHome() {
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.TUESDAY, switchAt2pm, switchAt2pm))
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.WEDNESDAY, 840, 840))
    }

    @Test
    fun saturdayIsToHome() {
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.SATURDAY, 0, switchAt2pm))
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.SATURDAY, 8 * 60, switchAt2pm))
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.SATURDAY, 23 * 60, switchAt2pm))
    }

    @Test
    fun sundayIsToHome() {
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.SUNDAY, 8 * 60, switchAt2pm))
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.SUNDAY, switchAt2pm - 1, switchAt2pm))
        assertEquals(Direction.TO_HOME, decideDirection(DayOfWeek.SUNDAY, switchAt2pm + 10, switchAt2pm))
    }
}
