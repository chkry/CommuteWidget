package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

/**
 * OFFERED and RIDING drive the airport map and its pills. LAYOVER is the window of a connecting
 * flight: the traveller is already inside an airport, so there is nothing to navigate to and only
 * the flight card is shown, until the Reached tap moves it on. REACHED is the flight card once the
 * airport has been reached. Stored by name, so the order here is free to change.
 */
@Serializable
enum class AirportPhase { OFFERED, RIDING, LAYOVER, REACHED }

@Serializable
data class AirportState(
    val eventId: Long,
    val localDate: String,
    val phase: AirportPhase = AirportPhase.OFFERED,
    val dismissed: Boolean = false,
    val rideTappedAtMillis: Long? = null,
    val reachedAtMillis: Long? = null,
    val lastStatus: FlightStatus? = null,
    val lastStatusFetchedAtMillis: Long? = null,
    val lastStatusError: String? = null,
)

fun encodeAirportState(value: AirportState): String =
    commuteJson.encodeToString(AirportState.serializer(), value)

fun decodeAirportState(json: String?): AirportState? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(AirportState.serializer(), json)
    }.getOrNull()
}
