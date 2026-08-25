package com.crpakala.commutewidget

import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CommuteWidgetDisplayTest {
    @Test
    fun destinationDisplayLabel_fallsBackToWorkWhenSnapshotLabelMissing() {
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_WORK, null))
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_WORK, ""))
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_WORK, "   "))
    }

    @Test
    fun destinationDisplayLabel_fallsBackToHomeWhenSnapshotLabelMissing() {
        assertEquals("To Home", destinationDisplayLabel(Direction.TO_HOME, null))
    }

    @Test
    fun destinationDisplayLabel_prefixesSnapshotLabel() {
        assertEquals("To Gym", destinationDisplayLabel(Direction.TO_WORK, "Gym"))
        assertEquals("To Client meeting", destinationDisplayLabel(Direction.TO_HOME, "Client meeting"))
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_HOME, "Work"))
    }

    @Test
    fun destinationDisplayLabel_trimsSnapshotLabel() {
        assertEquals("To Gym", destinationDisplayLabel(Direction.TO_WORK, "  Gym  "))
    }

    @Test
    fun formatLeaveByLine_formatsMorningAndAfternoon() {
        assertEquals("Leave by 8:42 am", formatLeaveByLine(8 * 60 + 42))
        assertEquals("Leave by 1:05 pm", formatLeaveByLine(13 * 60 + 5))
    }

    @Test
    fun formatLeaveByLine_midnightAndNoonAreTwelve() {
        assertEquals("Leave by 12:00 am", formatLeaveByLine(0))
        assertEquals("Leave by 12:00 pm", formatLeaveByLine(12 * 60))
    }

    @Test
    fun formatLeaveByLine_padsSingleDigitMinutes() {
        assertEquals("Leave by 9:05 am", formatLeaveByLine(9 * 60 + 5))
    }

    @Test
    fun formatLeaveByLine_clampsOutOfRange() {
        assertEquals("Leave by 12:00 am", formatLeaveByLine(-1))
        assertEquals("Leave by 11:59 pm", formatLeaveByLine(24 * 60))
    }

    @Test
    fun isLeaveByPast_onlyAfterTheLeaveByMinute() {
        val leaveBy = 8 * 60 + 42
        assertFalse(isLeaveByPast(leaveBy, leaveBy - 1))
        assertFalse(isLeaveByPast(leaveBy, leaveBy))
        assertTrue(isLeaveByPast(leaveBy, leaveBy + 1))
    }

    @Test
    fun favouriteChipsToShow_capsWideRowAtTwo() {
        val favourites = listOf(
            favourite("Gym"),
            favourite("School"),
            favourite("Airport"),
            favourite("Clinic"),
            favourite("Depot"),
        )
        val shown = favouriteChipsToShow(favourites, WIDE_MAX_FAVOURITE_CHIPS)
        assertEquals(listOf("Gym", "School"), shown.map { it.label })
    }

    @Test
    fun favouriteChipsToShow_preservesOrderAndAllowsAllOnLarge() {
        val favourites = listOf(favourite("Gym"), favourite("School"), favourite("Airport"))
        assertEquals(favourites, favouriteChipsToShow(favourites, favourites.size))
        assertEquals(emptyList<Favourite>(), favouriteChipsToShow(favourites, 0))
    }

    @Test
    fun formatEventAtTime_afternoon() {
        val zone = ZoneId.of("America/Los_Angeles")
        val epoch = ZonedDateTime.of(2026, 8, 25, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("at 3:30 pm", formatEventAtTime(epoch, zone))
    }

    @Test
    fun formatEventAtTime_morningPaddedMinute() {
        val zone = ZoneId.of("UTC")
        val epoch = ZonedDateTime.of(2026, 1, 1, 7, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("at 7:05 am", formatEventAtTime(epoch, zone))
    }

    @Test
    fun formatEventAtTime_noonAndMidnightAreTwelve() {
        val zone = ZoneId.of("UTC")
        val noon = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val midnight = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("at 12:00 pm", formatEventAtTime(noon, zone))
        assertEquals("at 12:00 am", formatEventAtTime(midnight, zone))
    }

    @Test
    fun formatNextWindowLine_usesClockTime() {
        assertEquals("Next: To Work at 7:00 am", formatNextWindowLine("To Work", 7 * 60))
        assertEquals("Next: To Home at 5:30 pm", formatNextWindowLine("To Home", 17 * 60 + 30))
    }

    @Test
    fun calendarEmptyCase_prefersUnlocatedEventOverNextWindow() {
        val snapshot = emptySnapshot(
            destinationLabel = "Dentist",
            eventStartEpochMillis = 1L,
            nextWindowLabel = "To Work",
            nextWindowStartMinuteOfDay = 420,
        )
        assertEquals(CalendarEmptyCase.UNLOCATED_EVENT, calendarEmptyCase(snapshot))
    }

    @Test
    fun calendarEmptyCase_nextWindowWhenEventFieldsIncomplete() {
        val snapshot = emptySnapshot(
            destinationLabel = "Dentist",
            eventStartEpochMillis = null,
            nextWindowLabel = "To Work",
            nextWindowStartMinuteOfDay = 420,
        )
        assertEquals(CalendarEmptyCase.NEXT_WINDOW, calendarEmptyCase(snapshot))
    }

    @Test
    fun calendarEmptyCase_nextWindowWhenNoEvent() {
        val snapshot = emptySnapshot(
            nextWindowLabel = "To Home",
            nextWindowStartMinuteOfDay = 17 * 60,
        )
        assertEquals(CalendarEmptyCase.NEXT_WINDOW, calendarEmptyCase(snapshot))
    }

    @Test
    fun calendarEmptyCase_noneWhenAllNull() {
        assertEquals(CalendarEmptyCase.NONE, calendarEmptyCase(emptySnapshot()))
    }

    @Test
    fun calendarEmptyCase_noneWhenWindowFieldsIncomplete() {
        val snapshot = emptySnapshot(
            nextWindowLabel = "To Work",
            nextWindowStartMinuteOfDay = null,
        )
        assertEquals(CalendarEmptyCase.NONE, calendarEmptyCase(snapshot))
    }

    @Test
    fun calendarEventTitle_trimsAndFallsBack() {
        assertEquals("Dentist", calendarEventTitle("  Dentist  "))
        assertEquals("Event", calendarEventTitle(null))
        assertEquals("Event", calendarEventTitle("   "))
    }

    private fun favourite(label: String): Favourite {
        return Favourite(label = label, place = Place(address = label, lat = 0.0, lng = 0.0))
    }

    private fun emptySnapshot(
        destinationLabel: String? = null,
        eventStartEpochMillis: Long? = null,
        nextWindowLabel: String? = null,
        nextWindowStartMinuteOfDay: Int? = null,
    ): CommuteSnapshot {
        return CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 0L,
            durationNoTrafficSeconds = 0L,
            distanceMeters = 0L,
            mapImagePath = null,
            fetchedAtEpochMillis = 0L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            destinationLabel = destinationLabel,
            mode = SnapshotMode.CALENDAR_EMPTY,
            eventStartEpochMillis = eventStartEpochMillis,
            nextWindowLabel = nextWindowLabel,
            nextWindowStartMinuteOfDay = nextWindowStartMinuteOfDay,
        )
    }
}
