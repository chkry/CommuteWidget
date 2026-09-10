package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.data.CommuteProbe
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.RidePhase
import com.crpakala.commutewidget.data.RideState
import com.crpakala.commutewidget.data.SnapshotMode

/**
 * Pure decision logic for the Sprint 2 tap-to-ride feature.
 * A ride moves through OFFERED (pill shown, no route fetched), RIDING (route and map fetched),
 * INTERRUPTED (an event takeover pre-empted a RIDING ride, offered again as a resumed ride), and
 * REACHED (the ride is consumed for the window).
 * Every stored [RideState] and [CommuteProbe] is keyed by local date and direction, so a value
 * left over from another day or from the other window's direction is inert and treated as absent
 * rather than resumed.
 * Every function here is a pure decision or transition with no I/O, no clock reads, and no
 * persistence - callers own reading and writing state and running the actual network calls.
 */

/** Ride phase for [localDate]/[direction] - OFFERED when no matching stored state exists, else the stored phase. */
internal fun resolveRidePhase(state: RideState?, localDate: String, direction: Direction): RidePhase {
    if (state == null || state.localDate != localDate || state.direction != direction) {
        return RidePhase.OFFERED
    }
    return state.phase
}

/** True when the stored ride state matches [localDate]/[direction] and its last ride attempt failed. */
internal fun rideLastFailed(state: RideState?, localDate: String, direction: Direction): Boolean {
    if (state == null || state.localDate != localDate || state.direction != direction) {
        return false
    }
    return state.lastRideFailed
}

/** True when the stored ride state matches [localDate]/[direction], is RIDING, and resumed from the device location. */
internal fun rideFromCurrentLocation(state: RideState?, localDate: String, direction: Direction): Boolean {
    if (state == null || state.localDate != localDate || state.direction != direction) {
        return false
    }
    return state.phase == RidePhase.RIDING && state.fromCurrentLocation
}

/** True when a commute window is active and the ride is RIDING, so the refresh should fetch the route and map. */
internal fun shouldRunCommutePipeline(widgetMode: WidgetMode, phase: RidePhase): Boolean =
    widgetMode is WidgetMode.Commute && phase == RidePhase.RIDING

/** What an in-window refresh does, in precedence order. */
internal enum class InWindowBranch {
    /** A near located event pre-empts the window and interrupts a ride in progress. */
    EVENT_TAKEOVER,
    /** A RIDING ride fetches the commute route and map. */
    RIDE,
    /** The one-call leave-by probe runs and the in-window calendar view renders with the Ride pill. */
    PROBE_AND_CALENDAR,
}

/** In-window precedence: an event takeover wins, then a RIDING ride, otherwise the probe plus the calendar view. */
internal fun resolveInWindowBranch(takeoverApplies: Boolean, phase: RidePhase): InWindowBranch = when {
    takeoverApplies -> InWindowBranch.EVENT_TAKEOVER
    phase == RidePhase.RIDING -> InWindowBranch.RIDE
    else -> InWindowBranch.PROBE_AND_CALENDAR
}

/**
 * True when a commute window is active, the card shows the empty-calendar surface, and the ride
 * is offerable: OFFERED or INTERRUPTED, or RIDING with no map and no refresh in flight, which
 * means the ride's fetch died (cancelled past the Glance budget, or thrown) and must be re-offered.
 */
internal fun shouldOfferRide(
    widgetMode: WidgetMode,
    phase: RidePhase,
    snapshotMode: SnapshotMode?,
    refreshingActive: Boolean,
): Boolean {
    if (widgetMode !is WidgetMode.Commute || snapshotMode != SnapshotMode.CALENDAR_EMPTY) {
        return false
    }
    return when (phase) {
        RidePhase.OFFERED, RidePhase.INTERRUPTED -> true
        RidePhase.RIDING -> !refreshingActive
        RidePhase.REACHED -> false
    }
}

/** True when a commute window is active, the ride is RIDING, and the snapshot already shows the routed commute map. */
internal fun shouldShowReached(widgetMode: WidgetMode, phase: RidePhase, snapshotMode: SnapshotMode?): Boolean =
    widgetMode is WidgetMode.Commute && phase == RidePhase.RIDING && snapshotMode == SnapshotMode.COMMUTE

/** True on the first OFFERED refresh inside a commute window with leave-by enabled and no probe already stored for this date and direction. */
internal fun shouldRunCommuteProbe(
    widgetMode: WidgetMode,
    phase: RidePhase,
    leaveByEnabled: Boolean,
    existing: CommuteProbe?,
    localDate: String,
): Boolean {
    if (widgetMode !is WidgetMode.Commute || phase != RidePhase.OFFERED || !leaveByEnabled) {
        return false
    }
    val existingMatches = existing != null && existing.localDate == localDate && existing.direction == widgetMode.direction
    return !existingMatches
}

/** The stored probe's leave-by minute when leave-by is enabled, the mode is Commute, the ride is not consumed (REACHED), and the probe matches this date and direction, else null. */
internal fun probeLeaveByMinute(probe: CommuteProbe?, widgetMode: WidgetMode, phase: RidePhase, leaveByEnabled: Boolean, localDate: String): Int? {
    if (!leaveByEnabled || widgetMode !is WidgetMode.Commute || phase == RidePhase.REACHED || probe == null) {
        return null
    }
    if (probe.localDate != localDate || probe.direction != widgetMode.direction) {
        return null
    }
    return probe.leaveByMinuteOfDay
}

/** Ride-tap transition: OFFERED (or non-matching state) starts a fresh ride, INTERRUPTED resumes from the device location, RIDING and REACHED are unchanged. */
internal fun applyRideTap(state: RideState?, localDate: String, direction: Direction): RideState {
    if (state == null || state.localDate != localDate || state.direction != direction) {
        return RideState(localDate, direction, RidePhase.RIDING, fromCurrentLocation = false, lastRideFailed = false)
    }
    return when (state.phase) {
        RidePhase.OFFERED -> RideState(localDate, direction, RidePhase.RIDING, fromCurrentLocation = false, lastRideFailed = false)
        RidePhase.INTERRUPTED -> RideState(localDate, direction, RidePhase.RIDING, fromCurrentLocation = true, lastRideFailed = false)
        RidePhase.RIDING, RidePhase.REACHED -> state
    }
}

/** Reached-tap transition: always REACHED for [localDate]/[direction], regardless of the prior phase. */
internal fun applyReached(state: RideState?, localDate: String, direction: Direction): RideState =
    RideState(localDate, direction, RidePhase.REACHED, fromCurrentLocation = false, lastRideFailed = false)

/** Event-takeover transition: RIDING becomes INTERRUPTED, any other phase (or non-matching state) is unchanged. */
internal fun applyRideInterrupted(state: RideState?, localDate: String, direction: Direction): RideState? {
    if (state == null || state.localDate != localDate || state.direction != direction || state.phase != RidePhase.RIDING) {
        return state
    }
    return RideState(localDate, direction, RidePhase.INTERRUPTED, fromCurrentLocation = false, lastRideFailed = false)
}

/** Ride-failure transition: RIDING reverts to OFFERED or INTERRUPTED depending on fromCurrentLocation and flags the failure, any other phase is unchanged. */
internal fun applyRideFailed(state: RideState?, localDate: String, direction: Direction): RideState? {
    if (state == null || state.localDate != localDate || state.direction != direction || state.phase != RidePhase.RIDING) {
        return state
    }
    return if (state.fromCurrentLocation) {
        RideState(localDate, direction, RidePhase.INTERRUPTED, fromCurrentLocation = false, lastRideFailed = true)
    } else {
        RideState(localDate, direction, RidePhase.OFFERED, fromCurrentLocation = false, lastRideFailed = true)
    }
}

/** Ride-routed transition: a matching RIDING ride records that it stored a routed snapshot, any other phase is unchanged. */
internal fun applyRideRouted(state: RideState?, localDate: String, direction: Direction): RideState? {
    if (state == null || state.localDate != localDate || state.direction != direction || state.phase != RidePhase.RIDING) {
        return state
    }
    return state.copy(routed = true)
}

/** Origin for a ride's route: the device fix when available, otherwise the fixed window origin (mirrors [eventRouteOrigin]). */
internal fun commuteRouteOrigin(deviceLocation: ApiResult<LatLng>?, fixedOrigin: LatLng): LatLng =
    if (deviceLocation is ApiResult.Success) deviceLocation.value else fixedOrigin

/**
 * True unless this very ride already stored a routed snapshot. Ride identity lives in
 * [RideState.routed] rather than in the previous snapshot's mode and label, so yesterday's
 * surviving commute snapshot cannot make today's first ride look like a same-target retry.
 * A missing or non-matching state counts as a first attempt.
 */
internal fun isRideFirstAttempt(state: RideState?, localDate: String, direction: Direction): Boolean {
    if (state == null || state.localDate != localDate || state.direction != direction) {
        return true
    }
    return !(state.phase == RidePhase.RIDING && state.routed)
}

/** The fixed trip for a commute window: Home to Work in the morning, Work to Home in the evening. */
internal data class CommuteTrip(val origin: LatLng, val destination: LatLng, val destinationLabel: String)

/** The fixed [CommuteTrip] for [direction], shared by the window probe and the ride pipeline so both derive it once. */
internal fun commuteTrip(home: Place, work: Place, direction: Direction): CommuteTrip =
    if (direction == Direction.TO_WORK) {
        CommuteTrip(LatLng(home.lat, home.lng), LatLng(work.lat, work.lng), "Work")
    } else {
        CommuteTrip(LatLng(work.lat, work.lng), LatLng(home.lat, home.lng), "Home")
    }

/** True when the shared refresh cooldown should skip this refresh: not bypassed, a prior refresh completed, and the gap since then is still short. */
internal fun shouldSkipForCooldown(
    elapsedSinceLastCompletedMillis: Long,
    lastCompletedSet: Boolean,
    bypassCooldown: Boolean,
    minGapMillis: Long,
): Boolean = !bypassCooldown && lastCompletedSet && elapsedSinceLastCompletedMillis < minGapMillis
