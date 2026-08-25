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
import com.crpakala.commutewidget.api.RouteTravelMode
import com.crpakala.commutewidget.api.RoutesClient
import com.crpakala.commutewidget.api.StaticMapUrl
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.data.ActiveFavourite
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.data.isActive
import com.crpakala.commutewidget.history.CommuteSample
import com.crpakala.commutewidget.history.HistoryStore
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
import java.time.DayOfWeek
import java.time.LocalTime
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
private const val LEAVE_BY_CHANNEL_ID = "leave_by"
private const val LEAVE_BY_CHANNEL_NAME = "Leave-by advisor"
private const val LEAVE_BY_NOTIFICATION_ID = 1001

/** Who initiated a refresh; drives both the fetch pipeline (map or not) and history bookkeeping. */
enum class RefreshTrigger { TAP, AUTO, SLOT }

fun decideDirection(
    dayOfWeek: DayOfWeek,
    minuteOfDay: Int,
    switchMinuteOfDay: Int,
): Direction {
    if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
        return Direction.TO_HOME
    }
    return if (minuteOfDay < switchMinuteOfDay) Direction.TO_WORK else Direction.TO_HOME
}

/**
 * Resolved commute destination after applying the v2 precedence rules (favourite > calendar >
 * default). [DefaultTarget] is the only variant that participates in leave-by advice and history
 * recording.
 */
internal sealed class DestinationTarget {
    data class FavouriteTarget(val favourite: com.crpakala.commutewidget.data.Favourite) : DestinationTarget()
    data class CalendarTarget(val label: String, val destination: LatLng) : DestinationTarget()
    data class DefaultTarget(val direction: Direction) : DestinationTarget()
}

/** Calendar destinations are only ever attempted for a user-initiated [RefreshTrigger.TAP]. */
internal fun shouldAttemptCalendarLookup(
    trigger: RefreshTrigger,
    settings: AppSettings,
    hasCalendarPermission: Boolean,
): Boolean {
    return trigger == RefreshTrigger.TAP &&
        settings.calendarEnabled &&
        hasCalendarPermission &&
        settings.selectedCalendarIds.isNotEmpty()
}

/**
 * Pure precedence decision: an unexpired active favourite always wins, then an already-resolved
 * (geocoded) calendar destination, otherwise the v1 home/work-by-time-of-day default.
 */
internal fun resolveDestinationTarget(
    settings: AppSettings,
    activeFavourite: ActiveFavourite?,
    calendarTarget: DestinationTarget.CalendarTarget?,
    now: ZonedDateTime,
): DestinationTarget {
    if (isActive(activeFavourite, now.toInstant().toEpochMilli())) {
        return DestinationTarget.FavouriteTarget(activeFavourite!!.favourite)
    }
    if (calendarTarget != null) {
        return calendarTarget
    }
    val direction = decideDirection(now.dayOfWeek, now.hour * 60 + now.minute, settings.switchMinuteOfDay)
    return DestinationTarget.DefaultTarget(direction)
}

/**
 * History sampling has two independent gates: the destination must be the plain default
 * home/work-by-time-of-day route (favourite and calendar targets are never recorded, regardless
 * of trigger), and the user's "Enable commute history" master switch must be on. [SlotFetchWorker]
 * already refuses to even attempt a fetch when history is disabled, but TAP and AUTO refreshes
 * flow through this same recording path as an ordinary side effect of every successful fetch, so
 * that path needs its own [AppSettings.historyEnabled] check to honor the same toggle.
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
 * A [RefreshTrigger.SLOT] fetch never downloads a fresh map; it may only keep showing the
 * previous one, and only when the previous snapshot was unambiguously the same route. Matching on
 * [Direction] alone is not sufficient: an active favourite or calendar destination can occupy a
 * SLOT-triggered fetch too (favourites apply to ANY trigger per spec), and those change the
 * physical origin/destination while leaving the home/work-by-time-of-day [Direction] untouched.
 * Comparing the resolved destination coordinates as well ensures the reused map still depicts the
 * route actually being displayed, instead of a stale favourite/calendar map bleeding into a
 * default-target render (or vice versa) after a target-type switch.
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
            try {
                performRefresh(appContext, trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val repo = SettingsRepository.get(appContext)
                val settings = repo.settingsSnapshot()
                saveFailure(repo, directionNow(settings), e.message ?: "Refresh failed")
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
        val isWeekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        val direction = decideDirection(now.dayOfWeek, now.hour * 60 + now.minute, settings.switchMinuteOfDay)

        val activeFavourite = repo.activeFavourite(nowEpochMillis)
        val calendarTarget = if (activeFavourite == null) {
            resolveCalendarTarget(context, trigger, settings, nowEpochMillis)
        } else {
            null
        }
        val target = resolveDestinationTarget(settings, activeFavourite, calendarTarget, now)

        val destination: LatLng
        val destinationLabel: String
        when (target) {
            is DestinationTarget.FavouriteTarget -> {
                destination = LatLng(target.favourite.place.lat, target.favourite.place.lng)
                destinationLabel = target.favourite.label
            }
            is DestinationTarget.CalendarTarget -> {
                destination = target.destination
                destinationLabel = target.label
            }
            is DestinationTarget.DefaultTarget -> {
                val destPlace = if (target.direction == Direction.TO_WORK) settings.work else settings.home
                destination = LatLng(destPlace.lat, destPlace.lng)
                destinationLabel = if (target.direction == Direction.TO_WORK) "Work" else "Home"
            }
        }

        val origin: LatLng = when (target) {
            is DestinationTarget.FavouriteTarget, is DestinationTarget.CalendarTarget -> {
                when (val location = currentDeviceLocation(context)) {
                    is ApiResult.Success -> location.value
                    is ApiResult.Failure -> {
                        saveFailure(repo, direction, location.message)
                        return
                    }
                }
            }
            is DestinationTarget.DefaultTarget -> {
                if (isWeekend) {
                    when (val location = currentDeviceLocation(context)) {
                        is ApiResult.Success -> location.value
                        is ApiResult.Failure -> {
                            saveFailure(repo, direction, location.message)
                            return
                        }
                    }
                } else if (target.direction == Direction.TO_WORK) {
                    LatLng(settings.home.lat, settings.home.lng)
                } else {
                    LatLng(settings.work.lat, settings.work.lng)
                }
            }
        }

        val mode = when (settings.travelMode) {
            TravelMode.DRIVE -> RouteTravelMode.DRIVE
            TravelMode.TWO_WHEELER -> RouteTravelMode.TWO_WHEELER
        }

        val route = when (val result = RoutesClient(settings.apiKey).computeRoute(origin, destination, mode)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                saveFailure(repo, direction, result.message)
                return
            }
        }

        val previousSnapshot = repo.snapshot()

        val mapImagePath: String? = when (trigger) {
            RefreshTrigger.SLOT -> {
                if (previousSnapshot != null &&
                    shouldReuseSlotMap(
                        previousDirection = previousSnapshot.direction,
                        previousDestinationLat = previousSnapshot.destinationLat,
                        previousDestinationLng = previousSnapshot.destinationLng,
                        direction = direction,
                        destination = destination,
                    )
                ) {
                    previousSnapshot.mapImagePath
                } else {
                    null
                }
            }
            RefreshTrigger.TAP, RefreshTrigger.AUTO -> {
                val mapUrl = StaticMapUrl.build(
                    apiKey = settings.apiKey,
                    widthPx = MAP_FETCH_WIDTH_PX,
                    heightPx = MAP_FETCH_HEIGHT_PX,
                    route = route,
                    origin = origin,
                    destination = destination,
                )
                val destFile = nextMapFile(context.filesDir, previousSnapshot?.mapImagePath)
                val mapFile = when (val fetched = MapImageFetcher().fetch(mapUrl, destFile)) {
                    is ApiResult.Success -> fetched.value
                    is ApiResult.Failure -> {
                        saveFailure(repo, direction, fetched.message)
                        return
                    }
                }
                withContext(Dispatchers.IO) {
                    downsampleMapFile(mapFile)
                }
                mapFile.absolutePath
            }
        }

        val leaveByPlan = leaveByPlanFor(settings, target, isWeekend, direction, route.durationSeconds)

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

    private suspend fun resolveCalendarTarget(
        context: Context,
        trigger: RefreshTrigger,
        settings: AppSettings,
        nowEpochMillis: Long,
    ): DestinationTarget.CalendarTarget? {
        val calendarReader = CalendarReader(context)
        if (!shouldAttemptCalendarLookup(trigger, settings, calendarReader.hasPermission())) {
            return null
        }
        val event = calendarReader.nextEventWithLocation(
            settings.selectedCalendarIds,
            nowEpochMillis,
            settings.calendarLookaheadMinutes,
        ) ?: return null

        return when (val geocoded = GeocodingClient(settings.apiKey).geocode(event.location)) {
            is ApiResult.Success -> {
                val hit = geocoded.value.firstOrNull() ?: return null
                DestinationTarget.CalendarTarget(event.title, hit.location)
            }
            is ApiResult.Failure -> null
        }
    }

    private fun leaveByPlanFor(
        settings: AppSettings,
        target: DestinationTarget,
        isWeekend: Boolean,
        direction: Direction,
        durationSeconds: Long,
    ): LeaveByPlan? {
        if (!settings.leaveByEnabled || target !is DestinationTarget.DefaultTarget || isWeekend) {
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

private fun directionNow(settings: AppSettings): Direction {
    val now = ZonedDateTime.now()
    return decideDirection(now.dayOfWeek, now.hour * 60 + now.minute, settings.switchMinuteOfDay)
}

private suspend fun saveFailure(
    repo: SettingsRepository,
    direction: Direction,
    message: String,
) {
    val previous = repo.snapshot()
    if (previous != null) {
        repo.saveSnapshot(
            previous.copy(
                lastFetchFailed = true,
                lastErrorMessage = message,
            ),
        )
    } else {
        repo.saveSnapshot(
            CommuteSnapshot(
                direction = direction,
                durationSeconds = 0L,
                durationNoTrafficSeconds = 0L,
                distanceMeters = 0L,
                mapImagePath = null,
                fetchedAtEpochMillis = 0L,
                lastFetchFailed = true,
                lastErrorMessage = message,
            ),
        )
    }
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

        val last = try {
            client.lastLocation.awaitNullable()
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
        if (last != null) {
            return ApiResult.Success(LatLng(last.latitude, last.longitude))
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
