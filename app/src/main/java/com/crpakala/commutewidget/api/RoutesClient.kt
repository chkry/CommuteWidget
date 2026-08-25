package com.crpakala.commutewidget.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

private val routesJson = Json { ignoreUnknownKeys = true }
private val routesJsonMediaType = "application/json".toMediaType()
private const val COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
private const val ROUTES_FIELD_MASK = "routes.duration,routes.staticDuration,routes.distanceMeters," +
    "routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals"

enum class SpeedClass { NORMAL, SLOW, TRAFFIC_JAM, UNKNOWN }

data class SpeedInterval(
    val startPolylinePointIndex: Int,
    val endPolylinePointIndex: Int,
    val speed: SpeedClass,
)

data class RouteResult(
    val durationSeconds: Long,
    val staticDurationSeconds: Long,
    val distanceMeters: Long,
    val encodedPolyline: String,
    val speedIntervals: List<SpeedInterval>,
)

class RoutesClient(
    private val apiKey: String,
    private val client: OkHttpClient = HttpClients.default,
) {
    suspend fun computeRoute(
        origin: LatLng,
        destination: LatLng,
        mode: RouteTravelMode,
    ): ApiResult<RouteResult> {
        val requestJson = routesJson.encodeToString(
            ComputeRoutesRequest.serializer(),
            ComputeRoutesRequest(
                origin = RouteWaypoint(WaypointLocation(LatLngProto(origin.lat, origin.lng))),
                destination = RouteWaypoint(WaypointLocation(LatLngProto(destination.lat, destination.lng))),
                travelMode = mode.name,
                routingPreference = "TRAFFIC_AWARE_OPTIMAL",
                extraComputations = listOf("TRAFFIC_ON_POLYLINE"),
                languageCode = "en",
                units = "METRIC",
            ),
        )

        val httpRequest = Request.Builder()
            .url(COMPUTE_ROUTES_URL)
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("X-Goog-FieldMask", ROUTES_FIELD_MASK)
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody(routesJsonMediaType))
            .build()

        return try {
            client.executeSuspend(httpRequest).use { response -> handleResponse(response) }
        } catch (e: IOException) {
            ApiResult.Failure("Network error", e)
        }
    }

    private fun handleResponse(response: Response): ApiResult<RouteResult> {
        val body = response.body.string()
        if (!response.isSuccessful) {
            return mapErrorResponse(response.code, body)
        }
        return parseComputeRoutesBody(body)
    }
}

internal fun parseComputeRoutesBody(body: String): ApiResult<RouteResult> {
    return try {
        val decoded = routesJson.decodeFromString(ComputeRoutesResponse.serializer(), body)
        val route = decoded.routes.firstOrNull() ?: return ApiResult.Failure("No route found")
        ApiResult.Success(route.toRouteResult())
    } catch (e: SerializationException) {
        ApiResult.Failure("Invalid server response", e)
    }
}

internal fun mapErrorResponse(httpCode: Int, body: String): ApiResult.Failure {
    if (httpCode == 403) {
        return ApiResult.Failure("API key invalid")
    }

    val errorDetail = try {
        routesJson.decodeFromString(RoutesErrorEnvelope.serializer(), body).error
    } catch (e: SerializationException) {
        null
    }
    val message = errorDetail?.message.orEmpty()
    val status = errorDetail?.status.orEmpty()
    val looksLikeKeyProblem = httpCode == 400 &&
        (message.contains("API key", ignoreCase = true) || status.contains("API_KEY", ignoreCase = true))

    return if (looksLikeKeyProblem) {
        ApiResult.Failure("API key invalid")
    } else {
        ApiResult.Failure("Route request failed")
    }
}

internal fun parseDurationSeconds(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return raw.trim().removeSuffix("s").toDoubleOrNull()?.toLong() ?: 0L
}

internal fun parseSpeedClass(raw: String?): SpeedClass = when (raw) {
    "NORMAL" -> SpeedClass.NORMAL
    "SLOW" -> SpeedClass.SLOW
    "TRAFFIC_JAM" -> SpeedClass.TRAFFIC_JAM
    else -> SpeedClass.UNKNOWN
}

private fun RouteDto.toRouteResult(): RouteResult = RouteResult(
    durationSeconds = parseDurationSeconds(duration),
    staticDurationSeconds = parseDurationSeconds(staticDuration),
    distanceMeters = distanceMeters,
    encodedPolyline = polyline?.encodedPolyline.orEmpty(),
    speedIntervals = travelAdvisory?.speedReadingIntervals.orEmpty().map { it.toSpeedInterval() },
)

private fun SpeedReadingIntervalDto.toSpeedInterval(): SpeedInterval = SpeedInterval(
    startPolylinePointIndex = startPolylinePointIndex,
    endPolylinePointIndex = endPolylinePointIndex,
    speed = parseSpeedClass(speed),
)

@Serializable
private data class LatLngProto(val latitude: Double, val longitude: Double)

@Serializable
private data class WaypointLocation(val latLng: LatLngProto)

@Serializable
private data class RouteWaypoint(val location: WaypointLocation)

@Serializable
private data class ComputeRoutesRequest(
    val origin: RouteWaypoint,
    val destination: RouteWaypoint,
    val travelMode: String,
    val routingPreference: String,
    val extraComputations: List<String>,
    val languageCode: String,
    val units: String,
)

@Serializable
internal data class ComputeRoutesResponse(val routes: List<RouteDto> = emptyList())

@Serializable
internal data class RouteDto(
    val duration: String? = null,
    val staticDuration: String? = null,
    val distanceMeters: Long = 0,
    val polyline: PolylineDto? = null,
    val travelAdvisory: TravelAdvisoryDto? = null,
)

@Serializable
internal data class PolylineDto(val encodedPolyline: String = "")

@Serializable
internal data class TravelAdvisoryDto(val speedReadingIntervals: List<SpeedReadingIntervalDto> = emptyList())

@Serializable
internal data class SpeedReadingIntervalDto(
    // The API omits this field entirely from the JSON when its value is the proto3 default (0).
    val startPolylinePointIndex: Int = 0,
    val endPolylinePointIndex: Int = 0,
    val speed: String? = null,
)

@Serializable
private data class RoutesErrorEnvelope(val error: RoutesErrorDetail? = null)

@Serializable
private data class RoutesErrorDetail(
    val code: Int = 0,
    val message: String = "",
    val status: String = "",
)
