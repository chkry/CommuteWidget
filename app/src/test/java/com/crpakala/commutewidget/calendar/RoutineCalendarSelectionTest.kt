package com.crpakala.commutewidget.calendar

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun selectUpcomingEvents_ordersByBeginThenEnd() {
        val events = selectUpcomingEvents(
            listOf(
                row(title = "Later", beginEpochMillis = now + 5_000L),
                row(title = "Longer", beginEpochMillis = now + 1_000L, endEpochMillis = now + 4_000L),
                row(title = "Sooner", beginEpochMillis = now + 1_000L, endEpochMillis = now + 2_000L),
            ),
            selectedCalendars,
            limit = 3,
            fromEpochMillis = 0L,
        )

        assertEquals(listOf("Sooner", "Longer", "Later"), events.map { it.title })
        assertEquals(now + 1_000L, events.first().startEpochMillis)
    }

    @Test
    fun selectUpcomingEvents_appliesLimit() {
        val events = selectUpcomingEvents(
            listOf(
                row(title = "First", beginEpochMillis = now + 1_000L),
                row(title = "Second", beginEpochMillis = now + 2_000L),
                row(title = "Third", beginEpochMillis = now + 3_000L),
            ),
            selectedCalendars,
            limit = 2,
            fromEpochMillis = 0L,
        )

        assertEquals(listOf("First", "Second"), events.map { it.title })
    }

    @Test
    fun selectUpcomingEvents_unlocatedEventIsIncluded() {
        val events = selectUpcomingEvents(listOf(row(location = null)), selectedCalendars, limit = 2, fromEpochMillis = 0L)

        assertEquals("Meeting", events.single().title)
    }

    @Test
    fun selectUpcomingEvents_blankTitleFallsBackToEvent() {
        val events = selectUpcomingEvents(listOf(row(title = "   ")), selectedCalendars, limit = 2, fromEpochMillis = 0L)

        assertEquals("Event", events.single().title)
    }

    @Test
    fun selectUpcomingEvents_excludesOtherCalendarAllDayCancelledAndDeclinedEvents() {
        val events = selectUpcomingEvents(
            listOf(
                row(calendarId = 2L),
                row(allDay = true),
                row(status = CalendarContract.Events.STATUS_CANCELED),
                row(selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED),
            ),
            selectedCalendars,
            limit = 2,
            fromEpochMillis = 0L,
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun selectUpcomingEvents_emptyRowsReturnsEmptyList() {
        assertTrue(selectUpcomingEvents(emptyList(), selectedCalendars, limit = 2, fromEpochMillis = 0L).isEmpty())
    }

    @Test
    fun selectUpcomingEvents_excludesInstanceBeforeFromEpochMillisAndKeepsInstanceAtBoundary() {
        val events = selectUpcomingEvents(
            listOf(
                row(title = "BeforeBoundary", beginEpochMillis = now - 1L),
                row(title = "AtBoundary", beginEpochMillis = now),
            ),
            selectedCalendars,
            limit = 2,
            fromEpochMillis = now,
        )

        assertEquals(listOf("AtBoundary"), events.map { it.title })
    }

    @Test
    fun upcomingQueryRange_startIsTomorrowMidnightAndEndIsSevenLocalDaysLater() {
        data class Case(
            val zone: java.time.ZoneId,
            val now: java.time.ZonedDateTime,
            val expectedStart: java.time.ZonedDateTime,
            val expectedEnd: java.time.ZonedDateTime,
        )

        val kolkata = java.time.ZoneId.of("Asia/Kolkata")
        val newYork = java.time.ZoneId.of("America/New_York")
        val cases = listOf(
            Case(
                zone = kolkata,
                now = java.time.ZonedDateTime.of(2026, 8, 26, 14, 0, 0, 0, kolkata),
                expectedStart = java.time.ZonedDateTime.of(2026, 8, 27, 0, 0, 0, 0, kolkata),
                expectedEnd = java.time.ZonedDateTime.of(2026, 9, 3, 0, 0, 0, 0, kolkata),
            ),
            // Spring-forward: on 2026-03-08 US clocks skip 2:00-3:00 am, so the seven local-day
            // window spans 167 wall-clock hours instead of 168 - proof the range comes from local
            // dates, not elapsed millis.
            Case(
                zone = newYork,
                now = java.time.ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, newYork),
                expectedStart = java.time.ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork),
                expectedEnd = java.time.ZonedDateTime.of(2026, 3, 15, 0, 0, 0, 0, newYork),
            ),
        )

        for (case in cases) {
            val range = upcomingQueryRange(case.now.toInstant().toEpochMilli(), case.zone, lookaheadDays = 7L)
            assertEquals(case.expectedStart.toInstant().toEpochMilli(), range.first)
            assertEquals(case.expectedEnd.toInstant().toEpochMilli(), range.last + 1)
        }
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
