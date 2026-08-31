package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.CustomPillOccurrence
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
 * the file-level doc on [failureSnapshot] for the full reasoning). It also pins the newer split
 * for a [SnapshotMode.CALENDAR_EVENT] failure specifically: a NEW-target (first-attempt) failure
 * now falls back to the plain card instead of a broken routed shell, while a SAME-target failure
 * is unaffected and keeps the original stale-preserving behavior.
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
        customPillOccurrences = listOf(
            CustomPillOccurrence(pillId = "p1", label = "Vitamin D", slotMinuteOfDay = 480, active = true),
        ),
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
        assertEquals(emptyList<CustomPillOccurrence>(), result.customPillOccurrences)
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
        assertEquals(staleCommuteWithHealth.customPillOccurrences, result.customPillOccurrences)
    }

    @Test
    fun eventTargetChanges_fallsBackToPlainCardButStillCarriesHealthFieldsForward() {
        // Health nudges are orthogonal to the route/mode/target a failure snapshot describes - a
        // first-attempt (new-target) CALENDAR_EVENT failure now falls back to the plain card (see
        // the plain-card-fallback tests below) rather than the old broken-shell shape, but that
        // fallback must still NOT blank the health nudges, nor the custom pill reminder fields
        // added afterward.
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
        assertEquals(staleCommuteWithHealth.customPillOccurrences, result.customPillOccurrences)
        // Confirm this is still exercising the target-change branch (route data cleared as
        // before), now landing on the plain card rather than a broken CALENDAR_EVENT shell.
        assertEquals(SnapshotMode.CALENDAR_EMPTY, result.mode)
        assertFalse(result.lastFetchFailed)
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
    fun commuteToCalendarEvent_newTargetFallsBackToPlainCardInsteadOfBrokenShell() {
        // A commute window ending right as a located event enters routing range is a NEW target
        // for CALENDAR_EVENT (the previous snapshot was COMMUTE) - its first-attempt failure must
        // render the plain name+time card, not a broken routed shell with a warning glyph and a
        // 0-min ETA.
        val result = failureSnapshot(
            previous = staleCommute,
            direction = Direction.TO_WORK,
            message = "Geocode failed",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Team sync",
            eventStartEpochMillisOverride = 5_000_000L,
        )

        assertEquals(SnapshotMode.CALENDAR_EMPTY, result.mode)
        assertFalse(result.lastFetchFailed)
        assertNull(result.lastErrorMessage)
        assertEquals("Team sync", result.destinationLabel)
        assertEquals(5_000_000L, result.eventStartEpochMillis)
        // None of the old commute's route/map/leave-by data may leak into the plain card.
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
    fun calendarEventToDifferentEvent_sameModeButDifferentLabel_fallsBackToPlainCard() {
        // Event A finishes and Event B becomes the next-up candidate but its first routing
        // attempt fails: a different label under the same mode is still a NEW target, so this
        // lands on the plain card exactly like the commute-to-event case above, not a broken
        // shell mislabeled with Event B's title.
        val result = failureSnapshot(
            previous = staleCalendarEvent,
            direction = Direction.TO_WORK,
            message = "Geocode failed",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Event B",
            eventStartEpochMillisOverride = 9_000_000L,
        )

        assertEquals(SnapshotMode.CALENDAR_EMPTY, result.mode)
        assertFalse(result.lastFetchFailed)
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
        // Contrast with the two NEW-target tests above: Event A routed successfully before, so a
        // same-target failure keeps today's byte-for-byte behavior - stale route/map preserved,
        // lastFetchFailed=true, still CALENDAR_EVENT (the warning-glyph broken shell), never the
        // plain-card fallback.
        val result = failureSnapshot(
            previous = staleCalendarEvent,
            direction = Direction.TO_WORK,
            message = "Route fetch failed",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Event A",
            eventStartEpochMillisOverride = staleCalendarEvent.eventStartEpochMillis,
        )

        assertEquals(SnapshotMode.CALENDAR_EVENT, result.mode)
        assertTrue(result.lastFetchFailed)
        assertEquals("/cache/map_b.png", result.mapImagePath)
        assertEquals(13.0, result.destinationLat!!, 0.0001)
        assertEquals(600L, result.durationSeconds)
    }

    @Test
    fun noPrevious_calendarEventFailureFallsBackToPlainCardImmediately() {
        // First-ever refresh, no previous snapshot at all: still a NEW target (there is nothing
        // to be the same target as), so this must land on the plain card rather than treating an
        // absent previous as license to build the old broken CALENDAR_EVENT shell.
        val result = failureSnapshot(
            previous = null,
            direction = Direction.TO_WORK,
            message = "Location unavailable",
            modeOverride = SnapshotMode.CALENDAR_EVENT,
            destinationLabelOverride = "Dentist",
            eventStartEpochMillisOverride = 1_000_000L,
        )

        assertEquals(SnapshotMode.CALENDAR_EMPTY, result.mode)
        assertFalse(result.lastFetchFailed)
        assertNull(result.lastErrorMessage)
        assertEquals("Dentist", result.destinationLabel)
        assertEquals(1_000_000L, result.eventStartEpochMillis)
        assertEquals(emptyList<HealthNudge>(), result.healthNudges)
        assertNull(result.sleepEstimateMinutes)
        assertFalse(result.shortSleepDay)
        assertEquals(emptyList<CustomPillOccurrence>(), result.customPillOccurrences)
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
