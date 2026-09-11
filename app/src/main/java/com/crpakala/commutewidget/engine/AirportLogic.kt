package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.FlightStatusResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AirportPhase
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.FlightPreview
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.schedule.statusTickMillisFor
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure decision logic for airport mode. A flight moves through OFFERED (Leave by, Best and
 * To Airport pills over the airport map), RIDING (To Airport tapped, navigation handed to Google
 * Maps) and REACHED (Reached tapped, the flight card replaces the map). A connecting flight
 * (see [connectionBefore]) starts in LAYOVER instead: the traveller is already at the airport, so
 * that window shows the flight card and nothing else until its own Reached tap.
 * Airport mode opens at T-5h and closes only on an explicit dismiss, except that a later flight
 * takes over once it enters its own window and the earlier one has departed.
 * Every function here is a pure decision or transition with no I/O, no clock reads and no
 * persistence - callers own reading and writing state and running the actual network calls.
 */

/** Floor on how long the window stays open before the arrive-by target, whatever the two settings say. */
internal const val AIRPORT_MIN_WINDOW_MINUTES = 30

/**
 * Longest gap between two flights that still counts as one connection. Past this the traveller
 * leaves the airport and the second flight is an ordinary departure again, with its own map and
 * its own To Airport pill.
 */
internal const val LAYOVER_MAX_MILLIS = 10 * 60 * 60_000L

/** Mean Earth radius (IUGG), metres. */
private const val EARTH_RADIUS_METERS = 6_371_008.8

/**
 * One flight's airport-mode window: when the pills open and when the user must be at the airport.
 * [layover] marks a window the traveller spends inside an airport rather than travelling to one,
 * with [connectionFrom] naming the flight that landed them there.
 */
internal data class AirportWindow(
    val flight: FlightEvent,
    val windowStartMillis: Long,
    val arriveByMillis: Long,
    val layover: Boolean = false,
    val connectionFrom: FlightEvent? = null,
)

/**
 * The flight the traveller connects from, or null when [flight] is not a connection: the latest
 * flight in [flights] that departs before [flight], is not [flight] itself, and lands no earlier
 * than [flight]'s departure and no more than [LAYOVER_MAX_MILLIS] before it. A negative gap means
 * the two overlap, which is a calendar the traveller cannot fly and never a connection.
 */
internal fun connectionBefore(flight: FlightEvent, flights: List<FlightEvent>): FlightEvent? = flights
    .filter { it.eventId != flight.eventId && it.departureMillis < flight.departureMillis }
    .filter { flight.departureMillis - it.arrivalMillis in 0L..LAYOVER_MAX_MILLIS }
    .maxByOrNull { it.departureMillis }

/** Every flight in [flights] that connects from [flight], per [connectionBefore]. */
internal fun connectionsAfter(flight: FlightEvent, flights: List<FlightEvent>): List<FlightEvent> = flights
    .filter { it.eventId != flight.eventId && connectionBefore(it, flights)?.eventId == flight.eventId }

/**
 * The dismissed set after Done on [flight]. Done on a leg means "show me the next one", so any
 * connecting leg the owner dismissed earlier is un-dismissed here; otherwise a stray Done on the
 * layover card would leave the connection unreachable until it departed.
 */
internal fun dismissedAfterDone(dismissed: Set<Long>, flight: FlightEvent, flights: List<FlightEvent>): Set<Long> =
    dismissed + flight.eventId - connectionsAfter(flight, flights).map { it.eventId }.toSet()

/**
 * The [AirportWindow] for [flight]: pills open [pillLeadMinutes] before departure, arrival target is
 * [arriveAheadMinutes] before it. The two settings are independent, so they can be set to cross
 * (lead 60 with arrive-ahead 360 would put arrive-by five hours before the window even opened);
 * the lead is therefore floored at [AIRPORT_MIN_WINDOW_MINUTES] ahead of the arrive-by target so
 * the pills always get a usable window rather than a negative one.
 */
internal fun airportWindowFor(
    flight: FlightEvent,
    pillLeadMinutes: Int,
    arriveAheadMinutes: Int,
    flights: List<FlightEvent> = emptyList(),
): AirportWindow {
    val effectiveLead = max(pillLeadMinutes, arriveAheadMinutes + AIRPORT_MIN_WINDOW_MINUTES)
    val connection = connectionBefore(flight, flights)
    return AirportWindow(
        flight = flight,
        // A layover window opens when the inbound flight lands, not on the pill lead: the lead is
        // travel time to an airport the traveller is about to be standing in.
        windowStartMillis = connection?.arrivalMillis
            ?: (flight.departureMillis - effectiveLead * 60_000L),
        arriveByMillis = flight.departureMillis - arriveAheadMinutes * 60_000L,
        layover = connection != null,
        connectionFrom = connection,
    )
}

/**
 * The flight that owns the widget now, or null when airport mode is inactive.
 * A candidate is a flight whose window is open ([airportWindowIsOpen]) and that has not been
 * dismissed. Dismissal is
 * remembered two ways: [dismissedEventIds] carries every flight the user has ever tapped Done on
 * (so dismissing flight A then flight B of a connecting itinerary cannot resurrect A, which a
 * single stored state could not express), and [state]'s own flag covers the active flight.
 * Candidates are walked in departure order and a departed one is skipped only when a later
 * candidate can take over, so a flight that has left with nothing behind it stays on screen until
 * the user dismisses it - the calendar reader's own lookback bounds how long that can last.
 */
internal fun selectActiveFlight(
    flights: List<FlightEvent>,
    state: AirportState?,
    nowEpochMillis: Long,
    pillLeadMinutes: Int,
    arriveAheadMinutes: Int,
    dismissedEventIds: Set<Long>,
): AirportWindow? {
    val candidates = flights
        .distinctBy { it.eventId to it.departureMillis }
        .sortedBy { it.departureMillis }
        .filter { it.eventId !in dismissedEventIds && !isAirportDismissed(state, it) }
        .map { airportWindowFor(it, pillLeadMinutes, arriveAheadMinutes, flights) }
        .filter { airportWindowIsOpen(it, state, nowEpochMillis, dismissedEventIds) }
    return candidates.filterIndexed { index, candidate ->
        val departed = nowEpochMillis >= effectiveDepartureMillis(candidate.flight, state)
        !(departed && index < candidates.lastIndex)
    }.firstOrNull()
}

/**
 * True when [window] is open for business. Ordinary windows open on the clock alone. A layover
 * window also opens the moment the traveller taps Done on the flight they connect from, whether or
 * not that flight has landed: Done is the owner saying they are finished with that leg, so the
 * connecting leg's card is due now rather than at an arrival that may still be hours away (and that
 * a diversion or a missed status fetch may never move past).
 */
internal fun airportWindowIsOpen(
    window: AirportWindow,
    state: AirportState?,
    nowEpochMillis: Long,
    dismissedEventIds: Set<Long>,
): Boolean {
    if (nowEpochMillis >= window.windowStartMillis) {
        return true
    }
    val inbound = window.connectionFrom?.takeIf { window.layover } ?: return false
    return inbound.eventId in dismissedEventIds || isAirportDismissed(state, inbound)
}

private fun isAirportDismissed(state: AirportState?, flight: FlightEvent): Boolean =
    state != null && state.eventId == flight.eventId && state.dismissed

/**
 * Stored state for [flight], or a fresh state when the stored one belongs to another flight. A
 * fresh state starts in OFFERED, or in LAYOVER when [layover] says this window is a connection -
 * there is no journey to offer when the traveller is already inside the airport. A fresh state is
 * seeded from [preview] when the calendar card's flight row already fetched a status for this same
 * flight: the two caches hold the same AirLabs answer, so opening the airport window inherits it
 * instead of paying for it again. A preview for another flight is ignored.
 */
internal fun resolveAirportState(
    state: AirportState?,
    flight: FlightEvent,
    zone: ZoneId,
    preview: FlightPreview? = null,
    layover: Boolean = false,
): AirportState =
    if (state != null && state.eventId == flight.eventId) {
        state
    } else {
        val seed = preview?.takeIf { it.eventId == flight.eventId }
        AirportState(
            eventId = flight.eventId,
            localDate = airportLocalDate(flight, zone),
            phase = if (layover) AirportPhase.LAYOVER else AirportPhase.OFFERED,
            lastStatus = seed?.status,
            lastStatusFetchedAtMillis = seed?.fetchedAtMillis,
            lastStatusError = seed?.error,
        )
    }

/** Local date of [flight]'s scheduled departure in [zone], yyyy-MM-dd. Also the date sent to the flight status API. */
internal fun airportLocalDate(flight: FlightEvent, zone: ZoneId): String =
    Instant.ofEpochMilli(flight.departureMillis).atZone(zone).toLocalDate().toString()

/**
 * True when the map's Reached pill should be offered. RIDING always offers it: the owner is on the
 * way, and ending the map to open the full flight card must not wait on a clock or a device fix.
 * OFFERED offers it once the arrive-by target has passed - before that there is nothing to have
 * reached. Never in LAYOVER or REACHED, where the map is gone - a layover card carries its own
 * pill ([airportShowsLayoverReachedPill]) instead.
 *
 * [deviceLocation] and [airportLocation] fed the old within-3-km rule for RIDING, which an
 * unconditional RIDING subsumes. They are kept in the signature so the refresher's call sites,
 * which hold both, do not have to be rewritten if a later rule needs them again.
 */
internal fun shouldShowAirportReached(
    state: AirportState,
    window: AirportWindow,
    nowEpochMillis: Long,
    deviceLocation: LatLng?,
    airportLocation: LatLng?,
): Boolean = when (state.phase) {
    AirportPhase.RIDING -> true
    AirportPhase.OFFERED -> nowEpochMillis >= window.arriveByMillis
    AirportPhase.LAYOVER, AirportPhase.REACHED -> false
}

/** True while the To Airport pill is on screen. Never in LAYOVER: there is no airport to travel to. */
internal fun airportShowsToAirportPill(phase: AirportPhase): Boolean = when (phase) {
    AirportPhase.OFFERED, AirportPhase.RIDING -> true
    AirportPhase.LAYOVER, AirportPhase.REACHED -> false
}

/**
 * True while the Leave by and Best pills are on screen. OFFERED only: once the owner has tapped
 * To Airport the ride has started, and a leave-by instant that has already passed is noise on a
 * card that is now about the journey in progress.
 */
internal fun airportShowsLeaveByAndBest(phase: AirportPhase): Boolean = phase == AirportPhase.OFFERED

/**
 * True while the airport map is on screen, and so the only phase that spends Static Maps quota.
 * OFFERED deliberately does not: it runs the Routes probe that feeds Leave by and Best, but the
 * owner has not asked to travel yet, so no map is built or downloaded until the To Airport tap.
 */
internal fun airportLoadsMap(phase: AirportPhase): Boolean = when (phase) {
    AirportPhase.RIDING -> true
    AirportPhase.OFFERED, AirportPhase.LAYOVER, AirportPhase.REACHED -> false
}

/** True while the flight card replaces the map. */
internal fun airportShowsFlightCard(phase: AirportPhase): Boolean = when (phase) {
    AirportPhase.LAYOVER, AirportPhase.REACHED -> true
    AirportPhase.OFFERED, AirportPhase.RIDING -> false
}

/**
 * True while the flight card carries its own Reached pill. Only LAYOVER does: that card is on
 * screen before the traveller has said they are at the gate, and the tap is what ends the layover.
 */
internal fun airportShowsLayoverReachedPill(phase: AirportPhase): Boolean = phase == AirportPhase.LAYOVER

/**
 * To Airport tap: OFFERED or RIDING becomes RIDING and stamps the first tap only; LAYOVER and
 * REACHED are unchanged (neither shows the pill, so neither can be tapped).
 */
internal fun applyAirportRideTap(state: AirportState, nowEpochMillis: Long): AirportState = when (state.phase) {
    AirportPhase.OFFERED, AirportPhase.RIDING -> state.copy(
        phase = AirportPhase.RIDING,
        rideTappedAtMillis = state.rideTappedAtMillis ?: nowEpochMillis,
    )
    AirportPhase.LAYOVER, AirportPhase.REACHED -> state
}

/** Reached tap: any phase becomes REACHED and stamps the first tap only. */
internal fun applyAirportReachedTap(state: AirportState, nowEpochMillis: Long): AirportState =
    state.copy(
        phase = AirportPhase.REACHED,
        reachedAtMillis = state.reachedAtMillis ?: nowEpochMillis,
    )

/** Done tap: the flight is dismissed and airport mode releases the widget, phase left as it was. */
internal fun applyAirportDismiss(state: AirportState): AirportState = state.copy(dismissed = true)

/**
 * [fresh] with every null STATIC field filled from [previous]. A fetch replaces the cached status
 * wholesale, and AirLabs answers the same leg with different amounts of detail from one call to the
 * next: the `flight` endpoint only names the airframe, the manufacturer, the serial, the engines
 * and the build year while an ADS-B match is live, and `schedules` alone names none of them, the
 * airline, the airport names or the countries. Replacing wholesale therefore blanked the aircraft,
 * fleet and airline lines mid-flight, which is what the owner watched happen at 2:01.
 *
 * Static means a fact about the airframe, the carrier, the two airports or the booked itinerary -
 * it does not change while the card is on screen, so an older answer is still the right one. The
 * status word, every time, both delays, every live telemetry field, the airline's own update stamp
 * and the endpoint the answer came from are all deliberately absent: those are what the fetch was
 * for, and carrying a stale one forward would print a fact that is no longer true.
 *
 * A [previous] for another designator is ignored outright.
 */
internal fun carryForwardStaticFields(fresh: FlightStatus, previous: FlightStatus?): FlightStatus {
    if (previous == null || previous.designator != fresh.designator) {
        return fresh
    }
    return fresh.copy(
        departureTerminal = fresh.departureTerminal ?: previous.departureTerminal,
        departureGate = fresh.departureGate ?: previous.departureGate,
        departureCheckInDesk = fresh.departureCheckInDesk ?: previous.departureCheckInDesk,
        arrivalTerminal = fresh.arrivalTerminal ?: previous.arrivalTerminal,
        arrivalGate = fresh.arrivalGate ?: previous.arrivalGate,
        arrivalBaggageBelt = fresh.arrivalBaggageBelt ?: previous.arrivalBaggageBelt,
        aircraftModel = fresh.aircraftModel ?: previous.aircraftModel,
        aircraftRegistration = fresh.aircraftRegistration ?: previous.aircraftRegistration,
        operatingAirline = fresh.operatingAirline ?: previous.operatingAirline,
        codeshareOf = fresh.codeshareOf ?: previous.codeshareOf,
        durationMinutes = fresh.durationMinutes ?: previous.durationMinutes,
        airlineName = fresh.airlineName ?: previous.airlineName,
        departureAirportName = fresh.departureAirportName ?: previous.departureAirportName,
        departureCity = fresh.departureCity ?: previous.departureCity,
        arrivalAirportName = fresh.arrivalAirportName ?: previous.arrivalAirportName,
        arrivalCity = fresh.arrivalCity ?: previous.arrivalCity,
        departureIata = fresh.departureIata ?: previous.departureIata,
        arrivalIata = fresh.arrivalIata ?: previous.arrivalIata,
        aircraftIcao = fresh.aircraftIcao ?: previous.aircraftIcao,
        departureUtcOffsetMinutes = fresh.departureUtcOffsetMinutes ?: previous.departureUtcOffsetMinutes,
        arrivalUtcOffsetMinutes = fresh.arrivalUtcOffsetMinutes ?: previous.arrivalUtcOffsetMinutes,
        departureCountry = fresh.departureCountry ?: previous.departureCountry,
        arrivalCountry = fresh.arrivalCountry ?: previous.arrivalCountry,
        airlineIata = fresh.airlineIata ?: previous.airlineIata,
        airlineIcao = fresh.airlineIcao ?: previous.airlineIcao,
        aircraftManufacturer = fresh.aircraftManufacturer ?: previous.aircraftManufacturer,
        aircraftSerialNumber = fresh.aircraftSerialNumber ?: previous.aircraftSerialNumber,
        aircraftEngineType = fresh.aircraftEngineType ?: previous.aircraftEngineType,
        aircraftEngineCount = fresh.aircraftEngineCount ?: previous.aircraftEngineCount,
        aircraftBuiltYear = fresh.aircraftBuiltYear ?: previous.aircraftBuiltYear,
        aircraftAgeYears = fresh.aircraftAgeYears ?: previous.aircraftAgeYears,
    )
}

/**
 * Folds a status fetch into the state: a success replaces the cached status, a failure keeps the
 * last good one and records the message. The replacement runs through [carryForwardStaticFields],
 * so an answer that came back thinner than the one it replaces keeps the airframe, the airline,
 * the airports and the offsets the card was already showing.
 */
internal fun applyFlightStatusResult(
    state: AirportState,
    result: FlightStatusResult,
    nowEpochMillis: Long,
): AirportState = when (result) {
    is FlightStatusResult.Success -> state.copy(
        lastStatus = carryForwardStaticFields(result.status, state.lastStatus),
        lastStatusFetchedAtMillis = nowEpochMillis,
        lastStatusError = null,
    )
    is FlightStatusResult.Failure -> state.copy(lastStatusError = result.message)
}

/**
 * True when a tap on the flight card itself may spend a status call: a key is configured and the
 * last fetch is at least [MANUAL_FLIGHT_STATUS_DEBOUNCE_MILLIS] old. The card renders only in
 * LAYOVER and REACHED, so no phase check is needed - the debounce is the whole point, so a double tap (or a
 * tap landing on a status the Reached tap just paid for) cannot spend a second query.
 */
internal fun shouldManualFetchFlightStatus(
    state: AirportState,
    apiKey: String,
    nowEpochMillis: Long,
    minIntervalMillis: Long = MANUAL_FLIGHT_STATUS_DEBOUNCE_MILLIS,
): Boolean {
    if (apiKey.isBlank()) {
        return false
    }
    val lastFetchedAt = state.lastStatusFetchedAtMillis ?: return true
    return nowEpochMillis - lastFetchedAt >= minIntervalMillis
}

/** Debounce between two manual flight-card taps. */
internal const val MANUAL_FLIGHT_STATUS_DEBOUNCE_MILLIS = 2 * 60_000L

/**
 * True once the flight has finished: nothing a further call could return would change the card, so
 * neither the periodic fetch nor the boundary tick that drives it is worth an AirLabs query.
 */
internal fun flightStatusIsFinal(status: String?): Boolean = status?.lowercase() in FINAL_FLIGHT_STATUSES

private val FINAL_FLIGHT_STATUSES = setOf("landed", "cancelled")

/**
 * True when a refresh (not a tap) may spend a flight status call: the card is showing (LAYOVER or
 * REACHED), a key is configured, the flight has not finished, and the last fetch is at least
 * [minIntervalMillis] old. The default interval is a minute short of that phase's own tick (see
 * [statusTickMillisFor]) because WorkManager fires the tick approximately - a wake-up a few seconds
 * early must not be skipped and then made to wait a further full tick for its status. A layover
 * card ticks on the hour rather than the half hour: nothing on it moves while the traveller is
 * sitting at the gate of a flight that has not started boarding.
 */
internal fun shouldAutoFetchFlightStatus(
    state: AirportState,
    apiKey: String,
    nowEpochMillis: Long,
    minIntervalMillis: Long = statusTickMillisFor(state.phase) - 60_000L,
): Boolean {
    if (!airportShowsFlightCard(state.phase) || apiKey.isBlank()) {
        return false
    }
    if (flightStatusIsFinal(state.lastStatus?.status)) {
        return false
    }
    val lastFetchedAt = state.lastStatusFetchedAtMillis ?: return true
    return nowEpochMillis - lastFetchedAt >= minIntervalMillis
}

/** Instant to leave to reach the airport by [arriveByMillis] given a [durationSeconds] drive. */
internal fun airportLeaveByMillis(arriveByMillis: Long, durationSeconds: Long): Long =
    arriveByMillis - durationSeconds * 1_000L

/**
 * Departure to treat as real: a live estimate later than the schedule, when the stored state
 * belongs to this flight. An earlier estimate is ignored - a flight is not considered departed
 * before its scheduled time on the strength of a moved-up estimate.
 */
internal fun effectiveDepartureMillis(flight: FlightEvent, state: AirportState?): Long {
    if (state == null || state.eventId != flight.eventId) {
        return flight.departureMillis
    }
    val status = state.lastStatus ?: return flight.departureMillis
    return effectiveDepartureMillis(flight, status)
}

/** The same decision from a status held directly, for a caller that has just fetched one. */
internal fun effectiveDepartureMillis(flight: FlightEvent, status: FlightStatus): Long {
    val estimated = status.departureEstimatedUtcMillis ?: return flight.departureMillis
    return if (estimated > flight.departureMillis) estimated else flight.departureMillis
}

/** Great-circle distance between [a] and [b] in metres (haversine). */
internal fun distanceMeters(a: LatLng, b: LatLng): Double {
    val latA = Math.toRadians(a.lat)
    val latB = Math.toRadians(b.lat)
    val halfDLat = Math.toRadians(b.lat - a.lat) / 2.0
    val halfDLng = Math.toRadians(b.lng - a.lng) / 2.0
    val h = sin(halfDLat) * sin(halfDLat) + cos(latA) * cos(latB) * sin(halfDLng) * sin(halfDLng)
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(min(1.0, h)))
}
