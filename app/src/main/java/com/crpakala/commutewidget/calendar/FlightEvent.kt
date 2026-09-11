package com.crpakala.commutewidget.calendar

import kotlinx.serialization.Serializable

@Serializable
data class FlightEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val airlineCode: String,
    val flightNumber: String,
    val departureMillis: Long,
    val arrivalMillis: Long,
    val locationText: String?,
    val departureAirportName: String?,
    val departureIata: String?,
    val arrivalAirportName: String?,
    val arrivalIata: String?,
    val confirmationNumber: String?,
    val seat: String?,
    val departureTerminal: String?,
    val arrivalTerminal: String?,
    /** Departure clock time exactly as the description's route line printed it, "11:35", or null. */
    val departureLocalHm: String? = null,
    /** Arrival clock time exactly as the description's route line printed it, "19:00", or null. */
    val arrivalLocalHm: String? = null,
) {
    val designator: String get() = airlineCode + flightNumber
}
