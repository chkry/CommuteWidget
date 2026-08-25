package com.crpakala.commutewidget.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private val geocodingJson = Json { ignoreUnknownKeys = true }
private const val GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"

data class GeocodeHit(val formattedAddress: String, val location: LatLng)

class GeocodingClient(
    private val apiKey: String,
    private val client: OkHttpClient = HttpClients.default,
) {
    suspend fun geocode(address: String): ApiResult<List<GeocodeHit>> {
        val url = GEOCODE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("address", address)
            .addQueryParameter("region", "in")
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).get().build()

        return try {
            client.executeSuspend(request).use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    ApiResult.Failure("Network error")
                } else {
                    parseGeocodeBody(body)
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure("Network error", e)
        }
    }
}

internal fun parseGeocodeBody(body: String): ApiResult<List<GeocodeHit>> {
    return try {
        val decoded = geocodingJson.decodeFromString(GeocodeResponse.serializer(), body)
        when (decoded.status) {
            "OK" -> ApiResult.Success(decoded.results.map { it.toGeocodeHit() })
            "ZERO_RESULTS" -> ApiResult.Success(emptyList())
            "REQUEST_DENIED" -> ApiResult.Failure("API key invalid or Geocoding API not enabled")
            "OVER_QUERY_LIMIT" -> ApiResult.Failure("Geocoding quota exceeded")
            "INVALID_REQUEST" -> ApiResult.Failure("Invalid geocoding request")
            else -> ApiResult.Failure("Geocoding failed")
        }
    } catch (e: SerializationException) {
        ApiResult.Failure("Invalid server response", e)
    }
}

private fun GeocodeResultDto.toGeocodeHit(): GeocodeHit =
    GeocodeHit(formattedAddress = formattedAddress, location = LatLng(geometry.location.lat, geometry.location.lng))

@Serializable
internal data class GeocodeResponse(
    val results: List<GeocodeResultDto> = emptyList(),
    val status: String = "UNKNOWN_ERROR",
)

@Serializable
internal data class GeocodeResultDto(
    @SerialName("formatted_address") val formattedAddress: String = "",
    val geometry: GeocodeGeometryDto = GeocodeGeometryDto(),
)

@Serializable
internal data class GeocodeGeometryDto(val location: GeocodeLocationDto = GeocodeLocationDto())

@Serializable
internal data class GeocodeLocationDto(val lat: Double = 0.0, val lng: Double = 0.0)
