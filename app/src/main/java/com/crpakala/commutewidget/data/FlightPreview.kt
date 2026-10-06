package com.crpakala.commutewidget.data

import com.crpakala.commutewidget.calendar.FlightEvent
import kotlinx.serialization.Serializable

/**
 * The next flight, once it is near, as shown on the calendar card before airport mode opens at T-5h.
 * Exactly one is stored at a time (the flight [com.crpakala.commutewidget.engine.selectPreviewFlight]
 * picks), because the card only ever has room for one flight row and every stored preview costs
 * AirLabs queries to keep fresh.
 *
 * [layoverFromDesignator] and [layoverMinutes] are set when the previewed flight is the second
 * leg of a connection (see [com.crpakala.commutewidget.engine.connectionBefore]), and
 * [routeDistanceKm] is the great-circle distance between the two airports the flight names, both
 * derived from data the row already holds rather than from a query of their own.
 *
 * [status] is the last successful fetch and [error] the last failed one; [fetchedAtMillis] stamps
 * either outcome, so a failure is not retried until the next refresh interval rather than on every
 * refresh. Kept apart from [AirportState] deliberately: that is airport mode's own state machine
 * with its own far more aggressive fetch cadence, and folding a preview into it would resurrect a
 * dismissed flight.
 */
@Serializable
data class FlightPreview(
    val eventId: Long,
    val flight: FlightEvent,
    val status: FlightStatus? = null,
    val fetchedAtMillis: Long? = null,
    val error: String? = null,
    val layoverFromDesignator: String? = null,
    val layoverMinutes: Int? = null,
    val routeDistanceKm: Int? = null,
)

fun encodeFlightPreview(value: FlightPreview): String =
    commuteJson.encodeToString(FlightPreview.serializer(), value)

fun decodeFlightPreview(json: String?): FlightPreview? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(FlightPreview.serializer(), json)
    }.getOrNull()
}
