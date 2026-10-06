package com.crpakala.commutewidget.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.core.graphics.scale
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingWorkPolicy
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.FlightStatusClient
import com.crpakala.commutewidget.api.FlightStatusResult
import com.crpakala.commutewidget.api.GeocodingClient
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.MapImageFetcher
import com.crpakala.commutewidget.api.RouteResult
import com.crpakala.commutewidget.api.RouteTravelMode
import com.crpakala.commutewidget.api.RoutesClient
import com.crpakala.commutewidget.api.StaticMapUrl
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.calendar.TodayEvent
import com.crpakala.commutewidget.data.AirportLocation
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CommuteProbe
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.FlightPreview
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.RidePhase
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.data.UpcomingEvent
import com.crpakala.commutewidget.data.pruneClosedEventKeys
import com.crpakala.commutewidget.schedule.AirportBoundaryScheduler
import com.crpakala.commutewidget.schedule.CalendarTickScheduler
import com.crpakala.commutewidget.schedule.CommuteLeaveByScheduler
import com.crpakala.commutewidget.schedule.EventLeaveByScheduler
import com.crpakala.commutewidget.schedule.EventNearScheduler
import com.crpakala.commutewidget.schedule.postCommuteLeaveByIfNotAlreadyNotified
import com.crpakala.commutewidget.schedule.shouldScheduleCalendarTick
import com.crpakala.commutewidget.schedule.shouldScheduleCommuteLeaveByAlarm
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToInt

// FIX-12: 600 at scale=2 yields 1200px from the server directly, matching MAP_MAX_LONG_EDGE_PX so
// the decode/resize/re-encode downsample step becomes a no-op instead of running on every fetch.
private const val MAP_FETCH_WIDTH_PX = 600
private const val MAP_FETCH_HEIGHT_PX = 600
private const val MAP_MAX_LONG_EDGE_PX = 1200
private const val LOCATION_TIMEOUT_MS = 15_000L
private const val LOCATION_WARM_UP_TIMEOUT_MS = 10_000L
// A resumed ride re-fixes the device location on a tap, so the fix gets a tighter budget than
// LOCATION_TIMEOUT_MS: the whole Glance action is cancelled after roughly 10 seconds and the
// Routes and Static Maps calls still have to fit after it.
private const val RESUMED_RIDE_FIX_TIMEOUT_MS = 5_000L
private const val MIN_REFRESH_GAP_MS = 5_000L
private const val TAP_COOLDOWN_PENDING_FRAME_MS = 250L
private const val MAP_FILE_A = "map_a.png"
private const val MAP_FILE_B = "map_b.png"
// Shared with schedule/EventLeaveByWorker.kt and schedule/CommuteLeaveByWorker.kt - both v4/v5
// advisors post on this same channel, per spec, so it is exposed at internal (module) visibility
// rather than duplicated as a second identical string literal in that package.
internal const val LEAVE_BY_CHANNEL_ID = "leave_by"
internal const val LEAVE_BY_CHANNEL_NAME = "Leave-by advisor"
private const val DEFAULT_LOCATION_MAX_AGE_MILLIS = 120_000L

/** Who initiated a refresh; drives the fetch pipeline (map or not) and the pre-fetch render gate. */
enum class RefreshTrigger { TAP, AUTO, TICK }

/** Minutes-of-day to leave, clamped to the start of the day (an already-late user still gets a value). */
internal fun computeLeaveByMinuteOfDay(arriveByMinuteOfDay: Int, durationSeconds: Long): Int {
    val travelMinutes = ceil(durationSeconds / 60.0).toInt()
    return (arriveByMinuteOfDay - travelMinutes).coerceAtLeast(0)
}

/**
 * v4 event advisor: departure-time probe for the located-event route request. Strictly more than
 * [thresholdMinutes] before [eventStartEpochMillis], PREDICTED traffic is requested by asking
 * [com.crpakala.commutewidget.api.RoutesClient.computeRoute] to depart at `eventStart - buffer` -
 * Google then returns traffic conditions predicted around the event's arrival time rather than
 * right now. At or within the threshold, real-time traffic is used instead (null - a nearby event
 * does not benefit from a prediction window and the current road conditions are the better
 * signal). Exactly at the threshold resolves to real-time (the boundary is exclusive on the
 * predicted side), matching [RoutesClient]'s own "> " floor semantics for its unrelated 30s
 * near-future guard.
 */
internal fun eventDepartureProbe(
    eventStartEpochMillis: Long,
    nowEpochMillis: Long,
    thresholdMinutes: Int,
    bufferMinutes: Int,
): Long? {
    val millisUntilStart = eventStartEpochMillis - nowEpochMillis
    if (millisUntilStart <= thresholdMinutes * 60_000L) {
        return null
    }
    return eventStartEpochMillis - bufferMinutes * 60_000L
}

/**
 * Event takeover: a LOCATED event (route drawable) whose start is within [takeoverMinutes] of now
 * outranks the window commute. Deliberately reused, unchanged, as the single nearness gate for
 * whether a located event in calendar mode is routed at all - see
 * [CommuteRefresher.performCalendarRefresh]'s far-located gate, which farther out shows the same
 * plain card an unlocated event gets instead of spending a geocode/Routes/Static-Maps call.
 * Already-started events also qualify (start - now is negative), matching the calendar reader's
 * own grace-window semantics.
 */
internal fun eventTakeoverApplies(
    eventStartEpochMillis: Long,
    eventHasLocation: Boolean,
    nowEpochMillis: Long,
    takeoverMinutes: Int,
): Boolean {
    return eventHasLocation && eventStartEpochMillis - nowEpochMillis <= takeoverMinutes * 60_000L
}

/**
 * Absolute instant (epoch millis) at which the far-located-event near-flip one-shot (see
 * [com.crpakala.commutewidget.schedule.EventNearScheduler]) should fire for an event starting at
 * [eventStartEpochMillis]: exactly [takeoverMinutes] before it - the same instant
 * [eventTakeoverApplies] starts returning true for this event.
 */
internal fun eventNearFlipEpochMillis(eventStartEpochMillis: Long, takeoverMinutes: Int): Long =
    eventStartEpochMillis - takeoverMinutes * 60_000L

/**
 * Origin for a located event's route: the device fix when one was obtained, otherwise the saved
 * Home place. Owner decision 2026-09-05: with device Location switched off, [currentDeviceLocation]
 * fails and used to drop a routable event to the plain card through [failureSnapshot]'s
 * first-attempt fallback, so the owner would rather see the route from Home than no map at all.
 * [home] is always present when a refresh runs ([CommuteRefresher.performRefresh] returns early
 * without it), so a failed fix is never a routing failure any more.
 */
internal fun eventRouteOrigin(deviceLocation: ApiResult<LatLng>, home: Place): LatLng = when (deviceLocation) {
    is ApiResult.Success -> deviceLocation.value
    is ApiResult.Failure -> LatLng(home.lat, home.lng)
}

/**
 * v4 event advisor: leaveBy = eventStart - buffer - route duration, in epoch millis. The same
 * arithmetic applies whether [durationSeconds] came from a PREDICTED or real-time route (the
 * traffic model used to obtain it is [eventDepartureProbe]'s concern, not this one's).
 */
internal fun eventLeaveByEpochMillis(
    eventStartEpochMillis: Long,
    bufferMinutes: Int,
    durationSeconds: Long,
): Long = eventStartEpochMillis - bufferMinutes * 60_000L - durationSeconds * 1_000L

/**
 * Local minute-of-day of [leaveByEpochMillis] for display on [CommuteSnapshot.leaveByMinuteOfDay].
 * Events are same-day by construction, so the only clamp needed is a pathologically long drive
 * pushing the computed leave-by instant before local midnight of [todayLocalDate] - that case
 * clamps to 0 (start of today) rather than producing a negative or wrapped-around minute-of-day.
 */
internal fun eventLeaveByMinuteOfDay(
    leaveByEpochMillis: Long,
    zoneId: ZoneId,
    todayLocalDate: LocalDate,
): Int {
    val leaveByZoned = Instant.ofEpochMilli(leaveByEpochMillis).atZone(zoneId)
    if (leaveByZoned.toLocalDate().isBefore(todayLocalDate)) {
        return 0
    }
    return leaveByZoned.hour * 60 + leaveByZoned.minute
}

/**
 * A [RefreshTrigger.TICK] fetch never downloads a fresh map for the commute pipeline; it may only
 * keep showing the previous one, and only when the previous snapshot was unambiguously the same
 * route (matching [Direction] and resolved destination coordinates). This logic dates back to the
 * v3 10-minute history-sampling cadence (formerly [RefreshTrigger.SLOT]) and is unchanged in v5 -
 * it now simply serves [com.crpakala.commutewidget.schedule.CalendarTickWorker]'s 20-minute
 * calendar-staleness tick instead, on the rare path where a tick lands after a commute window has
 * already opened (see that worker's own guard against the more common case).
 */
internal fun shouldReuseSlotMap(
    previousDirection: Direction?,
    previousDestinationLat: Double?,
    previousDestinationLng: Double?,
    direction: Direction,
    destination: LatLng,
): Boolean {
    return previousDirection == direction &&
        previousDestinationLat == destination.lat &&
        previousDestinationLng == destination.lng
}

/** Fire/no-fire predicate for the leave-by notification, kept side-effect free for testing. */
internal fun shouldFireLeaveByNotification(
    leaveByEnabled: Boolean,
    nowMinuteOfDay: Int,
    leaveByMinuteOfDay: Int,
    arriveByMinuteOfDay: Int,
    alreadyNotifiedToday: Boolean,
): Boolean {
    if (!leaveByEnabled || alreadyNotifiedToday) return false
    return nowMinuteOfDay in leaveByMinuteOfDay..arriveByMinuteOfDay
}

/**
 * Whether a location reading taken at [locationTimeEpochMillis] is fresh enough at
 * [nowEpochMillis] to use directly instead of requesting a new fix - part of the v3 refresh-lag
 * fix: `fusedClient.lastLocation` is checked first and used when fresh (age `<=` [maxAgeMillis]),
 * only falling back to the slower `getCurrentLocation` flow when it is stale.
 */
internal fun isLocationFresh(
    locationTimeEpochMillis: Long,
    nowEpochMillis: Long,
    maxAgeMillis: Long = DEFAULT_LOCATION_MAX_AGE_MILLIS,
): Boolean {
    return nowEpochMillis - locationTimeEpochMillis <= maxAgeMillis
}

/**
 * v5 FIX-1: the pre-fetch render (and the [SettingsRepository.setRefreshing] pending-alpha flag
 * it drives) only pays for itself on a tap, where a human is watching the instant the refresh
 * starts. AUTO (window boundaries) and TICK (calendar staleness) are background triggers nobody
 * is looking at, so they skip it entirely and never touch the flag.
 */
internal fun shouldRenderEarlyBeforeFetch(trigger: RefreshTrigger): Boolean = trigger == RefreshTrigger.TAP

/**
 * v5 FIX-2 debounced-tap pending frame: a tap landing inside [MIN_REFRESH_GAP_MS] still plays a
 * brief pending-then-settled frame pair (see [CommuteRefresher.refreshNow]) so the control never
 * reads as dead, even though no fetch actually runs. AUTO/TICK cooldown skips stay silent - no
 * one is watching a background trigger's cooldown skip either.
 */
internal fun shouldPlayCooldownPendingFrame(trigger: RefreshTrigger): Boolean = trigger == RefreshTrigger.TAP

private data class LeaveByPlan(
    val leaveByMinuteOfDay: Int,
    val arriveByMinuteOfDay: Int,
    val travelMinutes: Int,
)

object CommuteRefresher {
    private val mutex = Mutex()
    private var lastCompletedElapsedRealtime = 0L

    // FIX-4: a detached scope for the AUTO location warm-up (see warmUpDeviceLocationCache) - it
    // must run concurrently with, and independently of, the refresh's own coroutine so it can
    // never delay or fail the main pipeline. Mirrors CommuteScheduler's own fire-and-forget scope.
    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [bypassCooldown] is for the Ride and Reached pill taps: they write ride state first and the
     * fetch that follows must run even inside the debounce gap, or the pill would vanish with no
     * map ever loading until the next refresh. The Flights toggle passes it for the same reason:
     * it has just changed what the widget may show and the refresh must replace it now.
     *
     * [airportBoundaryPolicy] is the [ExistingWorkPolicy] this refresh uses to re-arm the airport
     * boundary chain at the end of [performRefresh]. It only ever differs for
     * [com.crpakala.commutewidget.schedule.AirportBoundaryWorker], which is itself the running
     * holder of that unique work name and would be cancelled mid-run by the default REPLACE.
     */
    suspend fun refreshNow(
        context: Context,
        trigger: RefreshTrigger,
        bypassCooldown: Boolean = false,
        airportBoundaryPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val appContext = context.applicationContext
        mutex.withLock {
            val repo = SettingsRepository.get(appContext)
            val nowElapsed = SystemClock.elapsedRealtime()
            val skipForCooldown = shouldSkipForCooldown(
                elapsedSinceLastCompletedMillis = nowElapsed - lastCompletedElapsedRealtime,
                lastCompletedSet = lastCompletedElapsedRealtime != 0L,
                bypassCooldown = bypassCooldown,
                minGapMillis = MIN_REFRESH_GAP_MS,
            )
            if (skipForCooldown) {
                if (shouldPlayCooldownPendingFrame(trigger)) {
                    repo.setRefreshing(true)
                    CommuteWidget().updateAll(appContext)
                    // finally, not a plain sequential call: a cancellation (or any exception)
                    // during the debounce delay must not strand refreshingSince=true - that would
                    // leave the widget showing the pending-alpha ETA indefinitely, with no fetch
                    // in flight to ever clear it via the main path's own finally block below.
                    try {
                        delay(TAP_COOLDOWN_PENDING_FRAME_MS)
                    } finally {
                        // NonCancellable: a cancelled coroutine cannot suspend, so without it the
                        // DataStore write and the render would throw immediately inside finally
                        // and strand the pending-alpha ETA on screen.
                        withContext(NonCancellable) {
                            repo.setRefreshing(false)
                            CommuteWidget().updateAll(appContext)
                        }
                    }
                }
                return
            }

            if (trigger == RefreshTrigger.AUTO) {
                // FIX-4: warm the fused provider's lastLocation cache for the next tap - detached
                // (own scope, not a structured child of this refresh) so it can never delay or
                // fail the main pipeline below. Result is deliberately discarded.
                warmUpScope.launch {
                    try {
                        warmUpDeviceLocationCache(appContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }

            if (shouldRenderEarlyBeforeFetch(trigger)) {
                // Set BEFORE any location/network work, with an immediate widget update, so the
                // widget can render the pending alpha instantly instead of only after the fetch.
                repo.setRefreshing(true)
                CommuteWidget().updateAll(appContext)
            }
            try {
                performRefresh(appContext, trigger, airportBoundaryPolicy)
                // Fire-and-forget: at most one sampling run per day, never on the pixel path.
                BestDepartureAdvisor.maybeComputeAsync(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val settings = repo.settingsSnapshot()
                saveFailure(repo, currentDirectionHint(settings, ZonedDateTime.now()), e.message ?: "Refresh failed")
            } finally {
                lastCompletedElapsedRealtime = SystemClock.elapsedRealtime()
                // NonCancellable: Glance action coroutines get cancelled after ~10s; a slow
                // route+map fetch reaching this finally in a cancelled coroutine must still be
                // able to clear the pending flag and render, or the ETA strands at 45% alpha.
                withContext(NonCancellable) {
                    if (shouldRenderEarlyBeforeFetch(trigger)) {
                        repo.setRefreshing(false)
                    }
                    CommuteWidget().updateAll(appContext)
                }
            }
        }
    }

    /** Compatibility overload for existing callers (widget RefreshAction): treated as a manual tap. */
    suspend fun refreshNow(context: Context) = refreshNow(context, RefreshTrigger.TAP)

    /**
     * Resolves the window model and runs exactly one pipeline. Outside a window the calendar
     * pipeline runs in true calendar mode. Inside a window [resolveInWindowBranch] picks the
     * branch: an event takeover interrupts a ride in progress and hands over to the calendar
     * pipeline, a RIDING ride fetches the route and map, and otherwise the one-call leave-by probe
     * runs and the calendar pipeline renders the in-window card carrying the Ride pill. Both
     * in-window branches pass `inWindow = true`, so the card body drops the next-window and
     * wind-down fields and keeps the probe's alarm alive.
     *
     * Airport mode outranks all of that. When a Gmail-added flight's window is open (see
     * [airportTakeoverApplies]) [performAirportRefresh] runs instead and the commute window, the
     * ride state machine, the once-per-window probe and the calendar pipeline are all skipped -
     * a stored [com.crpakala.commutewidget.data.RideState] is not even read. Every path, airport
     * or not, ends by re-arming the airport boundary chain.
     */
    private suspend fun performRefresh(
        context: Context,
        trigger: RefreshTrigger,
        airportBoundaryPolicy: ExistingWorkPolicy,
    ) {
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        if (settings.apiKey.isBlank() || settings.home == null || settings.work == null) {
            return
        }

        val now = ZonedDateTime.now()
        val nowEpochMillis = System.currentTimeMillis()
        val dayOfWeekIso = now.dayOfWeek.value
        val minuteOfDay = now.hour * 60 + now.minute

        val widgetMode = resolveWidgetMode(
            dayOfWeekIso = dayOfWeekIso,
            minuteOfDay = minuteOfDay,
            commuteDays = settings.commuteDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        )

        // A direction is still required unconditionally: it seeds CommuteSnapshot.direction even
        // in calendar mode (where it is otherwise a don't-care for rendering).
        val nextWindowResult = nextWindowFor(settings, dayOfWeekIso, minuteOfDay)
        val direction = resolveDirectionForSnapshot(widgetMode, nextWindowResult?.direction)

        val calendarReader = CalendarReader(context)
        val hasCalendarPermission = calendarReader.hasPermission()
        val calendarReadable = settings.calendarEnabled && hasCalendarPermission && settings.selectedCalendarIds.isNotEmpty()
        // Settings > Flights off reads no flights at all, which is the whole gate: nothing takes
        // the widget over, resolveFlightPreview clears the stored row, the airport boundary chain
        // finds no boundary and cancels, and a Gmail flight is just another calendar event.
        val flightsReadable = calendarReadable && settings.flightsEnabled
        // Read once at FLIGHT_PREVIEW_LOOKAHEAD_DAYS and shared by both consumers: airport mode's
        // takeover check and the calendar card's flight-preview row. selectActiveFlight only ever
        // considers flights whose window has already opened, so the wider list cannot change which
        // flight (if any) takes the widget over - it only lets the preview see further ahead.
        val flights = if (flightsReadable) {
            calendarReader.upcomingFlights(settings.selectedCalendarIds, nowEpochMillis, FLIGHT_PREVIEW_LOOKAHEAD_DAYS)
        } else {
            emptyList()
        }
        // The dismissed set only has to outlive the flights the calendar still returns, so it is
        // pruned to them on every read the calendar actually served - a read the calendar or the
        // Flights toggle gates out returns nothing and must not be mistaken for "these flights
        // are gone".
        val storedDismissed = repo.airportDismissedEventIds()
        val dismissedEventIds = if (flightsReadable) {
            val flightIds = flights.mapTo(mutableSetOf()) { it.eventId }
            val pruned = storedDismissed intersect flightIds
            if (pruned != storedDismissed) {
                repo.updateAirportDismissedEventIds { it intersect flightIds }
            }
            pruned
        } else {
            storedDismissed
        }
        // Closed calendar events: the key embeds the start, so the 48-hour prune is a pure
        // function of the stored set and needs no calendar read to decide what is still live.
        val storedClosedKeys = repo.closedEventKeys()
        val closedEventKeys = pruneClosedEventKeys(storedClosedKeys, nowEpochMillis)
        if (closedEventKeys != storedClosedKeys) {
            repo.updateClosedEventKeys { pruneClosedEventKeys(it, nowEpochMillis) }
        }
        val airportState = repo.airportState()
        val airportWindow = selectActiveFlight(
            flights = flights,
            state = airportState,
            nowEpochMillis = nowEpochMillis,
            pillLeadMinutes = settings.airportPillLeadMinutes,
            arriveAheadMinutes = settings.airportArriveAheadMinutes,
            dismissedEventIds = dismissedEventIds,
        )
        if (airportWindow != null &&
            airportTakeoverApplies(settings.calendarEnabled, hasCalendarPermission, settings.selectedCalendarIds, airportWindow)
        ) {
            val leaveByMillis = performAirportRefresh(
                context = context,
                repo = repo,
                settings = settings,
                trigger = trigger,
                direction = direction,
                window = airportWindow,
                storedState = airportState,
                now = now,
                nowEpochMillis = nowEpochMillis,
            )
            AirportBoundaryScheduler.schedule(
                context = context,
                flights = flights,
                state = repo.airportState(),
                dismissedEventIds = repo.airportDismissedEventIds(),
                settings = settings,
                leaveByMillis = leaveByMillis,
                nowEpochMillis = nowEpochMillis,
                existingWorkPolicy = airportBoundaryPolicy,
            )
            return
        }

        // Tap-to-ride state is keyed by local date and direction, so a value left over from
        // another day or from the other window is inert and resolves to OFFERED.
        val today = now.toLocalDate().toString()
        val rideState = repo.rideState()

        when (widgetMode) {
            is WidgetMode.Commute -> {
                val phase = resolveRidePhase(rideState, today, widgetMode.direction)
                val takeoverApplies = eventTakeoverCandidate(context, settings, nowEpochMillis, closedEventKeys) != null
                when (resolveInWindowBranch(takeoverApplies, phase)) {
                    InWindowBranch.EVENT_TAKEOVER -> {
                        // A located event starting within eventTakeoverMinutes outranks the window
                        // commute (owner decision after the v5 audit). The calendar pipeline re-selects
                        // the same event deterministically and handles routing, leave-by, and tick
                        // scheduling, and it must keep the window probe's alarm alive.
                        val postInterruptPhase = if (phase == RidePhase.RIDING) {
                            repo.updateRideState { applyRideInterrupted(it, today, widgetMode.direction) }
                            RidePhase.INTERRUPTED
                        } else {
                            phase
                        }
                        // The probe gates itself on phase OFFERED, so an interrupted ride runs nothing.
                        maybeRunCommuteProbe(context, repo, settings, widgetMode, postInterruptPhase, today, now, nowEpochMillis)
                        performCalendarRefresh(context, repo, settings, trigger, direction, now, nowEpochMillis, inWindow = true, cancelCommuteLeaveBy = false, flights = flights, dismissedEventIds = dismissedEventIds, closedEventKeys = closedEventKeys)
                    }
                    InWindowBranch.RIDE -> {
                        val fromCurrentLocation = rideFromCurrentLocation(rideState, today, widgetMode.direction)
                        performCommuteRefresh(context, repo, settings, trigger, widgetMode.direction, today, now, nowEpochMillis, fromCurrentLocation, flights, dismissedEventIds, closedEventKeys)
                    }
                    InWindowBranch.PROBE_AND_CALENDAR -> {
                        maybeRunCommuteProbe(context, repo, settings, widgetMode, phase, today, now, nowEpochMillis)
                        performCalendarRefresh(context, repo, settings, trigger, direction, now, nowEpochMillis, inWindow = true, cancelCommuteLeaveBy = false, flights = flights, dismissedEventIds = dismissedEventIds, closedEventKeys = closedEventKeys)
                    }
                }
            }
            WidgetMode.Calendar -> {
                performCalendarRefresh(context, repo, settings, trigger, direction, now, nowEpochMillis, inWindow = false, cancelCommuteLeaveBy = true, flights = flights, dismissedEventIds = dismissedEventIds, closedEventKeys = closedEventKeys)
            }
        }

        // No flight owns the widget right now, so there is no live leave-by to wake for - only
        // the calendar-derived boundaries, the earliest of which is the next window opening.
        AirportBoundaryScheduler.schedule(
            context = context,
            flights = flights,
            state = airportState,
            dismissedEventIds = dismissedEventIds,
            settings = settings,
            leaveByMillis = null,
            nowEpochMillis = nowEpochMillis,
            existingWorkPolicy = airportBoundaryPolicy,
        )
    }

    private fun eventTakeoverCandidate(
        context: Context,
        settings: AppSettings,
        nowEpochMillis: Long,
        closedEventKeys: Set<String>,
    ): TodayEvent? {
        if (!settings.calendarEnabled || settings.selectedCalendarIds.isEmpty()) return null
        val reader = CalendarReader(context)
        if (!reader.hasPermission()) return null
        val event = reader.nextEventToday(
            settings.selectedCalendarIds,
            nowEpochMillis,
            ZoneId.systemDefault(),
            minLookaheadMinutes = settings.eventTakeoverMinutes,
            closedEventKeys = closedEventKeys,
        ) ?: return null
        return event.takeIf {
            eventTakeoverApplies(it.startEpochMillis, it.location != null, nowEpochMillis, settings.eventTakeoverMinutes)
        }
    }

    /**
     * Airport mode's pipeline. Returns the leave-by instant this refresh computed, which the
     * caller feeds to the airport boundary chain; null whenever none exists (REACHED, or any
     * degraded outcome). Every exception is turned into a [SnapshotMode.AIRPORT] snapshot with the
     * airport block populated as far as it is known, so a failure here can never fall through to
     * [refreshNow]'s generic handler and drop the widget back into the commute pipeline.
     */
    private suspend fun performAirportRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        window: AirportWindow,
        storedState: AirportState?,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ): Long? {
        val state = resolveAirportState(storedState, window.flight, now.zone, repo.flightPreview(), window.layover)
        return try {
            runAirportPipeline(context, repo, settings, trigger, direction, window, state, storedState, now, nowEpochMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.saveSnapshot(
                airportSnapshotFrom(
                    window = window,
                    state = state,
                    direction = direction,
                    zone = now.zone,
                    nowEpochMillis = nowEpochMillis,
                    healthComputation = healthCarriedForward(repo.snapshot()),
                    reachedOffered = shouldShowAirportReached(state, window, nowEpochMillis, null, null),
                    errorMessage = e.message ?: "Airport refresh failed",
                ),
            )
            null
        }
    }

    private suspend fun runAirportPipeline(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        window: AirportWindow,
        state: AirportState,
        storedState: AirportState?,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ): Long? {
        // The commute window's precise alarm must not fire under an airport takeover - the same
        // cancel performCalendarRefresh(cancelCommuteLeaveBy = true) performs when it takes the
        // widget out of a window. EventLeaveByScheduler is deliberately NOT cancelled here: the
        // airport leave-by below is scheduled through it and would cancel itself every refresh.
        CommuteLeaveByScheduler.cancel(context)

        if (state != storedState) {
            repo.setAirportState(state)
        }

        val flight = window.flight
        val previousSnapshot = repo.snapshot()
        val healthComputation = computeHealthFieldsSafely(context, settings, nowEpochMillis, now.zone, previousSnapshot)

        // REACHED and LAYOVER show the flight card instead of the map, so they spend no Routes and
        // no Static Maps quota at all. The one AirLabs call is the flight status below, which
        // [shouldAutoFetchFlightStatus] rations to one per [statusTickMillisFor] (half an hour in
        // REACHED, an hour in LAYOVER) and stops entirely once the flight has landed or been
        // cancelled. The state is re-read after it so this refresh's snapshot carries the status it
        // just paid for. The only other network here is [cacheFlightAirports], which is Google
        // geocoding quota rather than AirLabs, runs at most once per airport for the life of the
        // install, and is what puts the route distance on the card.
        if (airportShowsFlightCard(state.phase)) {
            val fetched = if (shouldAutoFetchFlightStatus(state, settings.flightStatusApiKey, nowEpochMillis)) {
                refreshFlightStatus(context, repo, flight, settings.flightStatusApiKey, nowEpochMillis)
                repo.airportState()?.takeIf { it.eventId == flight.eventId } ?: state
            } else {
                state
            }
            val cache = cacheFlightAirports(repo, settings.apiKey, flight, fetched.lastStatus, nowEpochMillis)
            // Both caches are filled by [refreshFlightStatus] above; read here so the card renders
            // whatever this refresh just paid for rather than waiting a tick to show it.
            val status = fetched.lastStatus
            val registration = status?.aircraftRegistration?.uppercase()
            val departureIata = (status?.departureIata ?: flight.departureIata)?.uppercase()
            repo.saveSnapshot(
                airportSnapshotFrom(
                    window = window,
                    state = fetched,
                    direction = direction,
                    zone = now.zone,
                    nowEpochMillis = nowEpochMillis,
                    healthComputation = healthComputation,
                    routeDistanceKm = flightRouteDistanceKm(cache, flight, status),
                    aircraft = registration?.let { repo.aircraftFleet()[it] },
                    departureDelayStats = departureIata?.let { repo.airportDelayStats()[it] },
                ),
            )
            return null
        }

        // Same concurrency discipline as the located-event geocode in performCalendarRefresh: the
        // device fix and the airport lookup are independent, so they run together.
        val (destination, deviceResult) = coroutineScope {
            val deviceDeferred = async { currentDeviceLocation(context) }
            val resolved = resolveAirportDestination(repo, settings.apiKey, flight, state.lastStatus, nowEpochMillis)
            resolved to deviceDeferred.await()
        }
        val geocodesSpent = destination.geocodesSpent

        val deviceLocation = (deviceResult as? ApiResult.Success)?.value
        val home = settings.home
        val origin = when {
            home != null -> eventRouteOrigin(deviceResult, home)
            else -> deviceLocation
        }
        val airport = destination.location
        val airportCache = cacheFlightAirports(
            repo = repo,
            apiKey = settings.apiKey,
            flight = flight,
            status = state.lastStatus,
            nowEpochMillis = nowEpochMillis,
            budget = AIRPORT_GEOCODE_BUDGET_PER_REFRESH - geocodesSpent,
        )
        val routeDistanceKm = flightRouteDistanceKm(airportCache, flight, state.lastStatus)

        // A missing airport (no usable query text, or a geocode that failed) does not end airport
        // mode: the pills and the flight card still work off the calendar's own fields, there is
        // simply no route and no map to draw.
        if (airport == null || origin == null) {
            repo.saveSnapshot(
                airportSnapshotFrom(
                    window = window,
                    state = state,
                    direction = direction,
                    zone = now.zone,
                    nowEpochMillis = nowEpochMillis,
                    healthComputation = healthComputation,
                    originLabel = airportOriginLabel(deviceResult),
                    airport = airport,
                    airportName = destination.name,
                    reachedOffered = shouldShowAirportReached(state, window, nowEpochMillis, deviceLocation, airport),
                    routeDistanceKm = routeDistanceKm,
                    errorMessage = destination.error ?: "No location fix and no Home saved",
                ),
            )
            return null
        }

        val routes = RoutesClient(settings.apiKey)
        val travelMode = travelModeFor(settings.travelMode)
        // Real-time traffic, never a departure probe: the Leave by pill is about leaving now, and
        // the predicted-traffic question is the Best pill's, answered by the sampler below.
        val routeResult = routes.computeRoute(origin, airport, travelMode, departureTimeEpochMillis = null)
        val route = when (routeResult) {
            is ApiResult.Success -> routeResult.value
            is ApiResult.Failure -> {
                repo.saveSnapshot(
                    airportSnapshotFrom(
                        window = window,
                        state = state,
                        direction = direction,
                        zone = now.zone,
                        nowEpochMillis = nowEpochMillis,
                        healthComputation = healthComputation,
                        originLabel = airportOriginLabel(deviceResult),
                        airport = airport,
                        airportName = destination.name,
                        reachedOffered = shouldShowAirportReached(state, window, nowEpochMillis, deviceLocation, airport),
                        routeDistanceKm = routeDistanceKm,
                        errorMessage = routeResult.message,
                    ),
                )
                return null
            }
        }

        // Only RIDING draws the map (see [airportLoadsMap]). OFFERED has just spent the one Routes
        // call above, which is all Leave by needs; building and downloading a Static Maps PNG for
        // a journey the owner has not asked for yet is quota spent on nothing. Same render-cache
        // path the commute and calendar pipelines use when it does run: the key hashes the route
        // geometry, its traffic colors and both endpoints, so a tick whose route is unchanged
        // reuses the PNG already on disk instead of re-downloading it.
        val mapResult = if (airportLoadsMap(state.phase)) {
            resolveMapImagePath(context, repo, trigger, previousSnapshot, direction, airport, origin, route, settings.apiKey)
        } else {
            null
        }

        // The Best sampling is the Best pill's, and the pill is OFFERED-only, so a ride already in
        // progress never starts a sampling it has nowhere to show. One that ran while the window
        // was still OFFERED is kept and carried through.
        val existingBest = repo.airportDeparture()
        val best = if (airportShowsLeaveByAndBest(state.phase) &&
            shouldComputeAirportDeparture(existingBest, flight.eventId, nowEpochMillis, window.windowStartMillis, window.arriveByMillis)
        ) {
            AirportDepartureAdvisor.compute(
                routes = routes,
                origin = origin,
                airport = airport,
                mode = travelMode,
                eventId = flight.eventId,
                windowStartMillis = window.windowStartMillis,
                arriveByMillis = window.arriveByMillis,
                nowEpochMillis = nowEpochMillis,
            )?.also { repo.setAirportDeparture(it) } ?: existingBest
        } else {
            existingBest
        }

        val leaveByMillis = airportLeaveByMillis(window.arriveByMillis, route.durationSeconds)
        repo.saveSnapshot(
            airportSnapshotFrom(
                window = window,
                state = state,
                direction = direction,
                zone = now.zone,
                nowEpochMillis = nowEpochMillis,
                healthComputation = healthComputation,
                originLabel = airportOriginLabel(deviceResult),
                airport = airport,
                airportName = destination.name,
                route = route,
                mapImagePath = (mapResult as? ApiResult.Success)?.value,
                best = best,
                reachedOffered = shouldShowAirportReached(state, window, nowEpochMillis, deviceLocation, airport),
                routeDistanceKm = routeDistanceKm,
                errorMessage = (mapResult as? ApiResult.Failure)?.message,
            ),
        )

        // Dedup is EventLeaveByScheduler's own: its key is eventIdentityKey(start, title), and
        // both halves are stable for one flight (arrive-by target and airportLeaveByTitle), so a
        // tick inside the window re-arms the same wake-up rather than re-notifying, and the
        // airport state carries no notification flag of its own.
        EventLeaveByScheduler.scheduleOrPost(
            context = context,
            eventTitle = airportLeaveByTitle(flight),
            eventStartEpochMillis = window.arriveByMillis,
            leaveByEpochMillis = leaveByMillis,
            durationSeconds = route.durationSeconds,
            nowEpochMillis = nowEpochMillis,
        )

        return leaveByMillis
    }

    private fun nextWindowFor(settings: AppSettings, dayOfWeekIso: Int, minuteOfDay: Int): NextWindow? =
        nextWindow(
            dayOfWeekIso = dayOfWeekIso,
            minuteOfDay = minuteOfDay,
            commuteDays = settings.commuteDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        )

    /**
     * Once-per-window leave-by probe: one Routes call for the FIXED window trip, no Static Maps
     * and no snapshot write, so the Leave pill and the precise alarm exist before the owner taps
     * Ride. [shouldRunCommuteProbe] keeps it to the first OFFERED refresh of this window on this
     * local date, and a failure stores nothing so the next in-window refresh retries.
     * A probe must never fail the calendar refresh that follows it, so it degrades to doing
     * nothing on any exception, exactly like [computeHealthFieldsSafely] does for health.
     */
    private suspend fun maybeRunCommuteProbe(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        widgetMode: WidgetMode.Commute,
        phase: RidePhase,
        today: String,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ) {
        try {
            val existing = repo.commuteProbe()
            if (!shouldRunCommuteProbe(widgetMode, phase, settings.leaveByEnabled, existing, today)) {
                return
            }

            val direction = widgetMode.direction
            val trip = commuteTrip(settings.home!!, settings.work!!, direction)

            val route = when (
                val result = RoutesClient(settings.apiKey).computeRoute(trip.origin, trip.destination, travelModeFor(settings.travelMode))
            ) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return
            }

            val plan = commuteLeaveByPlanFor(settings, direction, route.durationSeconds)
            repo.setCommuteProbe(
                CommuteProbe(
                    localDate = today,
                    direction = direction,
                    durationSeconds = route.durationSeconds,
                    leaveByMinuteOfDay = plan?.leaveByMinuteOfDay,
                    probedAtEpochMillis = nowEpochMillis,
                ),
            )

            if (plan != null) {
                maybeNotifyLeaveBy(context, repo, settings, direction, plan, trip.destinationLabel, now)
                scheduleCommuteLeaveByAlarm(context, repo, direction, plan, trip.destinationLabel, now, nowEpochMillis)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * The ride pipeline: reached only while the ride phase is RIDING (see
     * [shouldRunCommutePipeline]), so an open commute window no longer fetches a route or a map on
     * its own. [fromCurrentLocation] is set for a resumed ride and routes from the device fix,
     * falling back to the fixed window origin when no fix arrives.
     */
    private suspend fun performCommuteRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        today: String,
        now: ZonedDateTime,
        nowEpochMillis: Long,
        fromCurrentLocation: Boolean,
        flights: List<FlightEvent>,
        dismissedEventIds: Set<Long>,
        closedEventKeys: Set<String>,
    ) {
        // v5: a commute-mode refresh of any outcome (success or failure) is "anything else" per
        // the calendar tick's schedule/cancel contract - see CalendarTickScheduler's doc.
        CalendarTickScheduler.cancel(context)

        val trip = commuteTrip(settings.home!!, settings.work!!, direction)
        val destination = trip.destination
        // A resumed ride routes from the device fix, bounded so a slow fix cannot eat the whole
        // Glance action budget. A failed or timed-out fix is not a routing failure here, it falls
        // back to the fixed window origin the way eventRouteOrigin falls back to Home.
        val origin = if (fromCurrentLocation) {
            val fix = withTimeoutOrNull(RESUMED_RIDE_FIX_TIMEOUT_MS) { currentDeviceLocation(context) }
                ?: ApiResult.Failure("Location timeout")
            commuteRouteOrigin(fix, trip.origin)
        } else {
            trip.origin
        }

        // Read before the Routes call, because resolveMapImagePath's reuse decision needs the
        // snapshot as it stood before this attempt.
        val previousSnapshot = repo.snapshot()

        val route = when (
            val result = RoutesClient(settings.apiKey).computeRoute(origin, destination, travelModeFor(settings.travelMode))
        ) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                saveRideFetchFailure(
                    context = context,
                    repo = repo,
                    settings = settings,
                    trigger = trigger,
                    direction = direction,
                    destinationLabel = trip.destinationLabel,
                    message = result.message,
                    today = today,
                    now = now,
                    nowEpochMillis = nowEpochMillis,
                    flights = flights,
                    dismissedEventIds = dismissedEventIds,
                    closedEventKeys = closedEventKeys,
                )
                return
            }
        }

        val mapImagePath = when (
            val mapResult = resolveMapImagePath(context, repo, trigger, previousSnapshot, direction, destination, origin, route, settings.apiKey)
        ) {
            is ApiResult.Success -> mapResult.value
            is ApiResult.Failure -> {
                saveRideFetchFailure(
                    context = context,
                    repo = repo,
                    settings = settings,
                    trigger = trigger,
                    direction = direction,
                    destinationLabel = trip.destinationLabel,
                    message = mapResult.message,
                    today = today,
                    now = now,
                    nowEpochMillis = nowEpochMillis,
                    flights = flights,
                    dismissedEventIds = dismissedEventIds,
                    closedEventKeys = closedEventKeys,
                )
                return
            }
        }

        val leaveByPlan = commuteLeaveByPlanFor(settings, direction, route.durationSeconds)
        val todaySummary = CalendarReader(context).takeIf {
            settings.calendarEnabled &&
                it.hasPermission() &&
                settings.selectedCalendarIds.isNotEmpty()
        }?.todaySummary(settings.selectedCalendarIds, nowEpochMillis, now.zone)

        // Sprint 2: baked in at computation time per the architecture contract - the widget only
        // re-filters these against day state and its own live audiobook check at render time.
        val healthComputation = computeHealthFieldsSafely(context, settings, nowEpochMillis, now.zone, previousSnapshot)

        repo.saveSnapshot(
            CommuteSnapshot(
                direction = direction,
                durationSeconds = route.durationSeconds,
                durationNoTrafficSeconds = route.staticDurationSeconds,
                distanceMeters = route.distanceMeters,
                mapImagePath = mapImagePath,
                fetchedAtEpochMillis = nowEpochMillis,
                lastFetchFailed = false,
                lastErrorMessage = null,
                destinationLabel = trip.destinationLabel,
                destinationLat = destination.lat,
                destinationLng = destination.lng,
                leaveByMinuteOfDay = leaveByPlan?.leaveByMinuteOfDay,
                mode = SnapshotMode.COMMUTE,
                eventStartEpochMillis = null,
                nextWindowLabel = null,
                nextWindowStartMinuteOfDay = null,
                todayEventCount = todaySummary?.remainingCount,
                todayFirstEventStartEpochMillis = todaySummary?.firstStartEpochMillis,
                healthNudges = healthComputation.healthNudges,
                sleepEstimateMinutes = healthComputation.sleepEstimateMinutes,
                shortSleepDay = healthComputation.shortSleepDay,
                customPillOccurrences = healthComputation.customPillOccurrences,
            ),
        )

        // The ride now owns a routed snapshot, so a later failure in this same ride counts as a
        // same-target failure and keeps the stale map instead of reverting the phase.
        repo.updateRideState { applyRideRouted(it, today, direction) }

        if (leaveByPlan != null) {
            maybeNotifyLeaveBy(context, repo, settings, direction, leaveByPlan, trip.destinationLabel, now)
            scheduleCommuteLeaveByAlarm(context, repo, direction, leaveByPlan, trip.destinationLabel, now, nowEpochMillis)
        }
    }

    /**
     * Ride fetch failure split, decided by ride identity rather than by the stored snapshot.
     * On the ride's FIRST attempt ([isRideFirstAttempt], so this ride has not stored a routed
     * snapshot yet) the phase reverts through [applyRideFailed] and the calendar pipeline
     * re-renders the in-window card, so the owner gets the Ride pill tinted late instead of a
     * broken routed shell. Once the ride has routed, a later failure keeps today's behavior and
     * [saveFailure] preserves the stale route and map with the warning glyph while the phase stays
     * RIDING. The ride state is re-read here because the success path may have flagged it since
     * this refresh started.
     */
    private suspend fun saveRideFetchFailure(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        destinationLabel: String,
        message: String,
        today: String,
        now: ZonedDateTime,
        nowEpochMillis: Long,
        flights: List<FlightEvent>,
        dismissedEventIds: Set<Long>,
        closedEventKeys: Set<String>,
    ) {
        if (!isRideFirstAttempt(repo.rideState(), today, direction)) {
            saveFailure(repo, direction, message, SnapshotMode.COMMUTE, destinationLabel)
            return
        }
        repo.updateRideState { applyRideFailed(it, today, direction) }
        performCalendarRefresh(context, repo, settings, trigger, direction, now, nowEpochMillis, inWindow = true, cancelCommuteLeaveBy = false, flights = flights, dismissedEventIds = dismissedEventIds, closedEventKeys = closedEventKeys)
    }

    /**
     * v3 calendar mode; v4 adds the event leave-by advisor for a located event with a start time;
     * v5 adds the opt-in coarse staleness tick (see [CalendarTickScheduler]); a later change adds
     * the far-located gate below. Never records history (the history subsystem is removed
     * entirely in v5).
     *
     * A located event is only ever routed (geocode + Routes + Static Maps) when
     * [eventTakeoverApplies] says it is within [AppSettings.eventTakeoverMinutes] of now (an
     * already-started event counts). Farther out it gets the exact same zero-API plain card an
     * unlocated event gets (see [calendarPlainEventSnapshot]) and arms [EventNearScheduler] to
     * wake exactly when it crosses into that window - at which point a normal AUTO refresh
     * re-resolves calendar mode and routes it automatically.
     *
     * Every branch cancels [CalendarTickScheduler], [EventLeaveByScheduler], and
     * [EventNearScheduler]'s pending work up front - the previously scheduled event/tick/flip may
     * have moved, been cancelled, or resolved to a different mode, so a stale wake-up must not
     * survive to fire. The FIX-16 commute leave-by alarm is different now that the window probe
     * owns it: it is cancelled only when [cancelCommuteLeaveBy] is set, which happens only when
     * the caller resolved [WidgetMode.Calendar]. An alarm from an earlier commute window must not
     * survive into calendar mode, but an in-window calendar refresh (the pre-ride render, or an
     * event takeover) must keep the probe's alarm alive. Only the far-located branch re-arms
     * [EventNearScheduler], and only the final located-event success path re-schedules the tick,
     * each only when its own condition holds, and the event leave-by is never re-scheduled from
     * this function.
     *
     * A failure anywhere in the routed pipeline below (geocode, Routes, Static Maps) goes through
     * [saveFailure] with `modeOverride = SnapshotMode.CALENDAR_EVENT`. A failed device fix is not
     * a failure here, it routes from the saved Home place instead (see [eventRouteOrigin]). See
     * [failureSnapshot]'s doc for the resulting split: a first attempt at this event falls back to
     * the plain card, while a same-target failure (this event routed successfully before) keeps
     * showing the stale route/map with the warning glyph.
     *
     * [inWindow] marks the two in-window callers (the pre-ride render and the event takeover).
     * When it is set and no event remains today, the CALENDAR_EMPTY snapshot is built for the
     * commute body instead of the calendar body: no next-window line, no wind-down fields and no
     * upcoming-events lookup, and the today counts the morning brief needs are read the same way
     * the ride pipeline reads them. Out of window with no event remaining today, the wind-down
     * card's "Next up" section is populated instead: the next two events from tomorrow's local
     * midnight onward, within seven days (see [CalendarReader.upcomingEvents]). Event selection,
     * the plain card, the routed event, the near-flip arming and the tick are identical either way.
     *
     * [flights] and [dismissedEventIds] are the caller's single calendar flight read, reused here
     * for the flight-preview row (see [resolveFlightPreview]) rather than queried a second time.
     * Every snapshot this function stores carries the resolved preview; the [saveFailure] branches
     * do not, so a failed routed event keeps whatever preview the previous snapshot held.
     */
    private suspend fun performCalendarRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        now: ZonedDateTime,
        nowEpochMillis: Long,
        inWindow: Boolean,
        cancelCommuteLeaveBy: Boolean,
        flights: List<FlightEvent>,
        dismissedEventIds: Set<Long>,
        closedEventKeys: Set<String>,
    ) {
        CalendarTickScheduler.cancel(context)
        EventLeaveByScheduler.cancel(context)
        if (cancelCommuteLeaveBy) {
            CommuteLeaveByScheduler.cancel(context)
        }
        EventNearScheduler.cancel(context)

        // Sprint 2: fetched once and reused for every branch below (no writes happen before any
        // of them, so one read is equivalent to reading again per-branch) - both for [resolveMapImagePath]'s
        // existing map-reuse decision and as this refresh's health-fallback baseline.
        val previousSnapshot = repo.snapshot()
        val healthComputation = computeHealthFieldsSafely(context, settings, nowEpochMillis, now.zone, previousSnapshot)

        val calendarReader = CalendarReader(context)
        val canReadCalendar = settings.calendarEnabled &&
            calendarReader.hasPermission() &&
            settings.selectedCalendarIds.isNotEmpty()

        val event: TodayEvent? = if (canReadCalendar) {
            calendarReader.nextEventToday(
                settings.selectedCalendarIds,
                nowEpochMillis,
                now.zone,
                minLookaheadMinutes = settings.eventTakeoverMinutes,
                closedEventKeys = closedEventKeys,
            )
        } else {
            null
        }

        // At most one AirLabs call per refresh, and only from here: every branch below reuses this
        // single resolution, so no path can spend a second query. It runs after the event read
        // because a flight that is today's headline event earns the row however far off it is.
        val flightPreview = resolveFlightPreview(
            repo = repo,
            settings = settings,
            flights = if (canReadCalendar) flights else emptyList(),
            dismissedEventIds = dismissedEventIds,
            headlineEvent = event,
            zone = now.zone,
            nowEpochMillis = nowEpochMillis,
        )

        if (event == null) {
            if (inWindow) {
                // The in-window card body is the window label plus the morning brief, so nothing
                // next-window or wind-down shaped is stored and tomorrow is never queried.
                val todaySummary = calendarReader.takeIf { canReadCalendar }
                    ?.todaySummary(settings.selectedCalendarIds, nowEpochMillis, now.zone)
                repo.saveSnapshot(
                    calendarEmptySnapshot(
                        direction = direction,
                        nowEpochMillis = nowEpochMillis,
                        healthComputation = healthComputation,
                        todayEventCount = todaySummary?.remainingCount,
                        todayFirstEventStartEpochMillis = todaySummary?.firstStartEpochMillis,
                        flightPreview = flightPreview,
                    ),
                )
                return
            }
            val upcomingEvents = if (canReadCalendar) {
                calendarReader.upcomingEvents(
                    settings.selectedCalendarIds,
                    nowEpochMillis,
                    now.zone,
                    closedEventKeys = closedEventKeys,
                )
            } else {
                emptyList()
            }
            repo.saveSnapshot(
                calendarEmptySnapshot(
                    direction = direction,
                    nowEpochMillis = nowEpochMillis,
                    upcomingEvents = upcomingEvents,
                    healthComputation = healthComputation,
                    flightPreview = flightPreview,
                ),
            )
            return
        }

        val location = event.location
        if (location.isNullOrBlank()) {
            repo.saveSnapshot(
                calendarPlainEventSnapshot(direction, nowEpochMillis, event.title, event.startEpochMillis, healthComputation, flightPreview),
            )
            return
        }

        // Far-located gate: this event HAS a location, but does not yet qualify under
        // eventTakeoverApplies (same threshold that decides commute-vs-calendar takeover, reused
        // here as the single nearness concept - see that function's doc). Render the identical
        // plain card the unlocated branch above just built, at zero Google API cost, and arm the
        // near-flip one-shot to re-check exactly when this event becomes near. An already-started
        // or already-near event falls through unchanged to the routed pipeline below.
        if (!eventTakeoverApplies(event.startEpochMillis, true, nowEpochMillis, settings.eventTakeoverMinutes)) {
            repo.saveSnapshot(
                calendarPlainEventSnapshot(direction, nowEpochMillis, event.title, event.startEpochMillis, healthComputation, flightPreview),
            )
            EventNearScheduler.scheduleAt(
                context,
                eventNearFlipEpochMillis(event.startEpochMillis, settings.eventTakeoverMinutes),
            )
            return
        }

        // FIX-11: geocoding and device location are independent - run them concurrently, and skip
        // the geocode network call entirely when the single-entry cache still matches this event's
        // location text (only one event is ever displayed, so one entry is the whole cache).
        val cachedGeocode = repo.geocodeCache()
        val (destinationResult, originResult) = coroutineScope {
            val originDeferred = async { currentDeviceLocation(context) }
            val destResult: ApiResult<LatLng> = if (cachedGeocode != null && cachedGeocode.address == location) {
                ApiResult.Success(LatLng(cachedGeocode.lat, cachedGeocode.lng))
            } else {
                when (val result = GeocodingClient(settings.apiKey).geocode(location)) {
                    is ApiResult.Success -> {
                        val hit = result.value.firstOrNull()
                        if (hit == null) {
                            ApiResult.Failure("Event location not found")
                        } else {
                            repo.setGeocodeCache(Place(address = location, lat = hit.location.lat, lng = hit.location.lng))
                            ApiResult.Success(hit.location)
                        }
                    }
                    is ApiResult.Failure -> ApiResult.Failure(result.message, result.cause)
                }
            }
            destResult to originDeferred.await()
        }
        val destination = when (destinationResult) {
            is ApiResult.Success -> destinationResult.value
            is ApiResult.Failure -> {
                saveFailure(repo, direction, destinationResult.message, SnapshotMode.CALENDAR_EVENT, event.title, event.startEpochMillis)
                return
            }
        }
        // A failed device fix (Location switched off, no fix within the timeout) falls back to the
        // saved Home place as the origin instead of failing the event - see eventRouteOrigin.
        val origin = eventRouteOrigin(originResult, settings.home!!)

        // v4: more than settings.eventRealtimeThresholdMinutes before the event, request PREDICTED
        // traffic around the event's arrival time instead of real-time (null keeps the existing
        // real-time behavior, which is also what RoutesClient falls back to on its own near-future
        // floor). Computed only when the advisor is enabled - a disabled advisor keeps the plain
        // real-time route request calendar mode has always made.
        val departureProbe = if (settings.leaveByEnabled) {
            eventDepartureProbe(
                eventStartEpochMillis = event.startEpochMillis,
                nowEpochMillis = nowEpochMillis,
                thresholdMinutes = settings.eventRealtimeThresholdMinutes,
                bufferMinutes = settings.eventLeaveByBufferMinutes,
            )
        } else {
            null
        }

        val route = when (
            val result = RoutesClient(settings.apiKey).computeRoute(
                origin,
                destination,
                travelModeFor(settings.travelMode),
                departureProbe,
            )
        ) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                saveFailure(repo, direction, result.message, SnapshotMode.CALENDAR_EVENT, event.title, event.startEpochMillis)
                return
            }
        }

        // v5: routed through the same trigger-aware resolver the commute pipeline uses (see
        // resolveMapImagePath's doc and SlotMapReuseTest, which documents shouldReuseSlotMap as
        // now serving RefreshTrigger.TICK's calendar-staleness role) - a TICK reuses the previous
        // map when it is still the same located event (same direction + destination coordinates)
        // rather than paying for a fresh Static Maps fetch on every 20-minute staleness tick.
        // (previousSnapshot was already fetched above, before the event branches.)
        val mapImagePath = when (
            val mapResult = resolveMapImagePath(context, repo, trigger, previousSnapshot, direction, destination, origin, route, settings.apiKey)
        ) {
            is ApiResult.Success -> mapResult.value
            is ApiResult.Failure -> {
                saveFailure(
                    repo,
                    direction,
                    mapResult.message,
                    SnapshotMode.CALENDAR_EVENT,
                    event.title,
                    event.startEpochMillis,
                )
                return
            }
        }

        val leaveByEpochMillis = if (settings.leaveByEnabled) {
            eventLeaveByEpochMillis(event.startEpochMillis, settings.eventLeaveByBufferMinutes, route.durationSeconds)
        } else {
            null
        }
        val leaveByMinuteOfDay = leaveByEpochMillis?.let { eventLeaveByMinuteOfDay(it, now.zone, now.toLocalDate()) }

        repo.saveSnapshot(
            CommuteSnapshot(
                direction = direction,
                durationSeconds = route.durationSeconds,
                durationNoTrafficSeconds = route.staticDurationSeconds,
                distanceMeters = route.distanceMeters,
                mapImagePath = mapImagePath,
                fetchedAtEpochMillis = nowEpochMillis,
                lastFetchFailed = false,
                lastErrorMessage = null,
                destinationLabel = event.title,
                destinationLat = destination.lat,
                destinationLng = destination.lng,
                leaveByMinuteOfDay = leaveByMinuteOfDay,
                mode = SnapshotMode.CALENDAR_EVENT,
                eventStartEpochMillis = event.startEpochMillis,
                nextWindowLabel = null,
                nextWindowStartMinuteOfDay = null,
                routedOverEarlier = event.preferredOverEarlierEvent,
                healthNudges = healthComputation.healthNudges,
                sleepEstimateMinutes = healthComputation.sleepEstimateMinutes,
                shortSleepDay = healthComputation.shortSleepDay,
                customPillOccurrences = healthComputation.customPillOccurrences,
                flightPreview = flightPreview,
            ),
        )

        if (leaveByEpochMillis != null) {
            EventLeaveByScheduler.scheduleOrPost(
                context = context,
                eventTitle = event.title,
                eventStartEpochMillis = event.startEpochMillis,
                leaveByEpochMillis = leaveByEpochMillis,
                durationSeconds = route.durationSeconds,
                nowEpochMillis = nowEpochMillis,
            )
        }

        // v5 calendar tick: only a LOCATED event snapshot (this branch) is eligible - see
        // CalendarTickScheduler's doc for why every other branch cancelled up front instead.
        if (shouldScheduleCalendarTick(SnapshotMode.CALENDAR_EVENT, settings.calendarTickEnabled)) {
            CalendarTickScheduler.schedule(context)
        }
    }

    /**
     * Resolves the calendar card's flight row and persists it, spending at most one AirLabs query.
     * Called once per calendar refresh, before any branch, so no path can spend a second.
     *
     * The row names [selectPreviewFlight]'s pick from the caller's already-read flight list: the
     * next flight once it is [headlineEvent], departs within eight hours, or has its airport window
     * open, dismissed or not, so the row keeps the flight's data on the card after Done ends the
     * takeover. No near flight clears the stored preview and renders nothing.
     *
     * A query is spent only when [previewFetchAllowed] and [shouldFetchPreview] both agree -
     * airport mode must not already be paying for this flight, and the stored preview must be new,
     * never fetched, or a full refresh interval old - so a card that re-renders every few minutes
     * still costs at most one query a day per flight. A status airport mode has already fetched
     * for this flight is folded in by [newerStatusForPreview] rather than bought again.
     *
     * The row also names the connection this flight is the second leg of and the great-circle
     * distance between its two airports. Both are free of AirLabs quota: the connection comes from
     * the calendar list the caller already read, and the distance from the permanent per-IATA
     * airport cache [cacheFlightAirports] fills with Google geocodes.
     *
     * A failed fetch keeps the last good status and records the message, mirroring
     * [applyFlightStatusResult]: a gate that came back yesterday is still the best thing to show
     * when today's call times out. Both outcomes stamp the fetch time, so a failure is not retried
     * until the next interval. Any exception degrades to error text on the row rather than failing
     * the calendar snapshot the caller is about to build.
     */
    private suspend fun resolveFlightPreview(
        repo: SettingsRepository,
        settings: AppSettings,
        flights: List<FlightEvent>,
        dismissedEventIds: Set<Long>,
        headlineEvent: TodayEvent?,
        zone: ZoneId,
        nowEpochMillis: Long,
    ): FlightPreview? {
        val airportState = repo.airportState()
        val flight = selectPreviewFlight(
            flights = flights,
            nowEpochMillis = nowEpochMillis,
            headlineEvent = headlineEvent,
            pillLeadMinutes = settings.airportPillLeadMinutes,
            arriveAheadMinutes = settings.airportArriveAheadMinutes,
            dismissedEventIds = dismissedEventIds,
            state = airportState,
        )
        return try {
            val stored = repo.flightPreview()
            if (flight == null) {
                if (stored != null) {
                    repo.setFlightPreview(null)
                }
                return null
            }
            val existing = stored?.takeIf { it.eventId == flight.eventId }
            // The calendar entry itself is free to re-read, so the row always carries the latest
            // title, terminal, seat and PNR even on a refresh that spends no query; airport mode's
            // own status is folded in on the same free terms.
            val carried = newerStatusForPreview(
                (existing ?: FlightPreview(eventId = flight.eventId, flight = flight)).copy(flight = flight),
                airportState,
            )
            val mayFetch = previewFetchAllowed(
                flight,
                dismissedEventIds,
                settings.airportPillLeadMinutes,
                settings.airportArriveAheadMinutes,
                nowEpochMillis,
            )
            val base = if (!mayFetch || !shouldFetchPreview(carried, flight, settings.flightStatusApiKey, nowEpochMillis)) {
                carried
            } else {
                when (
                    val result = FlightStatusClient(settings.flightStatusApiKey).fetch(
                        flight.designator,
                        LocalDate.parse(airportLocalDate(flight, zone)),
                        flight.departureIata,
                    )
                ) {
                    is FlightStatusResult.Success -> FlightPreview(
                        eventId = flight.eventId,
                        flight = flight,
                        // The row replaces its status wholesale exactly as the card does, so it
                        // needs the same guard: a thinner answer must not blank the airframe, the
                        // airline or the two offsets the row was already showing.
                        status = carryForwardStaticFields(result.status, carried.status),
                        fetchedAtMillis = nowEpochMillis,
                        error = null,
                    )
                    is FlightStatusResult.Failure -> FlightPreview(
                        eventId = flight.eventId,
                        flight = flight,
                        status = carried.status,
                        fetchedAtMillis = nowEpochMillis,
                        error = result.message,
                    )
                }
            }
            val connection = connectionBefore(flight, flights)
            val airportCache = cacheFlightAirports(repo, settings.apiKey, flight, base.status, nowEpochMillis)
            val enriched = base.copy(
                layoverFromDesignator = connection?.designator,
                layoverMinutes = connection?.let {
                    ((flight.departureMillis - it.arrivalMillis) / 60_000L).toInt()
                },
                routeDistanceKm = flightRouteDistanceKm(airportCache, flight, base.status),
            )
            // Written back only when it actually moved, so an idle refresh costs no disk write.
            if (enriched != stored) {
                repo.setFlightPreview(enriched)
            }
            enriched
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (flight == null) {
                null
            } else {
                FlightPreview(
                    eventId = flight.eventId,
                    flight = flight,
                    fetchedAtMillis = nowEpochMillis,
                    error = e.message ?: "Flight status failed",
                )
            }
        }
    }

    /**
     * Builds a [SnapshotMode.CALENDAR_EMPTY] snapshot for no event remaining today.
     * The commute window (`nextWindowLabel` / `nextWindowStartMinuteOfDay`) is never advertised on
     * this card any more, so both fields are always null here.
     * They stay on [CommuteSnapshot] only so previously stored JSON still decodes.
     * [upcomingEvents] is the wind-down card's "Next up" section (see
     * [CalendarReader.upcomingEvents]), populated only when the caller is out of window with no
     * event remaining today.
     * [CommuteSnapshot.tomorrowEventTitle] and [CommuteSnapshot.tomorrowEventStartEpochMillis] are
     * likewise no longer populated by any caller and stay only for old stored snapshots.
     */
    private fun calendarEmptySnapshot(
        direction: Direction,
        nowEpochMillis: Long,
        upcomingEvents: List<UpcomingEvent> = emptyList(),
        healthComputation: HealthComputation = HealthComputation(),
        todayEventCount: Int? = null,
        todayFirstEventStartEpochMillis: Long? = null,
        flightPreview: FlightPreview? = null,
    ): CommuteSnapshot = CommuteSnapshot(
        direction = direction,
        durationSeconds = 0L,
        durationNoTrafficSeconds = 0L,
        distanceMeters = 0L,
        mapImagePath = null,
        fetchedAtEpochMillis = nowEpochMillis,
        lastFetchFailed = false,
        lastErrorMessage = null,
        destinationLabel = null,
        destinationLat = null,
        destinationLng = null,
        leaveByMinuteOfDay = null,
        mode = SnapshotMode.CALENDAR_EMPTY,
        eventStartEpochMillis = null,
        nextWindowLabel = null,
        nextWindowStartMinuteOfDay = null,
        tomorrowEventTitle = null,
        tomorrowEventStartEpochMillis = null,
        todayEventCount = todayEventCount,
        todayFirstEventStartEpochMillis = todayFirstEventStartEpochMillis,
        healthNudges = healthComputation.healthNudges,
        sleepEstimateMinutes = healthComputation.sleepEstimateMinutes,
        shortSleepDay = healthComputation.shortSleepDay,
        customPillOccurrences = healthComputation.customPillOccurrences,
        upcomingEvents = upcomingEvents,
        flightPreview = flightPreview,
    )

    private fun commuteLeaveByPlanFor(
        settings: AppSettings,
        direction: Direction,
        durationSeconds: Long,
    ): LeaveByPlan? {
        if (!settings.leaveByEnabled) {
            return null
        }
        val arriveBy = if (direction == Direction.TO_WORK) {
            settings.arriveWorkByMinuteOfDay
        } else {
            settings.arriveHomeByMinuteOfDay
        }
        val travelMinutes = ceil(durationSeconds / 60.0).toInt()
        return LeaveByPlan(
            leaveByMinuteOfDay = computeLeaveByMinuteOfDay(arriveBy, durationSeconds),
            arriveByMinuteOfDay = arriveBy,
            travelMinutes = travelMinutes,
        )
    }

    /** In-refresh immediate-fire path; the notification's own dedup key check is shared with FIX-16's alarm (see below). */
    private suspend fun maybeNotifyLeaveBy(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        direction: Direction,
        plan: LeaveByPlan,
        destinationLabel: String,
        now: ZonedDateTime,
    ) {
        val nowMinuteOfDay = now.hour * 60 + now.minute
        val today = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val alreadyNotifiedToday = repo.leaveByNotifiedOn(direction) == today
        val shouldFire = shouldFireLeaveByNotification(
            leaveByEnabled = settings.leaveByEnabled,
            nowMinuteOfDay = nowMinuteOfDay,
            leaveByMinuteOfDay = plan.leaveByMinuteOfDay,
            arriveByMinuteOfDay = plan.arriveByMinuteOfDay,
            alreadyNotifiedToday = alreadyNotifiedToday,
        )
        if (!shouldFire) return

        // Routes through the same mutex-guarded check-then-post helper FIX-16's precise alarm
        // uses (see schedule/CommuteLeaveByWorker.kt) - both paths can race each other now that
        // the alarm exists, so there must be exactly one place that checks-then-posts-then-marks.
        postCommuteLeaveByIfNotAlreadyNotified(
            repo = repo,
            context = context,
            direction = direction,
            today = today,
            leaveByMinuteOfDay = plan.leaveByMinuteOfDay,
            arriveByMinuteOfDay = plan.arriveByMinuteOfDay,
            travelMinutes = plan.travelMinutes,
            destinationLabel = destinationLabel,
        )
    }

    /**
     * FIX-16: schedules the precise one-shot commute leave-by alarm when leave-by is still ahead
     * of now and this direction hasn't already fired today - see [shouldScheduleCommuteLeaveByAlarm].
     * The in-refresh immediate-fire path above ([maybeNotifyLeaveBy]) already handles the case
     * where leave-by has already arrived, so this only ever needs to schedule a future wake-up.
     */
    private suspend fun scheduleCommuteLeaveByAlarm(
        context: Context,
        repo: SettingsRepository,
        direction: Direction,
        plan: LeaveByPlan,
        destinationLabel: String,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ) {
        val today = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val alreadyNotifiedToday = repo.leaveByNotifiedOn(direction) == today
        val leaveByEpochMillis = now.withHour(plan.leaveByMinuteOfDay / 60)
            .withMinute(plan.leaveByMinuteOfDay % 60)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()

        if (!shouldScheduleCommuteLeaveByAlarm(alreadyNotifiedToday, leaveByEpochMillis, nowEpochMillis)) {
            return
        }

        CommuteLeaveByScheduler.schedule(
            context = context,
            direction = direction,
            leaveByMinuteOfDay = plan.leaveByMinuteOfDay,
            arriveByMinuteOfDay = plan.arriveByMinuteOfDay,
            travelMinutes = plan.travelMinutes,
            destinationLabel = destinationLabel,
            leaveByEpochMillis = leaveByEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
    }
}

internal fun travelModeFor(travelMode: TravelMode): RouteTravelMode = when (travelMode) {
    TravelMode.DRIVE -> RouteTravelMode.DRIVE
    TravelMode.TWO_WHEELER -> RouteTravelMode.TWO_WHEELER
}

/**
 * Sprint 2: [computeHealthState] already degrades every internal sub-step to null/empty/false and
 * never throws (see its own doc), but that means a genuinely-failed pass and a legitimately-empty
 * pass both come back as [HealthComputation]'s all-defaults value - which would incorrectly blank
 * out whatever health nudges were already on screen. This wrapper is the outer safety net its doc
 * calls for (DataStore/WorkManager calls it makes are not exhaustively covered by its own inner
 * catches): on any exception actually escaping it, [previous]'s health fields (including the
 * custom pill reminder fields added afterward) are carried forward unchanged instead, exactly
 * like [failureSnapshot] does for a route/map failure - a health-computation failure must never
 * blank the widget's health nudges, and must never fail the route refresh that called it.
 */
private suspend fun computeHealthFieldsSafely(
    context: Context,
    settings: AppSettings,
    nowEpochMillis: Long,
    zone: ZoneId,
    previous: CommuteSnapshot?,
): HealthComputation = try {
    computeHealthState(context, settings, nowEpochMillis, zone)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    healthCarriedForward(previous)
}

/** [previous]'s health fields as a [HealthComputation], for the paths that must not recompute them. */
private fun healthCarriedForward(previous: CommuteSnapshot?): HealthComputation = HealthComputation(
    healthNudges = previous?.healthNudges ?: emptyList(),
    sleepEstimateMinutes = previous?.sleepEstimateMinutes,
    shortSleepDay = previous?.shortSleepDay ?: false,
    customPillOccurrences = previous?.customPillOccurrences ?: emptyList(),
)

/**
 * Resolves the on-disk map path for a trigger-dependent pipeline. [RefreshTrigger.TICK] reuses
 * the previous map while the destination is unchanged (per [shouldReuseSlotMap], accepting stale
 * traffic colors as the tick's designed cost saving) and only fetches on a destination change,
 * where the old map would be for the wrong place. [RefreshTrigger.TAP] and [RefreshTrigger.AUTO]
 * fetch a fresh one UNLESS the render-content hash matches what is already on disk (FIX-10: the
 * audit's single CRITICAL waste finding was re-downloading an unchanged map on every tap). The
 * hash covers everything that draws: polyline geometry, per-segment traffic speeds, both
 * endpoints, and the requested dimensions.
 */
/**
 * Fills the permanent per-IATA airport cache with both ends of [flight] and returns it. This is
 * what the flight card's route distance is read from, and it is Google geocoding quota, not the
 * owner's 1000 lifetime AirLabs queries.
 *
 * An airport is geocoded once for the life of the install and then answered from the cache
 * forever, at most [budget] airports per refresh. A code the geocoder cannot answer is memoised in
 * `airport_geocode_failures_json` and not retried inside [AIRPORT_GEOCODE_RETRY_MILLIS], so a
 * calendar entry with a nonsense IATA costs one query a day rather than one per refresh; a later
 * success drops the memo.
 */
/**
 * What one refresh managed to make of the departure airport: where it is, what it is called, how
 * many geocodes that cost, and why it failed when it did.
 */
private data class AirportDestinationResult(
    val location: LatLng? = null,
    val name: String? = null,
    val geocodesSpent: Int = 0,
    val error: String? = null,
)

/**
 * Resolves the airport this refresh routes to, in the order [airportGeocodeQueries] lays out and
 * stopping at the first answer. The specific query comes first and is what fixes the owner's own
 * bug: sending the calendar's raw "Bengaluru BLR" to the geocoder resolves to Bengaluru city
 * centre, while "BLR airport terminal 2" resolves to the terminal the flight actually leaves from.
 *
 * Every step is cached under its own key, so the second refresh of a window pays nothing: a
 * terminal under `BLR/T2` and the airport as a whole under `BLR`, both permanent (airports do not
 * move), and the two name-based queries under the single-entry [SettingsRepository.geocodeCache]
 * they have always used. A terminal hit that lands more than
 * [AIRPORT_TERMINAL_MAX_OFFSET_METERS] from an already-cached `BLR` is the geocoder matching
 * something else of the same name and is rejected in favour of the next query.
 *
 * At most [AIRPORT_GEOCODE_BUDGET_PER_REFRESH] geocodes are spent here however many queries the
 * flight offers, and a query that comes back empty is memoised in `airport_geocode_failures_json`
 * under its own key, so a flight the geocoder has no answer for costs one query a day rather than
 * one per refresh.
 */
private suspend fun resolveAirportDestination(
    repo: SettingsRepository,
    apiKey: String,
    flight: FlightEvent,
    status: FlightStatus?,
    nowEpochMillis: Long,
): AirportDestinationResult {
    val queries = airportGeocodeQueries(flight, status)
    if (queries.isEmpty()) {
        return AirportDestinationResult(error = "No airport location on this flight")
    }
    val cache = repo.airportLocations()
    // The most specific key only: a cached plain airport must not short-circuit the terminal
    // query, or an airport geocoded before its terminal was known would never gain the terminal.
    airportEntryFor(cache, queries.first().first)?.let {
        return AirportDestinationResult(location = LatLng(it.lat, it.lng), name = it.name)
    }
    if (apiKey.isBlank()) {
        return AirportDestinationResult(error = "Airport not found")
    }
    val plainAirport = normalizedIata(flight.departureIata ?: status?.departureIata)
        ?.let { airportEntryFor(cache, it) }
    val failures = repo.airportGeocodeFailures()
    val placeCache = repo.geocodeCache()
    var spent = 0
    var lastError: String? = null
    for ((key, query) in queries) {
        if (spent >= AIRPORT_GEOCODE_BUDGET_PER_REFRESH) {
            break
        }
        airportEntryFor(cache, key)?.let {
            return AirportDestinationResult(
                location = LatLng(it.lat, it.lng),
                name = it.name,
                geocodesSpent = spent,
            )
        }
        if (key.isEmpty() && placeCache != null && placeCache.address == query) {
            return AirportDestinationResult(location = LatLng(placeCache.lat, placeCache.lng), geocodesSpent = spent)
        }
        val memoKey = key.ifEmpty { query }
        val failedAtMillis = failures[memoKey]
        if (failedAtMillis != null && nowEpochMillis - failedAtMillis < AIRPORT_GEOCODE_RETRY_MILLIS) {
            continue
        }
        spent++
        val hit = when (val result = GeocodingClient(apiKey).geocode(query)) {
            is ApiResult.Success -> result.value.firstOrNull()
            is ApiResult.Failure -> {
                lastError = result.message
                null
            }
        }
        val tooFarFromAirport = hit != null && key.contains('/') && plainAirport != null &&
            distanceMeters(hit.location, LatLng(plainAirport.lat, plainAirport.lng)) > AIRPORT_TERMINAL_MAX_OFFSET_METERS
        if (hit == null || tooFarFromAirport) {
            repo.updateAirportGeocodeFailures { it + (memoKey to nowEpochMillis) }
            lastError = lastError ?: "Airport not found"
            continue
        }
        val name = geocodedAirportName(hit.formattedAddress, airportDisplayName(flight))
        if (key.isEmpty()) {
            repo.setGeocodeCache(Place(address = query, lat = hit.location.lat, lng = hit.location.lng))
        } else {
            repo.updateAirportLocations {
                it + (
                    key to AirportLocation(
                        iata = key.substringBefore('/'),
                        name = name,
                        lat = hit.location.lat,
                        lng = hit.location.lng,
                    )
                    )
            }
        }
        if (failedAtMillis != null) {
            repo.updateAirportGeocodeFailures { it - memoKey }
        }
        return AirportDestinationResult(
            location = hit.location,
            name = name.takeIf { key.isNotEmpty() },
            geocodesSpent = spent,
        )
    }
    return AirportDestinationResult(geocodesSpent = spent, error = lastError ?: "Airport not found")
}

private suspend fun cacheFlightAirports(
    repo: SettingsRepository,
    apiKey: String,
    flight: FlightEvent,
    status: FlightStatus?,
    nowEpochMillis: Long,
    budget: Int = AIRPORT_GEOCODE_BUDGET_PER_REFRESH,
): Map<String, AirportLocation> {
    var cache = repo.airportLocations()
    if (apiKey.isBlank() || budget <= 0) {
        return cache
    }
    val targets = listOfNotNull(
        normalizedIata(flight.departureIata ?: status?.departureIata)?.let { code ->
            code to (flight.departureAirportName?.takeIf { it.isNotBlank() } ?: status?.departureAirportName ?: code)
        },
        normalizedIata(flight.arrivalIata ?: status?.arrivalIata)?.let { code ->
            code to (flight.arrivalAirportName?.takeIf { it.isNotBlank() } ?: status?.arrivalAirportName ?: code)
        },
    ).distinctBy { it.first }.filter { airportLocationFor(cache, it.first) == null }
    if (targets.isEmpty()) {
        return cache
    }
    val failures = repo.airportGeocodeFailures()
    var spent = 0
    for ((code, name) in targets) {
        if (spent >= budget) {
            break
        }
        val failedAtMillis = failures[code]
        if (failedAtMillis != null && nowEpochMillis - failedAtMillis < AIRPORT_GEOCODE_RETRY_MILLIS) {
            continue
        }
        spent++
        val hit = (GeocodingClient(apiKey).geocode("$code airport") as? ApiResult.Success)?.value?.firstOrNull()
        if (hit == null) {
            repo.updateAirportGeocodeFailures { it + (code to nowEpochMillis) }
            continue
        }
        val entry = AirportLocation(
            iata = code,
            name = geocodedAirportName(hit.formattedAddress, name),
            lat = hit.location.lat,
            lng = hit.location.lng,
        )
        repo.updateAirportLocations { it + (code to entry) }
        cache = cache + (code to entry)
        if (failedAtMillis != null) {
            repo.updateAirportGeocodeFailures { it - code }
        }
    }
    return cache
}

private suspend fun resolveMapImagePath(
    context: Context,
    repo: SettingsRepository,
    trigger: RefreshTrigger,
    previousSnapshot: CommuteSnapshot?,
    direction: Direction,
    destination: LatLng,
    origin: LatLng,
    route: RouteResult,
    apiKey: String,
): ApiResult<String?> {
    return when (trigger) {
        RefreshTrigger.TICK -> {
            val reuse = previousSnapshot != null &&
                shouldReuseSlotMap(
                    previousDirection = previousSnapshot.direction,
                    previousDestinationLat = previousSnapshot.destinationLat,
                    previousDestinationLng = previousSnapshot.destinationLng,
                    direction = direction,
                    destination = destination,
                )
            if (reuse) {
                ApiResult.Success(previousSnapshot.mapImagePath)
            } else {
                // Destination changed under a tick (e.g. the displayed event was deleted and the
                // tick flipped the mode back): the old map is for the wrong place, so this is the
                // one TICK case that fetches rather than leaving the widget mapless until the
                // next tap. Same hash-cache path as TAP/AUTO.
                fetchMapViaRenderCache(context, repo, previousSnapshot?.mapImagePath, apiKey, route, origin, destination)
            }
        }
        RefreshTrigger.TAP, RefreshTrigger.AUTO -> {
            fetchMapViaRenderCache(context, repo, previousSnapshot?.mapImagePath, apiKey, route, origin, destination)
        }
    }
}

/** FIX-10 shared path: reuse the on-disk map when the render hash matches, else fetch and record. */
private suspend fun fetchMapViaRenderCache(
    context: Context,
    repo: SettingsRepository,
    previousPath: String?,
    apiKey: String,
    route: RouteResult,
    origin: LatLng,
    destination: LatLng,
): ApiResult<String?> {
    val renderKey = mapRenderKey(route, origin, destination, MAP_FETCH_WIDTH_PX, MAP_FETCH_HEIGHT_PX)
    if (previousPath != null &&
        renderKey == repo.mapRenderKey() &&
        withContext(Dispatchers.IO) { File(previousPath).isFile }
    ) {
        return ApiResult.Success(previousPath)
    }
    return when (val fetched = fetchFreshMap(context, previousPath, apiKey, route, origin, destination)) {
        is ApiResult.Success -> {
            repo.setMapRenderKey(renderKey)
            ApiResult.Success(fetched.value)
        }
        is ApiResult.Failure -> ApiResult.Failure(fetched.message, fetched.cause)
    }
}

/**
 * Deterministic content hash of everything that affects the rendered Static Maps image. Traffic
 * speed intervals are included deliberately: an unchanged polyline with changed congestion colors
 * is a different image (the audit's stated risk for naive polyline-only caching).
 */
internal fun mapRenderKey(
    route: RouteResult,
    origin: LatLng,
    destination: LatLng,
    widthPx: Int,
    heightPx: Int,
): String {
    val material = buildString {
        append(route.encodedPolyline)
        append('|')
        route.speedIntervals.forEach { interval ->
            append(interval.startPolylinePointIndex)
            append(':')
            append(interval.endPolylinePointIndex)
            append(':')
            append(interval.speed.name)
            append(';')
        }
        append('|').append(origin.lat).append(',').append(origin.lng)
        append('|').append(destination.lat).append(',').append(destination.lng)
        append('|').append(widthPx).append('x').append(heightPx)
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private suspend fun fetchFreshMap(
    context: Context,
    previousMapPath: String?,
    apiKey: String,
    route: RouteResult,
    origin: LatLng,
    destination: LatLng,
): ApiResult<String> {
    val mapUrl = StaticMapUrl.build(
        apiKey = apiKey,
        widthPx = MAP_FETCH_WIDTH_PX,
        heightPx = MAP_FETCH_HEIGHT_PX,
        route = route,
        origin = origin,
        destination = destination,
    )
    val destFile = nextMapFile(context.filesDir, previousMapPath)
    return when (val fetched = MapImageFetcher().fetch(mapUrl, destFile)) {
        is ApiResult.Success -> {
            withContext(Dispatchers.IO) {
                downsampleMapFile(fetched.value)
            }
            ApiResult.Success(fetched.value.absolutePath)
        }
        is ApiResult.Failure -> ApiResult.Failure(fetched.message, fetched.cause)
    }
}

/**
 * Best-effort direction to attribute an out-of-band failure snapshot to (the exception handler in
 * [CommuteRefresher.refreshNow] has no [WidgetMode] in hand, since the exception may have been
 * thrown before it was computed). Mirrors the same resolution [CommuteRefresher.performRefresh]
 * uses for its own `direction` value.
 */
private fun currentDirectionHint(settings: AppSettings, now: ZonedDateTime): Direction {
    val dayOfWeekIso = now.dayOfWeek.value
    val minuteOfDay = now.hour * 60 + now.minute
    val mode = resolveWidgetMode(
        dayOfWeekIso = dayOfWeekIso,
        minuteOfDay = minuteOfDay,
        commuteDays = settings.commuteDays,
        morningStart = settings.morningSlotStartMinuteOfDay,
        morningEnd = settings.morningSlotEndMinuteOfDay,
        eveningStart = settings.eveningSlotStartMinuteOfDay,
        eveningEnd = settings.eveningSlotEndMinuteOfDay,
    )
    val nextWindowDirection = nextWindow(
        dayOfWeekIso = dayOfWeekIso,
        minuteOfDay = minuteOfDay,
        commuteDays = settings.commuteDays,
        morningStart = settings.morningSlotStartMinuteOfDay,
        morningEnd = settings.morningSlotEndMinuteOfDay,
        eveningStart = settings.eveningSlotStartMinuteOfDay,
        eveningEnd = settings.eveningSlotEndMinuteOfDay,
    )?.direction
    return resolveDirectionForSnapshot(mode, nextWindowDirection)
}

/**
 * Shared plain-card builder for [SnapshotMode.CALENDAR_EMPTY] when there IS a next event to show
 * but no route/map is being fetched for it - either it has no location at all, or it has one but
 * starts farther out than [AppSettings.eventTakeoverMinutes] (see
 * [CommuteRefresher.performCalendarRefresh]'s two call sites). [eventTitle] and
 * [eventStartEpochMillis] are the only inputs that differ between those callers; every other
 * field is the same "no stale route/map/leave-by/next-window data" shape, so the two outcomes
 * stay identical apart from their inputs - the same discipline [failureSnapshot] applies for a
 * mode/target change.
 */
internal fun calendarPlainEventSnapshot(
    direction: Direction,
    nowEpochMillis: Long,
    eventTitle: String,
    eventStartEpochMillis: Long,
    healthComputation: HealthComputation,
    flightPreview: FlightPreview? = null,
): CommuteSnapshot = CommuteSnapshot(
    direction = direction,
    durationSeconds = 0L,
    durationNoTrafficSeconds = 0L,
    distanceMeters = 0L,
    mapImagePath = null,
    fetchedAtEpochMillis = nowEpochMillis,
    lastFetchFailed = false,
    lastErrorMessage = null,
    destinationLabel = eventTitle,
    destinationLat = null,
    destinationLng = null,
    leaveByMinuteOfDay = null,
    mode = SnapshotMode.CALENDAR_EMPTY,
    eventStartEpochMillis = eventStartEpochMillis,
    nextWindowLabel = null,
    nextWindowStartMinuteOfDay = null,
    healthNudges = healthComputation.healthNudges,
    sleepEstimateMinutes = healthComputation.sleepEstimateMinutes,
    shortSleepDay = healthComputation.shortSleepDay,
    customPillOccurrences = healthComputation.customPillOccurrences,
    flightPreview = flightPreview,
)

/**
 * Whether [previous] ever actually routed - nonzero travel duration, or a saved map image, either
 * one is sufficient (a route that briefly reads 0 seconds but still has its map is still real
 * data). `false` for a snapshot that was always the broken shell (duration 0, no map): the state
 * a never-geocodable event's [SnapshotMode.CALENDAR_EVENT] target reaches when every attempt
 * fails, before this fix, since old same-target failures preserved it unconditionally.
 */
private fun previousHadUsableRoute(previous: CommuteSnapshot?): Boolean =
    previous != null && (previous.durationSeconds > 0L || previous.mapImagePath != null)

/**
 * Pure computation of the failure snapshot to save, preserving the previous fetch's data as
 * last-known-GOOD only when it is safe to do so - i.e. only when [previous] describes the *same
 * target* as this failed attempt (same [SnapshotMode], same [CommuteSnapshot.destinationLabel])
 * AND [previous] is actually good: for [SnapshotMode.CALENDAR_EVENT] specifically, a previous
 * snapshot that never had usable route data (see [previousHadUsableRoute]) is not "last known
 * good" - it is the broken shell itself, and preserving it is how a never-geocodable event
 * (an Outlook room resource, say) got stuck showing a 0-min ETA forever across every retry.
 *
 * This is the mode/target-transition guard: without it, a plain `copy()` leaves a snapshot's
 * route/map/leave-by/next-window fields untouched even when [modeOverride] changes the mode, so
 * (for example) a CALENDAR_EVENT geocode failure right after a COMMUTE window ends would inherit
 * a stale `leaveByMinuteOfDay` and the old commute's map/coordinates under the new event's label,
 * and a COMMUTE failure right after a window starts would inherit a stale `nextWindowLabel` (or
 * `eventStartEpochMillis`) left over from CALENDAR_EMPTY. The same reasoning applies within a
 * single mode when the destination itself changes (e.g. calendar mode moving on to a different
 * event) - [destinationLabelOverride] is passed at every call site that has a concrete new
 * target, so a label mismatch is enough to detect it without needing to thread destination
 * coordinates through every failure branch too.
 *
 * A NEW-target [SnapshotMode.CALENDAR_EVENT] failure (this event's first routed attempt, or any
 * target change landing here), and a SAME-target [SnapshotMode.CALENDAR_EVENT] failure whose
 * [previous] never actually routed, are both handled the same special way: instead of the generic
 * cleared-but-still-broken shell below (0-min ETA, warning glyph, calendar-emoji map placeholder -
 * alarming for what is usually just a junk location the calendar reader's marker list missed, or
 * a transient geocode/Static-Maps hiccup on an address that has never successfully routed), it
 * falls back to the exact zero-API plain card an unlocated or far-located event gets -
 * see [calendarPlainEventSnapshot]: `mode = CALENDAR_EMPTY`, `lastFetchFailed = false`, title and
 * start carried, no route/map data. Health fields still carry forward from [previous] exactly as
 * every other branch here does. A SAME-target event failure whose [previous] DID route
 * successfully at some point is unaffected (the branch above returns first) and keeps preserving
 * that stale route/map data with the warning glyph - that protects a real address that hiccups
 * after a previously successful render. No retry is armed for this fallback; recovery stays via
 * the next tap, window boundary, or calendar change, same as every other failure path in this
 * file. This only ever triggers when [modeOverride] resolves to [SnapshotMode.CALENDAR_EVENT] -
 * commute failures are untouched, in every case, byte-for-byte. That still holds for the
 * tap-to-ride pipeline: a ride's first-attempt revert happens in
 * [CommuteRefresher.performCommuteRefresh] before [saveFailure] is ever reached, so this function
 * never sees it.
 */
internal fun failureSnapshot(
    previous: CommuteSnapshot?,
    direction: Direction,
    message: String,
    modeOverride: SnapshotMode? = null,
    destinationLabelOverride: String? = null,
    eventStartEpochMillisOverride: Long? = null,
): CommuteSnapshot {
    val mode = modeOverride ?: previous?.mode ?: SnapshotMode.COMMUTE
    val labelMatches = destinationLabelOverride == null || previous?.destinationLabel == destinationLabelOverride
    val sameTarget = previous != null && previous.mode == mode && labelMatches
    // Only CALENDAR_EVENT needs the usability check - COMMUTE and CALENDAR_EMPTY same-target
    // preservation stays unconditional, exactly as before this check existed.
    val sameTargetIsGood = mode != SnapshotMode.CALENDAR_EVENT || previousHadUsableRoute(previous)

    if (previous != null && sameTarget && sameTargetIsGood) {
        return previous.copy(
            lastFetchFailed = true,
            lastErrorMessage = message,
            destinationLabel = destinationLabelOverride ?: previous.destinationLabel,
            eventStartEpochMillis = eventStartEpochMillisOverride ?: previous.eventStartEpochMillis,
        )
    }

    // First-attempt (new-target) event routing failure, OR a same-target event failure whose
    // previous snapshot was never actually good: fall back to the plain card instead of the
    // broken routed shell - see this function's doc for the full reasoning. Reached only for
    // CALENDAR_EVENT because sameTarget-and-good already returned above for the one case that
    // must keep the old broken-shell behavior (a same-target event failure with a usable route).
    if (mode == SnapshotMode.CALENDAR_EVENT) {
        return CommuteSnapshot(
            direction = direction,
            durationSeconds = 0L,
            durationNoTrafficSeconds = 0L,
            distanceMeters = 0L,
            mapImagePath = null,
            fetchedAtEpochMillis = 0L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            destinationLabel = destinationLabelOverride,
            destinationLat = null,
            destinationLng = null,
            leaveByMinuteOfDay = null,
            mode = SnapshotMode.CALENDAR_EMPTY,
            eventStartEpochMillis = eventStartEpochMillisOverride,
            nextWindowLabel = null,
            nextWindowStartMinuteOfDay = null,
            healthNudges = previous?.healthNudges ?: emptyList(),
            sleepEstimateMinutes = previous?.sleepEstimateMinutes,
            shortSleepDay = previous?.shortSleepDay ?: false,
            customPillOccurrences = previous?.customPillOccurrences ?: emptyList(),
        )
    }

    return CommuteSnapshot(
        direction = direction,
        durationSeconds = 0L,
        durationNoTrafficSeconds = 0L,
        distanceMeters = 0L,
        mapImagePath = null,
        fetchedAtEpochMillis = 0L,
        lastFetchFailed = true,
        lastErrorMessage = message,
        destinationLabel = destinationLabelOverride,
        destinationLat = null,
        destinationLng = null,
        leaveByMinuteOfDay = null,
        mode = mode,
        eventStartEpochMillis = eventStartEpochMillisOverride,
        nextWindowLabel = null,
        nextWindowStartMinuteOfDay = null,
        // Sprint 2: health nudges are orthogonal to the route/mode/target this snapshot describes
        // - a target change (unlike route/map/leave-by/next-window) must not blank them out. The
        // custom pill reminder fields added afterward follow the identical carry-through rule.
        healthNudges = previous?.healthNudges ?: emptyList(),
        sleepEstimateMinutes = previous?.sleepEstimateMinutes,
        shortSleepDay = previous?.shortSleepDay ?: false,
        customPillOccurrences = previous?.customPillOccurrences ?: emptyList(),
    )
}

private suspend fun saveFailure(
    repo: SettingsRepository,
    direction: Direction,
    message: String,
    modeOverride: SnapshotMode? = null,
    destinationLabelOverride: String? = null,
    eventStartEpochMillisOverride: Long? = null,
) {
    repo.saveSnapshot(
        failureSnapshot(
            previous = repo.snapshot(),
            direction = direction,
            message = message,
            modeOverride = modeOverride,
            destinationLabelOverride = destinationLabelOverride,
            eventStartEpochMillisOverride = eventStartEpochMillisOverride,
        ),
    )
}

private fun nextMapFile(filesDir: File, previousPath: String?): File {
    val nextName = if (previousPath != null && previousPath.endsWith(MAP_FILE_A)) {
        MAP_FILE_B
    } else {
        MAP_FILE_A
    }
    return File(filesDir, nextName)
}

@SuppressLint("MissingPermission")
private suspend fun currentDeviceLocation(context: Context): ApiResult<LatLng> {
    val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) {
        return ApiResult.Failure("Location unavailable")
    }

    val client = LocationServices.getFusedLocationProviderClient(context)

    // Refresh-lag fix: a fresh cached fix is used as-is, skipping the slower getCurrentLocation
    // round trip entirely. A stale (or absent) cached fix still isn't discarded - it is kept as
    // the final fallback below if getCurrentLocation itself times out or fails.
    val cachedLocation = try {
        client.lastLocation.awaitNullable()
    } catch (e: CancellationException) {
        throw e
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
    if (cachedLocation != null && isLocationFresh(cachedLocation.time, System.currentTimeMillis())) {
        return ApiResult.Success(LatLng(cachedLocation.latitude, cachedLocation.longitude))
    }

    val cts = CancellationTokenSource()
    try {
        val current = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            try {
                client.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cts.token,
                ).awaitNullable()
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }
        if (current != null) {
            return ApiResult.Success(LatLng(current.latitude, current.longitude))
        }
        if (cachedLocation != null) {
            return ApiResult.Success(LatLng(cachedLocation.latitude, cachedLocation.longitude))
        }
        return ApiResult.Failure("Location unavailable")
    } finally {
        cts.cancel()
    }
}

/**
 * FIX-4: fire-and-forget request that only exists to refresh the fused provider's own internal
 * `lastLocation` cache ahead of a likely upcoming tap (window-boundary AUTO refreshes happen
 * roughly when the owner is about to want a fresh commute reading). The result is deliberately
 * discarded - callers must never await anything meaningful from this beyond "it ran".
 */
@SuppressLint("MissingPermission")
private suspend fun warmUpDeviceLocationCache(context: Context) {
    val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return

    val client = LocationServices.getFusedLocationProviderClient(context)
    val cts = CancellationTokenSource()
    try {
        withTimeoutOrNull(LOCATION_WARM_UP_TIMEOUT_MS) {
            try {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).awaitNullable()
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    } finally {
        cts.cancel()
    }
}

private suspend fun <T> Task<T>.awaitNullable(): T? {
        if (isComplete) {
            exception?.let { throw it }
            if (isCanceled) throw CancellationException("Task cancelled")
            return result
        }
    return suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value ->
            if (cont.isActive) cont.resume(value)
        }
        addOnFailureListener { error ->
            if (cont.isActive) cont.resumeWithException(error)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}

/**
 * Caps the on-disk map so the widget's BitmapFactory decode stays at or under ~1200px on the
 * long edge (RemoteViews binder budget). Static Maps returns 1280x1280 at scale=2; inSampleSize
 * alone would keep 1280 (next power-of-two cut is 640), so a bilinear scale to 1200 is applied
 * when needed and the result is written over the same alternating filename.
 */
private fun downsampleMapFile(file: File) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return

    val longEdge = maxOf(width, height)
    val sample = mapInSampleSize(width, height, MAP_MAX_LONG_EDGE_PX)
    if (sample == 1 && longEdge <= MAP_MAX_LONG_EDGE_PX) return

    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return
    val output = constrainLongEdge(decoded, MAP_MAX_LONG_EDGE_PX)
    try {
        val tmp = File(file.parentFile, "${file.name}.ds.tmp")
        tmp.outputStream().buffered().use { out ->
            output.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
        }
    } finally {
        if (output !== decoded) decoded.recycle()
        output.recycle()
    }
}

internal fun mapInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var inSampleSize = 1
    val longEdge = maxOf(width, height)
    if (longEdge > maxEdge) {
        val half = longEdge / 2
        while (half / inSampleSize >= maxEdge) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun constrainLongEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    if (longEdge <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / longEdge.toFloat()
    val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return bitmap.scale(w, h)
}
