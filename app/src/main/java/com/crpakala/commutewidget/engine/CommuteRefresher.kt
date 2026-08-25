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
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.MapImageFetcher
import com.crpakala.commutewidget.api.RouteTravelMode
import com.crpakala.commutewidget.api.RoutesClient
import com.crpakala.commutewidget.api.StaticMapUrl
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.TravelMode
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
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val MAP_FETCH_WIDTH_PX = 640
private const val MAP_FETCH_HEIGHT_PX = 640
private const val MAP_MAX_LONG_EDGE_PX = 1200
private const val LOCATION_TIMEOUT_MS = 15_000L
private const val MIN_REFRESH_GAP_MS = 5_000L
private const val MAP_FILE_A = "map_a.png"
private const val MAP_FILE_B = "map_b.png"

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

object CommuteRefresher {
    private val mutex = Mutex()
    private var lastCompletedElapsedRealtime = 0L

    suspend fun refreshNow(context: Context) {
        val appContext = context.applicationContext
        mutex.withLock {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (lastCompletedElapsedRealtime != 0L &&
                nowElapsed - lastCompletedElapsedRealtime < MIN_REFRESH_GAP_MS
            ) {
                return
            }
            try {
                performRefresh(appContext)
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

    private suspend fun performRefresh(context: Context) {
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        if (settings.apiKey.isBlank() || settings.home == null || settings.work == null) {
            return
        }

        val now = ZonedDateTime.now()
        val direction = decideDirection(
            now.dayOfWeek,
            now.hour * 60 + now.minute,
            settings.switchMinuteOfDay,
        )
        val isWeekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY

        val origin = if (isWeekend) {
            when (val location = currentDeviceLocation(context)) {
                is ApiResult.Success -> location.value
                is ApiResult.Failure -> {
                    saveFailure(repo, direction, location.message)
                    return
                }
            }
        } else if (direction == Direction.TO_WORK) {
            LatLng(settings.home.lat, settings.home.lng)
        } else {
            LatLng(settings.work.lat, settings.work.lng)
        }

        val destPlace = if (direction == Direction.TO_WORK) settings.work else settings.home
        val destination = LatLng(destPlace.lat, destPlace.lng)

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

        val mapUrl = StaticMapUrl.build(
            apiKey = settings.apiKey,
            widthPx = MAP_FETCH_WIDTH_PX,
            heightPx = MAP_FETCH_HEIGHT_PX,
            route = route,
            origin = origin,
            destination = destination,
        )
        val destFile = nextMapFile(context.filesDir, repo.snapshot()?.mapImagePath)
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

        repo.saveSnapshot(
            CommuteSnapshot(
                direction = direction,
                durationSeconds = route.durationSeconds,
                durationNoTrafficSeconds = route.staticDurationSeconds,
                distanceMeters = route.distanceMeters,
                mapImagePath = mapFile.absolutePath,
                fetchedAtEpochMillis = System.currentTimeMillis(),
                lastFetchFailed = false,
                lastErrorMessage = null,
            ),
        )
    }
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
