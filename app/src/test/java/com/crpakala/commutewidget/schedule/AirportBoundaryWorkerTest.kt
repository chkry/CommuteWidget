package com.crpakala.commutewidget.schedule

import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AirportPhase
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.FlightStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val PILL_LEAD = 300
private const val ARRIVE_AHEAD = 180

/** 2027-01-15T08:00:00Z. */
private const val DEPARTURE = 1_800_000_000_000L
private const val WINDOW_START = DEPARTURE - 5 * HOUR
private const val ARRIVE_BY = DEPARTURE - 3 * HOUR

/** Pure next-boundary tests for the "airport_boundary" one-shot chain. */
class AirportBoundaryWorkerTest {

    private fun flight(
        eventId: Long = 1L,
        departureMillis: Long = DEPARTURE,
        arrivalMillis: Long = departureMillis + 90 * MINUTE,
    ): FlightEvent = FlightEvent(
        eventId = eventId,
        calendarId = 7L,
        title = "QF 401 to Sydney",
        airlineCode = "QF",
        flightNumber = "401",
        departureMillis = departureMillis,
        arrivalMillis = arrivalMillis,
        locationText = "Melbourne Airport (MEL)",
        departureAirportName = "Melbourne Airport",
        departureIata = "MEL",
        arrivalAirportName = "Sydney Airport",
        arrivalIata = "SYD",
        confirmationNumber = "ABC123",
        seat = "14A",
        departureTerminal = "T1",
        arrivalTerminal = "T2",
    )

    private fun reached(eventId: Long = 1L, statusWord: String? = null): AirportState = AirportState(
        eventId = eventId,
        localDate = "2027-01-15",
        phase = AirportPhase.REACHED,
        lastStatus = statusWord?.let {
            FlightStatus(
                designator = "QF401",
                status = it,
                departureScheduledUtcMillis = DEPARTURE,
                departureEstimatedUtcMillis = null,
                departureTerminal = null,
                departureGate = null,
                departureCheckInDesk = null,
                arrivalScheduledUtcMillis = null,
                arrivalEstimatedUtcMillis = null,
                arrivalTerminal = null,
                arrivalGate = null,
                arrivalBaggageBelt = null,
                aircraftModel = null,
                aircraftRegistration = null,
                operatingAirline = null,
                codeshareOf = null,
                lastUpdatedUtcMillis = null,
            )
        },
    )

    private fun next(
        flights: List<FlightEvent> = listOf(flight()),
        state: AirportState? = null,
        nowEpochMillis: Long,
        leaveByMillis: Long? = null,
        dismissedEventIds: Set<Long> = emptySet(),
    ): Long? = nextAirportBoundaryMillis(
        flights = flights,
        state = state,
        nowEpochMillis = nowEpochMillis,
        pillLeadMinutes = PILL_LEAD,
        arriveAheadMinutes = ARRIVE_AHEAD,
        leaveByMillis = leaveByMillis,
        dismissedEventIds = dismissedEventIds,
    )

    @Test
    fun nextAirportBoundaryMillis_beforeWindowReturnsWindowStart() {
        assertEquals(WINDOW_START, next(nowEpochMillis = WINDOW_START - HOUR))
    }

    @Test
    fun nextAirportBoundaryMillis_insideWindowReturnsLeaveByWhenGiven() {
        val leaveBy = ARRIVE_BY - 30 * MINUTE
        assertEquals(leaveBy, next(nowEpochMillis = leaveBy - 5 * MINUTE, leaveByMillis = leaveBy))
    }

    @Test
    fun nextAirportBoundaryMillis_insideWindowWithoutLeaveByReturnsArriveBy() {
        assertEquals(ARRIVE_BY, next(nowEpochMillis = ARRIVE_BY - 5 * MINUTE))
    }

    @Test
    fun nextAirportBoundaryMillis_pastLeaveByReturnsArriveBy() {
        val leaveBy = ARRIVE_BY - 10 * MINUTE
        assertEquals(ARRIVE_BY, next(nowEpochMillis = leaveBy, leaveByMillis = leaveBy))
    }

    @Test
    fun nextAirportBoundaryMillis_afterArriveByReturnsDeparture() {
        assertEquals(DEPARTURE, next(nowEpochMillis = DEPARTURE - 5 * MINUTE))
    }

    @Test
    fun nextAirportBoundaryMillis_insideWindowOfferedReturnsTickWhenNoBoundaryIsSooner() {
        val now = WINDOW_START + MINUTE
        assertEquals(now + AIRPORT_TICK_MILLIS, next(nowEpochMillis = now))
    }

    @Test
    fun nextAirportBoundaryMillis_insideWindowRidingReturnsTick() {
        val now = WINDOW_START + MINUTE
        val riding = AirportState(eventId = 1L, localDate = "2027-01-15", phase = AirportPhase.RIDING)
        assertEquals(now + AIRPORT_TICK_MILLIS, next(state = riding, nowEpochMillis = now))
    }

    @Test
    fun nextAirportBoundaryMillis_reachedAddsStatusTick() {
        val now = WINDOW_START + MINUTE
        assertEquals(now + AIRPORT_STATUS_TICK_MILLIS, next(state = reached(), nowEpochMillis = now))
    }

    @Test
    fun nextAirportBoundaryMillis_reachedStatusTickYieldsToASoonerBoundary() {
        val now = ARRIVE_BY - 5 * MINUTE
        assertEquals(ARRIVE_BY, next(state = reached(), nowEpochMillis = now))
    }

    @Test
    fun nextAirportBoundaryMillis_reachedAndLandedAddsNoTick() {
        assertEquals(
            ARRIVE_BY,
            next(state = reached(statusWord = "landed"), nowEpochMillis = WINDOW_START + MINUTE),
        )
        assertNull(next(state = reached(statusWord = "landed"), nowEpochMillis = DEPARTURE))
    }

    @Test
    fun nextAirportBoundaryMillis_reachedAndCancelledAddsNoTick() {
        assertEquals(
            ARRIVE_BY,
            next(state = reached(statusWord = "Cancelled"), nowEpochMillis = WINDOW_START + MINUTE),
        )
        assertNull(next(state = reached(statusWord = "Cancelled"), nowEpochMillis = DEPARTURE))
    }

    @Test
    fun nextAirportBoundaryMillis_beforeWindowAddsNoTick() {
        assertEquals(WINDOW_START, next(nowEpochMillis = WINDOW_START - 4 * HOUR))
    }

    @Test
    fun nextAirportBoundaryMillis_ignoresFlightInDismissedSet() {
        val later = flight(eventId = 2L, departureMillis = DEPARTURE + 12 * HOUR)

        assertEquals(
            DEPARTURE + 12 * HOUR - 5 * HOUR,
            next(
                flights = listOf(flight(), later),
                nowEpochMillis = WINDOW_START + MINUTE,
                dismissedEventIds = setOf(1L),
            ),
        )
    }

    @Test
    fun nextAirportBoundaryMillis_ignoresDismissedFlight() {
        val dismissed = AirportState(eventId = 1L, localDate = "2027-01-15", dismissed = true)
        val later = flight(eventId = 2L, departureMillis = DEPARTURE + 12 * HOUR)

        assertEquals(
            DEPARTURE + 12 * HOUR - 5 * HOUR,
            next(flights = listOf(flight(), later), state = dismissed, nowEpochMillis = WINDOW_START + MINUTE),
        )
    }

    @Test
    fun nextAirportBoundaryMillis_nullWhenNothingAhead() {
        assertNull(next(state = reached(statusWord = "landed"), nowEpochMillis = DEPARTURE))
        assertNull(next(flights = emptyList(), nowEpochMillis = WINDOW_START - HOUR))
    }

    // ---- layovers ----

    /** Lands three hours out, which is where its connection's window opens. */
    private fun inbound(): FlightEvent = flight(eventId = 1L, arrivalMillis = DEPARTURE + 3 * HOUR)

    @Test
    fun nextAirportBoundaryMillis_layoverAddsHourlyTick() {
        val onward = flight(eventId = 2L, departureMillis = DEPARTURE + 5 * HOUR)
        val now = DEPARTURE + 3 * HOUR + MINUTE
        val layover = AirportState(eventId = 2L, localDate = "2027-01-15", phase = AirportPhase.LAYOVER)

        assertEquals(
            now + AIRPORT_LAYOVER_TICK_MILLIS,
            next(flights = listOf(inbound(), onward), state = layover, nowEpochMillis = now),
        )
    }

    @Test
    fun nextAirportBoundaryMillis_layoverAndLandedAddsNoTick() {
        val onward = flight(eventId = 2L, departureMillis = DEPARTURE + 5 * HOUR)
        val landed = reached(eventId = 2L, statusWord = "landed")
            .copy(phase = AirportPhase.LAYOVER)
        val now = DEPARTURE + 3 * HOUR + MINUTE

        assertEquals(
            DEPARTURE + 5 * HOUR,
            next(flights = listOf(inbound(), onward), state = landed, nowEpochMillis = now),
        )
    }

    @Test
    fun nextAirportBoundaryMillis_connectionArrivalIsABoundaryBeforeTheLayoverOpens() {
        // Without the layover rule this window would have opened on the pill lead, at DEPARTURE + 2h.
        val onward = flight(eventId = 2L, departureMillis = DEPARTURE + 7 * HOUR)

        assertEquals(
            DEPARTURE + 3 * HOUR,
            next(
                flights = listOf(inbound(), onward),
                state = reached(statusWord = "landed"),
                nowEpochMillis = DEPARTURE + HOUR,
            ),
        )
    }

    @Test
    fun nextAirportBoundaryMillis_picksEarliestAcrossFlights() {
        val later = flight(eventId = 2L, departureMillis = DEPARTURE + 8 * HOUR)
        assertEquals(
            WINDOW_START,
            next(flights = listOf(later, flight()), nowEpochMillis = WINDOW_START - HOUR),
        )
    }
}
