package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.RouteResult
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AircraftRecord
import com.crpakala.commutewidget.data.AirportDelayStats
import com.crpakala.commutewidget.data.AirportDeparture
import com.crpakala.commutewidget.data.AirportLocation
import com.crpakala.commutewidget.data.AirportSnapshot
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.schedule.minuteOfDayFor
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Pure inputs/outputs for the airport-mode leg of the refresh pipeline. The suspending
 * orchestration (calendar read, geocode, Routes, Static Maps, persistence) lives in
 * [CommuteRefresher.performAirportRefresh]; everything here is a pure function of its arguments so
 * it can be tested without Android, exactly like [calendarPlainEventSnapshot] and
 * [failureSnapshot] are for the calendar leg.
 */

/**
 * Departure airport as shown on the widget: the geocoded airport's own name when one has been
 * resolved (so the card reads "Kempegowda International Airport" rather than the calendar's
 * "Bengaluru BLR"), else the parsed name, else the IATA code, else a generic label.
 */
internal fun airportDisplayName(flight: FlightEvent, geocodedName: String? = null): String =
    geocodedName?.takeIf { it.isNotBlank() }
        ?: flight.departureAirportName?.takeIf { it.isNotBlank() }
        ?: flight.departureIata?.takeIf { it.isNotBlank() }
        ?: "Airport"

/**
 * The ordered geocoder queries for the departure airport, each paired with the per-airport cache
 * key its answer is stored under (empty when the answer is not about an IATA code and so has
 * nowhere permanent to live). Most specific first:
 *
 * 1. `BLR airport terminal 2`, cached under `BLR/T2`, when both the code and a terminal are known.
 * 2. `BLR airport`, cached under `BLR`.
 * 3. `<parsed airport name> airport`.
 * 4. The calendar event's own location text, last of all - it is the string that sent the owner to
 *    Bengaluru city centre, and it only earns a query when nothing better exists.
 *
 * Empty when the flight carries no code, no name and no location text, which is the one case
 * airport mode cannot route at all.
 */
internal fun airportGeocodeQueries(flight: FlightEvent, status: FlightStatus?): List<Pair<String, String>> {
    val iata = normalizedIata(flight.departureIata ?: status?.departureIata)
    val terminal = normalizedTerminal(status?.departureTerminal ?: flight.departureTerminal)
    return buildList {
        if (iata != null) {
            if (terminal != null) {
                add("$iata/$terminal" to "$iata airport terminal ${terminal.drop(1)}")
            }
            add(iata to "$iata airport")
        }
        flight.departureAirportName?.takeIf { it.isNotBlank() }?.let { add("" to "$it airport") }
        flight.locationText?.takeIf { it.isNotBlank() }?.let { add("" to it) }
    }
}

/**
 * Google geocodes one airport refresh may spend. Two is exactly the departure and the arrival
 * airport of the flight on screen, so a refresh can never walk a whole itinerary.
 */
internal const val AIRPORT_GEOCODE_BUDGET_PER_REFRESH = 2

/** How long a failed airport geocode is remembered before the code is worth another query. */
internal const val AIRPORT_GEOCODE_RETRY_MILLIS = 24 * 60 * 60_000L

/**
 * How far a terminal geocode may land from the airport it claims to be a terminal of. Past this
 * the geocoder has matched a street or a suburb of the same name rather than the terminal, and the
 * plainer `<code> airport` query is the better answer. Only checked when the plain query's own
 * result is already cached; the first terminal lookup of an airport has nothing to check against.
 */
internal const val AIRPORT_TERMINAL_MAX_OFFSET_METERS = 8_000.0

/** An IATA code in the shape the per-IATA caches are keyed by, or null when there is none. */
internal fun normalizedIata(iata: String?): String? = iata?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

/**
 * A terminal in the shape the per-airport cache keys it by: `2`, `T2` and `Terminal 2` all become
 * `T2`. Null when there is no terminal, or when what is there is not a terminal designation at
 * all, in which case the airport resolves without one.
 */
internal fun normalizedTerminal(terminal: String?): String? {
    val compact = terminal?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
    val body = compact.removePrefix("TERMINAL")
    val token = if (body.length > 1 && body.first() == 'T') body.drop(1) else body
    return if (token.isEmpty() || token == "T" || token.length > 3) null else "T$token"
}

/**
 * The place name out of a geocoder's formatted address: everything before the first comma, which
 * is the airport's own name and not the city, state and postcode that follow it.
 */
internal fun geocodedAirportName(formattedAddress: String, fallback: String): String =
    formattedAddress.substringBefore(',').trim().takeIf { it.isNotEmpty() } ?: fallback

/**
 * Great-circle distance between the two ends of a flight, rounded to the nearest kilometre; null
 * when either end has not been geocoded yet. The flown distance is longer than this, but the
 * difference is well inside what the card is claiming.
 */
internal fun routeDistanceKm(departure: LatLng?, arrival: LatLng?): Int? {
    if (departure == null || arrival == null) {
        return null
    }
    return Math.round(distanceMeters(departure, arrival) / 1_000.0).toInt()
}

/**
 * The great-circle distance between [flight]'s two airports, read out of the per-IATA cache. The
 * calendar's own codes win; a status's codes fill in for a calendar entry that named neither.
 */
internal fun flightRouteDistanceKm(
    cache: Map<String, AirportLocation>,
    flight: FlightEvent,
    status: FlightStatus?,
): Int? = routeDistanceKm(
    airportLocationFor(cache, flight.departureIata ?: status?.departureIata),
    airportLocationFor(cache, flight.arrivalIata ?: status?.arrivalIata),
)

/**
 * The cached position for [iata], or null when the flight carries no IATA code or the cache has
 * not seen that airport yet. Matched case-insensitively, so a code stored from an older write (or
 * a calendar entry that spells it in lower case) still hits.
 */
internal fun airportLocationFor(cache: Map<String, AirportLocation>, iata: String?): LatLng? =
    airportEntryFor(cache, iata)?.let { LatLng(it.lat, it.lng) }

/**
 * The whole cached entry for [key], name included. Keys are plain IATA codes for an airport and
 * `BLR/T2` for one of its terminals, so the two are separate entries and a terminal never answers
 * a question about the airport as a whole.
 */
internal fun airportEntryFor(cache: Map<String, AirportLocation>, key: String?): AirportLocation? {
    val code = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return cache.entries.firstOrNull { it.key.equals(code, ignoreCase = true) }?.value
}

/**
 * Whether airport mode owns this refresh: the calendar is readable at all AND a flight window is
 * open. The single takeover gate - when it holds, the commute window, the ride state machine, the
 * commute probe and the calendar pipeline are all skipped for this refresh.
 */
internal fun airportTakeoverApplies(
    calendarEnabled: Boolean,
    hasCalendarPermission: Boolean,
    selectedCalendarIds: Set<Long>,
    window: AirportWindow?,
): Boolean =
    calendarEnabled && hasCalendarPermission && selectedCalendarIds.isNotEmpty() && window != null

/**
 * Origin label shown under the airport map: the device fix when one arrived, otherwise the saved
 * Home place [eventRouteOrigin] falls back to.
 */
internal fun airportOriginLabel(deviceLocation: ApiResult<LatLng>): String = when (deviceLocation) {
    is ApiResult.Success -> "Current location"
    is ApiResult.Failure -> "Home"
}

/**
 * Title carried by the airport leave-by notification. Also half of its dedup identity (see
 * [com.crpakala.commutewidget.data.eventIdentityKey]), so it must stay stable across refreshes
 * for one flight - it is derived from the flight alone, never from the clock or the route.
 */
internal fun airportLeaveByTitle(flight: FlightEvent): String =
    "${flight.designator} - leave for ${airportDisplayName(flight)}"

/**
 * The [SnapshotMode.AIRPORT] snapshot for one refresh. [route] null covers every degraded outcome
 * (REACHED, which deliberately spends no route quota at all, and a geocode/Routes/Static-Maps failure,
 * which still keeps airport mode on screen with working pills): duration, distance and the
 * leave-by all collapse to null/zero and [errorMessage] drives the warning glyph. [best] is only
 * read when it belongs to [window]'s flight, so a stale sampling result from an earlier flight is
 * ignored rather than shown against the wrong departure.
 *
 * The layover fields are copied straight off [window], so the widget can name the inbound flight
 * and the gap without re-deriving either; [routeDistanceKm] comes from the per-IATA airport cache
 * and is null until both of this flight's airports have been geocoded.
 */
internal fun airportSnapshotFrom(
    window: AirportWindow,
    state: AirportState,
    direction: Direction,
    zone: ZoneId,
    nowEpochMillis: Long,
    healthComputation: HealthComputation,
    originLabel: String? = null,
    airport: LatLng? = null,
    airportName: String? = null,
    route: RouteResult? = null,
    mapImagePath: String? = null,
    best: AirportDeparture? = null,
    reachedOffered: Boolean = false,
    routeDistanceKm: Int? = null,
    aircraft: AircraftRecord? = null,
    departureDelayStats: AirportDelayStats? = null,
    errorMessage: String? = null,
): CommuteSnapshot {
    val flight = window.flight
    val leaveByMillis = route?.let { airportLeaveByMillis(window.arriveByMillis, it.durationSeconds) }
    val ownBest = best?.takeIf { it.eventId == flight.eventId }
    return CommuteSnapshot(
        direction = direction,
        durationSeconds = route?.durationSeconds ?: 0L,
        durationNoTrafficSeconds = route?.staticDurationSeconds ?: 0L,
        distanceMeters = route?.distanceMeters ?: 0L,
        mapImagePath = mapImagePath,
        fetchedAtEpochMillis = nowEpochMillis,
        lastFetchFailed = errorMessage != null,
        lastErrorMessage = errorMessage,
        destinationLabel = airportDisplayName(flight, airportName),
        destinationLat = airport?.lat,
        destinationLng = airport?.lng,
        leaveByMinuteOfDay = leaveByMillis?.let { minuteOfDayFor(it, zone) },
        mode = SnapshotMode.AIRPORT,
        eventStartEpochMillis = flight.departureMillis,
        healthNudges = healthComputation.healthNudges,
        sleepEstimateMinutes = healthComputation.sleepEstimateMinutes,
        shortSleepDay = healthComputation.shortSleepDay,
        customPillOccurrences = healthComputation.customPillOccurrences,
        airport = AirportSnapshot(
            flight = flight,
            phase = state.phase,
            windowStartMillis = window.windowStartMillis,
            arriveByMillis = window.arriveByMillis,
            originLabel = originLabel,
            airportLat = airport?.lat,
            airportLng = airport?.lng,
            travelMinutes = route?.let { ceil(it.durationSeconds / 60.0).toInt() },
            distanceMeters = route?.distanceMeters?.toInt(),
            leaveByMillis = leaveByMillis,
            bestDepartureMillis = ownBest?.bestDepartureMillis,
            bestTravelMinutes = ownBest?.let { ceil(it.bestDurationSeconds / 60.0).toInt() },
            status = state.lastStatus,
            statusFetchedAtMillis = state.lastStatusFetchedAtMillis,
            statusError = state.lastStatusError,
            reachedOffered = reachedOffered,
            layover = window.layover,
            layoverFromDesignator = window.connectionFrom?.designator,
            layoverFromArrivalMillis = window.connectionFrom?.arrivalMillis,
            routeDistanceKm = routeDistanceKm,
            aircraft = aircraft,
            departureDelayStats = departureDelayStats,
        ),
    )
}
