package com.crpakala.commutewidget.calendar

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutineCalendarSelectionTest {
    @Test
    fun calendarQueryEnd_restOfDayByDefault() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val now = java.time.ZonedDateTime.of(2026, 8, 26, 14, 0, 0, 0, zone)
        val end = calendarQueryEndEpochMillis(now.toInstant().toEpochMilli(), zone, 0)
        val expectedMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        org.junit.Assert.assertEquals(expectedMidnight, end)
    }

    @Test
    fun calendarQueryEnd_extendsPastMidnightByLookahead() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val now = java.time.ZonedDateTime.of(2026, 8, 26, 23, 40, 0, 0, zone)
        val end = calendarQueryEndEpochMillis(now.toInstant().toEpochMilli(), zone, 120)
        val expected = now.toInstant().toEpochMilli() + 120 * 60_000L
        org.junit.Assert.assertEquals(expected, end)
    }

    private val now = 1_000_000L
    private val selectedCalendars = setOf(1L)

    @Test
    fun firstEventTomorrow_earliestBeginThenEndWins() {
        val event = selectFirstEventTomorrow(
            listOf(
                row(title = "Later", beginEpochMillis = now + 3_000L),
                row(title = "Longer", beginEpochMillis = now + 1_000L, endEpochMillis = now + 4_000L),
                row(title = "Sooner", beginEpochMillis = now + 1_000L, endEpochMillis = now + 2_000L),
            ),
            selectedCalendars,
        )

        assertEquals("Sooner", event?.title)
        assertEquals(now + 1_000L, event?.startEpochMillis)
    }

    @Test
    fun firstEventTomorrow_unlocatedEventIsIncluded() {
        val event = selectFirstEventTomorrow(listOf(row(location = null)), selectedCalendars)

        assertEquals("Meeting", event?.title)
    }

    @Test
    fun firstEventTomorrow_excludesAllDayCancelledAndDeclinedEvents() {
        val event = selectFirstEventTomorrow(
            listOf(
                row(allDay = true),
                row(status = CalendarContract.Events.STATUS_CANCELED),
                row(selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED),
            ),
            selectedCalendars,
        )

        assertNull(event)
    }

    @Test
    fun firstEventTomorrow_emptyRowsReturnsNull() {
        assertNull(selectFirstEventTomorrow(emptyList(), selectedCalendars))
    }

    @Test
    fun todaySummary_countsEligibleEventsIncludingOngoingAndOrdersFirstStart() {
        val summary = selectTodaySummary(
            listOf(
                row(title = "Upcoming", beginEpochMillis = now + 2_000L, endEpochMillis = now + 3_000L),
                row(title = "Ongoing", beginEpochMillis = now - 5_000L, endEpochMillis = now + 1_000L),
                row(title = "Finished", beginEpochMillis = now - 6_000L, endEpochMillis = now),
            ),
            selectedCalendars,
            now,
        )

        assertEquals(2, summary.remainingCount)
        assertEquals(now - 5_000L, summary.firstStartEpochMillis)
    }

    @Test
    fun todaySummary_excludesAllDayCancelledAndDeclinedEvents() {
        val summary = selectTodaySummary(
            listOf(
                row(allDay = true),
                row(status = CalendarContract.Events.STATUS_CANCELED),
                row(selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED),
            ),
            selectedCalendars,
            now,
        )

        assertEquals(0, summary.remainingCount)
        assertNull(summary.firstStartEpochMillis)
    }

    @Test
    fun todaySummary_emptyRowsHasZeroCountAndNoFirstStart() {
        val summary = selectTodaySummary(emptyList(), selectedCalendars, now)

        assertEquals(0, summary.remainingCount)
        assertNull(summary.firstStartEpochMillis)
    }

    private fun row(
        calendarId: Long = 1L,
        title: String? = "Meeting",
        location: String? = "123 Main Street",
        beginEpochMillis: Long = now + 1_000L,
        endEpochMillis: Long = now + 2_000L,
        allDay: Boolean = false,
        status: Int = CalendarContract.Events.STATUS_CONFIRMED,
        selfAttendeeStatus: Int = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
    ) = RawInstance(
        calendarId = calendarId,
        title = title,
        location = location,
        beginEpochMillis = beginEpochMillis,
        endEpochMillis = endEpochMillis,
        allDay = allDay,
        status = status,
        selfAttendeeStatus = selfAttendeeStatus,
    )
}
