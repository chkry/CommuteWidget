package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

@Serializable
data class FlightStatus(
    val designator: String,
    val status: String,
    val departureScheduledUtcMillis: Long?,
    val departureEstimatedUtcMillis: Long?,
    val departureTerminal: String?,
    val departureGate: String?,
    val departureCheckInDesk: String?,
    val arrivalScheduledUtcMillis: Long?,
    val arrivalEstimatedUtcMillis: Long?,
    val arrivalTerminal: String?,
    val arrivalGate: String?,
    val arrivalBaggageBelt: String?,
    val aircraftModel: String?,
    val aircraftRegistration: String?,
    val operatingAirline: String?,
    val codeshareOf: String?,
    val lastUpdatedUtcMillis: Long?,
    val departureActualUtcMillis: Long? = null,
    val arrivalActualUtcMillis: Long? = null,
    val departureDelayedMinutes: Int? = null,
    val arrivalDelayedMinutes: Int? = null,
    val durationMinutes: Int? = null,
    val airlineName: String? = null,
    val departureAirportName: String? = null,
    val departureCity: String? = null,
    val arrivalAirportName: String? = null,
    val arrivalCity: String? = null,
    val departureIata: String? = null,
    val arrivalIata: String? = null,
    val aircraftIcao: String? = null,
    val percentComplete: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Int? = null,
    val speedKmh: Int? = null,
    val headingDegrees: Int? = null,
    val source: String? = null,
    /** Minutes the departure airport is ahead of UTC on the day of the flight, e.g. 330 for BLR. */
    val departureUtcOffsetMinutes: Int? = null,
    /** Minutes the arrival airport is ahead of UTC on the day of the flight, e.g. 480 for SIN. */
    val arrivalUtcOffsetMinutes: Int? = null,
    val departureCountry: String? = null,
    val arrivalCountry: String? = null,
    val airlineIata: String? = null,
    val airlineIcao: String? = null,
    val aircraftManufacturer: String? = null,
    val aircraftSerialNumber: String? = null,
    val aircraftEngineType: String? = null,
    val aircraftEngineCount: Int? = null,
    val aircraftBuiltYear: Int? = null,
    val aircraftAgeYears: Int? = null,
    val verticalSpeedKmh: Int? = null,
    val transponderCode: String? = null,
) {
    /** Readable aircraft: the provider's own model string when it sent one, else the ICAO type code resolved through [aircraftTypeName]. */
    val aircraftDisplayName: String? get() = aircraftModel ?: aircraftTypeName(aircraftIcao)
    val departureDelayMinutes: Int? get() =
        departureDelayedMinutes ?: delay(departureScheduledUtcMillis, departureEstimatedUtcMillis)
    val arrivalDelayMinutes: Int? get() =
        arrivalDelayedMinutes ?: delay(arrivalScheduledUtcMillis, arrivalEstimatedUtcMillis)
    private fun delay(scheduled: Long?, estimated: Long?): Int? =
        if (scheduled == null || estimated == null) null else ((estimated - scheduled) / 60_000L).toInt()
}
