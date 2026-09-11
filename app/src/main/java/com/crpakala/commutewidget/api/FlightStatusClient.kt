package com.crpakala.commutewidget.api

import com.crpakala.commutewidget.data.AircraftRecord
import com.crpakala.commutewidget.data.AirportDelayStats
import com.crpakala.commutewidget.data.FlightStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val AIRLABS_BASE_URL = "https://airlabs.co/api/v9"
private const val MAX_BODY_BYTES = 1L * 1024 * 1024

/** AirLabs `delays` rejects anything under 30, and the board is only interesting from there up. */
private const val MIN_REPORTED_DELAY_MINUTES = 30

private val AUTH_ERROR_CODES =
    setOf("unknown_api_key", "expired_api_key", "wrong_params", "unknown_method", "unauthorized")
private val QUOTA_ERROR_CODES =
    setOf("minute_limit_exceeded", "hour_limit_exceeded", "month_limit_exceeded", "limit_exceeded")

sealed interface FlightStatusResult {
    data class Success(val status: FlightStatus) : FlightStatusResult
    data class Failure(val message: String, val retryable: Boolean) : FlightStatusResult
}

class FlightStatusClient(
    private val apiKey: String,
    private val client: OkHttpClient = HttpClients.default,
) {
    /**
     * [includeFlightDetails] buys the `flight` endpoint on a leg that is not yet airborne. It is
     * the only way to learn the airline's own name, the arrival gate and the airport names before
     * departure, none of which `schedules` carries, so the caller passes it exactly once per flight
     * (the first fetch, before a status with an airline name is stored) and the leg costs one query
     * on every fetch after that.
     */
    suspend fun fetch(
        designator: String,
        localDate: LocalDate,
        departureIata: String?,
        includeFlightDetails: Boolean = false,
    ): FlightStatusResult {
        val normalized = designator.replace(" ", "").uppercase()
        return try {
            when (val scheduleOutcome = get(schedulesUrl(normalized, departureIata))) {
                is FetchOutcome.Failed -> scheduleOutcome.result
                is FetchOutcome.Body ->
                    resolveFromSchedules(scheduleOutcome.body, normalized, localDate, departureIata, includeFlightDetails)
            }
        } catch (e: IOException) {
            FlightStatusResult.Failure("Network error", retryable = true)
        }
    }

    /**
     * The one airframe AirLabs holds for [registration], or null when the request itself failed.
     * A successful lookup that names nothing still returns a record, so the caller can cache the
     * miss rather than spend a query on it again.
     */
    suspend fun fetchFleet(registration: String, nowEpochMillis: Long): AircraftRecord? = try {
        when (val outcome = get(fleetUrl(registration))) {
            is FetchOutcome.Failed -> null
            is FetchOutcome.Body -> FlightStatusMapper.fromFleetJson(outcome.body, registration, nowEpochMillis)
        }
    } catch (e: IOException) {
        null
    }

    /** How [iata] is departing right now, or null when the request failed or nothing is delayed. */
    suspend fun fetchDepartureDelays(iata: String, nowEpochMillis: Long): AirportDelayStats? = try {
        when (val outcome = get(delaysUrl(iata))) {
            is FetchOutcome.Failed -> null
            is FetchOutcome.Body -> FlightStatusMapper.fromDelaysJson(outcome.body, iata, nowEpochMillis)
        }
    } catch (e: IOException) {
        null
    }

    private suspend fun resolveFromSchedules(
        body: String,
        normalized: String,
        localDate: LocalDate,
        departureIata: String?,
        includeFlightDetails: Boolean,
    ): FlightStatusResult {
        val schedule = FlightStatusMapper.fromSchedulesJson(body, normalized, localDate)
            ?: return resolveFromRoutes(normalized, localDate, departureIata)
        if (schedule.status != "active" && !includeFlightDetails) return FlightStatusResult.Success(schedule)

        return when (val flightOutcome = get(flightUrl(normalized))) {
            is FetchOutcome.Failed -> FlightStatusResult.Success(schedule)
            is FetchOutcome.Body -> FlightStatusResult.Success(mergeIfSameLeg(schedule, flightOutcome.body, normalized))
        }
    }

    private suspend fun resolveFromRoutes(normalized: String, localDate: LocalDate, departureIata: String?): FlightStatusResult {
        val noTimetableFailure = FlightStatusResult.Failure("No schedule or timetable for $normalized", retryable = false)
        return when (val routesOutcome = get(routesUrl(normalized, departureIata))) {
            is FetchOutcome.Failed -> routesOutcome.result
            is FetchOutcome.Body -> {
                val route = FlightStatusMapper.fromRoutesJson(routesOutcome.body, normalized, localDate, departureIata)
                if (route != null) FlightStatusResult.Success(route) else noTimetableFailure
            }
        }
    }

    /**
     * The `flight` endpoint answers for the designator, not for a date, so before departure it can
     * hand back a leg other than the one `schedules` selected. Matching the scheduled departure
     * instant identifies the same leg outright; the same UTC date is accepted too, because a leg
     * whose schedule moved after the timetable was published is still that day's flight.
     */
    private fun mergeIfSameLeg(schedule: FlightStatus, flightBody: String, normalized: String): FlightStatus {
        val live = FlightStatusMapper.fromFlightJson(flightBody, normalized) ?: return schedule
        if (!sameDepartureDay(live.departureScheduledUtcMillis, schedule.departureScheduledUtcMillis)) return schedule
        return FlightStatusMapper.merge(schedule, live).copy(source = "schedules+flight")
    }

    private suspend fun get(url: HttpUrl): FetchOutcome {
        val request = Request.Builder().url(url).addHeader("Accept", "application/json").get().build()
        return client.executeSuspend(request).use { response ->
            httpLevelFailure(response.code)?.let { return@use FetchOutcome.Failed(it) }
            val rawBody = readBoundedBody(response)
                ?: return@use FetchOutcome.Failed(FlightStatusResult.Failure("Response too large", retryable = false))
            val body = stripRequestBlock(rawBody)
            val errorCode = FlightStatusMapper.errorCode(body)
            if (errorCode != null) return@use FetchOutcome.Failed(bodyErrorFailure(errorCode))
            FetchOutcome.Body(body)
        }
    }

    private fun schedulesUrl(designator: String, departureIata: String?): HttpUrl {
        val builder = AIRLABS_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("schedules")
            .addQueryParameter("flight_iata", designator)
            .addQueryParameter("api_key", apiKey)
        if (departureIata != null) builder.addQueryParameter("dep_iata", departureIata)
        return builder.build()
    }

    private fun flightUrl(designator: String): HttpUrl =
        AIRLABS_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("flight")
            .addQueryParameter("flight_iata", designator)
            .addQueryParameter("api_key", apiKey)
            .build()

    private fun fleetUrl(registration: String): HttpUrl =
        AIRLABS_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("fleets")
            .addQueryParameter("reg_number", registration)
            .addQueryParameter("api_key", apiKey)
            .build()

    private fun delaysUrl(iata: String): HttpUrl =
        AIRLABS_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("delays")
            .addQueryParameter("dep_iata", iata)
            .addQueryParameter("type", "departures")
            .addQueryParameter("delay", MIN_REPORTED_DELAY_MINUTES.toString())
            .addQueryParameter("api_key", apiKey)
            .build()

    private fun routesUrl(designator: String, departureIata: String?): HttpUrl {
        val builder = AIRLABS_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("routes")
            .addQueryParameter("flight_iata", designator)
            .addQueryParameter("api_key", apiKey)
        if (departureIata != null) builder.addQueryParameter("dep_iata", departureIata)
        return builder.build()
    }

    private fun readBoundedBody(response: Response): String? {
        val body = response.body
        if (body.contentLength() > MAX_BODY_BYTES) return null
        val source = body.source()
        return try {
            if (source.request(MAX_BODY_BYTES + 1)) null else source.readUtf8()
        } catch (e: IOException) {
            null
        }
    }
}

private fun sameDepartureDay(live: Long?, schedule: Long?): Boolean {
    if (live == null || schedule == null) return false
    if (live == schedule) return true
    return Instant.ofEpochMilli(live).atOffset(ZoneOffset.UTC).toLocalDate() ==
        Instant.ofEpochMilli(schedule).atOffset(ZoneOffset.UTC).toLocalDate()
}

private sealed interface FetchOutcome {
    data class Body(val body: String) : FetchOutcome
    data class Failed(val result: FlightStatusResult.Failure) : FetchOutcome
}

private fun httpLevelFailure(code: Int): FlightStatusResult.Failure? = when {
    code == 401 || code == 403 -> FlightStatusResult.Failure("AirLabs API key invalid", retryable = false)
    code == 429 -> FlightStatusResult.Failure("AirLabs quota exceeded", retryable = false)
    code in 500..599 -> FlightStatusResult.Failure("AirLabs server error", retryable = true)
    code !in 200..299 -> FlightStatusResult.Failure("AirLabs request failed", retryable = false)
    else -> null
}

private fun bodyErrorFailure(code: String): FlightStatusResult.Failure = when (code) {
    in QUOTA_ERROR_CODES -> FlightStatusResult.Failure("AirLabs quota exceeded", retryable = false)
    in AUTH_ERROR_CODES -> FlightStatusResult.Failure("AirLabs API key invalid", retryable = false)
    "internal_error" -> FlightStatusResult.Failure("AirLabs server error", retryable = true)
    else -> FlightStatusResult.Failure("AirLabs request failed", retryable = false)
}

/**
 * AirLabs echoes the request (api_key included) back in a "request" block. Strip it before the
 * body is parsed or retained anywhere, so the key is never carried past this point.
 */
private fun stripRequestBlock(body: String): String {
    val element = try {
        Json.parseToJsonElement(body)
    } catch (e: SerializationException) {
        return body
    } catch (e: IllegalArgumentException) {
        return body
    }
    val obj = element as? JsonObject ?: return body
    if (!obj.containsKey("request")) return body
    return JsonObject(obj.filterKeys { it != "request" }).toString()
}
