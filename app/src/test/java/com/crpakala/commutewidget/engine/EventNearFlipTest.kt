package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [eventNearFlipEpochMillis] and [calendarPlainEventSnapshot] back the far-located-event gate in
 * [CommuteRefresher.performCalendarRefresh]: a located event farther out than
 * `settings.eventTakeoverMinutes` renders the same zero-API plain card an unlocated event gets,
 * and [com.crpakala.commutewidget.schedule.EventNearScheduler] is armed for exactly the instant
 * [eventNearFlipEpochMillis] computes.
 */
class EventNearFlipTest {
    @Test
    fun eventNearFlipEpochMillis_subtractsTakeoverWindowFromEventStart() {
        val eventStart = 2_000_000_000L

        val result = eventNearFlipEpochMillis(eventStart, takeoverMinutes = 120)

        assertEquals(eventStart - 120 * 60_000L, result)
    }

    @Test
    fun eventNearFlipEpochMillis_matchesEventTakeoverAppliesBoundaryExactly() {
        // By definition the flip instant is where eventTakeoverApplies starts returning true.
        val eventStart = 1_800_000_000L
        val takeoverMinutes = 45

        val flip = eventNearFlipEpochMillis(eventStart, takeoverMinutes)

        assertTrue(eventTakeoverApplies(eventStart, true, flip, takeoverMinutes))
        assertFalse(eventTakeoverApplies(eventStart, true, flip - 1, takeoverMinutes))
    }

    @Test
    fun eventNearFlipEpochMillis_zeroTakeoverMinutes_equalsEventStart() {
        val eventStart = 5_000_000L

        assertEquals(eventStart, eventNearFlipEpochMillis(eventStart, takeoverMinutes = 0))
    }

    @Test
    fun calendarPlainEventSnapshot_unlocatedAndFarLocatedCallers_identicalApartFromInputs() {
        val health = HealthComputation()
        val unlocated = calendarPlainEventSnapshot(
            direction = Direction.TO_WORK,
            nowEpochMillis = 1_000_000L,
            eventTitle = "Dentist",
            eventStartEpochMillis = 1_500_000L,
            healthComputation = health,
        )
        val farLocated = calendarPlainEventSnapshot(
            direction = Direction.TO_WORK,
            nowEpochMillis = 1_000_000L,
            eventTitle = "Dinner",
            eventStartEpochMillis = 9_000_000L,
            healthComputation = health,
        )

        val unlocatedWithFarLocatedInputs = unlocated.copy(
            destinationLabel = farLocated.destinationLabel,
            eventStartEpochMillis = farLocated.eventStartEpochMillis,
        )
        assertEquals(unlocatedWithFarLocatedInputs, farLocated)
    }

    @Test
    fun calendarPlainEventSnapshot_carriesTitleAndStartButNoRouteOrMapData() {
        val result = calendarPlainEventSnapshot(
            direction = Direction.TO_HOME,
            nowEpochMillis = 42L,
            eventTitle = "Team sync",
            eventStartEpochMillis = 99_000L,
            healthComputation = HealthComputation(),
        )

        assertEquals("Team sync", result.destinationLabel)
        assertEquals(99_000L, result.eventStartEpochMillis)
        assertEquals(SnapshotMode.CALENDAR_EMPTY, result.mode)
        assertEquals(42L, result.fetchedAtEpochMillis)
        assertEquals(0L, result.durationSeconds)
        assertFalse(result.lastFetchFailed)
        assertNull(result.mapImagePath)
        assertNull(result.destinationLat)
        assertNull(result.destinationLng)
        assertNull(result.leaveByMinuteOfDay)
        assertNull(result.nextWindowLabel)
        assertNull(result.nextWindowStartMinuteOfDay)
    }

    @Test
    fun calendarPlainEventSnapshot_carriesHealthComputationThrough() {
        val health = HealthComputation(sleepEstimateMinutes = 410, shortSleepDay = true)

        val result = calendarPlainEventSnapshot(
            direction = Direction.TO_WORK,
            nowEpochMillis = 1L,
            eventTitle = "Standup",
            eventStartEpochMillis = 2L,
            healthComputation = health,
        )

        assertEquals(410, result.sleepEstimateMinutes)
        assertTrue(result.shortSleepDay)
    }
}
