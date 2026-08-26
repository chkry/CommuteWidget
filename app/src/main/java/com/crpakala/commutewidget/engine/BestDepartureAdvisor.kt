package com.crpakala.commutewidget.engine

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.RoutesClient
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.BestDeparture
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private const val SAMPLE_STEP_MINUTES = 30
private const val COMPUTE_LEAD_MINUTES = 180
/** Matches RoutesClient's near-future guard: instants closer than this sample real-time anyway. */
private const val MIN_FUTURE_MILLIS = 60_000L

/**
 * Best-departure advisor: Google's API has no "best time to leave in a range" endpoint, so this
 * reconstructs it by sampling the Routes API's predicted-traffic durations across the configured
 * departure slot at 30-minute steps and keeping the minimum. Runs at most once per local date
 * (results barely move intra-day), fired-and-forgotten after a regular refresh so it never delays
 * the pixel path; roughly 9 extra Routes calls per day with the default 4-hour slot.
 */
internal object BestDepartureAdvisor {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun maybeComputeAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { maybeCompute(appContext) }
        }
    }

    suspend fun maybeCompute(context: Context) {
        mutex.withLock {
            val repo = SettingsRepository.get(context)
            val settings = repo.settingsSnapshot()
            if (settings.apiKey.isBlank() || settings.home == null || settings.work == null) return
            val now = ZonedDateTime.now()
            val nowMinuteOfDay = now.hour * 60 + now.minute
            val today = now.toLocalDate().toString()
            val existing = repo.bestDeparture()
            if (!shouldComputeBestDeparture(
                    enabled = settings.bestDepartureEnabled,
                    computedForDate = existing?.localDate,
                    today = today,
                    nowMinuteOfDay = nowMinuteOfDay,
                    slotStartMinuteOfDay = settings.departureSlotStartMinuteOfDay,
                    slotEndMinuteOfDay = settings.departureSlotEndMinuteOfDay,
                )
            ) {
                return
            }

            val instants = departureSampleInstants(
                slotStartMinuteOfDay = settings.departureSlotStartMinuteOfDay,
                slotEndMinuteOfDay = settings.departureSlotEndMinuteOfDay,
                nowEpochMillis = now.toInstant().toEpochMilli(),
                localDate = now.toLocalDate(),
                zone = now.zone,
            )
            if (instants.isEmpty()) return

            val direction = settings.departureSlotDirection
            val home = LatLng(settings.home.lat, settings.home.lng)
            val work = LatLng(settings.work.lat, settings.work.lng)
            val origin = if (direction == Direction.TO_WORK) home else work
            val destination = if (direction == Direction.TO_WORK) work else home
            val client = RoutesClient(settings.apiKey)
            val mode = travelModeFor(settings.travelMode)

            val sampled = coroutineScope {
                instants.map { instant ->
                    async {
                        val result = client.computeRoute(origin, destination, mode, departureTimeEpochMillis = instant)
                        instant to (result as? ApiResult.Success)?.value?.durationSeconds
                    }
                }.awaitAll()
            }
            val best = sampled
                .mapNotNull { (instant, duration) -> duration?.let { instant to it } }
                .minByOrNull { it.second }
                ?: return

            val bestZoned = Instant.ofEpochMilli(best.first).atZone(now.zone)
            repo.setBestDeparture(
                BestDeparture(
                    localDate = today,
                    direction = direction,
                    bestMinuteOfDay = bestZoned.hour * 60 + bestZoned.minute,
                    bestDurationSeconds = best.second,
                ),
            )
            runCatching { CommuteWidget().updateAll(context) }
        }
    }
}

/**
 * Compute at most once per local date, only within [COMPUTE_LEAD_MINUTES] of the slot start
 * (predictions tighten closer to departure) and only while part of the slot is still ahead.
 */
internal fun shouldComputeBestDeparture(
    enabled: Boolean,
    computedForDate: String?,
    today: String,
    nowMinuteOfDay: Int,
    slotStartMinuteOfDay: Int,
    slotEndMinuteOfDay: Int,
    leadMinutes: Int = COMPUTE_LEAD_MINUTES,
): Boolean {
    if (!enabled || slotStartMinuteOfDay >= slotEndMinuteOfDay) return false
    if (computedForDate == today) return false
    if (nowMinuteOfDay < slotStartMinuteOfDay - leadMinutes) return false
    return nowMinuteOfDay < slotEndMinuteOfDay
}

/** Future-only departure instants across the slot at [SAMPLE_STEP_MINUTES] steps, ends inclusive. */
internal fun departureSampleInstants(
    slotStartMinuteOfDay: Int,
    slotEndMinuteOfDay: Int,
    nowEpochMillis: Long,
    localDate: LocalDate,
    zone: ZoneId,
): List<Long> {
    if (slotStartMinuteOfDay >= slotEndMinuteOfDay) return emptyList()
    val instants = mutableListOf<Long>()
    var minute = slotStartMinuteOfDay
    while (minute <= slotEndMinuteOfDay) {
        val instant = localDate.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        if (instant - nowEpochMillis >= MIN_FUTURE_MILLIS) {
            instants.add(instant)
        }
        minute += SAMPLE_STEP_MINUTES
    }
    return instants
}

/** Whether the stored result should still render: same-day and the slot end has not passed. */
internal fun shouldShowBestDeparture(
    result: BestDeparture?,
    enabled: Boolean,
    today: String,
    nowMinuteOfDay: Int,
    slotEndMinuteOfDay: Int,
): Boolean {
    return enabled && result != null && result.localDate == today && nowMinuteOfDay <= slotEndMinuteOfDay
}
