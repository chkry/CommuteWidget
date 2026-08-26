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
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.GeocodingClient
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.MapImageFetcher
import com.crpakala.commutewidget.api.RouteResult
import com.crpakala.commutewidget.api.RouteTravelMode
import com.crpakala.commutewidget.api.RoutesClient
import com.crpakala.commutewidget.api.StaticMapUrl
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.calendar.TodayEvent
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.schedule.CalendarTickScheduler
import com.crpakala.commutewidget.schedule.CommuteLeaveByScheduler
import com.crpakala.commutewidget.schedule.EventLeaveByScheduler
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
 * outranks the window commute. Already-started events also qualify (start - now is negative),
 * matching the calendar reader's own grace-window semantics.
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

    suspend fun refreshNow(context: Context, trigger: RefreshTrigger) {
        val appContext = context.applicationContext
        mutex.withLock {
            val repo = SettingsRepository.get(appContext)
            val nowElapsed = SystemClock.elapsedRealtime()
            if (lastCompletedElapsedRealtime != 0L &&
                nowElapsed - lastCompletedElapsedRealtime < MIN_REFRESH_GAP_MS
            ) {
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
                performRefresh(appContext, trigger)
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

    private suspend fun performRefresh(context: Context, trigger: RefreshTrigger) {
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

        when (widgetMode) {
            is WidgetMode.Commute -> {
                // Event takeover: a located event starting within eventTakeoverMinutes outranks
                // the window commute (owner decision after the v5 audit). The calendar pipeline
                // re-selects the same event deterministically and handles routing, leave-by,
                // tick scheduling, and commute-alarm cancellation.
                if (eventTakeoverCandidate(context, settings, nowEpochMillis) != null) {
                    performCalendarRefresh(context, repo, settings, trigger, direction, nextWindowResult, now, nowEpochMillis)
                } else {
                    performCommuteRefresh(context, repo, settings, trigger, widgetMode.direction, now, nowEpochMillis)
                }
            }
            WidgetMode.Calendar -> {
                performCalendarRefresh(context, repo, settings, trigger, direction, nextWindowResult, now, nowEpochMillis)
            }
        }
    }

    private fun eventTakeoverCandidate(
        context: Context,
        settings: AppSettings,
        nowEpochMillis: Long,
    ): TodayEvent? {
        if (!settings.calendarEnabled || settings.selectedCalendarIds.isEmpty()) return null
        val reader = CalendarReader(context)
        if (!reader.hasPermission()) return null
        val event = reader.nextEventToday(settings.selectedCalendarIds, nowEpochMillis, ZoneId.systemDefault())
            ?: return null
        return event.takeIf {
            eventTakeoverApplies(it.startEpochMillis, it.location != null, nowEpochMillis, settings.eventTakeoverMinutes)
        }
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

    private suspend fun performCommuteRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ) {
        // v5: a commute-mode refresh of any outcome (success or failure) is "anything else" per
        // the calendar tick's schedule/cancel contract - see CalendarTickScheduler's doc.
        CalendarTickScheduler.cancel(context)

        val home = settings.home!!
        val work = settings.work!!
        val destinationLabel = if (direction == Direction.TO_WORK) "Work" else "Home"
        val destination = if (direction == Direction.TO_WORK) LatLng(work.lat, work.lng) else LatLng(home.lat, home.lng)
        val origin = if (direction == Direction.TO_WORK) LatLng(home.lat, home.lng) else LatLng(work.lat, work.lng)

        val route = when (
            val result = RoutesClient(settings.apiKey).computeRoute(origin, destination, travelModeFor(settings.travelMode))
        ) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                saveFailure(repo, direction, result.message, SnapshotMode.COMMUTE, destinationLabel)
                return
            }
        }

        val previousSnapshot = repo.snapshot()
        val mapImagePath = when (
            val mapResult = resolveMapImagePath(context, repo, trigger, previousSnapshot, direction, destination, origin, route, settings.apiKey)
        ) {
            is ApiResult.Success -> mapResult.value
            is ApiResult.Failure -> {
                saveFailure(repo, direction, mapResult.message, SnapshotMode.COMMUTE, destinationLabel)
                return
            }
        }

        val leaveByPlan = commuteLeaveByPlanFor(settings, direction, route.durationSeconds)

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
                destinationLabel = destinationLabel,
                destinationLat = destination.lat,
                destinationLng = destination.lng,
                leaveByMinuteOfDay = leaveByPlan?.leaveByMinuteOfDay,
                mode = SnapshotMode.COMMUTE,
                eventStartEpochMillis = null,
                nextWindowLabel = null,
                nextWindowStartMinuteOfDay = null,
            ),
        )

        if (leaveByPlan != null) {
            maybeNotifyLeaveBy(context, repo, settings, direction, leaveByPlan, destinationLabel, now)
            scheduleCommuteLeaveByAlarm(context, repo, direction, leaveByPlan, destinationLabel, now, nowEpochMillis)
        }
    }

    /**
     * v3 calendar mode; v4 adds the event leave-by advisor for a located event with a start time;
     * v5 adds the opt-in coarse staleness tick (see [CalendarTickScheduler]). Never records
     * history (the history subsystem is removed entirely in v5).
     *
     * Every branch cancels [CalendarTickScheduler], [EventLeaveByScheduler], and
     * [CommuteLeaveByScheduler]'s pending work up front - the previously scheduled event/tick/
     * alarm may have moved, been cancelled, or resolved to a different mode, so a stale wake-up
     * must not survive to fire. In particular, a FIX-16 commute leave-by alarm scheduled during an
     * earlier commute window must not survive into calendar mode (mirrors [performCommuteRefresh]
     * cancelling [CalendarTickScheduler] on every commute-mode outcome). Only the final
     * located-event success path re-schedules the tick, and only when the corresponding condition
     * holds; the commute leave-by alarm and event leave-by are never re-scheduled from this
     * function.
     */
    private suspend fun performCalendarRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        nextWindowResult: NextWindow?,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ) {
        CalendarTickScheduler.cancel(context)
        EventLeaveByScheduler.cancel(context)
        CommuteLeaveByScheduler.cancel(context)

        val calendarReader = CalendarReader(context)
        val canReadCalendar = settings.calendarEnabled &&
            calendarReader.hasPermission() &&
            settings.selectedCalendarIds.isNotEmpty()

        val event: TodayEvent? = if (canReadCalendar) {
            calendarReader.nextEventToday(settings.selectedCalendarIds, nowEpochMillis, now.zone)
        } else {
            null
        }

        if (event == null) {
            repo.saveSnapshot(calendarEmptySnapshot(direction, nowEpochMillis, nextWindowResult))
            return
        }

        val location = event.location
        if (location.isNullOrBlank()) {
            repo.saveSnapshot(
                CommuteSnapshot(
                    direction = direction,
                    durationSeconds = 0L,
                    durationNoTrafficSeconds = 0L,
                    distanceMeters = 0L,
                    mapImagePath = null,
                    fetchedAtEpochMillis = nowEpochMillis,
                    lastFetchFailed = false,
                    lastErrorMessage = null,
                    destinationLabel = event.title,
                    destinationLat = null,
                    destinationLng = null,
                    leaveByMinuteOfDay = null,
                    mode = SnapshotMode.CALENDAR_EMPTY,
                    eventStartEpochMillis = event.startEpochMillis,
                    nextWindowLabel = null,
                    nextWindowStartMinuteOfDay = null,
                ),
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
        val origin = when (originResult) {
            is ApiResult.Success -> originResult.value
            is ApiResult.Failure -> {
                saveFailure(
                    repo,
                    direction,
                    originResult.message,
                    SnapshotMode.CALENDAR_EVENT,
                    event.title,
                    event.startEpochMillis,
                )
                return
            }
        }

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
        val previousSnapshot = repo.snapshot()
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

    private fun calendarEmptySnapshot(
        direction: Direction,
        nowEpochMillis: Long,
        nextWindowResult: NextWindow?,
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
        nextWindowLabel = nextWindowResult?.label,
        nextWindowStartMinuteOfDay = nextWindowResult?.startMinuteOfDay,
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
 * Resolves the on-disk map path for a trigger-dependent pipeline. [RefreshTrigger.TICK] reuses
 * the previous map while the destination is unchanged (per [shouldReuseSlotMap], accepting stale
 * traffic colors as the tick's designed cost saving) and only fetches on a destination change,
 * where the old map would be for the wrong place. [RefreshTrigger.TAP] and [RefreshTrigger.AUTO]
 * fetch a fresh one UNLESS the render-content hash matches what is already on disk (FIX-10: the
 * audit's single CRITICAL waste finding was re-downloading an unchanged map on every tap). The
 * hash covers everything that draws: polyline geometry, per-segment traffic speeds, both
 * endpoints, and the requested dimensions.
 */
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
                ApiResult.Success(previousSnapshot?.mapImagePath)
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
 * Pure computation of the failure snapshot to save, preserving the previous fetch's data as
 * last-known-good only when it is safe to do so - i.e. only when [previous] describes the *same
 * target* as this failed attempt (same [SnapshotMode], same [CommuteSnapshot.destinationLabel]).
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

    if (previous != null && sameTarget) {
        return previous.copy(
            lastFetchFailed = true,
            lastErrorMessage = message,
            destinationLabel = destinationLabelOverride ?: previous.destinationLabel,
            eventStartEpochMillis = eventStartEpochMillisOverride ?: previous.eventStartEpochMillis,
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
