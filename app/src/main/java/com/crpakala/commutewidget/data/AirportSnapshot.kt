package com.crpakala.commutewidget.data

import com.crpakala.commutewidget.calendar.FlightEvent
import kotlinx.serialization.Serializable

@Serializable
data class AirportSnapshot(
    val flight: FlightEvent,
    val phase: AirportPhase,
    val windowStartMillis: Long,
    val arriveByMillis: Long,
    val originLabel: String?,
    val airportLat: Double?,
    val airportLng: Double?,
    val travelMinutes: Int?,
    val distanceMeters: Int?,
    val leaveByMillis: Long?,
    val bestDepartureMillis: Long?,
    val bestTravelMinutes: Int?,
    val status: FlightStatus?,
    val statusFetchedAtMillis: Long?,
    val statusError: String?,
    val reachedOffered: Boolean = false,
    val layover: Boolean = false,
    val layoverFromDesignator: String? = null,
    val layoverFromArrivalMillis: Long? = null,
    val routeDistanceKm: Int? = null,
    /** The airframe's own record, cached under [FlightStatus.aircraftRegistration]; LARGE only. */
    val aircraft: AircraftRecord? = null,
    /** How the departure airport is running, cached under its IATA code; LARGE only. */
    val departureDelayStats: AirportDelayStats? = null,
)
