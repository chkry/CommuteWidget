package com.crpakala.commutewidget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.crpakala.commutewidget.api.FlightStatusClient
import com.crpakala.commutewidget.api.FlightStatusResult
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.FLIGHT_PREVIEW_LOOKAHEAD_DAYS
import com.crpakala.commutewidget.engine.PreviewTapOutcome
import com.crpakala.commutewidget.engine.RefreshTrigger
import com.crpakala.commutewidget.engine.airportLocalDate
import com.crpakala.commutewidget.engine.airportWindowFor
import com.crpakala.commutewidget.engine.applyAirportDismiss
import com.crpakala.commutewidget.engine.applyAirportReachedTap
import com.crpakala.commutewidget.engine.applyAirportRideTap
import com.crpakala.commutewidget.engine.carryForwardStaticFields
import com.crpakala.commutewidget.engine.dismissedAfterDone
import com.crpakala.commutewidget.engine.previewTapOutcome
import com.crpakala.commutewidget.engine.refreshFlightStatus
import com.crpakala.commutewidget.engine.resolveAirportState
import com.crpakala.commutewidget.engine.shouldAutoFetchFlightStatus
import com.crpakala.commutewidget.engine.shouldManualFetchFlightStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal fun airportRideTapAction(): Action = actionRunCallback<AirportRideAction>()

internal fun airportReachedTapAction(): Action = actionRunCallback<AirportReachedAction>()

/**
 * Spends the one status call a tap is allowed. The caller owns the decision - [AirportReachedAction]
 * takes the periodic gate now that a preview fetch can already have paid for this flight's status,
 * [FlightCardTapAction] takes the shorter manual debounce - so this only resolves the flight the
 * card is showing and runs the call.
 */
private suspend fun fetchAndPersistFlightStatus(
    context: Context,
    repo: SettingsRepository,
    apiKey: String,
    nowEpochMillis: Long,
) {
    val flight = repo.snapshot()?.airport?.flight ?: return
    refreshFlightStatus(context, repo, flight, apiKey, nowEpochMillis)
}

/**
 * To Airport / Riding tap: starts (or resumes) the ride and always relaunches driving navigation,
 * matching the owner's request for car directions regardless of the configured travel mode. The
 * cooldown is bypassed only when the tap actually changed the phase, mirroring [RideAction].
 */
class AirportRideAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = SettingsRepository.get(context)
        val now = System.currentTimeMillis()
        var hadState = false
        val changed = withContext(NonCancellable) {
            repo.updateAirportState { state ->
                hadState = state != null
                state?.let { applyAirportRideTap(it, now) }
            }
        }
        if (!hadState) return
        val airport = repo.snapshot()?.airport
        val lat = airport?.airportLat
        val lng = airport?.airportLng
        if (lat != null && lng != null) {
            launchNavigation(context, lat, lng, "d")
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = changed)
    }
}

/**
 * Reached tap: moves the phase to REACHED, then spends a status call only when
 * [shouldAutoFetchFlightStatus] agrees. The phase is REACHED by then so that check passes, and the
 * interval gate is what matters: the state may have been seeded from a flight-preview fetch made
 * minutes ago (see [com.crpakala.commutewidget.engine.resolveAirportState]), and re-buying that
 * answer on the tap is exactly the wasted query this decision removes. The refresh always bypasses
 * cooldown - the pre-fetch render has already hidden the Reached pill for the flight card, so a
 * cooldown-skipped refresh would strand the widget without it.
 */
class AirportReachedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = SettingsRepository.get(context)
        val now = System.currentTimeMillis()
        withContext(NonCancellable) {
            repo.updateAirportState { state -> state?.let { applyAirportReachedTap(it, now) } }
            val next = repo.airportState() ?: return@withContext
            val apiKey = repo.settingsSnapshot().flightStatusApiKey
            if (shouldAutoFetchFlightStatus(next, apiKey, now)) {
                fetchAndPersistFlightStatus(context, repo, apiKey, now)
            }
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = true)
    }
}

/**
 * A tap on the flight card itself: a manual refresh, so it does not wait out the periodic
 * interval - only the two-minute [shouldManualFetchFlightStatus] debounce, which stops a double
 * tap (or a tap right after the Reached tap already paid) from spending a second query. The phase
 * is untouched.
 */
class FlightCardTapAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = SettingsRepository.get(context)
        val now = System.currentTimeMillis()
        withContext(NonCancellable) {
            val state = repo.airportState() ?: return@withContext
            val apiKey = repo.settingsSnapshot().flightStatusApiKey
            if (shouldManualFetchFlightStatus(state, apiKey, now)) {
                fetchAndPersistFlightStatus(context, repo, apiKey, now)
            }
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = true)
    }
}

/**
 * Done tap: dismisses the flight and releases the widget from airport mode. The event id also goes
 * into the dismissed set, which outlives the single stored state and so survives a later flight
 * taking the widget over. It ends the takeover only - the flight keeps the calendar card's flight
 * row (see [com.crpakala.commutewidget.engine.selectPreviewFlight]), and a tap on that row undoes
 * this ([FlightPreviewTapAction]).
 */
class AirportDismissAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = SettingsRepository.get(context)
        withContext(NonCancellable) {
            val state = repo.airportState() ?: return@withContext
            val settings = repo.settingsSnapshot()
            val reader = CalendarReader(context)
            val flights = if (settings.calendarEnabled && reader.hasPermission()) {
                reader.upcomingFlights(settings.selectedCalendarIds, System.currentTimeMillis(), FLIGHT_PREVIEW_LOOKAHEAD_DAYS)
            } else {
                emptyList()
            }
            val flight = flights.firstOrNull { it.eventId == state.eventId }
            repo.updateAirportDismissedEventIds { current ->
                if (flight == null) current + state.eventId else dismissedAfterDone(current, flight, flights)
            }
            repo.updateAirportState { current -> current?.let { applyAirportDismiss(it) } }
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = true)
    }
}

/**
 * A tap on the calendar card's flight row, which [previewTapOutcome] reads two ways.
 *
 * On a flight already inside its airport window that the owner tapped Done on, it is Undo: the id
 * leaves the dismissed set and a REACHED state is written for it, seeded from the status the
 * preview already holds so reopening the card costs nothing. Otherwise it is a manual refresh of
 * the row on the two-minute debounce, which is the only way to pull a status before the row's own
 * daily cadence would.
 *
 * The whole decision plus its writes sit inside [NonCancellable] because the fetch can block for
 * up to 30 seconds and Glance may tear the callback down first.
 */
class FlightPreviewTapAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = SettingsRepository.get(context)
        val now = System.currentTimeMillis()
        val changed = withContext(NonCancellable) {
            val settings = repo.settingsSnapshot()
            val preview = repo.flightPreview() ?: return@withContext null
            val dismissed = repo.airportDismissedEventIds()
            val zone = ZoneId.systemDefault()
            val reader = CalendarReader(context)
            val flights = if (settings.calendarEnabled && reader.hasPermission()) {
                reader.upcomingFlights(settings.selectedCalendarIds, now, FLIGHT_PREVIEW_LOOKAHEAD_DAYS)
            } else {
                emptyList()
            }
            val stored = repo.airportState()
            when (
                previewTapOutcome(
                    preview = preview,
                    dismissedEventIds = dismissed,
                    apiKey = settings.flightStatusApiKey,
                    pillLeadMinutes = settings.airportPillLeadMinutes,
                    arriveAheadMinutes = settings.airportArriveAheadMinutes,
                    nowEpochMillis = now,
                    flights = flights,
                    state = stored,
                )
            ) {
                PreviewTapOutcome.Reopen -> {
                    repo.updateAirportDismissedEventIds { it - preview.eventId }
                    // A fresh state rather than a transform of the stored one: that one may name
                    // another flight by now, and the row is the authority on which flight was tapped.
                    val layover = airportWindowFor(
                        preview.flight,
                        settings.airportPillLeadMinutes,
                        settings.airportArriveAheadMinutes,
                        flights,
                    ).layover
                    val seeded = resolveAirportState(null, preview.flight, zone, preview, layover)
                    repo.setAirportState(applyAirportReachedTap(seeded, now))
                    true
                }
                PreviewTapOutcome.Fetch -> {
                    val result = FlightStatusClient(settings.flightStatusApiKey).fetch(
                        preview.flight.designator,
                        LocalDate.parse(airportLocalDate(preview.flight, zone)),
                        preview.flight.departureIata,
                    )
                    val updated = when (result) {
                        is FlightStatusResult.Success -> preview.copy(
                            status = carryForwardStaticFields(result.status, preview.status),
                            fetchedAtMillis = now,
                            error = null,
                        )
                        is FlightStatusResult.Failure ->
                            preview.copy(fetchedAtMillis = now, error = result.message)
                    }
                    repo.setFlightPreview(updated)
                    updated != preview
                }
                PreviewTapOutcome.Nothing -> false
            }
        } ?: return
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = changed)
    }
}
