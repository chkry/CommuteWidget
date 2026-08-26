package com.crpakala.commutewidget.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.core.graphics.scale
import androidx.glance.appwidget.updateAll
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.MainActivity
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
import com.crpakala.commutewidget.data.ActiveFavourite
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.history.CommuteSample
import com.crpakala.commutewidget.history.HistoryStore
import com.crpakala.commutewidget.schedule.EventLeaveByScheduler
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val MAP_FETCH_WIDTH_PX = 640
private const val MAP_FETCH_HEIGHT_PX = 640
private const val MAP_MAX_LONG_EDGE_PX = 1200
private const val LOCATION_TIMEOUT_MS = 15_000L
private const val MIN_REFRESH_GAP_MS = 5_000L
private const val MAP_FILE_A = "map_a.png"
private const val MAP_FILE_B = "map_b.png"
// Shared with EventLeaveByScheduler/EventLeaveByWorker (schedule/) - the v4 event advisor posts
// on this same channel, per spec, so it is exposed at internal (module) visibility rather than
// duplicated as a second identical string literal in that package.
internal const val LEAVE_BY_CHANNEL_ID = "leave_by"
internal const val LEAVE_BY_CHANNEL_NAME = "Leave-by advisor"
private const val LEAVE_BY_NOTIFICATION_ID = 1001
private const val DEFAULT_LOCATION_MAX_AGE_MILLIS = 120_000L

/** Who initiated a refresh; drives both the fetch pipeline (map or not) and history bookkeeping. */
enum class RefreshTrigger { TAP, AUTO, SLOT }

/**
 * Resolved commute destination after applying the v3 precedence rule: an unexpired active
 * favourite always wins over the window-model default (home/work-by-window [direction]).
 * Calendar mode is not part of this precedence chain anymore - it is a distinct [WidgetMode]
 * branch handled entirely separately (see [CommuteRefresher.performRefresh]), not a per-refresh
 * override attempted alongside the default target the way the v2 tap-triggered calendar lookup was.
 */
internal sealed class DestinationTarget {
    data class FavouriteTarget(val favourite: Favourite) : DestinationTarget()
    data class DefaultTarget(val direction: Direction) : DestinationTarget()
}

/**
 * [activeFavourite] is assumed already expiry-checked (i.e. sourced from
 * [SettingsRepository.activeFavourite], which auto-clears an expired one and returns null) - this
 * function only encodes the precedence, not the expiry check itself.
 */
internal fun resolveDestinationTarget(
    activeFavourite: ActiveFavourite?,
    direction: Direction,
): DestinationTarget {
    return if (activeFavourite != null) {
        DestinationTarget.FavouriteTarget(activeFavourite.favourite)
    } else {
        DestinationTarget.DefaultTarget(direction)
    }
}

/**
 * History sampling only ever applies to the plain window-model commute (never a favourite
 * override, never calendar mode - calendar mode never even calls this). [SlotFetchWorker] already
 * refuses to even attempt a fetch when history is disabled, but TAP and AUTO refreshes flow
 * through this same recording path as an ordinary side effect of every successful commute-mode
 * fetch, so that path needs its own [AppSettings.historyEnabled] check to honor the same toggle.
 */
internal fun shouldRecordHistorySample(historyEnabled: Boolean, target: DestinationTarget): Boolean {
    return historyEnabled && target is DestinationTarget.DefaultTarget
}

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
 * A [RefreshTrigger.SLOT] fetch never downloads a fresh map; it may only keep showing the
 * previous one, and only when the previous snapshot was unambiguously the same route. Matching on
 * [Direction] alone is not sufficient: an active favourite can occupy a SLOT-triggered fetch too
 * (favourites apply to ANY trigger per spec), and that changes the physical origin/destination
 * while leaving the home/work-by-window [Direction] untouched. Comparing the resolved destination
 * coordinates as well ensures the reused map still depicts the route actually being displayed,
 * instead of a stale favourite map bleeding into a default-target render (or vice versa) after a
 * target-type switch.
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

private data class LeaveByPlan(
    val leaveByMinuteOfDay: Int,
    val arriveByMinuteOfDay: Int,
    val travelMinutes: Int,
)

object CommuteRefresher {
    private val mutex = Mutex()
    private var lastCompletedElapsedRealtime = 0L

    suspend fun refreshNow(context: Context, trigger: RefreshTrigger) {
        val appContext = context.applicationContext
        mutex.withLock {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (lastCompletedElapsedRealtime != 0L &&
                nowElapsed - lastCompletedElapsedRealtime < MIN_REFRESH_GAP_MS
            ) {
                return
            }

            val repo = SettingsRepository.get(appContext)
            // Immediate render before any location/network work so state changes made by the
            // caller (e.g. a just-activated favourite chip) appear without waiting for the fetch.
            CommuteWidget().updateAll(appContext)
            try {
                performRefresh(appContext, trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val settings = repo.settingsSnapshot()
                saveFailure(repo, currentDirectionHint(settings, ZonedDateTime.now()), e.message ?: "Refresh failed")
            } finally {
                lastCompletedElapsedRealtime = SystemClock.elapsedRealtime()
                CommuteWidget().updateAll(appContext)
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
            historyDays = settings.historyDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        )

        // A direction is still required unconditionally: it seeds CommuteSnapshot.direction even
        // in calendar mode (where it is otherwise a don't-care for rendering) and for a favourite
        // override that happens to land outside both windows.
        val nextWindowResult = nextWindowFor(settings, dayOfWeekIso, minuteOfDay)
        val direction = when (widgetMode) {
            is WidgetMode.Commute -> widgetMode.direction
            WidgetMode.Calendar -> nextWindowResult?.direction ?: Direction.TO_WORK
        }

        val activeFavourite = repo.activeFavourite(nowEpochMillis)
        when (val target = resolveDestinationTarget(activeFavourite, direction)) {
            is DestinationTarget.FavouriteTarget -> {
                performFavouriteRefresh(context, repo, settings, trigger, target.favourite, direction, nowEpochMillis)
            }
            is DestinationTarget.DefaultTarget -> {
                when (widgetMode) {
                    is WidgetMode.Commute -> {
                        performCommuteRefresh(context, repo, settings, trigger, widgetMode.direction, now, nowEpochMillis)
                    }
                    WidgetMode.Calendar -> {
                        performCalendarRefresh(context, repo, settings, direction, nextWindowResult, now, nowEpochMillis)
                    }
                }
            }
        }
    }

    private fun nextWindowFor(settings: AppSettings, dayOfWeekIso: Int, minuteOfDay: Int): NextWindow? =
        nextWindow(
            dayOfWeekIso = dayOfWeekIso,
            minuteOfDay = minuteOfDay,
            historyDays = settings.historyDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        )

    private suspend fun performFavouriteRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        favourite: Favourite,
        direction: Direction,
        nowEpochMillis: Long,
    ) {
        val destination = LatLng(favourite.place.lat, favourite.place.lng)
        val destinationLabel = favourite.label

        val origin = when (val location = currentDeviceLocation(context)) {
            is ApiResult.Success -> location.value
            is ApiResult.Failure -> {
                saveFailure(repo, direction, location.message, SnapshotMode.COMMUTE, destinationLabel)
                return
            }
        }

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
            val mapResult = resolveMapImagePath(context, trigger, previousSnapshot, direction, destination, origin, route, settings.apiKey)
        ) {
            is ApiResult.Success -> mapResult.value
            is ApiResult.Failure -> {
                saveFailure(repo, direction, mapResult.message, SnapshotMode.COMMUTE, destinationLabel)
                return
            }
        }

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
                leaveByMinuteOfDay = null,
                mode = SnapshotMode.COMMUTE,
                eventStartEpochMillis = null,
                nextWindowLabel = null,
                nextWindowStartMinuteOfDay = null,
            ),
        )
        // Favourite overrides never record history and never drive leave-by, unchanged from v2.
    }

    private suspend fun performCommuteRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        trigger: RefreshTrigger,
        direction: Direction,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ) {
        val target = DestinationTarget.DefaultTarget(direction)
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
            val mapResult = resolveMapImagePath(context, trigger, previousSnapshot, direction, destination, origin, route, settings.apiKey)
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

        if (shouldRecordHistorySample(settings.historyEnabled, target)) {
            HistoryStore.get(context).insert(
                CommuteSample.of(
                    timestampEpochMillis = nowEpochMillis,
                    zoneId = now.zone,
                    direction = direction.name,
                    durationSeconds = route.durationSeconds,
                    staticDurationSeconds = route.staticDurationSeconds,
                    distanceMeters = route.distanceMeters,
                    source = trigger.name,
                ),
            )
        }

        if (leaveByPlan != null) {
            maybeNotifyLeaveBy(context, repo, settings, direction, leaveByPlan, destinationLabel, now)
        }
    }

    /**
     * v3 calendar mode; v4 adds the event leave-by advisor for a located event with a start time.
     * Never records history (that remains the exclusive domain of [performCommuteRefresh]).
     * A located event's route-fetch failure uses [saveFailure]'s standard preserve-stale-data
     * behavior, but forces `mode`/`destinationLabel`/`eventStartEpochMillis` to the attempted
     * calendar target so a stale unrelated destination label never lingers under a CALENDAR_EVENT
     * mode tag.
     *
     * Every branch that does not end in a located event with a freshly computed leave-by cancels
     * [EventLeaveByScheduler]'s pending one-shot work (no event, unlocated event, leave-by
     * disabled, or any route/geocode/map failure) - the previously scheduled event may have moved
     * or been cancelled outright, so a stale wake-up must not survive to fire. Only the final
     * located-event success path schedules (or immediately posts) instead of cancelling.
     */
    private suspend fun performCalendarRefresh(
        context: Context,
        repo: SettingsRepository,
        settings: AppSettings,
        direction: Direction,
        nextWindowResult: NextWindow?,
        now: ZonedDateTime,
        nowEpochMillis: Long,
    ) {
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
            EventLeaveByScheduler.cancel(context)
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
            EventLeaveByScheduler.cancel(context)
            return
        }

        val geocoded = when (val result = GeocodingClient(settings.apiKey).geocode(location)) {
            is ApiResult.Success -> result.value.firstOrNull()
            is ApiResult.Failure -> {
                saveFailure(repo, direction, result.message, SnapshotMode.CALENDAR_EVENT, event.title, event.startEpochMillis)
                EventLeaveByScheduler.cancel(context)
                return
            }
        }
        if (geocoded == null) {
            saveFailure(
                repo,
                direction,
                "Event location not found",
                SnapshotMode.CALENDAR_EVENT,
                event.title,
                event.startEpochMillis,
            )
            EventLeaveByScheduler.cancel(context)
            return
        }
        val destination = geocoded.location

        val origin = when (val deviceLocation = currentDeviceLocation(context)) {
            is ApiResult.Success -> deviceLocation.value
            is ApiResult.Failure -> {
                saveFailure(
                    repo,
                    direction,
                    deviceLocation.message,
                    SnapshotMode.CALENDAR_EVENT,
                    event.title,
                    event.startEpochMillis,
                )
                EventLeaveByScheduler.cancel(context)
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
                EventLeaveByScheduler.cancel(context)
                return
            }
        }

        val previousSnapshot = repo.snapshot()
        val mapImagePath = when (
            val mapResult = fetchFreshMap(context, previousSnapshot?.mapImagePath, settings.apiKey, route, origin, destination)
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
                EventLeaveByScheduler.cancel(context)
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
        } else {
            EventLeaveByScheduler.cancel(context)
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

        // Only record the day/direction as notified when a notification actually went out. If
        // POST_NOTIFICATIONS is denied (or later revoked), marking it here would silently and
        // permanently suppress the notification for the rest of the day even after the user
        // grants the permission mid-window - the widget's own leave-by text is unaffected either
        // way since that read is independent of this flag (see shouldShowLeaveBy).
        if (postLeaveByNotification(context, plan, destinationLabel)) {
            repo.markLeaveByNotified(direction, today)
        }
    }
}

private fun travelModeFor(travelMode: TravelMode): RouteTravelMode = when (travelMode) {
    TravelMode.DRIVE -> RouteTravelMode.DRIVE
    TravelMode.TWO_WHEELER -> RouteTravelMode.TWO_WHEELER
}

/**
 * Resolves the on-disk map path for a trigger-dependent pipeline: [RefreshTrigger.SLOT] only ever
 * reuses the previous map (per [shouldReuseSlotMap]) and never fetches; [RefreshTrigger.TAP] and
 * [RefreshTrigger.AUTO] always fetch and downsample a fresh one. Calendar mode's located-event
 * pipeline does not use this - it always wants a fresh map regardless of trigger, see
 * [fetchFreshMap].
 */
private suspend fun resolveMapImagePath(
    context: Context,
    trigger: RefreshTrigger,
    previousSnapshot: CommuteSnapshot?,
    direction: Direction,
    destination: LatLng,
    origin: LatLng,
    route: RouteResult,
    apiKey: String,
): ApiResult<String?> {
    return when (trigger) {
        RefreshTrigger.SLOT -> {
            val reuse = previousSnapshot != null &&
                shouldReuseSlotMap(
                    previousDirection = previousSnapshot.direction,
                    previousDestinationLat = previousSnapshot.destinationLat,
                    previousDestinationLng = previousSnapshot.destinationLng,
                    direction = direction,
                    destination = destination,
                )
            ApiResult.Success(if (reuse) previousSnapshot.mapImagePath else null)
        }
        RefreshTrigger.TAP, RefreshTrigger.AUTO -> {
            fetchFreshMap(context, previousSnapshot?.mapImagePath, apiKey, route, origin, destination)
        }
    }
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

private fun postLeaveByNotification(context: Context, plan: LeaveByPlan, destinationLabel: String): Boolean {
    val hasPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return false

    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    manager.createNotificationChannel(
        NotificationChannel(LEAVE_BY_CHANNEL_ID, LEAVE_BY_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
    )

    val leaveByTime = formatMinuteOfDay(plan.leaveByMinuteOfDay)
    val arriveByTime = formatMinuteOfDay(plan.arriveByMinuteOfDay)
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = Notification.Builder(context, LEAVE_BY_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Time to leave")
        .setContentText(
            "Leave by $leaveByTime to reach $destinationLabel by $arriveByTime - ${plan.travelMinutes} min drive",
        )
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    manager.notify(LEAVE_BY_NOTIFICATION_ID, notification)
    return true
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
}

/**
 * Best-effort direction to attribute an out-of-band failure snapshot to (the exception handler in
 * [CommuteRefresher.refreshNow] has no [WidgetMode]/[DestinationTarget] in hand, since the
 * exception may have been thrown before either was computed). Mirrors the same resolution
 * [CommuteRefresher.performRefresh] uses for its own `direction` value.
 */
private fun currentDirectionHint(settings: AppSettings, now: ZonedDateTime): Direction {
    val dayOfWeekIso = now.dayOfWeek.value
    val minuteOfDay = now.hour * 60 + now.minute
    val mode = resolveWidgetMode(
        dayOfWeekIso = dayOfWeekIso,
        minuteOfDay = minuteOfDay,
        historyDays = settings.historyDays,
        morningStart = settings.morningSlotStartMinuteOfDay,
        morningEnd = settings.morningSlotEndMinuteOfDay,
        eveningStart = settings.eveningSlotStartMinuteOfDay,
        eveningEnd = settings.eveningSlotEndMinuteOfDay,
    )
    return when (mode) {
        is WidgetMode.Commute -> mode.direction
        WidgetMode.Calendar -> nextWindow(
            dayOfWeekIso = dayOfWeekIso,
            minuteOfDay = minuteOfDay,
            historyDays = settings.historyDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        )?.direction ?: Direction.TO_WORK
    }
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
 * event, or a favourite override with a different direction) - [destinationLabelOverride] is
 * passed at every call site that has a concrete new target, so a label mismatch is enough to
 * detect it without needing to thread destination coordinates through every failure branch too.
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
