package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

/**
 * The best-departure sampling result for one flight: the departure instant inside the airport
 * window with the lowest Google-predicted drive time that still arrives by the flight's arrive-by
 * target. Computed at most once per flight (see the engine's AirportDepartureAdvisor).
 */
@Serializable
data class AirportDeparture(
    val eventId: Long,
    val bestDepartureMillis: Long,
    val bestDurationSeconds: Long,
    val sampleCount: Int,
    val computedAtMillis: Long,
)

fun encodeAirportDeparture(value: AirportDeparture): String =
    commuteJson.encodeToString(AirportDeparture.serializer(), value)

fun decodeAirportDeparture(json: String?): AirportDeparture? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(AirportDeparture.serializer(), json)
    }.getOrNull()
}
