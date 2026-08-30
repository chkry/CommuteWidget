package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.HealthNudge
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [failureSnapshot] is the mode/target-transition guard for [saveFailure][CommuteRefresher] -
 * these tests exist because that guard's absence was a real v3 bug: a `previous.copy()` silently
 * carried stale route/map/leave-by/next-window fields across a mode or destination change (see
 * the file-level doc on [failureSnapshot] for the full reasoning).
 */
class FailureSnapshotTest {
    private val staleCommute = CommuteSnapshot(
        direction = Direction.TO_WORK,
        durationSeconds = 1500L,
        durationNoTrafficSeconds = 1200L,
        distanceMeters = 9000L,
        mapImagePath = "/cache/map_a.png",
        fetchedAtEpochMillis = 1_000_000L,
        lastFetchFailed = false,
        lastErrorMessage = null,
        destinationLabel = "Work",
        destinationLat = 12.9,
        destinationLng = 77.6,
        leaveByMinuteOfDay = 500,
        mode = SnapshotMode.COMMUTE,
        eventStartEpochMillis = null,
        nextWindowLabel = null,
        nextWindowStartMinuteOfDay = null,
    )

    private val staleCalendarEvent = CommuteSnapshot(
        direction = Direction.TO_WORK,
        durationSeconds = 600L,
        durationNoTrafficSeconds = 500L,
        distanceMeters = 3000L,
        mapImagePath = "/cache/map_b.png",
        fetchedAtEpochMillis = 2_000_000L,
        lastFetchFailed = false,
        lastErrorMessage = null,
        destinationLabel = "Event A",
        destinationLat = 13.0,
        destinationLng = 78.0,
        leaveByMinuteOfDay = null,
        mode = SnapshotMode.CALENDAR_EVENT,
        eventStartEpochMillis = 3_000_000L,
        nextWindowLabel = null,
        nextWindowStartMinuteOfDay = null,
    )

    private val staleCalendarEmpty = CommuteSnapshot(
        direction = Direction.TO_WORK,
        durationSeconds = 0L,
        durationNoTrafficSeconds = 0L,
        distanceMeters = 0L,
        mapImagePath = null,
        fetchedAtEpochMillis = 4_000_000L,
        lastFetchFailed = false,
        lastErrorMessage = null,
        destinationLabel = null,
        destinationLat = null,
        destinationLng = null,
        leaveByMinuteOfDay = null,
        mode = SnapshotMode.CALENDAR_EMPTY,
        eventStartEpochMillis = null,
        nextWindowLabel = "To Work",
        nextWindowStartMinuteOfDay = 420,
    )

    /** Sprint 2: a stale snapshot carrying non-default health fields, for the carry-through tests below. */
    private val staleCommuteWithHealth = staleCommute.copy(
        healthNudges = listOf(
            HealthNudge(kind = HealthNudgeKind.WATER, label = "Water", startMinuteOfDay = 480, endMinuteOfDay = 510),
        ),
        sleepEstimateMinutes = 390,
        shortSleepDay = true,
    )

    @Test
    fun noPrevious_healthFieldsDefaultToEmptyRatherThanCrashing() {
        val result = failureSnapshot(
            previous = null,
            direction = Direction.TO_WORK,
            message = "Route fetch failed",
            modeOverride = SnapshotMode.COMMUTE,
            destinationLabelOverride = "Work",
        )

        assertEquals(emptyList<HealthNudge>(), result.healthNudges)
        assertNull(result.sleepEstimateMinutes)
        assertFalse(result.shortSleepDay)
    }

    @Test
    fun sameTarget_preservesHealthFieldsAsLastKnownGood() {
        // Already true via previous.copy() (health fields are untouched by that copy), but
        // asserted explicitly since it is the load-bearing half of Sprint 2's carry-through
        // contract - see the target-changes test below for the half that needed a real fix.
        val result = failureSnapshot(
            previous = staleCommuteWithHealth,
            direction = Direction.TO_WORK,
            message = "Route fetch failed",
            modeOverride = SnapshotMode.COMMUTE,
            destinationLabelOverride = "Work",
        )

        assertEquals(staleCommuteWithHealth.healthNudges, result.healthNudges)
        assertEquals(390, result.sleepEstimateMinutes)
        assertTrue(result.shortSleepDay)
    }

    @Test
    fun targetChanges_stillCarriesHealthFieldsForwardEvenThoughRouteFieldsAreCleared() {
        // Health nudges are orthogonal to the route/mode/target a failure snapshot describes -
        // a mode/destination change must clear stale route/map/leave-by/next-window data (the
        // existing guard this test file otherwise covers) but must NOT blank the health nudges.
        val result = failureSnapshot(
            previous = staleCommuteWithHealth,
            direction = Direction.TO_WORK,
            message = "Geocode failed",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Team sync",
            eventStartEpochMillisOverride = 5_000_000L,
        )

        assertEquals(staleCommuteWithHealth.healthNudges, result.healthNudges)
        assertEquals(390, result.sleepEstimateMinutes)
        assertTrue(result.shortSleepDay)
        // Confirm this is still exercising the target-change branch (route data cleared as before).
        assertNull(result.mapImagePath)
    }

    @Test
    fun noPrevious_buildsFreshEmptyFailureSnapshotForRequestedMode() {
        val result = failureSnapshot(
            previous = null,
            direction = Direction.TO_WORK,
            message = "Route fetch failed",
            modeOverride = SnapshotMode.COMMUTE,
            destinationLabelOverride = "Work",
        )

        assertEquals(SnapshotMode.COMMUTE, result.mode)
        assertTrue(result.lastFetchFailed)
        assertEquals("Route fetch failed", result.lastErrorMessage)
        assertEquals("Work", result.destinationLabel)
        assertEquals(0L, result.durationSeconds)
        assertNull(result.mapImagePath)
        assertNull(result.destinationLat)
        assertNull(result.destinationLng)
        assertNull(result.leaveByMinuteOfDay)
        assertNull(result.eventStartEpochMillis)
    }

    @Test
    fun sameModeSameLabel_preservesStaleRouteAndMapDataAsLastKnownGood() {
        val result = failureSnapshot(
            previous = staleCommute,
            direction = Direction.TO_WORK,
            message = "Route fetch failed",
            modeOverride = SnapshotMode.COMMUTE,
            destinationLabelOverride = "Work",
        )

        assertEquals(SnapshotMode.COMMUTE, result.mode)
        assertTrue(result.lastFetchFailed)
        assertEquals("Route fetch failed", result.lastErrorMessage)
        assertEquals(1500L, result.durationSeconds)
        assertEquals("/cache/map_a.png", result.mapImagePath)
        assertEquals(12.9, result.destinationLat!!, 0.0001)
        assertEquals(500, result.leaveByMinuteOfDay)
    }

    @Test
    fun commuteToCalendarEvent_clearsLeaveByAndMapAndCoordsFromOldCommute() {
        val result = failureSnapshot(
            previous = staleCommute,
            direction = Direction.TO_WORK,
            message = "Geocode failed",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Team sync",
            eventStartEpochMillisOverride = 5_000_000L,
        )

        assertEquals(SnapshotMode.CALENDAR_EVENT, result.mode)
        assertEquals("Team sync", result.destinationLabel)
        assertEquals(5_000_000L, result.eventStartEpochMillis)
        // None of the old commute's route/map/leave-by data may leak into the calendar snapshot.
        assertNull(result.mapImagePath)
        assertNull(result.destinationLat)
        assertNull(result.destinationLng)
        assertNull(result.leaveByMinuteOfDay)
        assertEquals(0L, result.durationSeconds)
    }

    @Test
    fun calendarEmptyToCommute_clearsNextWindowFieldsFromOldCalendarEmpty() {
        val result = failureSnapshot(
            previous = staleCalendarEmpty,
            direction = Direction.TO_HOME,
            message = "Route fetch failed",
            modeOverride = SnapshotMode.COMMUTE,
            destinationLabelOverride = "Home",
        )

        assertEquals(SnapshotMode.COMMUTE, result.mode)
        assertEquals("Home", result.destinationLabel)
        assertNull(result.nextWindowLabel)
        assertNull(result.nextWindowStartMinuteOfDay)
        assertNull(result.eventStartEpochMillis)
    }

    @Test
    fun calendarEventToDifferentEvent_sameModeButDifferentLabel_clearsStaleRouteData() {
        val result = failureSnapshot(
            previous = staleCalendarEvent,
            direction = Direction.TO_WORK,
            message = "Geocode failed",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Event B",
            eventStartEpochMillisOverride = 9_000_000L,
        )

        assertEquals(SnapshotMode.CALENDAR_EVENT, result.mode)
        assertEquals("Event B", result.destinationLabel)
        assertEquals(9_000_000L, result.eventStartEpochMillis)
        // Event A's map/coordinates must not be attributed to Event B.
        assertNull(result.mapImagePath)
        assertNull(result.destinationLat)
        assertNull(result.destinationLng)
        assertEquals(0L, result.durationSeconds)
    }

    @Test
    fun calendarEventToSameEvent_preservesStaleRouteDataAsLastKnownGood() {
        val result = failureSnapshot(
            previous = staleCalendarEvent,
            direction = Direction.TO_WORK,
            message = "Route fetch failed",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Event A",
            eventStartEpochMillisOverride = staleCalendarEvent.eventStartEpochMillis,
        )

        assertEquals(SnapshotMode.CALENDAR_EVENT, result.mode)
        assertEquals("/cache/map_b.png", result.mapImagePath)
        assertEquals(13.0, result.destinationLat!!, 0.0001)
        assertEquals(600L, result.durationSeconds)
    }

    @Test
    fun genericFallback_noOverrides_preservesEverythingAndJustFlagsFailure() {
        // Mirrors CommuteRefresher.refreshNow's catch-all: no mode/label/event-start is known at
        // that call site, so the only safe behavior is to keep showing the last snapshot as-is.
        val result = failureSnapshot(
            previous = staleCalendarEvent,
            direction = Direction.TO_WORK,
            message = "Unexpected error",
        )

        assertEquals(staleCalendarEvent.mode, result.mode)
        assertEquals(staleCalendarEvent.mapImagePath, result.mapImagePath)
        assertEquals(staleCalendarEvent.eventStartEpochMillis, result.eventStartEpochMillis)
        assertTrue(result.lastFetchFailed)
        assertEquals("Unexpected error", result.lastErrorMessage)
    }
}
