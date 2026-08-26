package com.crpakala.commutewidget.engine

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.RoutesClient
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
 * The commute window the Best pill currently describes: the morning (To Work) window until it
 * ends, then the evening (To Home) window until it ends, then nothing for the day. Derived from
 * the commute windows directly - there is no separate slot configuration.
 */
internal data class BestDepartureTarget(
    val direction: Direction,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
)

internal fun currentBestDepartureTarget(
    nowMinuteOfDay: Int,
    morningStart: Int,
    morningEnd: Int,
    eveningStart: Int,
    eveningEnd: Int,
): BestDepartureTarget? {
    if (morningStart < morningEnd && nowMinuteOfDay < morningEnd) {
        return BestDepartureTarget(Direction.TO_WORK, morningStart, morningEnd)
    }
    if (eveningStart < eveningEnd && nowMinuteOfDay < eveningEnd) {
        return BestDepartureTarget(Direction.TO_HOME, eveningStart, eveningEnd)
    }
    return null
}

/**
 * Best-departure advisor: Google's API has no "best time to leave in a range" endpoint, so this
 * reconstructs it by sampling the Routes API's predicted-traffic durations across the current
 * commute window at 30-minute steps and keeping the minimum. Auto-switches with the windows:
 * home-to-work across the morning window, work-to-home across the evening window, computed at
 * most once per window per local date on commute days, fired-and-forgotten after a regular
 * refresh so it never delays the pixel path.
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
            val target = currentBestDepartureTarget(
                nowMinuteOfDay = nowMinuteOfDay,
                morningStart = settings.morningSlotStartMinuteOfDay,
                morningEnd = settings.morningSlotEndMinuteOfDay,
                eveningStart = settings.eveningSlotStartMinuteOfDay,
                eveningEnd = settings.eveningSlotEndMinuteOfDay,
            ) ?: return
            val existing = repo.bestDeparture()
            if (!shouldComputeBestDeparture(
                    enabled = settings.bestDepartureEnabled,
                    todayIsCommuteDay = now.dayOfWeek.value in settings.commuteDays,
                    existing = existing,
                    today = today,
                    target = target,
                    nowMinuteOfDay = nowMinuteOfDay,
                )
            ) {
                return
            }

            val instants = departureSampleInstants(
                slotStartMinuteOfDay = target.startMinuteOfDay,
                slotEndMinuteOfDay = target.endMinuteOfDay,
                nowEpochMillis = now.toInstant().toEpochMilli(),
                localDate = now.toLocalDate(),
                zone = now.zone,
            )
            if (instants.isEmpty()) return

            val home = LatLng(settings.home.lat, settings.home.lng)
            val work = LatLng(settings.work.lat, settings.work.lng)
            val origin = if (target.direction == Direction.TO_WORK) home else work
            val destination = if (target.direction == Direction.TO_WORK) work else home
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
                    direction = target.direction,
                    bestMinuteOfDay = bestZoned.hour * 60 + bestZoned.minute,
                    bestDurationSeconds = best.second,
                ),
            )
            runCatching { CommuteWidget().updateAll(context) }
        }
    }
}

/**
 * Compute at most once per window per local date (the stored result carries date + direction, so
 * the evening window recomputes even though a morning result exists), only within
 * [COMPUTE_LEAD_MINUTES] of the window start, and only on commute days.
 */
internal fun shouldComputeBestDeparture(
    enabled: Boolean,
    todayIsCommuteDay: Boolean,
    existing: BestDeparture?,
    today: String,
    target: BestDepartureTarget,
    nowMinuteOfDay: Int,
    leadMinutes: Int = COMPUTE_LEAD_MINUTES,
): Boolean {
    if (!enabled || !todayIsCommuteDay) return false
    if (existing != null && existing.localDate == today && existing.direction == target.direction) return false
    if (nowMinuteOfDay < target.startMinuteOfDay - leadMinutes) return false
    return nowMinuteOfDay < target.endMinuteOfDay
}

/** Future-only departure instants across the window at [SAMPLE_STEP_MINUTES] steps, ends inclusive. */
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

/**
 * Whether the stored result should render: it must describe the CURRENT target window (same date
 * and direction), the window end must not have passed, today must be a commute day, and the
 * widget must not be showing a calendar event - the Best pill describes the commute, and next to
 * an event's own leave-by it reads as (wrong) advice about the event.
 */
internal fun shouldShowBestDeparture(
    result: BestDeparture?,
    enabled: Boolean,
    todayIsCommuteDay: Boolean,
    today: String,
    target: BestDepartureTarget?,
    showingCalendarEvent: Boolean,
): Boolean {
    return enabled &&
        todayIsCommuteDay &&
        !showingCalendarEvent &&
        target != null &&
        result != null &&
        result.localDate == today &&
        result.direction == target.direction
}
