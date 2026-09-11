package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.FlightPreview

/**
 * Pure decision logic for the calendar card's flight row: which upcoming flight it names, when that
 * flight is worth an AirLabs query, and what a tap on the row does. The row is resolved for every
 * upcoming flight, dismissed or not and window open or not - it simply is not on screen while
 * airport mode owns the widget, because the calendar card is not rendered then. The only budget
 * that matters throughout is the owner's 1000 lifetime AirLabs queries.
 *
 * No I/O, no clock reads, no persistence - the refresher owns all three.
 */

/**
 * How far ahead the calendar is read for flights. Deliberately far wider than airport mode's own
 * seven days: the row exists to surface a flight that is still weeks out, and it is the same
 * single provider query either way ([com.crpakala.commutewidget.calendar.CalendarReader.upcomingFlights]),
 * so a wider read costs nothing extra. [selectActiveFlight] only ever considers flights whose
 * window has already opened, so the wider list cannot change airport mode's takeover decision.
 */
internal const val FLIGHT_PREVIEW_LOOKAHEAD_DAYS = 45

/** Departure horizon that splits the two refresh cadences below. */
private const val PREVIEW_NEAR_DEPARTURE_MILLIS = 48 * 60 * 60_000L

/** Refresh cadence for a flight more than [PREVIEW_NEAR_DEPARTURE_MILLIS] out: nothing moves this early. */
private const val PREVIEW_FAR_INTERVAL_MILLIS = 72 * 60 * 60_000L

/** Refresh cadence inside [PREVIEW_NEAR_DEPARTURE_MILLIS]: a terminal or a schedule change is now plausible. */
private const val PREVIEW_NEAR_INTERVAL_MILLIS = 24 * 60 * 60_000L

/**
 * The flight the row names: the earliest one still ahead of [nowEpochMillis]. A flight that has
 * already departed is never previewed - the row is about what is coming.
 *
 * Dismissal is deliberately not consulted. Done ends the map and card takeover for a flight
 * ([selectActiveFlight] does honour the dismissed set); it does not mean the owner stopped caring
 * about the flight, and dropping the row would demote today's boarding pass to a bare calendar
 * title while a flight weeks out took the row.
 */
internal fun selectPreviewFlight(
    flights: List<FlightEvent>,
    nowEpochMillis: Long,
): FlightEvent? = flights
    .filter { it.departureMillis > nowEpochMillis }
    .minByOrNull { it.departureMillis }

/**
 * How long a stored preview stays fresh. More than 48 hours from departure a schedule is a
 * timetable entry that will not move, so 72 hours between queries; inside 48 hours the gate,
 * terminal and delay start to become real and it tightens to 24 hours. Exactly 48 hours out
 * resolves to the tighter interval.
 */
internal fun previewRefreshIntervalMillis(flight: FlightEvent, nowEpochMillis: Long): Long =
    if (flight.departureMillis - nowEpochMillis > PREVIEW_NEAR_DEPARTURE_MILLIS) {
        PREVIEW_FAR_INTERVAL_MILLIS
    } else {
        PREVIEW_NEAR_INTERVAL_MILLIS
    }

/**
 * True when this refresh may spend an AirLabs query on [flight]. A blank key spends nothing ever.
 * Otherwise a preview is due when there is none, when the stored one names another flight, when it
 * has never actually been fetched, or when its stamp is a full [previewRefreshIntervalMillis] old.
 *
 * A stored failure carries [FlightPreview.fetchedAtMillis] exactly like a success does, so it
 * counts as fetched: a flight AirLabs has no schedule for is retried on the next interval, never on
 * every refresh, and the error text shown on the row is the one that stands until then.
 */
internal fun shouldFetchPreview(
    existing: FlightPreview?,
    flight: FlightEvent,
    apiKey: String,
    nowEpochMillis: Long,
): Boolean {
    if (apiKey.isBlank()) {
        return false
    }
    if (existing == null || existing.eventId != flight.eventId) {
        return true
    }
    val fetchedAtMillis = existing.fetchedAtMillis ?: return true
    return nowEpochMillis - fetchedAtMillis >= previewRefreshIntervalMillis(flight, nowEpochMillis)
}

/**
 * True when a refresh may spend a query on [flight] for the preview at all, before
 * [shouldFetchPreview]'s cadence gets a say. The one case it rules out is a flight whose airport
 * window is open and that has not been dismissed: airport mode owns the widget for that flight and
 * is already fetching its status on a far tighter cadence, so a preview fetch would buy the same
 * answer twice. Once the owner taps Done the takeover is gone but the row stays, and the preview
 * becomes the only thing keeping that flight's status current - so a dismissed flight in its own
 * window is allowed again.
 */
internal fun previewFetchAllowed(
    flight: FlightEvent,
    dismissedEventIds: Set<Long>,
    pillLeadMinutes: Int,
    arriveAheadMinutes: Int,
    nowEpochMillis: Long,
): Boolean {
    val windowOpen =
        airportWindowFor(flight, pillLeadMinutes, arriveAheadMinutes).windowStartMillis <= nowEpochMillis
    return !windowOpen || flight.eventId in dismissedEventIds
}

/**
 * The stored preview with airport mode's status folded in when that one is newer. The two are
 * separate caches of the same AirLabs answer, and airport mode fetches far more often, so a
 * refresh that finds a fresher status on [state] takes it instead of buying its own. A state for
 * another flight, or one with no status, leaves [preview] untouched.
 */
internal fun newerStatusForPreview(preview: FlightPreview, state: AirportState?): FlightPreview {
    if (state == null || state.eventId != preview.eventId) {
        return preview
    }
    val status = state.lastStatus ?: return preview
    val stateFetchedAt = state.lastStatusFetchedAtMillis ?: return preview
    val previewFetchedAt = preview.fetchedAtMillis
    if (previewFetchedAt != null && previewFetchedAt >= stateFetchedAt) {
        return preview
    }
    return preview.copy(status = status, fetchedAtMillis = stateFetchedAt, error = null)
}

/** What a tap on the calendar card's flight row does. */
internal sealed interface PreviewTapOutcome {
    /** Bring the dismissed flight's airport card back, straight into REACHED. */
    object Reopen : PreviewTapOutcome

    /** Spend one status call and store the answer on the preview. */
    object Fetch : PreviewTapOutcome

    /** Nothing worth doing: no preview, no key, or a status fetched moments ago. */
    object Nothing : PreviewTapOutcome
}

/**
 * The decision behind a tap on the flight row. Reopening wins whenever it applies, because a tap
 * on a flight the owner dismissed inside its own window can only mean they want the card back -
 * and undoing Done is what makes Done safe to tap. Everything else is a manual refresh on the same
 * [MANUAL_FLIGHT_STATUS_DEBOUNCE_MILLIS] debounce the flight card itself uses, so a double tap
 * cannot spend two queries.
 */
internal fun previewTapOutcome(
    preview: FlightPreview?,
    dismissedEventIds: Set<Long>,
    apiKey: String,
    pillLeadMinutes: Int,
    arriveAheadMinutes: Int,
    nowEpochMillis: Long,
    flights: List<FlightEvent> = emptyList(),
    state: AirportState? = null,
): PreviewTapOutcome {
    if (preview == null) {
        return PreviewTapOutcome.Nothing
    }
    val window = airportWindowFor(preview.flight, pillLeadMinutes, arriveAheadMinutes, flights)
    val windowOpen = airportWindowIsOpen(window, state, nowEpochMillis, dismissedEventIds)
    if (windowOpen && preview.eventId in dismissedEventIds) {
        return PreviewTapOutcome.Reopen
    }
    if (apiKey.isBlank()) {
        return PreviewTapOutcome.Nothing
    }
    val fetchedAt = preview.fetchedAtMillis ?: return PreviewTapOutcome.Fetch
    return if (nowEpochMillis - fetchedAt >= MANUAL_FLIGHT_STATUS_DEBOUNCE_MILLIS) {
        PreviewTapOutcome.Fetch
    } else {
        PreviewTapOutcome.Nothing
    }
}
