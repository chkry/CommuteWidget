package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.RouteResult
import com.crpakala.commutewidget.api.RouteTravelMode
import com.crpakala.commutewidget.api.RoutesClient
import com.crpakala.commutewidget.data.AirportDeparture
import com.crpakala.commutewidget.data.decodeAirportDeparture
import com.crpakala.commutewidget.data.encodeAirportDeparture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirportDepartureAdvisorTest {
    private val minute = 60_000L
    // Scheduled departure T; window is [T-5h, T-3h).
    private val t = 1_700_000_000_000L
    private val windowStart = t - 300 * minute
    private val arriveBy = t - 180 * minute
    private val lastAllowed = arriveBy - 30 * minute
    private val routes = RoutesClient(apiKey = "test")

    @Test
    fun sampleInstants_fiveHourWindow_fourInstants() {
        val now = windowStart - 60 * minute
        val instants = airportSampleInstants(windowStart, arriveBy, now)
        assertEquals(
            listOf(windowStart, windowStart + 30 * minute, windowStart + 60 * minute, windowStart + 90 * minute),
            instants,
        )
        assertEquals(lastAllowed, instants.last())
    }

    @Test
    fun sampleInstants_lateStart_insertsNowReading() {
        val now = t - 245 * minute // T-4h05m
        val minFuture = now + minute
        val instants = airportSampleInstants(windowStart, arriveBy, now)
        assertEquals(listOf(minFuture, t - 240 * minute, t - 210 * minute), instants)
        assertEquals(lastAllowed, instants.last())
    }

    @Test
    fun sampleInstants_nowAtOrPastLastAllowed_empty() {
        assertEquals(emptyList<Long>(), airportSampleInstants(windowStart, arriveBy, lastAllowed))
        assertEquals(emptyList<Long>(), airportSampleInstants(windowStart, arriveBy, lastAllowed + minute))
    }

    @Test
    fun pickBest_excludesLateArrivals() {
        val late = AirportSample(departureMillis = arriveBy - 40 * minute, durationSeconds = 50 * 60L)
        val onTime = AirportSample(departureMillis = arriveBy - 60 * minute, durationSeconds = 50 * 60L)
        assertEquals(onTime, pickBestAirportDeparture(listOf(late, onTime), arriveBy))
    }

    @Test
    fun pickBest_tieBreakPrefersLaterDeparture() {
        val earlier = AirportSample(departureMillis = arriveBy - 90 * minute, durationSeconds = 30 * 60L)
        val later = AirportSample(departureMillis = arriveBy - 60 * minute, durationSeconds = 30 * 60L)
        assertEquals(later, pickBestAirportDeparture(listOf(earlier, later), arriveBy))
    }

    @Test
    fun pickBest_noneQualify_null() {
        val tooLate = AirportSample(departureMillis = arriveBy - 10 * minute, durationSeconds = 50 * 60L)
        assertNull(pickBestAirportDeparture(listOf(tooLate), arriveBy))
        assertNull(pickBestAirportDeparture(emptyList(), arriveBy))
    }

    @Test
    fun shouldCompute_trueOnlyInWindowForNewFlight() {
        val existingSame = AirportDeparture(1L, 0L, 0L, sampleCount = 1, computedAtMillis = 0L)
        val existingOther = existingSame.copy(eventId = 2L)
        assertFalse(shouldComputeAirportDeparture(null, 1L, windowStart - minute, windowStart, arriveBy))
        assertTrue(shouldComputeAirportDeparture(null, 1L, windowStart, windowStart, arriveBy))
        assertFalse(shouldComputeAirportDeparture(existingSame, 1L, windowStart + minute, windowStart, arriveBy))
        assertTrue(shouldComputeAirportDeparture(existingOther, 1L, windowStart + minute, windowStart, arriveBy))
        assertFalse(shouldComputeAirportDeparture(null, 1L, arriveBy, windowStart, arriveBy))
    }

    // RoutesClient is a final class with no seam for a fake HTTP backend, so `compute` takes an
    // internal `sampler` parameter (default: routes.computeRoute) that tests substitute directly.
    @Test
    fun compute_picksFastestQualifyingSample_ignoresFailuresAndLateArrivals() = runBlocking {
        val now = windowStart - 60 * minute
        val seen = mutableListOf<Long>()
        val sampler: suspend (Long) -> ApiResult<RouteResult> = { departureMillis ->
            seen.add(departureMillis)
            when (departureMillis) {
                windowStart -> ApiResult.Success(routeResult(durationMinutes = 100))
                windowStart + 30 * minute -> ApiResult.Success(routeResult(durationMinutes = 60))
                windowStart + 60 * minute -> ApiResult.Failure("boom")
                else -> ApiResult.Success(routeResult(durationMinutes = 40))
            }
        }

        val result = AirportDepartureAdvisor.compute(
            routes = routes,
            origin = LatLng(1.0, 1.0),
            airport = LatLng(2.0, 2.0),
            mode = RouteTravelMode.DRIVE,
            eventId = 7L,
            windowStartMillis = windowStart,
            arriveByMillis = arriveBy,
            nowEpochMillis = now,
            sampler = sampler,
        )

        assertEquals(4, seen.size)
        assertEquals(7L, result!!.eventId)
        assertEquals(windowStart + 30 * minute, result.bestDepartureMillis)
        assertEquals(3600L, result.bestDurationSeconds)
        assertEquals(3, result.sampleCount)
        assertEquals(now, result.computedAtMillis)
    }

    @Test
    fun compute_allSamplesFail_returnsNull() = runBlocking {
        val now = windowStart - 60 * minute
        val result = AirportDepartureAdvisor.compute(
            routes = routes,
            origin = LatLng(1.0, 1.0),
            airport = LatLng(2.0, 2.0),
            mode = RouteTravelMode.DRIVE,
            eventId = 7L,
            windowStartMillis = windowStart,
            arriveByMillis = arriveBy,
            nowEpochMillis = now,
            sampler = { ApiResult.Failure("boom") },
        )
        assertNull(result)
    }

    @Test
    fun compute_noSampleQualifies_returnsNull() = runBlocking {
        val now = windowStart - 60 * minute
        val result = AirportDepartureAdvisor.compute(
            routes = routes,
            origin = LatLng(1.0, 1.0),
            airport = LatLng(2.0, 2.0),
            mode = RouteTravelMode.DRIVE,
            eventId = 7L,
            windowStartMillis = windowStart,
            arriveByMillis = arriveBy,
            nowEpochMillis = now,
            sampler = { ApiResult.Success(routeResult(durationMinutes = 400)) },
        )
        assertNull(result)
    }

    @Test
    fun codec_roundTrips() {
        val value = AirportDeparture(
            eventId = 42L,
            bestDepartureMillis = 1_700_000_000_000L,
            bestDurationSeconds = 3600L,
            sampleCount = 4,
            computedAtMillis = 1_700_000_100_000L,
        )
        assertEquals(value, decodeAirportDeparture(encodeAirportDeparture(value)))
    }

    @Test
    fun codec_blankAndInvalidDecodeToNull() {
        assertNull(decodeAirportDeparture(null))
        assertNull(decodeAirportDeparture(""))
        assertNull(decodeAirportDeparture("not json"))
    }

    private fun routeResult(durationMinutes: Long): RouteResult = RouteResult(
        durationSeconds = durationMinutes * 60,
        staticDurationSeconds = durationMinutes * 60,
        distanceMeters = 0,
        encodedPolyline = "",
        speedIntervals = emptyList(),
    )
}
