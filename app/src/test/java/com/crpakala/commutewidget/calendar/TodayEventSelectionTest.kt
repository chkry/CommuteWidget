package com.crpakala.commutewidget.calendar

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayEventSelectionTest {
    private val now = 1_000_000L

    @Test
    fun unlocatedEvent_isIncluded() {
        val result = selectTodayEvent(listOf(row(location = null)), setOf(1L), now)

        assertEquals("Meeting", result?.title)
        assertNull(result?.location)
    }

    @Test
    fun blankLocation_isNormalizedToNull() {
        val result = selectTodayEvent(listOf(row(location = " \t ")), setOf(1L), now)

        assertNull(result?.location)
    }

    @Test
    fun locatedEvent_locationIsTrimmed() {
        val result = selectTodayEvent(listOf(row(location = "  123 Main Street  ")), setOf(1L), now)

        assertEquals("123 Main Street", result?.location)
    }

    @Test
    fun emptySelection_returnsNull() {
        assertNull(selectTodayEvent(listOf(row()), emptySet(), now))
    }

    @Test
    fun allDayEvent_isExcluded() {
        assertNull(selectTodayEvent(listOf(row(allDay = true)), setOf(1L), now))
    }

    @Test
    fun cancelledEvent_isExcluded() {
        assertNull(
            selectTodayEvent(listOf(row(status = CalendarContract.Events.STATUS_CANCELED)), setOf(1L), now),
        )
    }

    @Test
    fun declinedEvent_isExcluded() {
        assertNull(
            selectTodayEvent(
                listOf(row(selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)),
                setOf(1L),
                now,
            ),
        )
    }

    @Test
    fun finishedEvent_isExcluded() {
        assertNull(selectTodayEvent(listOf(row(endEpochMillis = now)), setOf(1L), now))
    }

    @Test
    fun noUnlocatedCandidate_earliestLocatedWins() {
        val result = selectTodayEvent(
            listOf(
                row(title = "Later", location = "A", beginEpochMillis = now + 2_000L),
                row(title = "Sooner", location = "B", beginEpochMillis = now + 1_000L),
            ),
            setOf(1L),
            now,
        )

        assertEquals("Sooner", result?.title)
        assertEquals(false, result?.preferredOverEarlierEvent)
    }

    @Test
    fun noLocatedCandidate_earliestUnlocatedWins() {
        val result = selectTodayEvent(
            listOf(
                row(title = "Later", location = null, beginEpochMillis = now + 2_000L),
                row(title = "Sooner", location = null, beginEpochMillis = now + 1_000L),
            ),
            setOf(1L),
            now,
        )

        assertEquals("Sooner", result?.title)
        assertEquals(false, result?.preferredOverEarlierEvent)
    }

    @Test
    fun locatedCandidateStartsWithin30Minutes_ofUnlocated_locatedWins() {
        val result = selectTodayEvent(
            listOf(
                row(title = "Unlocated first", location = null, beginEpochMillis = now),
                row(title = "Located soon after", location = "Cafe", beginEpochMillis = now + 29 * 60_000L),
            ),
            setOf(1L),
            now,
        )

        assertEquals("Located soon after", result?.title)
        assertEquals(true, result?.preferredOverEarlierEvent)
    }

    @Test
    fun locatedCandidateStartsExactly30MinutesAfterUnlocated_locatedWins_boundaryInclusive() {
        val result = selectTodayEvent(
            listOf(
                row(title = "Unlocated first", location = null, beginEpochMillis = now),
                row(title = "Located at boundary", location = "Cafe", beginEpochMillis = now + 30 * 60_000L),
            ),
            setOf(1L),
            now,
        )

        assertEquals("Located at boundary", result?.title)
        assertEquals(true, result?.preferredOverEarlierEvent)
    }

    @Test
    fun locatedCandidateStartsMoreThan30MinutesAfterUnlocated_unlocatedWins() {
        val result = selectTodayEvent(
            listOf(
                row(title = "Unlocated first", location = null, beginEpochMillis = now),
                row(title = "Located much later", location = "Cafe", beginEpochMillis = now + 31 * 60_000L),
            ),
            setOf(1L),
            now,
        )

        assertEquals("Unlocated first", result?.title)
        assertEquals(false, result?.preferredOverEarlierEvent)
    }

    @Test
    fun locatedCandidateStartsBeforeUnlocated_locatedWins_butIsNotFlaggedAsPreferred() {
        val result = selectTodayEvent(
            listOf(
                row(title = "Unlocated later", location = null, beginEpochMillis = now + 10_000L),
                row(title = "Located earlier", location = "Cafe", beginEpochMillis = now + 1_000L),
            ),
            setOf(1L),
            now,
        )

        // The located event genuinely is the earliest here, so choosing it is not a reordering:
        // the "Routed" caption must not fire for this case (see UX-AUDIT.md ruling f).
        assertEquals("Located earlier", result?.title)
        assertEquals(false, result?.preferredOverEarlierEvent)
    }

    @Test
    fun onlyOneCandidateAtAll_neverFlaggedAsPreferred() {
        val locatedOnly = selectTodayEvent(listOf(row(location = "Cafe")), setOf(1L), now)
        val unlocatedOnly = selectTodayEvent(listOf(row(location = null)), setOf(1L), now)

        assertEquals(false, locatedOnly?.preferredOverEarlierEvent)
        assertEquals(false, unlocatedOnly?.preferredOverEarlierEvent)
    }

    @Test
    fun nullOrBlankTitle_usesEventDisplayTitle() {
        val nullTitle = selectTodayEvent(listOf(row(title = null)), setOf(1L), now)
        val blankTitle = selectTodayEvent(listOf(row(title = "  ")), setOf(1L), now)

        assertEquals("Event", nullTitle?.title)
        assertEquals("Event", blankTitle?.title)
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
