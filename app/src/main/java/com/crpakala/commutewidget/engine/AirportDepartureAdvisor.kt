package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.RouteResult
import com.crpakala.commutewidget.api.RouteTravelMode
import com.crpakala.commutewidget.api.RoutesClient
import com.crpakala.commutewidget.data.AirportDeparture

internal const val AIRPORT_SAMPLE_STEP_MINUTES = 30
/** Matches RoutesClient's near-future guard: instants closer than this sample real-time anyway. */
private const val MIN_FUTURE_MILLIS = 60_000L

/**
 * Future-only departure instants across [windowStartMillis, arriveByMillis) at [stepMinutes]
 * steps. A sample that departs at arriveBy can't possibly arrive in time, so the last allowed
 * instant is arriveBy - step. Past instants are dropped; if that drops the window start, now is
 * substituted in as the first sample so a late start still gets a reading.
 */
internal fun airportSampleInstants(
    windowStartMillis: Long,
    arriveByMillis: Long,
    nowEpochMillis: Long,
    stepMinutes: Int = AIRPORT_SAMPLE_STEP_MINUTES,
): List<Long> {
    val stepMillis = stepMinutes * 60_000L
    val lastAllowedMillis = arriveByMillis - stepMillis
    val raw = mutableListOf<Long>()
    var instant = windowStartMillis
    while (instant <= lastAllowedMillis) {
        raw.add(instant)
        instant += stepMillis
    }

    val minFutureMillis = nowEpochMillis + MIN_FUTURE_MILLIS
    val future = raw.filter { it >= minFutureMillis }
    if (future.isEmpty()) return emptyList()

    val result = future.toMutableList()
    if (result.first() > windowStartMillis) {
        result.add(0, minFutureMillis)
    }
    return result.distinct().sorted()
}

internal data class AirportSample(val departureMillis: Long, val durationSeconds: Long)

/** Minimum duration among samples that still arrive by [arriveByMillis]; ties prefer the latest departure. */
internal fun pickBestAirportDeparture(samples: List<AirportSample>, arriveByMillis: Long): AirportSample? {
    val qualifying = samples.filter { it.departureMillis + it.durationSeconds * 1000 <= arriveByMillis }
    val minDurationSeconds = qualifying.minOfOrNull { it.durationSeconds } ?: return null
    return qualifying.filter { it.durationSeconds == minDurationSeconds }.maxByOrNull { it.departureMillis }
}

/** Compute at most once per flight: only inside the airport window, and only for a new eventId. */
internal fun shouldComputeAirportDeparture(
    existing: AirportDeparture?,
    eventId: Long,
    nowEpochMillis: Long,
    windowStartMillis: Long,
    arriveByMillis: Long,
): Boolean {
    if (nowEpochMillis < windowStartMillis || nowEpochMillis >= arriveByMillis) return false
    return existing == null || existing.eventId != eventId
}

/**
 * Airport equivalent of BestDepartureAdvisor: samples the Routes API across the airport window and
 * keeps the fastest departure that still arrives by [arriveByMillis]. Unlike
 * BestDepartureAdvisor.maybeCompute(context), this reads nothing itself - the caller (the
 * refresher) supplies settings, event and clock, so this object stays a pure function of its
 * arguments.
 */
internal object AirportDepartureAdvisor {
    suspend fun compute(
        routes: RoutesClient,
        origin: LatLng,
        airport: LatLng,
        mode: RouteTravelMode,
        eventId: Long,
        windowStartMillis: Long,
        arriveByMillis: Long,
        nowEpochMillis: Long,
        sampler: suspend (departureMillis: Long) -> ApiResult<RouteResult> = { departureMillis ->
            routes.computeRoute(origin, airport, mode, departureTimeEpochMillis = departureMillis)
        },
    ): AirportDeparture? {
        val instants = airportSampleInstants(windowStartMillis, arriveByMillis, nowEpochMillis)
        if (instants.isEmpty()) return null

        val samples = mutableListOf<AirportSample>()
        for (instant in instants) {
            val result = runCatching { sampler(instant) }.getOrNull()
            if (result is ApiResult.Success) {
                samples.add(AirportSample(instant, result.value.durationSeconds))
            }
        }

        val best = pickBestAirportDeparture(samples, arriveByMillis) ?: return null
        return AirportDeparture(
            eventId = eventId,
            bestDepartureMillis = best.departureMillis,
            bestDurationSeconds = best.durationSeconds,
            sampleCount = samples.size,
            computedAtMillis = nowEpochMillis,
        )
    }
}
