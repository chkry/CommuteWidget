package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.FlightPreview
import com.crpakala.commutewidget.data.FlightStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection and fetch policy for the calendar card's flight row. Every instant here is an explicit
 * literal: [NOW] is the reference clock and the flights are offset from it in whole hours.
 */
class FlightPreviewLogicTest {
    private val hour = 60 * 60_000L

    private fun flight(
        eventId: Long = 1L,
        departureMillis: Long = NOW + 10 * hour,
    ): FlightEvent = FlightEvent(
        eventId = eventId,
        calendarId = 7L,
        title = "SQ509 to Singapore",
        airlineCode = "SQ",
        flightNumber = "509",
        departureMillis = departureMillis,
        arrivalMillis = departureMillis + 4 * hour,
        locationText = null,
        departureAirportName = "Kempegowda International",
        departureIata = "BLR",
        arrivalAirportName = "Changi",
        arrivalIata = "SIN",
        confirmationNumber = "ABC123",
        seat = "14A",
        departureTerminal = "2",
        arrivalTerminal = "3",
    )

    private fun preview(
        eventId: Long = 1L,
        departureMillis: Long = NOW + 10 * hour,
        fetchedAtMillis: Long? = null,
    ): FlightPreview = FlightPreview(
        eventId = eventId,
        flight = flight(eventId = eventId, departureMillis = departureMillis),
        fetchedAtMillis = fetchedAtMillis,
    )

    private fun airportState(eventId: Long, fetchedAtMillis: Long): AirportState = AirportState(
        eventId = eventId,
        localDate = "2026-10-13",
        lastStatus = FlightStatus(
            designator = "SQ509",
            status = "scheduled",
            departureScheduledUtcMillis = NOW + 10 * hour,
            departureEstimatedUtcMillis = null,
            departureTerminal = "2",
            departureGate = "A11",
            departureCheckInDesk = null,
            arrivalScheduledUtcMillis = NOW + 14 * hour,
            arrivalEstimatedUtcMillis = null,
            arrivalTerminal = "3",
            arrivalGate = null,
            arrivalBaggageBelt = null,
            aircraftModel = null,
            aircraftRegistration = null,
            operatingAirline = "Singapore Airlines",
            codeshareOf = null,
            lastUpdatedUtcMillis = fetchedAtMillis,
        ),
        lastStatusFetchedAtMillis = fetchedAtMillis,
    )

    @Test
    fun selectPreviewFlight_picksTheEarliestFlightStillAhead() {
        val soon = flight(eventId = 1L, departureMillis = NOW + 30 * hour)
        val later = flight(eventId = 2L, departureMillis = NOW + 200 * hour)
        assertEquals(soon, selectPreviewFlight(listOf(later, soon), NOW))
    }

    @Test
    fun selectPreviewFlight_skipsDepartedFlights() {
        val departed = flight(eventId = 1L, departureMillis = NOW - hour)
        val ahead = flight(eventId = 2L, departureMillis = NOW + 5 * hour)
        assertEquals(ahead, selectPreviewFlight(listOf(departed, ahead), NOW))
    }

    /** Done ends the takeover, not the row: the dismissed flight keeps the preview it had. */
    @Test
    fun selectPreviewFlight_picksADismissedFlightThatIsStillAhead() {
        val dismissed = flight(eventId = 1L, departureMillis = NOW + 5 * hour)
        val ahead = flight(eventId = 2L, departureMillis = NOW + 50 * hour)
        assertEquals(dismissed, selectPreviewFlight(listOf(dismissed, ahead), NOW))
    }

    @Test
    fun selectPreviewFlight_nullWhenEveryFlightHasDeparted() {
        val departed = flight(eventId = 1L, departureMillis = NOW - hour)
        val alsoDeparted = flight(eventId = 2L, departureMillis = NOW - 5 * hour)
        assertNull(selectPreviewFlight(listOf(departed, alsoDeparted), NOW))
        assertNull(selectPreviewFlight(emptyList(), NOW))
    }

    @Test
    fun selectPreviewFlight_flightDepartingExactlyNowIsNotAhead() {
        assertNull(selectPreviewFlight(listOf(flight(departureMillis = NOW)), NOW))
    }

    @Test
    fun previewRefreshIntervalMillis_seventyTwoHoursBeyondTheBoundary() {
        assertEquals(72 * hour, previewRefreshIntervalMillis(flight(departureMillis = NOW + 49 * hour), NOW))
        assertEquals(72 * hour, previewRefreshIntervalMillis(flight(departureMillis = NOW + 48 * hour + 1), NOW))
    }

    @Test
    fun previewRefreshIntervalMillis_twentyFourHoursAtAndInsideTheBoundary() {
        assertEquals(24 * hour, previewRefreshIntervalMillis(flight(departureMillis = NOW + 48 * hour), NOW))
        assertEquals(24 * hour, previewRefreshIntervalMillis(flight(departureMillis = NOW + 3 * hour), NOW))
    }

    @Test
    fun shouldFetchPreview_falseWhenApiKeyIsBlank() {
        assertFalse(shouldFetchPreview(null, flight(), "", NOW))
        assertFalse(shouldFetchPreview(null, flight(), "   ", NOW))
    }

    @Test
    fun shouldFetchPreview_trueWhenNothingIsStored() {
        assertTrue(shouldFetchPreview(null, flight(), "key", NOW))
    }

    @Test
    fun shouldFetchPreview_trueWhenStoredPreviewNamesAnotherFlight() {
        val other = flight(eventId = 9L)
        val stored = FlightPreview(eventId = 9L, flight = other, fetchedAtMillis = NOW)
        assertTrue(shouldFetchPreview(stored, flight(eventId = 1L), "key", NOW))
    }

    @Test
    fun shouldFetchPreview_trueWhenStoredPreviewWasNeverFetched() {
        val subject = flight()
        val stored = FlightPreview(eventId = subject.eventId, flight = subject, fetchedAtMillis = null)
        assertTrue(shouldFetchPreview(stored, subject, "key", NOW))
    }

    @Test
    fun shouldFetchPreview_trueWhenTheStampIsAFullIntervalOld() {
        val near = flight(departureMillis = NOW + 10 * hour)
        val stored = FlightPreview(eventId = near.eventId, flight = near, fetchedAtMillis = NOW - 24 * hour)
        assertTrue(shouldFetchPreview(stored, near, "key", NOW))

        val far = flight(departureMillis = NOW + 400 * hour)
        val staleFar = FlightPreview(eventId = far.eventId, flight = far, fetchedAtMillis = NOW - 72 * hour)
        assertTrue(shouldFetchPreview(staleFar, far, "key", NOW))
    }

    @Test
    fun shouldFetchPreview_falseWhileTheStoredPreviewIsStillFresh() {
        val near = flight(departureMillis = NOW + 10 * hour)
        val stored = FlightPreview(eventId = near.eventId, flight = near, fetchedAtMillis = NOW - 23 * hour)
        assertFalse(shouldFetchPreview(stored, near, "key", NOW))

        val far = flight(departureMillis = NOW + 400 * hour)
        val freshFar = FlightPreview(eventId = far.eventId, flight = far, fetchedAtMillis = NOW - 71 * hour)
        assertFalse(shouldFetchPreview(freshFar, far, "key", NOW))
    }

    @Test
    fun shouldFetchPreview_storedErrorWithAStampIsNotRefetchedEarly() {
        val subject = flight(departureMillis = NOW + 10 * hour)
        val failed = FlightPreview(
            eventId = subject.eventId,
            flight = subject,
            status = null,
            fetchedAtMillis = NOW - hour,
            error = "No schedule or timetable for SQ509",
        )
        assertFalse(shouldFetchPreview(failed, subject, "key", NOW))
        assertTrue(shouldFetchPreview(failed, subject, "key", NOW + 23 * hour))
    }

    @Test
    fun previewFetchAllowed_falseInsideTheWindowWhileAirportModeOwnsTheFlight() {
        // Lead 300 minutes puts the window start exactly at NOW.
        val subject = flight(departureMillis = NOW + 5 * hour)
        assertFalse(previewFetchAllowed(subject, emptySet(), 300, 180, NOW))
        assertFalse(previewFetchAllowed(subject, setOf(9L), 300, 180, NOW + hour))
    }

    @Test
    fun previewFetchAllowed_trueInsideTheWindowOnceTheFlightIsDismissed() {
        val subject = flight(departureMillis = NOW + 5 * hour)
        assertTrue(previewFetchAllowed(subject, setOf(subject.eventId), 300, 180, NOW))
        assertTrue(previewFetchAllowed(subject, setOf(subject.eventId), 300, 180, NOW + hour))
    }

    @Test
    fun previewFetchAllowed_trueBeforeTheWindowOpens() {
        val subject = flight(departureMillis = NOW + 5 * hour)
        assertTrue(previewFetchAllowed(subject, emptySet(), 300, 180, NOW - 1))
        assertTrue(previewFetchAllowed(subject, emptySet(), 60, 30, NOW))
    }

    @Test
    fun newerStatusForPreview_takesTheAirportStatusWhenItIsNewer() {
        val subject = flight(departureMillis = NOW + 5 * hour)
        val stored = FlightPreview(
            eventId = subject.eventId,
            flight = subject,
            fetchedAtMillis = NOW - 3 * hour,
            error = "Network error",
        )
        val state = airportState(eventId = subject.eventId, fetchedAtMillis = NOW - hour)
        val merged = newerStatusForPreview(stored, state)
        assertEquals(state.lastStatus, merged.status)
        assertEquals(NOW - hour, merged.fetchedAtMillis)
        assertNull(merged.error)
    }

    @Test
    fun newerStatusForPreview_keepsThePreviewWhenItIsAtLeastAsNew() {
        val subject = flight(departureMillis = NOW + 5 * hour)
        val stored = FlightPreview(eventId = subject.eventId, flight = subject, fetchedAtMillis = NOW - hour)
        assertEquals(stored, newerStatusForPreview(stored, airportState(subject.eventId, NOW - hour)))
        assertEquals(stored, newerStatusForPreview(stored, airportState(subject.eventId, NOW - 2 * hour)))
    }

    @Test
    fun newerStatusForPreview_ignoresAStateForAnotherFlightOrWithNoStatus() {
        val subject = flight(departureMillis = NOW + 5 * hour)
        val stored = FlightPreview(eventId = subject.eventId, flight = subject, fetchedAtMillis = null)
        assertEquals(stored, newerStatusForPreview(stored, airportState(eventId = 99L, fetchedAtMillis = NOW)))
        assertEquals(stored, newerStatusForPreview(stored, null))
        assertEquals(
            stored,
            newerStatusForPreview(
                stored,
                airportState(subject.eventId, NOW).copy(lastStatus = null),
            ),
        )
    }

    @Test
    fun previewTapOutcome_nothingWithoutAPreview() {
        assertEquals(PreviewTapOutcome.Nothing, previewTapOutcome(null, setOf(1L), "key", 300, 180, NOW))
    }

    @Test
    fun previewTapOutcome_reopensADismissedFlightInsideItsWindow() {
        val preview = preview(departureMillis = NOW + 5 * hour, fetchedAtMillis = NOW)
        assertEquals(
            PreviewTapOutcome.Reopen,
            previewTapOutcome(preview, setOf(preview.eventId), "key", 300, 180, NOW),
        )
        // Even with no key: reopening the card costs nothing, it reuses the status already stored.
        assertEquals(
            PreviewTapOutcome.Reopen,
            previewTapOutcome(preview, setOf(preview.eventId), "", 300, 180, NOW),
        )
    }

    @Test
    fun previewTapOutcome_reopensADismissedConnectionOnceTheInboundLegWasDismissed() {
        val inbound = flight(eventId = 1L, departureMillis = NOW - 2 * hour)
        val onward = flight(eventId = 2L, departureMillis = NOW + 7 * hour)
        val preview = preview(eventId = 2L, departureMillis = NOW + 7 * hour, fetchedAtMillis = NOW)
        assertEquals(
            PreviewTapOutcome.Reopen,
            previewTapOutcome(preview, setOf(1L, 2L), "key", 300, 180, NOW, listOf(inbound, onward)),
        )
        assertEquals(
            PreviewTapOutcome.Nothing,
            previewTapOutcome(preview, setOf(2L), "key", 300, 180, NOW, listOf(inbound, onward)),
        )
    }

    @Test
    fun previewTapOutcome_fetchesInsideTheWindowWhenTheFlightWasNotDismissed() {
        val preview = preview(departureMillis = NOW + 5 * hour, fetchedAtMillis = NOW - 2 * 60_000L)
        assertEquals(PreviewTapOutcome.Fetch, previewTapOutcome(preview, emptySet(), "key", 300, 180, NOW))
    }

    @Test
    fun previewTapOutcome_nothingInsideTheWindowWhileTheStatusIsFresh() {
        val preview = preview(departureMillis = NOW + 5 * hour, fetchedAtMillis = NOW - 60_000L)
        assertEquals(PreviewTapOutcome.Nothing, previewTapOutcome(preview, emptySet(), "key", 300, 180, NOW))
    }

    @Test
    fun previewTapOutcome_nothingBeforeTheWindowWithoutAKey() {
        val preview = preview(departureMillis = NOW + 50 * hour, fetchedAtMillis = null)
        assertEquals(PreviewTapOutcome.Nothing, previewTapOutcome(preview, emptySet(), "  ", 300, 180, NOW))
    }

    @Test
    fun previewTapOutcome_fetchesBeforeTheWindowWhenTheStatusIsStale() {
        val never = preview(departureMillis = NOW + 50 * hour, fetchedAtMillis = null)
        assertEquals(PreviewTapOutcome.Fetch, previewTapOutcome(never, emptySet(), "key", 300, 180, NOW))

        val stale = preview(departureMillis = NOW + 50 * hour, fetchedAtMillis = NOW - 2 * 60_000L)
        assertEquals(PreviewTapOutcome.Fetch, previewTapOutcome(stale, emptySet(), "key", 300, 180, NOW))
    }

    @Test
    fun previewTapOutcome_nothingBeforeTheWindowAMinuteAfterTheLastFetch() {
        val preview = preview(departureMillis = NOW + 50 * hour, fetchedAtMillis = NOW - 60_000L)
        assertEquals(PreviewTapOutcome.Nothing, previewTapOutcome(preview, emptySet(), "key", 300, 180, NOW))
    }

    @Test
    fun selectActiveFlight_ignoresTheFarFlightsTheWiderPreviewLookaheadAdds() {
        val open = flight(eventId = 1L, departureMillis = NOW + 2 * hour)
        val far = flight(eventId = 2L, departureMillis = NOW + 40 * 24 * hour)
        val selected = selectActiveFlight(
            flights = listOf(open, far),
            state = null,
            nowEpochMillis = NOW,
            pillLeadMinutes = 300,
            arriveAheadMinutes = 180,
            dismissedEventIds = emptySet(),
        )
        assertEquals(open, selected?.flight)
    }

    private companion object {
        /** 2026-10-13T00:00:00Z. */
        const val NOW = 1_791_849_600_000L
    }
}
