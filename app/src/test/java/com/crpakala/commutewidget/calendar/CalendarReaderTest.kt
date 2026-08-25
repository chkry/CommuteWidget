package com.crpakala.commutewidget.calendar

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarReaderTest {
    private val now = 1_000_000L

    @Test
    fun emptySelectionReturnsNull() {
        assertNull(selectEvent(listOf(validRow()), emptySet(), now))
    }

    @Test
    fun blankLocationIsExcluded() {
        assertNull(selectEvent(listOf(validRow(location = " \t ")), setOf(1L), now))
    }

    @Test
    fun allDayEventIsExcluded() {
        assertNull(selectEvent(listOf(validRow(allDay = true)), setOf(1L), now))
    }

    @Test
    fun cancelledEventIsExcluded() {
        assertNull(
            selectEvent(
                listOf(validRow(status = CalendarContract.Events.STATUS_CANCELED)),
                setOf(1L),
                now,
            ),
        )
    }

    @Test
    fun declinedEventIsExcluded() {
        assertNull(
            selectEvent(
                listOf(
                    validRow(
                        selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                    ),
                ),
                setOf(1L),
                now,
            ),
        )
    }

    @Test
    fun finishedEventIsExcluded() {
        assertNull(selectEvent(listOf(validRow(endEpochMillis = now)), setOf(1L), now))
    }

    @Test
    fun earliestBeginIsSelected() {
        val result = selectEvent(
            listOf(
                validRow(title = "Later", beginEpochMillis = now + 2_000L),
                validRow(title = "Sooner", beginEpochMillis = now + 1_000L),
            ),
            setOf(1L),
            now,
        )

        assertEquals("Sooner", result?.title)
    }

    @Test
    fun earliestEndBreaksBeginTimeTie() {
        val result = selectEvent(
            listOf(
                validRow(title = "Longer", beginEpochMillis = now + 1_000L, endEpochMillis = now + 5_000L),
                validRow(title = "Shorter", beginEpochMillis = now + 1_000L, endEpochMillis = now + 3_000L),
            ),
            setOf(1L),
            now,
        )

        assertEquals("Shorter", result?.title)
    }

    @Test
    fun nullOrBlankTitleUsesEventDisplayTitle() {
        val nullTitle = selectEvent(listOf(validRow(title = null)), setOf(1L), now)
        val blankTitle = selectEvent(listOf(validRow(title = "  ")), setOf(1L), now)

        assertEquals("Event", nullTitle?.title)
        assertEquals("Event", blankTitle?.title)
    }

    private fun validRow(
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
