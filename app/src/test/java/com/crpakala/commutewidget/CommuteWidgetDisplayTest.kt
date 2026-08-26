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
    fun formatDistanceKm_oneDecimal() {
        assertEquals("12.4 km", formatDistanceKm(12_400L))
        assertEquals("0.5 km", formatDistanceKm(500L))
    }

    @Test
    fun isClockAppAlarm_allowsClockAppsOnly() {
        assertTrue(isClockAppAlarm("com.sec.android.app.clockpackage"))
        assertTrue(isClockAppAlarm("com.google.android.deskclock"))
        assertFalse(isClockAppAlarm("com.samsung.android.app.routines"))
        assertFalse(isClockAppAlarm(null))
    }

    @Test
    fun bestDepartureLineText_shortForm() {
        assertEquals("Best: 5:39 pm", bestDepartureLineText(17 * 60 + 39))
        assertEquals("Best: 9:05 am", bestDepartureLineText(9 * 60 + 5))
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

    @Test
    fun isWindDown_trueWhenTitleAndStartPresent() {
        val snapshot = emptySnapshot().copy(
            tomorrowEventTitle = "Standup",
            tomorrowEventStartEpochMillis = 1L,
        )
        assertTrue(isWindDown(snapshot))
    }

    @Test
    fun isWindDown_falseWhenTitleMissing() {
        val snapshot = emptySnapshot().copy(tomorrowEventStartEpochMillis = 1L)
        assertFalse(isWindDown(snapshot))
    }

    @Test
    fun isWindDown_falseWhenStartMissing() {
        val snapshot = emptySnapshot().copy(tomorrowEventTitle = "Standup")
        assertFalse(isWindDown(snapshot))
    }

    @Test
    fun isWindDown_falseWhenBothMissing() {
        assertFalse(isWindDown(emptySnapshot()))
    }

    @Test
    fun formatCountdown_hoursAndMinutes() {
        assertEquals("in 2h 10m", formatCountdown(2 * 60 + 10))
        assertEquals("in 1h 45m", formatCountdown(60 + 45))
    }

    @Test
    fun formatCountdown_minutesOnly() {
        assertEquals("in 45m", formatCountdown(45))
    }

    @Test
    fun formatCountdown_exactHours() {
        assertEquals("in 2h", formatCountdown(120))
    }

    @Test
    fun formatFreeFor_hoursAndMinutes() {
        assertEquals("Free for 2h 10m", formatFreeFor(2 * 60 + 10))
        assertEquals("Free for 1h 45m", formatFreeFor(60 + 45))
    }

    @Test
    fun formatFreeFor_minutesOnly() {
        assertEquals("Free for 45m", formatFreeFor(45))
    }

    @Test
    fun formatFreeFor_exactHours() {
        assertEquals("Free for 2h", formatFreeFor(120))
    }

    @Test
    fun eventCountdownMinutes_nullWhenStartMissing() {
        assertEquals(null, eventCountdownMinutes(null, nowEpochMillis = 1_000L))
    }

    @Test
    fun eventCountdownMinutes_nullWhenAlreadyStarted() {
        val now = 10_000L
        assertEquals(null, eventCountdownMinutes(now, now))
        assertEquals(null, eventCountdownMinutes(now - 1L, now))
    }

    @Test
    fun eventCountdownMinutes_nullWhenLessThanOneMinuteAway() {
        val now = 10_000L
        assertEquals(null, eventCountdownMinutes(now + 59_999L, now))
    }

    @Test
    fun eventCountdownMinutes_floorsWholeMinutes() {
        val now = 10_000L
        assertEquals(1, eventCountdownMinutes(now + 60_000L, now))
        assertEquals(105, eventCountdownMinutes(now + 105L * 60_000L, now))
    }

    @Test
    fun formatTodayBrief_pluralWithFirstStart() {
        val zone = ZoneId.of("UTC")
        val first = ZonedDateTime.of(2026, 8, 26, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("3 meetings · first 10:00 am", formatTodayBrief(3, first, zone))
    }

    @Test
    fun formatTodayBrief_singular() {
        val zone = ZoneId.of("UTC")
        val first = ZonedDateTime.of(2026, 8, 26, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("1 meeting · first 9:05 am", formatTodayBrief(1, first, zone))
    }

    @Test
    fun formatTodayBrief_nullFirstStartOmitsTime() {
        assertEquals("3 meetings", formatTodayBrief(3, null))
        assertEquals("1 meeting", formatTodayBrief(1, null))
    }

    @Test
    fun formatAlarmLine_prefixesGlyphAndClockTime() {
        val zone = ZoneId.of("UTC")
        val epoch = ZonedDateTime.of(2026, 8, 26, 6, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("⏰ Alarm 6:30 am", formatAlarmLine(epoch, zone))
    }

    @Test
    fun shouldShowEventCountdown_calendarEventWhenSizeAllows() {
        val snapshot = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EVENT)
        assertTrue(shouldShowEventCountdown(snapshot, captionAllowedForSize = true))
    }

    @Test
    fun shouldShowEventCountdown_hiddenWhenSizeDoesNotAllowIt() {
        val snapshot = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EVENT)
        assertFalse(shouldShowEventCountdown(snapshot, captionAllowedForSize = false))
    }

    @Test
    fun shouldShowEventCountdown_hiddenForNonCalendarEventModes() {
        val commute = emptySnapshot().copy(mode = SnapshotMode.COMMUTE)
        val calendarEmpty = emptySnapshot().copy(mode = SnapshotMode.CALENDAR_EMPTY)
        assertFalse(shouldShowEventCountdown(commute, captionAllowedForSize = true))
        assertFalse(shouldShowEventCountdown(calendarEmpty, captionAllowedForSize = true))
    }

    @Test
    fun shouldShowTodayBrief_commuteToWorkWithPositiveCountWhenSizeAllows() {
        val snapshot = emptySnapshot().copy(
            mode = SnapshotMode.COMMUTE,
            direction = Direction.TO_WORK,
            todayEventCount = 3,
        )
        assertTrue(shouldShowTodayBrief(snapshot, captionAllowedForSize = true))
    }

    @Test
    fun shouldShowTodayBrief_hiddenWhenSizeDoesNotAllowIt() {
        val snapshot = emptySnapshot().copy(
            mode = SnapshotMode.COMMUTE,
            direction = Direction.TO_WORK,
            todayEventCount = 3,
        )
        assertFalse(shouldShowTodayBrief(snapshot, captionAllowedForSize = false))
    }

    @Test
    fun shouldShowTodayBrief_hiddenWhenCountNullOrZero() {
        val missing = emptySnapshot().copy(mode = SnapshotMode.COMMUTE, direction = Direction.TO_WORK)
        val zero = emptySnapshot().copy(
            mode = SnapshotMode.COMMUTE,
            direction = Direction.TO_WORK,
            todayEventCount = 0,
        )
        assertFalse(shouldShowTodayBrief(missing, captionAllowedForSize = true))
        assertFalse(shouldShowTodayBrief(zero, captionAllowedForSize = true))
    }

    @Test
    fun shouldShowTodayBrief_hiddenForToHomeAndNonCommute() {
        val toHome = emptySnapshot().copy(
            mode = SnapshotMode.COMMUTE,
            direction = Direction.TO_HOME,
            todayEventCount = 3,
        )
        val calendarEvent = emptySnapshot().copy(
            mode = SnapshotMode.CALENDAR_EVENT,
            direction = Direction.TO_WORK,
            todayEventCount = 3,
        )
        assertFalse(shouldShowTodayBrief(toHome, captionAllowedForSize = true))
        assertFalse(shouldShowTodayBrief(calendarEvent, captionAllowedForSize = true))
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
