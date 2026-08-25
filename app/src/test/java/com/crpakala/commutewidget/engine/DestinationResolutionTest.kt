package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.data.ActiveFavourite
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DestinationResolutionTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val morningWeekday = ZonedDateTime.of(2026, 8, 24, 8, 0, 0, 0, zone)
    private val eveningWeekday = ZonedDateTime.of(2026, 8, 24, 18, 0, 0, 0, zone)
    private val settings = AppSettings(switchMinuteOfDay = 14 * 60)
    private val favourite = Favourite("Gym", Place("123 Gym St", 12.9, 77.6))
    private val calendarTarget = DestinationTarget.CalendarTarget("Dentist", LatLng(12.8, 77.5))

    @Test
    fun activeFavourite_takesPrecedenceOverCalendarAndDefault() {
        val active = ActiveFavourite(favourite, activatedAtEpochMillis = 0, expiresAtEpochMillis = Long.MAX_VALUE)

        val target = resolveDestinationTarget(settings, active, calendarTarget, morningWeekday)

        assertTrue(target is DestinationTarget.FavouriteTarget)
        assertEquals(favourite, (target as DestinationTarget.FavouriteTarget).favourite)
    }

    @Test
    fun expiredFavourite_fallsThroughToCalendar() {
        val expired = ActiveFavourite(favourite, activatedAtEpochMillis = 0, expiresAtEpochMillis = 1)

        val target = resolveDestinationTarget(settings, expired, calendarTarget, morningWeekday)

        assertTrue(target is DestinationTarget.CalendarTarget)
        assertEquals(calendarTarget, target)
    }

    @Test
    fun noFavourite_calendarTargetPresent_usesCalendar() {
        val target = resolveDestinationTarget(settings, null, calendarTarget, morningWeekday)

        assertEquals(calendarTarget, target)
    }

    @Test
    fun noFavouriteNoCalendar_fallsBackToDefaultWithDecidedDirection() {
        val morningTarget = resolveDestinationTarget(settings, null, null, morningWeekday)
        val eveningTarget = resolveDestinationTarget(settings, null, null, eveningWeekday)

        assertEquals(DestinationTarget.DefaultTarget(Direction.TO_WORK), morningTarget)
        assertEquals(DestinationTarget.DefaultTarget(Direction.TO_HOME), eveningTarget)
    }

    @Test
    fun nullActiveFavourite_isNeverTreatedAsActive() {
        val target = resolveDestinationTarget(settings, null, null, morningWeekday)

        assertFalse(target is DestinationTarget.FavouriteTarget)
    }

    @Test
    fun shouldRecordHistorySample_onlyDefaultTargetAndHistoryEnabled() {
        val default = DestinationTarget.DefaultTarget(Direction.TO_WORK)
        val favouriteTarget = DestinationTarget.FavouriteTarget(favourite)

        assertTrue(shouldRecordHistorySample(historyEnabled = true, target = default))
        assertFalse(shouldRecordHistorySample(historyEnabled = false, target = default))
        assertFalse(shouldRecordHistorySample(historyEnabled = true, target = favouriteTarget))
        assertFalse(shouldRecordHistorySample(historyEnabled = true, target = calendarTarget))
        assertFalse(shouldRecordHistorySample(historyEnabled = false, target = favouriteTarget))
    }

    @Test
    fun shouldAttemptCalendarLookup_onlyWhenTapAndFullyConfigured() {
        val enabledSettings = settings.copy(calendarEnabled = true, selectedCalendarIds = setOf(1L))

        assertTrue(shouldAttemptCalendarLookup(RefreshTrigger.TAP, enabledSettings, hasCalendarPermission = true))
        assertFalse(shouldAttemptCalendarLookup(RefreshTrigger.AUTO, enabledSettings, hasCalendarPermission = true))
        assertFalse(shouldAttemptCalendarLookup(RefreshTrigger.SLOT, enabledSettings, hasCalendarPermission = true))
        assertFalse(shouldAttemptCalendarLookup(RefreshTrigger.TAP, enabledSettings, hasCalendarPermission = false))
        assertFalse(shouldAttemptCalendarLookup(RefreshTrigger.TAP, settings, hasCalendarPermission = true))
        assertFalse(
            shouldAttemptCalendarLookup(
                RefreshTrigger.TAP,
                enabledSettings.copy(selectedCalendarIds = emptySet()),
                hasCalendarPermission = true,
            ),
        )
    }
}
