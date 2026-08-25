package com.crpakala.commutewidget.schedule

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CommuteSchedulerTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun nextWeekdayOccurrence_weekdayBeforeTarget_returnsToday() {
        val now = at(2026, 8, 24, 7, 59)

        assertEquals(at(2026, 8, 24, 8, 0), CommuteScheduler.nextWeekdayOccurrence(now, 480))
    }

    @Test
    fun nextWeekdayOccurrence_weekdayAfterTarget_returnsNextWeekday() {
        val now = at(2026, 8, 24, 8, 1)

        assertEquals(at(2026, 8, 25, 8, 0), CommuteScheduler.nextWeekdayOccurrence(now, 480))
    }

    @Test
    fun nextWeekdayOccurrence_fridayAfterEveningTarget_returnsMonday() {
        val now = at(2026, 8, 21, 17, 1)

        assertEquals(at(2026, 8, 24, 17, 0), CommuteScheduler.nextWeekdayOccurrence(now, 1020))
    }

    @Test
    fun nextWeekdayOccurrence_saturday_returnsMonday() {
        val now = at(2026, 8, 22, 7, 0)

        assertEquals(at(2026, 8, 24, 8, 0), CommuteScheduler.nextWeekdayOccurrence(now, 480))
    }

    @Test
    fun nextWeekdayOccurrence_sunday_returnsMonday() {
        val now = at(2026, 8, 23, 7, 0)

        assertEquals(at(2026, 8, 24, 8, 0), CommuteScheduler.nextWeekdayOccurrence(now, 480))
    }

    @Test
    fun nextWeekdayOccurrence_exactTargetMinute_returnsNextWeekday() {
        val now = at(2026, 8, 24, 8, 0)

        assertEquals(at(2026, 8, 25, 8, 0), CommuteScheduler.nextWeekdayOccurrence(now, 480))
    }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
}
