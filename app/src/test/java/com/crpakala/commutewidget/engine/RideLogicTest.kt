package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.data.CommuteProbe
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.RidePhase
import com.crpakala.commutewidget.data.RideState
import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DATE = "2026-09-09"
private const val OTHER_DATE = "2026-09-08"

/** Pure decision and transition tests for [RideLogic]'s tap-to-ride functions (Sprint 2). */
class RideLogicTest {

    private val home = Place(address = "Home", lat = 12.90, lng = 77.60)
    private val work = Place(address = "Work", lat = 12.94, lng = 77.72)

    // ---- resolveRidePhase ----

    @Test
    fun resolveRidePhase_nullState_isOffered() {
        assertEquals(RidePhase.OFFERED, resolveRidePhase(null, DATE, Direction.TO_WORK))
    }

    @Test
    fun resolveRidePhase_otherDate_isOffered() {
        val state = RideState(OTHER_DATE, Direction.TO_WORK, RidePhase.RIDING)
        assertEquals(RidePhase.OFFERED, resolveRidePhase(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun resolveRidePhase_otherDirection_isOffered() {
        val state = RideState(DATE, Direction.TO_HOME, RidePhase.RIDING)
        assertEquals(RidePhase.OFFERED, resolveRidePhase(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun resolveRidePhase_matchingOffered_returnsOffered() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED)
        assertEquals(RidePhase.OFFERED, resolveRidePhase(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun resolveRidePhase_matchingRiding_returnsRiding() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING)
        assertEquals(RidePhase.RIDING, resolveRidePhase(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun resolveRidePhase_matchingInterrupted_returnsInterrupted() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED)
        assertEquals(RidePhase.INTERRUPTED, resolveRidePhase(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun resolveRidePhase_matchingReached_returnsReached() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.REACHED)
        assertEquals(RidePhase.REACHED, resolveRidePhase(state, DATE, Direction.TO_WORK))
    }

    // ---- rideLastFailed ----

    @Test
    fun rideLastFailed_matchingWithFlag_true() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED, lastRideFailed = true)
        assertTrue(rideLastFailed(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideLastFailed_matchingWithoutFlag_false() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED, lastRideFailed = false)
        assertFalse(rideLastFailed(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideLastFailed_nonMatchingDate_false() {
        val state = RideState(OTHER_DATE, Direction.TO_WORK, RidePhase.OFFERED, lastRideFailed = true)
        assertFalse(rideLastFailed(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideLastFailed_nonMatchingDirection_false() {
        val state = RideState(DATE, Direction.TO_HOME, RidePhase.OFFERED, lastRideFailed = true)
        assertFalse(rideLastFailed(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideLastFailed_nullState_false() {
        assertFalse(rideLastFailed(null, DATE, Direction.TO_WORK))
    }

    // ---- rideFromCurrentLocation ----

    @Test
    fun rideFromCurrentLocation_matchingRidingWithFlag_true() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true)
        assertTrue(rideFromCurrentLocation(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideFromCurrentLocation_matchingRidingWithoutFlag_false() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = false)
        assertFalse(rideFromCurrentLocation(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideFromCurrentLocation_notRidingEvenWithFlagSet_false() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED, fromCurrentLocation = true)
        assertFalse(rideFromCurrentLocation(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideFromCurrentLocation_nonMatchingDate_false() {
        val state = RideState(OTHER_DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true)
        assertFalse(rideFromCurrentLocation(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideFromCurrentLocation_nonMatchingDirection_false() {
        val state = RideState(DATE, Direction.TO_HOME, RidePhase.RIDING, fromCurrentLocation = true)
        assertFalse(rideFromCurrentLocation(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun rideFromCurrentLocation_nullState_false() {
        assertFalse(rideFromCurrentLocation(null, DATE, Direction.TO_WORK))
    }

    // ---- shouldRunCommutePipeline ----

    @Test
    fun shouldRunCommutePipeline_commuteAndRiding_true() {
        assertTrue(shouldRunCommutePipeline(WidgetMode.Commute(Direction.TO_WORK), RidePhase.RIDING))
    }

    @Test
    fun shouldRunCommutePipeline_commuteAndOffered_false() {
        assertFalse(shouldRunCommutePipeline(WidgetMode.Commute(Direction.TO_WORK), RidePhase.OFFERED))
    }

    @Test
    fun shouldRunCommutePipeline_commuteAndInterrupted_false() {
        assertFalse(shouldRunCommutePipeline(WidgetMode.Commute(Direction.TO_WORK), RidePhase.INTERRUPTED))
    }

    @Test
    fun shouldRunCommutePipeline_commuteAndReached_false() {
        assertFalse(shouldRunCommutePipeline(WidgetMode.Commute(Direction.TO_WORK), RidePhase.REACHED))
    }

    @Test
    fun shouldRunCommutePipeline_calendarAndRiding_false() {
        assertFalse(shouldRunCommutePipeline(WidgetMode.Calendar, RidePhase.RIDING))
    }

    // ---- resolveInWindowBranch ----

    @Test
    fun resolveInWindowBranch_everyTakeoverAndPhaseCombination() {
        val cases = listOf(
            Triple(true, RidePhase.OFFERED, InWindowBranch.EVENT_TAKEOVER),
            Triple(true, RidePhase.RIDING, InWindowBranch.EVENT_TAKEOVER),
            Triple(true, RidePhase.INTERRUPTED, InWindowBranch.EVENT_TAKEOVER),
            Triple(true, RidePhase.REACHED, InWindowBranch.EVENT_TAKEOVER),
            Triple(false, RidePhase.OFFERED, InWindowBranch.PROBE_AND_CALENDAR),
            Triple(false, RidePhase.RIDING, InWindowBranch.RIDE),
            Triple(false, RidePhase.INTERRUPTED, InWindowBranch.PROBE_AND_CALENDAR),
            Triple(false, RidePhase.REACHED, InWindowBranch.PROBE_AND_CALENDAR),
        )
        for ((takeoverApplies, phase, expected) in cases) {
            assertEquals(
                "takeover=$takeoverApplies phase=$phase",
                expected,
                resolveInWindowBranch(takeoverApplies, phase),
            )
        }
    }

    // ---- shouldOfferRide ----

    @Test
    fun shouldOfferRide_commuteCombinations() {
        val snapshotModes = listOf(null, SnapshotMode.COMMUTE, SnapshotMode.CALENDAR_EVENT, SnapshotMode.CALENDAR_EMPTY)
        val phases = RidePhase.entries
        for (phase in phases) {
            for (snapshotMode in snapshotModes) {
                for (refreshingActive in listOf(false, true)) {
                    val offerablePhase = when (phase) {
                        RidePhase.OFFERED, RidePhase.INTERRUPTED -> true
                        RidePhase.RIDING -> !refreshingActive
                        RidePhase.REACHED -> false
                    }
                    val expected = offerablePhase && snapshotMode == SnapshotMode.CALENDAR_EMPTY
                    val actual = shouldOfferRide(WidgetMode.Commute(Direction.TO_WORK), phase, snapshotMode, refreshingActive)
                    assertEquals("phase=$phase snapshotMode=$snapshotMode refreshing=$refreshingActive", expected, actual)
                }
            }
        }
    }

    @Test
    fun shouldOfferRide_strandedRidingIsReofferedOnlyWhenNoRefreshInFlight() {
        val commute = WidgetMode.Commute(Direction.TO_HOME)
        assertFalse(shouldOfferRide(commute, RidePhase.RIDING, SnapshotMode.CALENDAR_EMPTY, refreshingActive = true))
        assertTrue(shouldOfferRide(commute, RidePhase.RIDING, SnapshotMode.CALENDAR_EMPTY, refreshingActive = false))
        assertFalse(shouldOfferRide(commute, RidePhase.RIDING, SnapshotMode.COMMUTE, refreshingActive = false))
    }

    @Test
    fun shouldOfferRide_calendarMode_alwaysFalse() {
        val snapshotModes = listOf(null, SnapshotMode.COMMUTE, SnapshotMode.CALENDAR_EVENT, SnapshotMode.CALENDAR_EMPTY)
        for (phase in RidePhase.entries) {
            for (snapshotMode in snapshotModes) {
                assertFalse(
                    "phase=$phase snapshotMode=$snapshotMode",
                    shouldOfferRide(WidgetMode.Calendar, phase, snapshotMode, refreshingActive = false),
                )
            }
        }
    }

    // ---- shouldShowReached ----

    @Test
    fun shouldShowReached_commuteRidingCommuteSnapshot_true() {
        assertTrue(shouldShowReached(WidgetMode.Commute(Direction.TO_WORK), RidePhase.RIDING, SnapshotMode.COMMUTE))
    }

    @Test
    fun shouldShowReached_commuteRidingOtherSnapshotModes_false() {
        val snapshotModes = listOf(null, SnapshotMode.CALENDAR_EVENT, SnapshotMode.CALENDAR_EMPTY)
        for (snapshotMode in snapshotModes) {
            assertFalse(shouldShowReached(WidgetMode.Commute(Direction.TO_WORK), RidePhase.RIDING, snapshotMode))
        }
    }

    @Test
    fun shouldShowReached_commuteNonRidingPhases_false() {
        for (phase in listOf(RidePhase.OFFERED, RidePhase.INTERRUPTED, RidePhase.REACHED)) {
            assertFalse(shouldShowReached(WidgetMode.Commute(Direction.TO_WORK), phase, SnapshotMode.COMMUTE))
        }
    }

    @Test
    fun shouldShowReached_calendarMode_false() {
        assertFalse(shouldShowReached(WidgetMode.Calendar, RidePhase.RIDING, SnapshotMode.COMMUTE))
    }

    // ---- shouldRunCommuteProbe ----

    @Test
    fun shouldRunCommuteProbe_leaveByDisabled_false() {
        val result = shouldRunCommuteProbe(
            widgetMode = WidgetMode.Commute(Direction.TO_WORK),
            phase = RidePhase.OFFERED,
            leaveByEnabled = false,
            existing = null,
            localDate = DATE,
        )
        assertFalse(result)
    }

    @Test
    fun shouldRunCommuteProbe_nonOfferedPhases_false() {
        for (phase in listOf(RidePhase.RIDING, RidePhase.INTERRUPTED, RidePhase.REACHED)) {
            val result = shouldRunCommuteProbe(
                widgetMode = WidgetMode.Commute(Direction.TO_WORK),
                phase = phase,
                leaveByEnabled = true,
                existing = null,
                localDate = DATE,
            )
            assertFalse("phase=$phase", result)
        }
    }

    @Test
    fun shouldRunCommuteProbe_existingForSameDateAndDirection_false() {
        val existing = CommuteProbe(DATE, Direction.TO_WORK, durationSeconds = 900L, probedAtEpochMillis = 1L)
        val result = shouldRunCommuteProbe(
            widgetMode = WidgetMode.Commute(Direction.TO_WORK),
            phase = RidePhase.OFFERED,
            leaveByEnabled = true,
            existing = existing,
            localDate = DATE,
        )
        assertFalse(result)
    }

    @Test
    fun shouldRunCommuteProbe_existingForOtherDate_true() {
        val existing = CommuteProbe(OTHER_DATE, Direction.TO_WORK, durationSeconds = 900L, probedAtEpochMillis = 1L)
        val result = shouldRunCommuteProbe(
            widgetMode = WidgetMode.Commute(Direction.TO_WORK),
            phase = RidePhase.OFFERED,
            leaveByEnabled = true,
            existing = existing,
            localDate = DATE,
        )
        assertTrue(result)
    }

    @Test
    fun shouldRunCommuteProbe_existingForOtherDirection_true() {
        val existing = CommuteProbe(DATE, Direction.TO_HOME, durationSeconds = 900L, probedAtEpochMillis = 1L)
        val result = shouldRunCommuteProbe(
            widgetMode = WidgetMode.Commute(Direction.TO_WORK),
            phase = RidePhase.OFFERED,
            leaveByEnabled = true,
            existing = existing,
            localDate = DATE,
        )
        assertTrue(result)
    }

    @Test
    fun shouldRunCommuteProbe_calendarMode_false() {
        val result = shouldRunCommuteProbe(
            widgetMode = WidgetMode.Calendar,
            phase = RidePhase.OFFERED,
            leaveByEnabled = true,
            existing = null,
            localDate = DATE,
        )
        assertFalse(result)
    }

    // ---- probeLeaveByMinute ----

    @Test
    fun probeLeaveByMinute_enabledCommuteMatching_returnsMinute() {
        val probe = CommuteProbe(DATE, Direction.TO_WORK, durationSeconds = 900L, leaveByMinuteOfDay = 420, probedAtEpochMillis = 1L)
        val result = probeLeaveByMinute(probe, WidgetMode.Commute(Direction.TO_WORK), RidePhase.OFFERED, leaveByEnabled = true, localDate = DATE)
        assertEquals(420, result)
    }

    @Test
    fun probeLeaveByMinute_disabled_null() {
        val probe = CommuteProbe(DATE, Direction.TO_WORK, durationSeconds = 900L, leaveByMinuteOfDay = 420, probedAtEpochMillis = 1L)
        assertNull(probeLeaveByMinute(probe, WidgetMode.Commute(Direction.TO_WORK), RidePhase.OFFERED, leaveByEnabled = false, localDate = DATE))
    }

    @Test
    fun probeLeaveByMinute_calendarMode_null() {
        val probe = CommuteProbe(DATE, Direction.TO_WORK, durationSeconds = 900L, leaveByMinuteOfDay = 420, probedAtEpochMillis = 1L)
        assertNull(probeLeaveByMinute(probe, WidgetMode.Calendar, RidePhase.OFFERED, leaveByEnabled = true, localDate = DATE))
    }

    @Test
    fun probeLeaveByMinute_otherDate_null() {
        val probe = CommuteProbe(OTHER_DATE, Direction.TO_WORK, durationSeconds = 900L, leaveByMinuteOfDay = 420, probedAtEpochMillis = 1L)
        assertNull(probeLeaveByMinute(probe, WidgetMode.Commute(Direction.TO_WORK), RidePhase.OFFERED, leaveByEnabled = true, localDate = DATE))
    }

    @Test
    fun probeLeaveByMinute_otherDirection_null() {
        val probe = CommuteProbe(DATE, Direction.TO_HOME, durationSeconds = 900L, leaveByMinuteOfDay = 420, probedAtEpochMillis = 1L)
        assertNull(probeLeaveByMinute(probe, WidgetMode.Commute(Direction.TO_WORK), RidePhase.OFFERED, leaveByEnabled = true, localDate = DATE))
    }

    @Test
    fun probeLeaveByMinute_nullProbe_null() {
        assertNull(probeLeaveByMinute(null, WidgetMode.Commute(Direction.TO_WORK), RidePhase.OFFERED, leaveByEnabled = true, localDate = DATE))
    }

    @Test
    fun probeLeaveByMinute_matchingProbeWithNullMinute_null() {
        val probe = CommuteProbe(DATE, Direction.TO_WORK, durationSeconds = 900L, leaveByMinuteOfDay = null, probedAtEpochMillis = 1L)
        assertNull(probeLeaveByMinute(probe, WidgetMode.Commute(Direction.TO_WORK), RidePhase.OFFERED, leaveByEnabled = true, localDate = DATE))
    }

    @Test
    fun probeLeaveByMinute_reachedPhase_null() {
        val probe = CommuteProbe(DATE, Direction.TO_WORK, durationSeconds = 900L, leaveByMinuteOfDay = 420, probedAtEpochMillis = 1L)
        assertNull(probeLeaveByMinute(probe, WidgetMode.Commute(Direction.TO_WORK), RidePhase.REACHED, leaveByEnabled = true, localDate = DATE))
    }

    @Test
    fun probeLeaveByMinute_ridingPhase_returnsMinute() {
        val probe = CommuteProbe(DATE, Direction.TO_WORK, durationSeconds = 900L, leaveByMinuteOfDay = 420, probedAtEpochMillis = 1L)
        assertEquals(420, probeLeaveByMinute(probe, WidgetMode.Commute(Direction.TO_WORK), RidePhase.RIDING, leaveByEnabled = true, localDate = DATE))
    }

    // ---- applyRideTap ----

    @Test
    fun applyRideTap_nullState_startsFreshRiding() {
        val result = applyRideTap(null, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyRideTap_otherDate_startsFreshRiding() {
        val state = RideState(OTHER_DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true, lastRideFailed = true)
        val result = applyRideTap(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyRideTap_otherDirection_startsFreshRiding() {
        val state = RideState(DATE, Direction.TO_HOME, RidePhase.INTERRUPTED)
        val result = applyRideTap(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyRideTap_offered_startsRidingFromFixedOrigin() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED)
        val result = applyRideTap(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyRideTap_interrupted_resumesFromCurrentLocation() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED, lastRideFailed = true)
        val result = applyRideTap(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true, lastRideFailed = false), result)
    }

    @Test
    fun applyRideTap_riding_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true)
        assertSame(state, applyRideTap(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideTap_reached_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.REACHED)
        assertSame(state, applyRideTap(state, DATE, Direction.TO_WORK))
    }

    // ---- applyReached ----

    @Test
    fun applyReached_nullState_reached() {
        val result = applyReached(null, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.REACHED, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyReached_offered_reached() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED)
        val result = applyReached(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.REACHED, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyReached_riding_reached() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true, lastRideFailed = true)
        val result = applyReached(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.REACHED, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyReached_interrupted_reached() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED)
        val result = applyReached(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.REACHED, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyReached_alreadyReached_idempotent() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.REACHED)
        val result = applyReached(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.REACHED, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyReached_mismatchedState_usesNewDateAndDirection() {
        val state = RideState(OTHER_DATE, Direction.TO_HOME, RidePhase.RIDING)
        val result = applyReached(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.REACHED, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    // ---- applyRideInterrupted ----

    @Test
    fun applyRideInterrupted_riding_becomesInterrupted() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true)
        val result = applyRideInterrupted(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED, fromCurrentLocation = false, lastRideFailed = false), result)
    }

    @Test
    fun applyRideInterrupted_offered_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED)
        assertSame(state, applyRideInterrupted(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideInterrupted_alreadyInterrupted_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED)
        assertSame(state, applyRideInterrupted(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideInterrupted_reached_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.REACHED)
        assertSame(state, applyRideInterrupted(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideInterrupted_nullState_returnsNull() {
        assertNull(applyRideInterrupted(null, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideInterrupted_nonMatchingState_returnsSameInstanceUnchanged() {
        val state = RideState(OTHER_DATE, Direction.TO_WORK, RidePhase.RIDING)
        assertSame(state, applyRideInterrupted(state, DATE, Direction.TO_WORK))
    }

    // ---- applyRideFailed ----

    @Test
    fun applyRideFailed_ridingFromFixedOrigin_revertsToOffered() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = false)
        val result = applyRideFailed(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED, fromCurrentLocation = false, lastRideFailed = true), result)
    }

    @Test
    fun applyRideFailed_ridingFromCurrentLocation_revertsToInterrupted() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true)
        val result = applyRideFailed(state, DATE, Direction.TO_WORK)
        assertEquals(RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED, fromCurrentLocation = false, lastRideFailed = true), result)
    }

    @Test
    fun applyRideFailed_offered_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.OFFERED)
        assertSame(state, applyRideFailed(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideFailed_interrupted_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.INTERRUPTED)
        assertSame(state, applyRideFailed(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideFailed_reached_returnsSameInstance() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.REACHED)
        assertSame(state, applyRideFailed(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideFailed_nullState_returnsNull() {
        assertNull(applyRideFailed(null, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideFailed_nonMatchingState_returnsSameInstanceUnchanged() {
        val state = RideState(DATE, Direction.TO_HOME, RidePhase.RIDING)
        assertSame(state, applyRideFailed(state, DATE, Direction.TO_WORK))
    }

    // ---- applyRideRouted ----

    @Test
    fun applyRideRouted_matchingRiding_setsRouted() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true)
        val result = applyRideRouted(state, DATE, Direction.TO_WORK)
        assertEquals(state.copy(routed = true), result)
    }

    @Test
    fun applyRideRouted_alreadyRouted_isIdempotent() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, routed = true)
        assertEquals(state, applyRideRouted(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideRouted_nonRidingPhases_returnSameInstance() {
        for (phase in listOf(RidePhase.OFFERED, RidePhase.INTERRUPTED, RidePhase.REACHED)) {
            val state = RideState(DATE, Direction.TO_WORK, phase)
            assertSame(state, applyRideRouted(state, DATE, Direction.TO_WORK))
        }
    }

    @Test
    fun applyRideRouted_nonMatchingState_returnsSameInstanceUnchanged() {
        val state = RideState(DATE, Direction.TO_HOME, RidePhase.RIDING)
        assertSame(state, applyRideRouted(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun applyRideRouted_nullState_returnsNull() {
        assertNull(applyRideRouted(null, DATE, Direction.TO_WORK))
    }

    // ---- commuteRouteOrigin ----

    @Test
    fun commuteRouteOrigin_success_returnsDeviceValue() {
        val device = LatLng(12.91, 77.64)
        val fixed = LatLng(12.94, 77.72)
        assertEquals(device, commuteRouteOrigin(ApiResult.Success(device), fixed))
    }

    @Test
    fun commuteRouteOrigin_failure_returnsFixed() {
        val fixed = LatLng(12.94, 77.72)
        assertEquals(fixed, commuteRouteOrigin(ApiResult.Failure("Location unavailable"), fixed))
    }

    @Test
    fun commuteRouteOrigin_null_returnsFixed() {
        val fixed = LatLng(12.94, 77.72)
        assertEquals(fixed, commuteRouteOrigin(null, fixed))
    }

    // ---- commuteTrip ----

    @Test
    fun commuteTrip_toWork_routesHomeToWork() {
        val trip = commuteTrip(home, work, Direction.TO_WORK)
        assertEquals(LatLng(home.lat, home.lng), trip.origin)
        assertEquals(LatLng(work.lat, work.lng), trip.destination)
        assertEquals("Work", trip.destinationLabel)
    }

    @Test
    fun commuteTrip_toHome_routesWorkToHome() {
        val trip = commuteTrip(home, work, Direction.TO_HOME)
        assertEquals(LatLng(work.lat, work.lng), trip.origin)
        assertEquals(LatLng(home.lat, home.lng), trip.destination)
        assertEquals("Home", trip.destinationLabel)
    }

    // ---- isRideFirstAttempt ----

    @Test
    fun isRideFirstAttempt_nullState_true() {
        assertTrue(isRideFirstAttempt(null, DATE, Direction.TO_WORK))
    }

    @Test
    fun isRideFirstAttempt_ridingNotYetRouted_true() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, routed = false)
        assertTrue(isRideFirstAttempt(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun isRideFirstAttempt_ridingAlreadyRouted_false() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, routed = true)
        assertFalse(isRideFirstAttempt(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun isRideFirstAttempt_routedStateFromAnotherDate_true() {
        val state = RideState(OTHER_DATE, Direction.TO_WORK, RidePhase.RIDING, routed = true)
        assertTrue(isRideFirstAttempt(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun isRideFirstAttempt_routedStateFromOtherDirection_true() {
        val state = RideState(DATE, Direction.TO_HOME, RidePhase.RIDING, routed = true)
        assertTrue(isRideFirstAttempt(state, DATE, Direction.TO_WORK))
    }

    @Test
    fun isRideFirstAttempt_resumedRideAfterInterruption_true() {
        val state = RideState(DATE, Direction.TO_WORK, RidePhase.RIDING, fromCurrentLocation = true, routed = false)
        assertTrue(isRideFirstAttempt(state, DATE, Direction.TO_WORK))
    }

    // ---- shouldSkipForCooldown ----

    @Test
    fun shouldSkipForCooldown_bypassWins() {
        val result = shouldSkipForCooldown(
            elapsedSinceLastCompletedMillis = 100L,
            lastCompletedSet = true,
            bypassCooldown = true,
            minGapMillis = 5000L,
        )
        assertFalse(result)
    }

    @Test
    fun shouldSkipForCooldown_lastCompletedNotSet_neverSkips() {
        val result = shouldSkipForCooldown(
            elapsedSinceLastCompletedMillis = 100L,
            lastCompletedSet = false,
            bypassCooldown = false,
            minGapMillis = 5000L,
        )
        assertFalse(result)
    }

    @Test
    fun shouldSkipForCooldown_elapsedBelowGap_skips() {
        val result = shouldSkipForCooldown(
            elapsedSinceLastCompletedMillis = 4999L,
            lastCompletedSet = true,
            bypassCooldown = false,
            minGapMillis = 5000L,
        )
        assertTrue(result)
    }

    @Test
    fun shouldSkipForCooldown_elapsedAtGap_doesNotSkip() {
        val result = shouldSkipForCooldown(
            elapsedSinceLastCompletedMillis = 5000L,
            lastCompletedSet = true,
            bypassCooldown = false,
            minGapMillis = 5000L,
        )
        assertFalse(result)
    }

    @Test
    fun shouldSkipForCooldown_elapsedAboveGap_doesNotSkip() {
        val result = shouldSkipForCooldown(
            elapsedSinceLastCompletedMillis = 5001L,
            lastCompletedSet = true,
            bypassCooldown = false,
            minGapMillis = 5000L,
        )
        assertFalse(result)
    }
}
