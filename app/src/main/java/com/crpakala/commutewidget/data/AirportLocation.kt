package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * An airport's geocoded position, cached under its IATA code. Airports do not move, so
 * unlike the single-entry [Place] geocode cache (which is overwritten by whichever event routed
 * last) this map is kept forever: the second flight out of MEL costs no geocode at all.
 */
@Serializable
data class AirportLocation(
    val iata: String,
    val name: String,
    val lat: Double,
    val lng: Double,
)

private val airportGeocodeFailureMapSerializer =
    MapSerializer(String.serializer(), Long.serializer())

/**
 * When each IATA code's airport geocode last failed, so a code the geocoder has no answer for is
 * not retried on every refresh. Kept apart from [AirportLocation] because a failure is a fact
 * about the last attempt, not about the airport.
 */
fun encodeAirportGeocodeFailures(value: Map<String, Long>): String =
    commuteJson.encodeToString(airportGeocodeFailureMapSerializer, value)

fun decodeAirportGeocodeFailures(json: String?): Map<String, Long> {
    if (json.isNullOrBlank()) {
        return emptyMap()
    }
    return runCatching {
        commuteJson.decodeFromString(airportGeocodeFailureMapSerializer, json)
    }.getOrElse { emptyMap() }
}

private val airportLocationMapSerializer =
    MapSerializer(String.serializer(), AirportLocation.serializer())

fun encodeAirportLocations(value: Map<String, AirportLocation>): String =
    commuteJson.encodeToString(airportLocationMapSerializer, value)

fun decodeAirportLocations(json: String?): Map<String, AirportLocation> {
    if (json.isNullOrBlank()) {
        return emptyMap()
    }
    return runCatching {
        commuteJson.decodeFromString(airportLocationMapSerializer, json)
    }.getOrElse { emptyMap() }
}
