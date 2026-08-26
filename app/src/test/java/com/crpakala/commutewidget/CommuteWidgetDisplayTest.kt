package com.crpakala.commutewidget

import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CommuteWidgetDisplayTest {
    @Test
    fun formatEta_minutesKeepFullSuffix() {
        assertEquals("46 min", formatEta(46 * 60L))
        assertEquals("1 min", formatEta(30L))
        assertEquals("0 min", formatEta(0L))
    }

    @Test
    fun formatEta_hoursUseCompactForm() {
        assertEquals("1h 15m", formatEta((75 * 60).toLong()))
        assertEquals("2h", formatEta((120 * 60).toLong()))
    }

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
    fun shouldShowLeaveBy_commuteShownWhenEnabledAndPresent() {
        val snapshot = emptySnapshot().copy(
            mode = SnapshotMode.COMMUTE,
            leaveByMinuteOfDay = 14 * 60 + 40,
        )
        assertTrue(shouldShowLeaveBy(snapshot, leaveByEnabled = true))
    }

    @Test
    fun shouldShowLeaveBy_calendarEventShownWhenEnabledAndPresent() {
        val snapshot = emptySnapshot().copy(
            mode = SnapshotMode.CALENDAR_EVENT,
            leaveByMinuteOfDay = 14 * 60 + 40,
        )
        assertTrue(shouldShowLeaveBy(snapshot, leaveByEnabled = true))
    }

    @Test
    fun shouldShowLeaveBy_calendarEmptyNeverShown() {
        val snapshot = emptySnapshot().copy(leaveByMinuteOfDay = 14 * 60 + 40)
        assertFalse(shouldShowLeaveBy(snapshot, leaveByEnabled = true))
    }

    @Test
    fun shouldShowLeaveBy_disabledToggleHidesEvenWhenFieldPresent() {
        val commute = emptySnapshot().copy(
            mode = SnapshotMode.COMMUTE,
            leaveByMinuteOfDay = 14 * 60 + 40,
        )
        val event = emptySnapshot().copy(
            mode = SnapshotMode.CALENDAR_EVENT,
            leaveByMinuteOfDay = 14 * 60 + 40,
        )
        assertFalse(shouldShowLeaveBy(commute, leaveByEnabled = false))
        assertFalse(shouldShowLeaveBy(event, leaveByEnabled = false))
    }

    @Test
    fun shouldShowLeaveBy_nullFieldHidesEvenWhenEnabled() {
        val commute = emptySnapshot().copy(mode = SnapshotMode.COMMUTE, leaveByMinuteOfDay = null)
        val event = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EVENT, leaveByMinuteOfDay = null)
        assertFalse(shouldShowLeaveBy(commute, leaveByEnabled = true))
        assertFalse(shouldShowLeaveBy(event, leaveByEnabled = true))
    }

    @Test
    fun shouldShowRoutedCaption_calendarEventRoutedAndAllowedForSize() {
        val snapshot = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EVENT, routedOverEarlier = true)
        assertTrue(shouldShowRoutedCaption(snapshot, captionAllowedForSize = true))
    }

    @Test
    fun shouldShowRoutedCaption_hiddenWhenSizeDoesNotAllowIt() {
        val snapshot = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EVENT, routedOverEarlier = true)
        assertFalse(shouldShowRoutedCaption(snapshot, captionAllowedForSize = false))
    }

    @Test
    fun shouldShowRoutedCaption_hiddenWhenNotRouted() {
        val snapshot = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EVENT, routedOverEarlier = false)
        assertFalse(shouldShowRoutedCaption(snapshot, captionAllowedForSize = true))
    }

    @Test
    fun shouldShowRoutedCaption_hiddenForNonCalendarEventModesEvenIfFlagIsSet() {
        val commute = emptySnapshot().copy(mode = SnapshotMode.COMMUTE, routedOverEarlier = true)
        val calendarEmpty = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EMPTY, routedOverEarlier = true)
        assertFalse(shouldShowRoutedCaption(commute, captionAllowedForSize = true))
        assertFalse(shouldShowRoutedCaption(calendarEmpty, captionAllowedForSize = true))
    }

    @Test
    fun formatEventClockTime_afternoon() {
        val zone = ZoneId.of("America/Los_Angeles")
        val epoch = ZonedDateTime.of(2026, 8, 25, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("3:30 pm", formatEventClockTime(epoch, zone))
    }

    @Test
    fun formatEventClockTime_morningPaddedMinute() {
        val zone = ZoneId.of("UTC")
        val epoch = ZonedDateTime.of(2026, 1, 1, 7, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("7:05 am", formatEventClockTime(epoch, zone))
    }

    @Test
    fun formatEventClockTime_noonAndMidnightAreTwelve() {
        val zone = ZoneId.of("UTC")
        val noon = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val midnight = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("12:00 pm", formatEventClockTime(noon, zone))
        assertEquals("12:00 am", formatEventClockTime(midnight, zone))
    }

    @Test
    fun formatClockTime_windowStartMorningAndEvening() {
        assertEquals("7:00 am", formatClockTime(7 * 60))
        assertEquals("5:30 pm", formatClockTime(17 * 60 + 30))
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

    @Test
    fun mapAreaPlaceholderLines_emptyForCommuteMode() {
        val snapshot = emptySnapshot().copy(mode = SnapshotMode.COMMUTE)
        assertEquals(emptyList<String>(), mapAreaPlaceholderLines(snapshot))
    }

    @Test
    fun mapAreaPlaceholderLines_calendarEventShowsTitleAndTime() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 8, 26, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        val snapshot = emptySnapshot(
            destinationLabel = "Client meeting",
            eventStartEpochMillis = start,
        ).copy(mode = SnapshotMode.CALENDAR_EVENT)
        assertEquals(listOf("Client meeting", "3:30 pm"), mapAreaPlaceholderLines(snapshot, zone))
    }

    @Test
    fun mapAreaPlaceholderLines_calendarEventOmitsLeaveByWhenPresent() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 8, 26, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        val snapshot = emptySnapshot(
            destinationLabel = "Client meeting",
            eventStartEpochMillis = start,
        ).copy(
            mode = SnapshotMode.CALENDAR_EVENT,
            leaveByMinuteOfDay = 14 * 60 + 40,
        )
        assertEquals(listOf("Client meeting", "3:30 pm"), mapAreaPlaceholderLines(snapshot, zone))
    }

    @Test
    fun mapAreaPlaceholderLines_emptyForUnlocatedEvent() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 8, 26, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        val snapshot = emptySnapshot(
            destinationLabel = "Dentist",
            eventStartEpochMillis = start,
        )
        assertEquals(emptyList<String>(), mapAreaPlaceholderLines(snapshot, zone))
    }

    @Test
    fun mapAreaPlaceholderLines_emptyForNextWindow() {
        val snapshot = emptySnapshot(
            nextWindowLabel = "To Work",
            nextWindowStartMinuteOfDay = 7 * 60,
        )
        assertEquals(emptyList<String>(), mapAreaPlaceholderLines(snapshot))
    }

    @Test
    fun mapAreaPlaceholderLines_emptyForNone() {
        assertEquals(emptyList<String>(), mapAreaPlaceholderLines(emptySnapshot()))
    }

    @Test
    fun etaDisplayState_pendingWhenRefreshIsActive() {
        val now = 1_000_000L
        assertEquals(
            EtaDisplayState.PENDING,
            etaDisplayState(
                refreshingSinceEpochMillis = now - 1_000L,
                fetchedAtEpochMillis = now - 1_000L,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun etaDisplayState_pendingWinsOverStale() {
        val now = 1_000_000L
        assertEquals(
            EtaDisplayState.PENDING,
            etaDisplayState(
                refreshingSinceEpochMillis = now,
                fetchedAtEpochMillis = now - ETA_STALE_AFTER_MILLIS - 1L,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun etaDisplayState_staleWhenSettledAndOlderThanTenMinutes() {
        val now = 1_000_000L
        assertEquals(
            EtaDisplayState.STALE,
            etaDisplayState(
                refreshingSinceEpochMillis = null,
                fetchedAtEpochMillis = now - ETA_STALE_AFTER_MILLIS - 1L,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun etaDisplayState_freshAtExactlyTenMinutes() {
        val now = 1_000_000L
        assertEquals(
            EtaDisplayState.FRESH,
            etaDisplayState(
                refreshingSinceEpochMillis = null,
                fetchedAtEpochMillis = now - ETA_STALE_AFTER_MILLIS,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun etaDisplayState_freshWhenSettledAndRecent() {
        val now = 1_000_000L
        assertEquals(
            EtaDisplayState.FRESH,
            etaDisplayState(
                refreshingSinceEpochMillis = null,
                fetchedAtEpochMillis = now - 30_000L,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun etaDisplayState_freshWhenRefreshingFlagHasExpiredAndFetchIsRecent() {
        val now = 1_000_000L
        assertEquals(
            EtaDisplayState.FRESH,
            etaDisplayState(
                refreshingSinceEpochMillis = now - 60_000L,
                fetchedAtEpochMillis = now - 1_000L,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun etaDisplayState_staleWhenRefreshingFlagHasExpiredAndFetchIsOld() {
        val now = 1_000_000L
        assertEquals(
            EtaDisplayState.STALE,
            etaDisplayState(
                refreshingSinceEpochMillis = now - 60_000L,
                fetchedAtEpochMillis = now - ETA_STALE_AFTER_MILLIS - 1L,
                nowEpochMillis = now,
            ),
        )
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
