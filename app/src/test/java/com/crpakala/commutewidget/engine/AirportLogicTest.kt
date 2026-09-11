package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.FlightStatusResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AirportPhase
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.FlightPreview
import com.crpakala.commutewidget.data.FlightStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val PILL_LEAD = 300
private const val ARRIVE_AHEAD = 180

/** 2027-01-15T08:00:00Z, which is 2027-01-15T19:00+11:00 in Melbourne. */
private const val DEPARTURE = 1_800_000_000_000L
private const val WINDOW_START = DEPARTURE - 5 * HOUR
private const val ARRIVE_BY = DEPARTURE - 3 * HOUR

private val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
private val MELBOURNE_CBD = LatLng(-37.8136, 144.9631)
private val MELBOURNE_AIRPORT = LatLng(-37.6690, 144.8410)
private val NEAR_AIRPORT = LatLng(-37.6700, 144.8420)
private val FOUR_KM_FROM_AIRPORT = LatLng(-37.7050, 144.8410)

/** Pure decision and transition tests for [AirportLogic]'s airport-mode functions. */
class AirportLogicTest {

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

    private fun status(estimatedDeparture: Long?): FlightStatus = FlightStatus(
        designator = "QF401",
        status = "Scheduled",
        departureScheduledUtcMillis = DEPARTURE,
        departureEstimatedUtcMillis = estimatedDeparture,
        departureTerminal = "T1",
        departureGate = "12",
        departureCheckInDesk = "F",
        arrivalScheduledUtcMillis = DEPARTURE + 90 * MINUTE,
        arrivalEstimatedUtcMillis = null,
        arrivalTerminal = "T2",
        arrivalGate = null,
        arrivalBaggageBelt = null,
        aircraftModel = "Boeing 737",
        aircraftRegistration = "VH-VZX",
        operatingAirline = "Qantas",
        codeshareOf = null,
        lastUpdatedUtcMillis = DEPARTURE - 4 * HOUR,
    )

    private fun state(
        eventId: Long = 1L,
        phase: AirportPhase = AirportPhase.OFFERED,
        dismissed: Boolean = false,
        lastStatus: FlightStatus? = null,
    ): AirportState = AirportState(
        eventId = eventId,
        localDate = "2027-01-15",
        phase = phase,
        dismissed = dismissed,
        lastStatus = lastStatus,
    )

    // ---- airportWindowFor ----

    @Test
    fun airportWindowFor_derivesWindowStartAndArriveBy() {
        val window = airportWindowFor(flight(), PILL_LEAD, ARRIVE_AHEAD)
        assertEquals(WINDOW_START, window.windowStartMillis)
        assertEquals(ARRIVE_BY, window.arriveByMillis)
        assertEquals(1L, window.flight.eventId)
    }

    @Test
    fun airportWindowFor_honoursNonDefaultLeadTimes() {
        val window = airportWindowFor(flight(), pillLeadMinutes = 240, arriveAheadMinutes = 120)
        assertEquals(DEPARTURE - 4 * HOUR, window.windowStartMillis)
        assertEquals(DEPARTURE - 2 * HOUR, window.arriveByMillis)
    }

    @Test
    fun airportWindowFor_leadShorterThanArriveAhead_keepsMinimumWindow() {
        val window = airportWindowFor(flight(), pillLeadMinutes = 60, arriveAheadMinutes = 360)
        assertEquals(DEPARTURE - 6 * HOUR, window.arriveByMillis)
        assertEquals(DEPARTURE - 6 * HOUR - AIRPORT_MIN_WINDOW_MINUTES * MINUTE, window.windowStartMillis)
    }

    // ---- connectionBefore ----

    /** Lands three hours after it leaves, so the connection below sits two hours behind its gate. */
    private fun inbound(): FlightEvent = flight(eventId = 1L, arrivalMillis = DEPARTURE + 3 * HOUR)

    /** Departs two hours after [inbound] lands - a layover, not a fresh journey to an airport. */
    private fun onward(): FlightEvent = flight(eventId = 2L, departureMillis = DEPARTURE + 5 * HOUR)

    @Test
    fun connectionBefore_picksTheLatestQualifyingFlight() {
        val earlier = flight(eventId = 3L, departureMillis = DEPARTURE - 6 * HOUR, arrivalMillis = DEPARTURE - HOUR)
        val connection = connectionBefore(onward(), listOf(earlier, inbound(), onward()))
        assertEquals(1L, connection?.eventId)
    }

    @Test
    fun connectionsAfter_namesTheLegsThatConnectFromTheFlight() {
        val unrelated = flight(eventId = 3L, departureMillis = DEPARTURE + 30 * HOUR, arrivalMillis = DEPARTURE + 35 * HOUR)
        val after = connectionsAfter(inbound(), listOf(inbound(), onward(), unrelated))
        assertEquals(listOf(2L), after.map { it.eventId })
    }

    @Test
    fun dismissedAfterDone_addsTheLegAndClearsItsConnection() {
        val result = dismissedAfterDone(setOf(2L, 9L), inbound(), listOf(inbound(), onward()))
        assertEquals(setOf(1L, 9L), result)
    }

    @Test
    fun dismissedAfterDone_flightWithoutConnections_onlyAddsItself() {
        val result = dismissedAfterDone(setOf(9L), onward(), listOf(inbound(), onward()))
        assertEquals(setOf(2L, 9L), result)
    }

    @Test
    fun connectionBefore_gapOverTenHours_isNotAConnection() {
        val stale = flight(
            eventId = 3L,
            departureMillis = DEPARTURE - 20 * HOUR,
            arrivalMillis = DEPARTURE + 5 * HOUR - 11 * HOUR,
        )
        assertNull(connectionBefore(onward(), listOf(stale, onward())))
    }

    @Test
    fun connectionBefore_gapExactlyTenHours_isAConnection() {
        val edge = flight(
            eventId = 3L,
            departureMillis = DEPARTURE - 20 * HOUR,
            arrivalMillis = DEPARTURE + 5 * HOUR - LAYOVER_MAX_MILLIS,
        )
        assertEquals(3L, connectionBefore(onward(), listOf(edge, onward()))?.eventId)
    }

    @Test
    fun connectionBefore_flightDepartingAfter_isNotAConnection() {
        val later = flight(eventId = 3L, departureMillis = DEPARTURE + 8 * HOUR)
        assertNull(connectionBefore(onward(), listOf(later, onward())))
    }

    @Test
    fun connectionBefore_overlappingFlights_areNotAConnection() {
        val overlapping = flight(eventId = 3L, departureMillis = DEPARTURE, arrivalMillis = DEPARTURE + 9 * HOUR)
        assertNull(connectionBefore(onward(), listOf(overlapping, onward())))
    }

    @Test
    fun connectionBefore_sameEventId_isIgnored() {
        val self = flight(eventId = 2L, departureMillis = DEPARTURE, arrivalMillis = DEPARTURE + 3 * HOUR)
        assertNull(connectionBefore(onward(), listOf(self, onward())))
    }

    @Test
    fun connectionBefore_noOtherFlights_isNull() {
        assertNull(connectionBefore(onward(), listOf(onward())))
        assertNull(connectionBefore(onward(), emptyList()))
    }

    // ---- layover windows ----

    @Test
    fun airportWindowFor_connection_opensWhenTheInboundFlightLands() {
        val window = airportWindowFor(onward(), PILL_LEAD, ARRIVE_AHEAD, listOf(inbound(), onward()))
        assertEquals(DEPARTURE + 3 * HOUR, window.windowStartMillis)
        assertEquals(DEPARTURE + 5 * HOUR - 3 * HOUR, window.arriveByMillis)
        assertTrue(window.layover)
        assertEquals(1L, window.connectionFrom?.eventId)
    }

    @Test
    fun airportWindowFor_withoutFlights_isNeverALayover() {
        val window = airportWindowFor(onward(), PILL_LEAD, ARRIVE_AHEAD)
        assertEquals(DEPARTURE + 5 * HOUR - 5 * HOUR, window.windowStartMillis)
        assertFalse(window.layover)
        assertNull(window.connectionFrom)
    }

    @Test
    fun selectActiveFlight_inboundStillAirborne_keepsTheInboundFlight() {
        val active = selectActiveFlight(
            listOf(inbound(), onward()),
            null,
            DEPARTURE + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            emptySet(),
        )
        assertEquals(1L, active?.flight?.eventId)
        assertFalse(active!!.layover)
    }

    @Test
    fun selectActiveFlight_atInboundArrival_handsOverToTheConnection() {
        val active = selectActiveFlight(
            listOf(inbound(), onward()),
            null,
            DEPARTURE + 3 * HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            emptySet(),
        )
        assertEquals(2L, active?.flight?.eventId)
        assertTrue(active!!.layover)
        assertEquals(DEPARTURE + 3 * HOUR, active.windowStartMillis)
    }

    /** Done on leg A two hours before it lands: leg B is due at once, not at A's arrival. */
    @Test
    fun selectActiveFlight_doneOnTheInboundLeg_handsOverBeforeItLands() {
        val active = selectActiveFlight(
            listOf(inbound(), onward()),
            state(eventId = 1L, phase = AirportPhase.REACHED, dismissed = true),
            DEPARTURE + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            setOf(1L),
        )
        assertEquals(2L, active?.flight?.eventId)
        assertTrue(active!!.layover)
        assertEquals(1L, active.connectionFrom?.eventId)
    }

    @Test
    fun selectActiveFlight_doneOnTheInboundLeg_dismissedSetAloneIsEnough() {
        val active = selectActiveFlight(
            listOf(inbound(), onward()),
            null,
            DEPARTURE + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            setOf(1L),
        )
        assertEquals(2L, active?.flight?.eventId)
        assertTrue(active!!.layover)
    }

    @Test
    fun selectActiveFlight_doneOnTheInboundLeg_stateFlagAloneIsEnough() {
        val active = selectActiveFlight(
            listOf(inbound(), onward()),
            state(eventId = 1L, phase = AirportPhase.REACHED, dismissed = true),
            DEPARTURE + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            emptySet(),
        )
        assertEquals(2L, active?.flight?.eventId)
        assertTrue(active!!.layover)
    }

    @Test
    fun selectActiveFlight_inboundNotDismissed_connectionWaitsForTheLanding() {
        val active = selectActiveFlight(
            listOf(inbound(), onward()),
            state(eventId = 1L, phase = AirportPhase.REACHED),
            DEPARTURE + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            emptySet(),
        )
        assertEquals(1L, active?.flight?.eventId)
        assertFalse(active!!.layover)
    }

    /** An ordinary second journey is never pulled forward by a Done on the flight before it. */
    @Test
    fun selectActiveFlight_doneOnANonConnectingFlight_doesNotOpenTheNextWindow() {
        val later = flight(eventId = 2L, departureMillis = DEPARTURE + 13 * HOUR)
        val active = selectActiveFlight(
            listOf(flight(), later),
            state(eventId = 1L, dismissed = true),
            DEPARTURE + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            setOf(1L),
        )
        assertNull(active)
    }

    @Test
    fun resolveAirportState_afterDoneOnTheInboundLeg_startsTheConnectionInLayover() {
        val dismissed = state(eventId = 1L, phase = AirportPhase.REACHED, dismissed = true)
        val active = selectActiveFlight(
            listOf(inbound(), onward()),
            dismissed,
            DEPARTURE + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            setOf(1L),
        )!!
        val resolved = resolveAirportState(dismissed, active.flight, MELBOURNE, null, active.layover)
        assertEquals(2L, resolved.eventId)
        assertEquals(AirportPhase.LAYOVER, resolved.phase)
        assertFalse(resolved.dismissed)
    }

    @Test
    fun resolveAirportState_layoverWindow_startsInLayover() {
        val resolved = resolveAirportState(null, onward(), MELBOURNE, null, layover = true)
        assertEquals(AirportPhase.LAYOVER, resolved.phase)
        assertEquals(2L, resolved.eventId)
    }

    @Test
    fun resolveAirportState_storedStateWins_evenForALayoverWindow() {
        val stored = state(eventId = 2L, phase = AirportPhase.REACHED)
        assertSame(stored, resolveAirportState(stored, onward(), MELBOURNE, null, layover = true))
    }

    // ---- selectActiveFlight ----

    @Test
    fun selectActiveFlight_oneMinuteBeforeWindow_isNull() {
        val active = selectActiveFlight(listOf(flight()), null, WINDOW_START - MINUTE, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertNull(active)
    }

    @Test
    fun selectActiveFlight_exactlyAtWindowStart_isActive() {
        val active = selectActiveFlight(listOf(flight()), null, WINDOW_START, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(1L, active?.flight?.eventId)
        assertEquals(WINDOW_START, active?.windowStartMillis)
    }

    @Test
    fun selectActiveFlight_noFlights_isNull() {
        assertNull(selectActiveFlight(emptyList(), null, WINDOW_START, PILL_LEAD, ARRIVE_AHEAD, emptySet()))
    }

    @Test
    fun selectActiveFlight_dismissedFlight_isNull() {
        val active = selectActiveFlight(
            listOf(flight()),
            state(eventId = 1L, dismissed = true),
            WINDOW_START + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            emptySet(),
        )
        assertNull(active)
    }

    @Test
    fun selectActiveFlight_dismissedStateForAnotherFlight_isIgnored() {
        val active = selectActiveFlight(
            listOf(flight()),
            state(eventId = 99L, dismissed = true),
            WINDOW_START + HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            emptySet(),
        )
        assertEquals(1L, active?.flight?.eventId)
    }

    @Test
    fun selectActiveFlight_dismissedEventIdSet_survivesStateMovingToLaterFlight() {
        // Thirteen hours out, so the two are separate journeys rather than one connection.
        val flights = listOf(flight(eventId = 1L), flight(eventId = 2L, departureMillis = DEPARTURE + 13 * HOUR))
        val active = selectActiveFlight(
            flights,
            state(eventId = 2L),
            DEPARTURE + 9 * HOUR,
            PILL_LEAD,
            ARRIVE_AHEAD,
            setOf(1L),
        )
        assertEquals(2L, active?.flight?.eventId)
    }

    @Test
    fun selectActiveFlight_earlierFlightNotDeparted_winsOverLaterCandidate() {
        val flights = listOf(flight(eventId = 1L), flight(eventId = 2L, departureMillis = DEPARTURE + 4 * HOUR))
        val active = selectActiveFlight(flights, null, DEPARTURE - HOUR, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(1L, active?.flight?.eventId)
    }

    @Test
    fun selectActiveFlight_earlierFlightDepartedAndLaterInWindow_advances() {
        // Thirteen hours out, so the two are separate journeys rather than one connection.
        val flights = listOf(flight(eventId = 1L), flight(eventId = 2L, departureMillis = DEPARTURE + 13 * HOUR))
        val active = selectActiveFlight(flights, null, DEPARTURE + 9 * HOUR, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(2L, active?.flight?.eventId)
        assertFalse(active!!.layover)
    }

    @Test
    fun selectActiveFlight_earlierFlightDepartedAndLaterNotInWindow_keepsEarlier() {
        val flights = listOf(flight(eventId = 1L), flight(eventId = 2L, departureMillis = DEPARTURE + 8 * HOUR))
        val active = selectActiveFlight(flights, null, DEPARTURE + MINUTE, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(1L, active?.flight?.eventId)
    }

    @Test
    fun selectActiveFlight_departedWithNoLaterCandidate_staysActive() {
        val active = selectActiveFlight(listOf(flight()), null, DEPARTURE + 2 * HOUR, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(1L, active?.flight?.eventId)
    }

    @Test
    fun selectActiveFlight_delayedEarlierFlightBlocksAdvance() {
        val flights = listOf(flight(eventId = 1L), flight(eventId = 2L, departureMillis = DEPARTURE + 4 * HOUR))
        val delayed = state(eventId = 1L, lastStatus = status(DEPARTURE + 2 * HOUR))
        val active = selectActiveFlight(flights, delayed, DEPARTURE + MINUTE, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(1L, active?.flight?.eventId)
    }

    @Test
    fun selectActiveFlight_recurringInstancesOfOneEventId_advanceIndependently() {
        val flights = listOf(flight(eventId = 1L), flight(eventId = 1L, departureMillis = DEPARTURE + 4 * HOUR))
        val active = selectActiveFlight(flights, null, DEPARTURE + MINUTE, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(DEPARTURE + 4 * HOUR, active?.flight?.departureMillis)
    }

    @Test
    fun selectActiveFlight_repeatedIdenticalInstance_collapsesToOneCandidate() {
        val flights = listOf(flight(eventId = 1L), flight(eventId = 1L))
        val active = selectActiveFlight(flights, null, DEPARTURE + MINUTE, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(DEPARTURE, active?.flight?.departureMillis)
    }

    @Test
    fun selectActiveFlight_unsortedInput_stillPicksEarliestCandidate() {
        val flights = listOf(flight(eventId = 2L, departureMillis = DEPARTURE + 4 * HOUR), flight(eventId = 1L))
        val active = selectActiveFlight(flights, null, DEPARTURE - HOUR, PILL_LEAD, ARRIVE_AHEAD, emptySet())
        assertEquals(1L, active?.flight?.eventId)
    }

    // ---- effectiveDepartureMillis ----

    @Test
    fun effectiveDepartureMillis_noState_isScheduled() {
        assertEquals(DEPARTURE, effectiveDepartureMillis(flight(), null))
    }

    @Test
    fun effectiveDepartureMillis_laterEstimate_wins() {
        val delayed = state(lastStatus = status(DEPARTURE + 45 * MINUTE))
        assertEquals(DEPARTURE + 45 * MINUTE, effectiveDepartureMillis(flight(), delayed))
    }

    @Test
    fun effectiveDepartureMillis_earlierEstimate_isIgnored() {
        val early = state(lastStatus = status(DEPARTURE - 20 * MINUTE))
        assertEquals(DEPARTURE, effectiveDepartureMillis(flight(), early))
    }

    @Test
    fun effectiveDepartureMillis_stateForAnotherFlight_isIgnored() {
        val other = state(eventId = 99L, lastStatus = status(DEPARTURE + 45 * MINUTE))
        assertEquals(DEPARTURE, effectiveDepartureMillis(flight(), other))
    }

    @Test
    fun effectiveDepartureMillis_statusWithoutEstimate_isScheduled() {
        assertEquals(DEPARTURE, effectiveDepartureMillis(flight(), state(lastStatus = status(null))))
    }

    // ---- resolveAirportState ----

    @Test
    fun resolveAirportState_matchingEventId_returnsStoredState() {
        val stored = state(phase = AirportPhase.RIDING)
        assertSame(stored, resolveAirportState(stored, flight(), MELBOURNE))
    }

    @Test
    fun resolveAirportState_staleEventId_returnsFreshOfferedState() {
        val stale = state(eventId = 99L, phase = AirportPhase.REACHED, dismissed = true)
        val resolved = resolveAirportState(stale, flight(), MELBOURNE)
        assertEquals(1L, resolved.eventId)
        assertEquals(AirportPhase.OFFERED, resolved.phase)
        assertFalse(resolved.dismissed)
        assertEquals("2027-01-15", resolved.localDate)
    }

    @Test
    fun resolveAirportState_nullState_returnsFreshOfferedState() {
        val resolved = resolveAirportState(null, flight(), MELBOURNE)
        assertEquals(1L, resolved.eventId)
        assertEquals(AirportPhase.OFFERED, resolved.phase)
        assertNull(resolved.lastStatus)
        assertNull(resolved.lastStatusFetchedAtMillis)
    }

    @Test
    fun resolveAirportState_matchingPreview_seedsStatusAndStamp() {
        val preview = FlightPreview(
            eventId = 1L,
            flight = flight(),
            status = status(null),
            fetchedAtMillis = WINDOW_START - 30 * MINUTE,
            error = "AirLabs timed out",
        )

        val resolved = resolveAirportState(null, flight(), MELBOURNE, preview)

        assertEquals(status(null), resolved.lastStatus)
        assertEquals(WINDOW_START - 30 * MINUTE, resolved.lastStatusFetchedAtMillis)
        assertEquals("AirLabs timed out", resolved.lastStatusError)
    }

    @Test
    fun resolveAirportState_previewForAnotherFlight_isIgnored() {
        val preview = FlightPreview(
            eventId = 99L,
            flight = flight(eventId = 99L),
            status = status(null),
            fetchedAtMillis = WINDOW_START - 30 * MINUTE,
        )

        val resolved = resolveAirportState(null, flight(), MELBOURNE, preview)

        assertNull(resolved.lastStatus)
        assertNull(resolved.lastStatusFetchedAtMillis)
        assertNull(resolved.lastStatusError)
    }

    // ---- airportLocalDate ----

    @Test
    fun airportLocalDate_usesZoneNotUtc() {
        assertEquals("2027-01-16", airportLocalDate(flight(departureMillis = 1_800_048_600_000L), MELBOURNE))
        assertEquals("2027-01-15", airportLocalDate(flight(departureMillis = 1_800_048_600_000L), ZoneId.of("UTC")))
    }

    // ---- shouldShowAirportReached ----

    private val window = airportWindowFor(flight(), PILL_LEAD, ARRIVE_AHEAD)

    @Test
    fun shouldShowAirportReached_exactlyAtArriveBy_isTrue() {
        assertTrue(shouldShowAirportReached(state(), window, ARRIVE_BY, null, null))
    }

    @Test
    fun shouldShowAirportReached_oneMinuteBeforeArriveBy_isFalse() {
        assertFalse(shouldShowAirportReached(state(), window, ARRIVE_BY - MINUTE, null, null))
    }

    @Test
    fun shouldShowAirportReached_ridingNearAirport_isTrue() {
        val riding = state(phase = AirportPhase.RIDING)
        assertTrue(shouldShowAirportReached(riding, window, ARRIVE_BY - HOUR, NEAR_AIRPORT, MELBOURNE_AIRPORT))
    }

    @Test
    fun shouldShowAirportReached_offeredNearAirport_isFalse() {
        assertFalse(shouldShowAirportReached(state(), window, ARRIVE_BY - HOUR, NEAR_AIRPORT, MELBOURNE_AIRPORT))
    }

    @Test
    fun shouldShowAirportReached_ridingFarFromTheAirportAndLongBeforeArriveBy_isTrue() {
        val riding = state(phase = AirportPhase.RIDING)
        assertTrue(shouldShowAirportReached(riding, window, ARRIVE_BY - HOUR, FOUR_KM_FROM_AIRPORT, MELBOURNE_AIRPORT))
        assertTrue(shouldShowAirportReached(riding, window, WINDOW_START, MELBOURNE_CBD, MELBOURNE_AIRPORT))
    }

    @Test
    fun shouldShowAirportReached_ridingWithoutDeviceFix_isTrue() {
        val riding = state(phase = AirportPhase.RIDING)
        assertTrue(shouldShowAirportReached(riding, window, ARRIVE_BY - HOUR, null, MELBOURNE_AIRPORT))
        assertTrue(shouldShowAirportReached(riding, window, ARRIVE_BY - HOUR, NEAR_AIRPORT, null))
    }

    @Test
    fun shouldShowAirportReached_reachedPhase_isFalse() {
        val reached = state(phase = AirportPhase.REACHED)
        assertFalse(shouldShowAirportReached(reached, window, ARRIVE_BY + HOUR, NEAR_AIRPORT, MELBOURNE_AIRPORT))
    }

    // ---- surface visibility ----

    @Test
    fun airportShowsToAirportPill_offeredAndRidingOnly() {
        assertTrue(airportShowsToAirportPill(AirportPhase.OFFERED))
        assertTrue(airportShowsToAirportPill(AirportPhase.RIDING))
        assertFalse(airportShowsToAirportPill(AirportPhase.REACHED))
    }

    @Test
    fun airportShowsLeaveByAndBest_offeredOnly() {
        assertTrue(airportShowsLeaveByAndBest(AirportPhase.OFFERED))
        assertFalse(airportShowsLeaveByAndBest(AirportPhase.RIDING))
        assertFalse(airportShowsLeaveByAndBest(AirportPhase.REACHED))
    }

    @Test
    fun airportLoadsMap_ridingOnly() {
        assertTrue(airportLoadsMap(AirportPhase.RIDING))
        assertFalse(airportLoadsMap(AirportPhase.OFFERED))
        assertFalse(airportLoadsMap(AirportPhase.LAYOVER))
        assertFalse(airportLoadsMap(AirportPhase.REACHED))
    }

    @Test
    fun airportShowsFlightCard_reachedOnly() {
        assertFalse(airportShowsFlightCard(AirportPhase.OFFERED))
        assertFalse(airportShowsFlightCard(AirportPhase.RIDING))
        assertTrue(airportShowsFlightCard(AirportPhase.REACHED))
    }

    // ---- transitions ----

    @Test
    fun applyAirportRideTap_offered_becomesRidingAndStampsTap() {
        val tapped = applyAirportRideTap(state(), WINDOW_START + HOUR)
        assertEquals(AirportPhase.RIDING, tapped.phase)
        assertEquals(WINDOW_START + HOUR, tapped.rideTappedAtMillis)
    }

    @Test
    fun applyAirportRideTap_secondTap_keepsFirstTimestamp() {
        val first = applyAirportRideTap(state(), WINDOW_START + HOUR)
        val second = applyAirportRideTap(first, WINDOW_START + 2 * HOUR)
        assertEquals(AirportPhase.RIDING, second.phase)
        assertEquals(WINDOW_START + HOUR, second.rideTappedAtMillis)
    }

    @Test
    fun applyAirportRideTap_reached_isUnchanged() {
        val reached = state(phase = AirportPhase.REACHED)
        assertSame(reached, applyAirportRideTap(reached, WINDOW_START + 3 * HOUR))
    }

    @Test
    fun applyAirportReachedTap_fromRiding_becomesReachedAndStampsTap() {
        val riding = applyAirportRideTap(state(), WINDOW_START + HOUR)
        val reached = applyAirportReachedTap(riding, ARRIVE_BY)
        assertEquals(AirportPhase.REACHED, reached.phase)
        assertEquals(ARRIVE_BY, reached.reachedAtMillis)
        assertEquals(WINDOW_START + HOUR, reached.rideTappedAtMillis)
    }

    @Test
    fun applyAirportReachedTap_fromOffered_becomesReached() {
        val reached = applyAirportReachedTap(state(), ARRIVE_BY)
        assertEquals(AirportPhase.REACHED, reached.phase)
        assertEquals(ARRIVE_BY, reached.reachedAtMillis)
        assertNull(reached.rideTappedAtMillis)
    }

    @Test
    fun applyAirportReachedTap_secondTap_keepsFirstTimestamp() {
        val first = applyAirportReachedTap(state(), ARRIVE_BY)
        val second = applyAirportReachedTap(first, ARRIVE_BY + 30 * MINUTE)
        assertEquals(ARRIVE_BY, second.reachedAtMillis)
    }

    @Test
    fun applyAirportDismiss_keepsPhase() {
        val reached = applyAirportReachedTap(state(), ARRIVE_BY)
        val dismissed = applyAirportDismiss(reached)
        assertTrue(dismissed.dismissed)
        assertEquals(AirportPhase.REACHED, dismissed.phase)
        assertEquals(ARRIVE_BY, dismissed.reachedAtMillis)
    }

    // ---- status fetch ----

    @Test
    fun applyFlightStatusResult_success_storesStatusAndClearsError() {
        val withError = state(phase = AirportPhase.REACHED).copy(lastStatusError = "Network error")
        val updated = applyFlightStatusResult(
            withError,
            FlightStatusResult.Success(status(DEPARTURE + 20 * MINUTE)),
            ARRIVE_BY + MINUTE,
        )
        assertEquals(DEPARTURE + 20 * MINUTE, updated.lastStatus?.departureEstimatedUtcMillis)
        assertEquals(ARRIVE_BY + MINUTE, updated.lastStatusFetchedAtMillis)
        assertNull(updated.lastStatusError)
    }

    @Test
    fun applyFlightStatusResult_failure_keepsLastGoodStatus() {
        val good = applyFlightStatusResult(
            state(phase = AirportPhase.REACHED),
            FlightStatusResult.Success(status(DEPARTURE + 20 * MINUTE)),
            ARRIVE_BY,
        )
        val failed = applyFlightStatusResult(
            good,
            FlightStatusResult.Failure("AirLabs quota exceeded", retryable = false),
            ARRIVE_BY + 10 * MINUTE,
        )
        assertEquals(good.lastStatus, failed.lastStatus)
        assertEquals(ARRIVE_BY, failed.lastStatusFetchedAtMillis)
        assertEquals("AirLabs quota exceeded", failed.lastStatusError)
    }

    // ---- carryForwardStaticFields ----

    /** The live answer the owner's card held at 12:40: an ADS-B match, so the airframe is named. */
    private fun liveStatus(): FlightStatus = status(DEPARTURE + 14 * MINUTE).copy(
        status = "active",
        aircraftModel = "Airbus A350-900",
        aircraftRegistration = "9V-SHW",
        aircraftIcao = "A359",
        aircraftManufacturer = "Airbus",
        aircraftSerialNumber = "240",
        aircraftEngineType = "jet",
        aircraftEngineCount = 2,
        aircraftBuiltYear = 2019,
        aircraftAgeYears = 7,
        airlineName = "Singapore Airlines",
        airlineIata = "SQ",
        airlineIcao = "SIA",
        departureAirportName = "Kempegowda International Airport",
        departureCity = "Bengaluru",
        departureCountry = "IN",
        arrivalAirportName = "Singapore Changi Airport",
        arrivalCity = "Singapore",
        arrivalCountry = "SG",
        departureIata = "BLR",
        arrivalIata = "SIN",
        departureUtcOffsetMinutes = 330,
        arrivalUtcOffsetMinutes = 480,
        durationMinutes = 295,
        arrivalGate = "D41",
        arrivalBaggageBelt = "4",
        speedKmh = 902,
        altitudeMeters = 12_593,
        verticalSpeedKmh = -5,
        headingDegrees = 137,
        transponderCode = "2251",
        percentComplete = 58,
        latitude = 8.1,
        longitude = 89.4,
        source = "schedules+flight",
    )

    /** The 2:01 answer: the same leg with no ADS-B match, so every airframe field came back null. */
    private fun thinStatus(): FlightStatus = FlightStatus(
        designator = "QF401",
        status = "active",
        departureScheduledUtcMillis = DEPARTURE,
        departureEstimatedUtcMillis = DEPARTURE + 14 * MINUTE,
        departureTerminal = null,
        departureGate = null,
        departureCheckInDesk = null,
        arrivalScheduledUtcMillis = DEPARTURE + 90 * MINUTE,
        arrivalEstimatedUtcMillis = null,
        arrivalTerminal = null,
        arrivalGate = null,
        arrivalBaggageBelt = null,
        aircraftModel = null,
        aircraftRegistration = null,
        operatingAirline = null,
        codeshareOf = null,
        lastUpdatedUtcMillis = DEPARTURE - HOUR,
        source = "schedules",
    )

    @Test
    fun carryForwardStaticFields_keepsTheAirframeTheAirlineTheAirportsAndTheOffsets() {
        val carried = carryForwardStaticFields(thinStatus(), liveStatus())

        assertEquals("Airbus A350-900", carried.aircraftModel)
        assertEquals("9V-SHW", carried.aircraftRegistration)
        assertEquals("A359", carried.aircraftIcao)
        assertEquals("Airbus", carried.aircraftManufacturer)
        assertEquals("240", carried.aircraftSerialNumber)
        assertEquals("jet", carried.aircraftEngineType)
        assertEquals(2, carried.aircraftEngineCount)
        assertEquals(2019, carried.aircraftBuiltYear)
        assertEquals(7, carried.aircraftAgeYears)
        assertEquals("Singapore Airlines", carried.airlineName)
        assertEquals("SQ", carried.airlineIata)
        assertEquals("SIA", carried.airlineIcao)
        assertEquals("Qantas", carried.operatingAirline)
        assertEquals("Kempegowda International Airport", carried.departureAirportName)
        assertEquals("Singapore Changi Airport", carried.arrivalAirportName)
        assertEquals("IN", carried.departureCountry)
        assertEquals("SG", carried.arrivalCountry)
        assertEquals("BLR", carried.departureIata)
        assertEquals("SIN", carried.arrivalIata)
        assertEquals(330, carried.departureUtcOffsetMinutes)
        assertEquals(480, carried.arrivalUtcOffsetMinutes)
        assertEquals("T1", carried.departureTerminal)
        assertEquals("12", carried.departureGate)
        assertEquals("F", carried.departureCheckInDesk)
        assertEquals("T2", carried.arrivalTerminal)
        assertEquals("D41", carried.arrivalGate)
        assertEquals("4", carried.arrivalBaggageBelt)
        assertEquals(295, carried.durationMinutes)
    }

    @Test
    fun carryForwardStaticFields_neverCarriesTheStatusWordTheTimesOrTheTelemetry() {
        val landed = thinStatus().copy(status = "landed")
        val carried = carryForwardStaticFields(landed, liveStatus())

        assertEquals("landed", carried.status)
        assertNull(carried.speedKmh)
        assertNull(carried.altitudeMeters)
        assertNull(carried.verticalSpeedKmh)
        assertNull(carried.headingDegrees)
        assertNull(carried.transponderCode)
        assertNull(carried.latitude)
        assertNull(carried.longitude)
        assertNull(carried.percentComplete)
        assertNull(carried.arrivalEstimatedUtcMillis)
        assertNull(carried.departureDelayedMinutes)
        assertNull(carried.arrivalDelayedMinutes)
        assertEquals(DEPARTURE - HOUR, carried.lastUpdatedUtcMillis)
        assertEquals("schedules", carried.source)
    }

    @Test
    fun carryForwardStaticFields_ignoresAStatusForAnotherDesignator() {
        val other = liveStatus().copy(designator = "SQ509")

        assertEquals(thinStatus(), carryForwardStaticFields(thinStatus(), other))
        assertEquals(thinStatus(), carryForwardStaticFields(thinStatus(), null))
    }

    @Test
    fun applyFlightStatusResult_thinSuccessKeepsTheAirframeTheCardWasShowing() {
        val held = state(phase = AirportPhase.REACHED, lastStatus = liveStatus())
        val updated = applyFlightStatusResult(held, FlightStatusResult.Success(thinStatus()), ARRIVE_BY)

        assertEquals("9V-SHW", updated.lastStatus?.aircraftRegistration)
        assertEquals(480, updated.lastStatus?.arrivalUtcOffsetMinutes)
        assertNull(updated.lastStatus?.speedKmh)
        assertEquals(ARRIVE_BY, updated.lastStatusFetchedAtMillis)
    }

    @Test
    fun applyFlightStatusResult_neverCarriesAcrossFlights() {
        val held = state(phase = AirportPhase.REACHED, lastStatus = liveStatus().copy(designator = "SQ509"))
        val updated = applyFlightStatusResult(held, FlightStatusResult.Success(thinStatus()), ARRIVE_BY)

        assertNull(updated.lastStatus?.aircraftRegistration)
        assertNull(updated.lastStatus?.departureUtcOffsetMinutes)
    }

    @Test
    fun shouldManualFetchFlightStatus_neverFetchedWithKey_isTrue() {
        assertTrue(shouldManualFetchFlightStatus(state(phase = AirportPhase.REACHED), "key", ARRIVE_BY))
    }

    @Test
    fun shouldManualFetchFlightStatus_blankKey_isFalse() {
        assertFalse(shouldManualFetchFlightStatus(state(phase = AirportPhase.REACHED), "   ", ARRIVE_BY))
    }

    @Test
    fun shouldManualFetchFlightStatus_secondTapInsideDebounce_isFalse() {
        val fetched = state(phase = AirportPhase.REACHED).copy(lastStatusFetchedAtMillis = ARRIVE_BY)

        assertFalse(shouldManualFetchFlightStatus(fetched, "key", ARRIVE_BY + 90_000L))
        assertTrue(shouldManualFetchFlightStatus(fetched, "key", ARRIVE_BY + 2 * MINUTE))
    }

    // ---- shouldAutoFetchFlightStatus ----

    private fun reachedState(
        lastStatusFetchedAtMillis: Long? = null,
        statusWord: String = "active",
    ): AirportState = AirportState(
        eventId = 1L,
        localDate = "2027-01-15",
        phase = AirportPhase.REACHED,
        lastStatus = status(null).copy(status = statusWord),
        lastStatusFetchedAtMillis = lastStatusFetchedAtMillis,
    )

    @Test
    fun shouldAutoFetchFlightStatus_neverFetched_isTrue() {
        assertTrue(shouldAutoFetchFlightStatus(reachedState(lastStatusFetchedAtMillis = null), "key", DEPARTURE))
    }

    @Test
    fun shouldAutoFetchFlightStatus_freshFetch_isFalse() {
        val state = reachedState(lastStatusFetchedAtMillis = DEPARTURE - 5 * MINUTE)
        assertFalse(shouldAutoFetchFlightStatus(state, "key", DEPARTURE))
    }

    @Test
    fun shouldAutoFetchFlightStatus_staleFetch_isTrue() {
        val state = reachedState(lastStatusFetchedAtMillis = DEPARTURE - 31 * MINUTE)
        assertTrue(shouldAutoFetchFlightStatus(state, "key", DEPARTURE))
    }

    @Test
    fun shouldAutoFetchFlightStatus_tickFiringOneMinuteEarly_isTrue() {
        val state = reachedState(lastStatusFetchedAtMillis = DEPARTURE - 29 * MINUTE)
        assertTrue(shouldAutoFetchFlightStatus(state, "key", DEPARTURE))
        assertFalse(shouldAutoFetchFlightStatus(reachedState(DEPARTURE - 28 * MINUTE), "key", DEPARTURE))
    }

    @Test
    fun shouldAutoFetchFlightStatus_landedOrCancelled_isFalse() {
        assertFalse(shouldAutoFetchFlightStatus(reachedState(statusWord = "landed"), "key", DEPARTURE))
        assertFalse(shouldAutoFetchFlightStatus(reachedState(statusWord = "Cancelled"), "key", DEPARTURE))
    }

    @Test
    fun shouldAutoFetchFlightStatus_blankKey_isFalse() {
        assertFalse(shouldAutoFetchFlightStatus(reachedState(), "   ", DEPARTURE))
    }

    @Test
    fun shouldAutoFetchFlightStatus_beforeReached_isFalse() {
        assertFalse(shouldAutoFetchFlightStatus(state(), "key", DEPARTURE))
        assertFalse(shouldAutoFetchFlightStatus(state(phase = AirportPhase.RIDING), "key", DEPARTURE))
    }

    @Test
    fun flightStatusIsFinal_landedAndCancelledOnly() {
        assertTrue(flightStatusIsFinal("landed"))
        assertTrue(flightStatusIsFinal("CANCELLED"))
        assertFalse(flightStatusIsFinal("active"))
        assertFalse(flightStatusIsFinal(null))
    }

    // ---- leave by ----

    @Test
    fun airportLeaveByMillis_subtractsDriveDuration() {
        assertEquals(ARRIVE_BY - 45 * MINUTE, airportLeaveByMillis(ARRIVE_BY, 45 * 60L))
    }

    // ---- distance ----

    @Test
    fun distanceMeters_melbourneCbdToAirport_isAboutTwentyKilometres() {
        val metres = distanceMeters(MELBOURNE_CBD, MELBOURNE_AIRPORT)
        assertTrue("expected 19-21 km, was $metres m", metres in 19_000.0..21_000.0)
    }

    @Test
    fun distanceMeters_samePoint_isZero() {
        assertEquals(0.0, distanceMeters(MELBOURNE_AIRPORT, MELBOURNE_AIRPORT), 0.0001)
    }

    // ---- LAYOVER predicates and transitions ----

    @Test
    fun airportShowsToAirportPill_layover_isFalse() {
        assertFalse(airportShowsToAirportPill(AirportPhase.LAYOVER))
    }

    @Test
    fun airportShowsLeaveByAndBest_layover_isFalse() {
        assertFalse(airportShowsLeaveByAndBest(AirportPhase.LAYOVER))
    }

    @Test
    fun airportShowsFlightCard_layover_isTrue() {
        assertTrue(airportShowsFlightCard(AirportPhase.LAYOVER))
    }

    @Test
    fun airportShowsLayoverReachedPill_onlyInLayover() {
        assertTrue(airportShowsLayoverReachedPill(AirportPhase.LAYOVER))
        assertFalse(airportShowsLayoverReachedPill(AirportPhase.OFFERED))
        assertFalse(airportShowsLayoverReachedPill(AirportPhase.RIDING))
        assertFalse(airportShowsLayoverReachedPill(AirportPhase.REACHED))
    }

    @Test
    fun shouldShowAirportReached_layover_isFalse() {
        val layover = state(phase = AirportPhase.LAYOVER)
        val window = airportWindowFor(flight(), PILL_LEAD, ARRIVE_AHEAD)
        assertFalse(shouldShowAirportReached(layover, window, ARRIVE_BY + HOUR, NEAR_AIRPORT, MELBOURNE_AIRPORT))
    }

    @Test
    fun applyAirportRideTap_layover_isUnchanged() {
        val layover = state(phase = AirportPhase.LAYOVER)
        val tapped = applyAirportRideTap(layover, ARRIVE_BY)
        assertEquals(AirportPhase.LAYOVER, tapped.phase)
        assertNull(tapped.rideTappedAtMillis)
    }

    @Test
    fun applyAirportReachedTap_fromLayover_isReached() {
        val tapped = applyAirportReachedTap(state(phase = AirportPhase.LAYOVER), ARRIVE_BY)
        assertEquals(AirportPhase.REACHED, tapped.phase)
        assertEquals(ARRIVE_BY, tapped.reachedAtMillis)
    }

    @Test
    fun applyAirportDismiss_fromLayover_keepsPhase() {
        val dismissed = applyAirportDismiss(state(phase = AirportPhase.LAYOVER))
        assertTrue(dismissed.dismissed)
        assertEquals(AirportPhase.LAYOVER, dismissed.phase)
    }

    @Test
    fun shouldAutoFetchFlightStatus_layoverWaitsFiftyNineMinutes() {
        val layover = reachedState(lastStatusFetchedAtMillis = DEPARTURE - 58 * MINUTE)
            .copy(phase = AirportPhase.LAYOVER)
        assertFalse(shouldAutoFetchFlightStatus(layover, "key", DEPARTURE))
        assertTrue(
            shouldAutoFetchFlightStatus(
                layover.copy(lastStatusFetchedAtMillis = DEPARTURE - 59 * MINUTE),
                "key",
                DEPARTURE,
            ),
        )
    }

    @Test
    fun shouldAutoFetchFlightStatus_reachedWaitsTwentyNineMinutes() {
        assertFalse(shouldAutoFetchFlightStatus(reachedState(DEPARTURE - 28 * MINUTE), "key", DEPARTURE))
        assertTrue(shouldAutoFetchFlightStatus(reachedState(DEPARTURE - 29 * MINUTE), "key", DEPARTURE))
    }

    @Test
    fun shouldAutoFetchFlightStatus_layoverAndLanded_isFalse() {
        val landed = reachedState(statusWord = "landed").copy(phase = AirportPhase.LAYOVER)
        assertFalse(shouldAutoFetchFlightStatus(landed, "key", DEPARTURE))
    }

    @Test
    fun distanceMeters_isSymmetric() {
        assertEquals(
            distanceMeters(MELBOURNE_CBD, MELBOURNE_AIRPORT),
            distanceMeters(MELBOURNE_AIRPORT, MELBOURNE_CBD),
            0.0001,
        )
    }
}
