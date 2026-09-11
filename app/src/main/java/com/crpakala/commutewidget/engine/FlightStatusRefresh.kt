package com.crpakala.commutewidget.engine

import android.content.Context
import com.crpakala.commutewidget.api.FlightStatusClient
import com.crpakala.commutewidget.api.FlightStatusResult
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AIRPORT_DELAY_STATS_MAX_AGE_MILLIS
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.data.SettingsRepository
import java.time.Instant
import java.time.ZoneId

/**
 * Spends one AirLabs status call for [flight] and folds the result into whatever state is stored
 * when the request returns, never into a state captured before it. The request can block for up to
 * 30 seconds, and writing a captured state back would revert a Done tap made in the meantime; a
 * state that has moved to another flight is left alone, because the status belongs to the flight it
 * was requested for. Returns whether the stored state actually changed.
 *
 * The only place the flight status API is called from: the Reached tap, the flight-card tap, and
 * the REACHED leg of [CommuteRefresher.performAirportRefresh]. No state is written before the
 * fetch, so a failed call leaves the last good status on the card. Every one of those callers is
 * inside a card phase, which is what keeps the two enrichment calls below off the map phases.
 *
 * The budget, per flight: the first fetch costs two queries (`schedules` plus `flight`, which is
 * the only source of the airline name, the airport names and the arrival gate before departure) and
 * every later fetch costs one, rising to two again only once the leg is airborne and `flight` is
 * bought for the live position. On top of that a registration costs one `fleets` query once for the
 * life of the install, and the departure airport costs one `delays` query per half hour while the
 * flight is still on the ground.
 */
internal suspend fun refreshFlightStatus(
    context: Context,
    repo: SettingsRepository,
    flight: FlightEvent,
    apiKey: String,
    nowEpochMillis: Long,
): Boolean {
    // Same local date [airportLocalDate] stamps on the state: AirLabs schedules are keyed on the
    // departure airport's day, which is the flight's own departure day in the device zone.
    val localDate = Instant.ofEpochMilli(flight.departureMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val stored = repo.airportState()?.takeIf { it.eventId == flight.eventId }?.lastStatus
    val client = FlightStatusClient(apiKey)
    val result = client.fetch(
        designator = flight.designator,
        localDate = localDate,
        departureIata = flight.departureIata,
        // The airline name only ever arrives from the flight endpoint, so its absence is exactly
        // "this flight has not had its one detail fetch yet".
        includeFlightDetails = stored?.airlineName == null,
    )
    val changed = repo.updateAirportState { current ->
        if (current == null || current.eventId != flight.eventId) {
            current
        } else {
            applyFlightStatusResult(current, result, nowEpochMillis)
        }
    }
    // The calendar card's preview and the airport card are two caches of the same AirLabs answer.
    // A success here is newer than whatever the preview holds for this flight, so it is written
    // back (clearing the stale error the same way [applyFlightStatusResult] does) rather than
    // left to disagree - and to keep [resolveAirportState]'s seed correct if the state is ever
    // rebuilt for this flight.
    if (result is FlightStatusResult.Success) {
        val preview = repo.flightPreview()
        if (preview != null && preview.eventId == flight.eventId) {
            repo.setFlightPreview(
                preview.copy(
                    // The same guard the state fold applies, against the row's own cached answer:
                    // the two caches must not disagree about the airframe after a thin fetch.
                    status = carryForwardStaticFields(result.status, preview.status),
                    fetchedAtMillis = nowEpochMillis,
                    error = null,
                ),
            )
        }
        cacheAircraftRecord(repo, client, result.status, nowEpochMillis)
        cacheDepartureDelayStats(repo, client, flight, result.status, nowEpochMillis)
    }
    return changed
}

/**
 * One `fleets` query the first time an airframe is named, then never again. The record is written
 * even when every detail field came back null (which is what the free plan returns for most
 * airframes), because an empty answer is still the answer for that registration.
 */
private suspend fun cacheAircraftRecord(
    repo: SettingsRepository,
    client: FlightStatusClient,
    status: FlightStatus,
    nowEpochMillis: Long,
) {
    val registration = status.aircraftRegistration?.uppercase() ?: return
    if (repo.aircraftFleet().containsKey(registration)) return
    val record = client.fetchFleet(registration, nowEpochMillis) ?: return
    repo.updateAircraftFleet { it + (registration to record) }
}

/**
 * One `delays` query per departure airport per half hour, and only while the flight is still on the
 * ground with something left to learn: once it has pushed back, or landed or cancelled, how the
 * rest of the board is running is no longer the traveller's problem.
 */
private suspend fun cacheDepartureDelayStats(
    repo: SettingsRepository,
    client: FlightStatusClient,
    flight: FlightEvent,
    status: FlightStatus,
    nowEpochMillis: Long,
) {
    if (flightStatusIsFinal(status.status)) return
    if (nowEpochMillis >= effectiveDepartureMillis(flight, status)) return
    val iata = (status.departureIata ?: flight.departureIata)?.uppercase() ?: return
    val cached = repo.airportDelayStats()[iata]
    if (cached != null && nowEpochMillis - cached.fetchedAtMillis < AIRPORT_DELAY_STATS_MAX_AGE_MILLIS) return
    val stats = client.fetchDepartureDelays(iata, nowEpochMillis) ?: return
    repo.updateAirportDelayStats { it + (iata to stats) }
}
