package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AirportPhase
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import com.crpakala.commutewidget.engine.airportWindowFor
import com.crpakala.commutewidget.engine.flightStatusIsFinal
import com.crpakala.commutewidget.engine.selectActiveFlight
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Airport mode's one-shot wake-up: fires an [RefreshTrigger.AUTO] refresh at the next airport
 * boundary (a window opening, an arrive-by target, a computed leave-by instant, or a scheduled
 * departure - see [nextAirportBoundaryMillis]). It does not reschedule itself: the refresh it
 * triggers re-arms the chain from the end of `CommuteRefresher.performRefresh`, on both the
 * airport and the non-airport path, so there is exactly one place that decides the next boundary.
 * Cadence inside an open window is part of the same chain: no other worker ticks for airport mode,
 * so [nextAirportBoundaryMillis] emits its own [AIRPORT_TICK_MILLIS] candidate while the window is
 * open and the flight has not been marked Reached.
 */
class AirportBoundaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            // APPEND_OR_REPLACE, not the default REPLACE: this worker IS the current holder of
            // AIRPORT_BOUNDARY_WORK_NAME, and the refresh below re-arms that same unique name.
            // REPLACE cancels pending-or-running work under the name, so it would cancel this
            // still-running invocation mid-flight - the anti-self-cancel rule CommuteScheduler
            // documents for the commute window boundary chain, threaded through the refresh
            // because here it is the refresh, not the worker, that owns the rescheduling.
            CommuteRefresher.refreshNow(
                context = applicationContext,
                trigger = RefreshTrigger.AUTO,
                airportBoundaryPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refresh errors are recorded in the snapshot; the chain is re-armed by the refresh.
        }
        return Result.success()
    }
}

object AirportBoundaryScheduler {
    const val AIRPORT_BOUNDARY_WORK_NAME = "airport_boundary"

    /**
     * Reads the flights and airport state itself, for callers that hold neither (app boot, widget
     * enable, settings changes via [CommuteScheduler.ensureScheduled]). The active flight's
     * leave-by comes from the stored snapshot, which is where the last refresh left it.
     */
    suspend fun ensureScheduled(context: Context, settings: AppSettings) {
        val appContext = context.applicationContext
        val repo = SettingsRepository.get(appContext)
        val nowEpochMillis = System.currentTimeMillis()
        // CommuteScheduler.ensureScheduled is called from rememberCoroutineScope() in the settings
        // screens, so the cursor query and the parsing behind it must not run on the caller.
        val flights = withContext(Dispatchers.IO) {
            val reader = CalendarReader(appContext)
            if (settings.calendarEnabled && reader.hasPermission() && settings.selectedCalendarIds.isNotEmpty()) {
                reader.upcomingFlights(settings.selectedCalendarIds, nowEpochMillis)
            } else {
                emptyList()
            }
        }
        schedule(
            context = appContext,
            flights = flights,
            state = repo.airportState(),
            dismissedEventIds = repo.airportDismissedEventIds(),
            settings = settings,
            leaveByMillis = repo.snapshot()?.airport?.leaveByMillis,
            nowEpochMillis = nowEpochMillis,
        )
    }

    /** Arms the next boundary, or cancels the chain when no boundary remains ahead of now. */
    fun schedule(
        context: Context,
        flights: List<FlightEvent>,
        state: AirportState?,
        dismissedEventIds: Set<Long>,
        settings: AppSettings,
        leaveByMillis: Long?,
        nowEpochMillis: Long,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val target = nextAirportBoundaryMillis(
            flights = flights,
            state = state,
            nowEpochMillis = nowEpochMillis,
            pillLeadMinutes = settings.airportPillLeadMinutes,
            arriveAheadMinutes = settings.airportArriveAheadMinutes,
            leaveByMillis = leaveByMillis,
            dismissedEventIds = dismissedEventIds,
        )
        if (target == null) {
            cancel(context)
            return
        }
        scheduleAt(context, target, nowEpochMillis, existingWorkPolicy)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(AIRPORT_BOUNDARY_WORK_NAME)
    }

    private fun scheduleAt(
        context: Context,
        targetEpochMillis: Long,
        nowEpochMillis: Long,
        existingWorkPolicy: ExistingWorkPolicy,
    ) {
        val request = OneTimeWorkRequestBuilder<AirportBoundaryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay((targetEpochMillis - nowEpochMillis).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            AIRPORT_BOUNDARY_WORK_NAME,
            existingWorkPolicy,
            request,
        )
    }
}

/** Cadence inside an open airport window, as a boundary candidate the chain re-arms from. */
internal const val AIRPORT_TICK_MILLIS = 15 * 60_000L

/**
 * Cadence once the flight card is showing. Slower than [AIRPORT_TICK_MILLIS] because every one of
 * these ticks spends an AirLabs query against a hard 1000-query budget, and the card's own contents
 * (gate, delay, live position) move on the order of tens of minutes, not minutes.
 */
internal const val AIRPORT_STATUS_TICK_MILLIS = 30 * 60_000L

/**
 * Cadence while a layover card is showing. Slower again than [AIRPORT_STATUS_TICK_MILLIS]: the
 * traveller is sitting inside the departure airport hours before boarding, and nothing the card
 * carries moves fast enough at that range to be worth twice the AirLabs queries.
 */
internal const val AIRPORT_LAYOVER_TICK_MILLIS = 60 * 60_000L

/** The tick a flight-card phase runs on, and so the interval its status fetch is rationed to. */
internal fun statusTickMillisFor(phase: AirportPhase): Long = when (phase) {
    AirportPhase.LAYOVER -> AIRPORT_LAYOVER_TICK_MILLIS
    AirportPhase.OFFERED, AirportPhase.RIDING, AirportPhase.REACHED -> AIRPORT_STATUS_TICK_MILLIS
}

/**
 * The next instant at which airport mode's view of the world changes, or null when none remains.
 * Over every non-dismissed flight that comes back from the calendar: the window opening, the
 * arrive-by target and the scheduled departure (which is when a later flight may take over from a
 * departed one). [leaveByMillis] is the active flight's computed leave-by, supplied by the caller
 * because it depends on a route the calendar knows nothing about. Only instants strictly after
 * [nowEpochMillis] qualify, so a boundary that has just fired cannot re-arm itself in place.
 *
 * While a window is open in OFFERED or RIDING there is a fourth candidate, [AIRPORT_TICK_MILLIS]
 * ahead of now: nothing else ticks for airport mode (CalendarTickWorker only covers
 * CALENDAR_EVENT), so without it the Leave by pill and its alarm would stay frozen at the traffic
 * reading taken when the window opened. REACHED ticks on the slower [AIRPORT_STATUS_TICK_MILLIS] and LAYOVER on the slower
 * still [AIRPORT_LAYOVER_TICK_MILLIS], which is what drives the flight card's periodic status
 * fetch, and both stop ticking entirely once the flight has landed or been cancelled. A closed
 * window gets no tick at all.
 *
 * A layover window that has not opened yet is anchored on its inbound flight's arrival rather than
 * on a pill lead, so that arrival is a boundary candidate in its own right - the widget has to
 * wake when the connecting flight lands, which is the instant the layover card is due.
 */
internal fun nextAirportBoundaryMillis(
    flights: List<FlightEvent>,
    state: AirportState?,
    nowEpochMillis: Long,
    pillLeadMinutes: Int,
    arriveAheadMinutes: Int,
    leaveByMillis: Long?,
    dismissedEventIds: Set<Long>,
): Long? {
    val flightBoundaries = flights
        .filter { it.eventId !in dismissedEventIds }
        .filter { state == null || !state.dismissed || state.eventId != it.eventId }
        .flatMap { flight ->
            val window = airportWindowFor(flight, pillLeadMinutes, arriveAheadMinutes, flights)
            listOfNotNull(
                window.windowStartMillis,
                window.arriveByMillis,
                flight.departureMillis,
                window.connectionFrom?.arrivalMillis,
            )
        }
    val active = selectActiveFlight(
        flights = flights,
        state = state,
        nowEpochMillis = nowEpochMillis,
        pillLeadMinutes = pillLeadMinutes,
        arriveAheadMinutes = arriveAheadMinutes,
        dismissedEventIds = dismissedEventIds,
    )
    // A flight the stored state does not name resolves to a fresh OFFERED state on the next
    // refresh, which is exactly what resolveAirportState does.
    val activePhase = when {
        active == null -> null
        state != null && state.eventId == active.flight.eventId -> state.phase
        active.layover -> AirportPhase.LAYOVER
        else -> AirportPhase.OFFERED
    }
    val tickMillis = when (activePhase) {
        AirportPhase.OFFERED, AirportPhase.RIDING -> nowEpochMillis + AIRPORT_TICK_MILLIS
        AirportPhase.LAYOVER, AirportPhase.REACHED ->
            if (flightStatusIsFinal(state?.lastStatus?.status)) {
                null
            } else {
                nowEpochMillis + statusTickMillisFor(activePhase)
            }
        null -> null
    }
    return (flightBoundaries + listOfNotNull(leaveByMillis, tickMillis))
        .filter { it > nowEpochMillis }
        .minOrNull()
}
