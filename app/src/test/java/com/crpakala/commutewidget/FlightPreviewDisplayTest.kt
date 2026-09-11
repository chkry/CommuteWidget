package com.crpakala.commutewidget

import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.FlightPreview
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.data.UpcomingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The three lines of the calendar card's flight row, plus its duplicate suppression. */
class FlightPreviewDisplayTest {
    private val zone = ZoneId.of("UTC")

    /** The one candidate zone AirLabs' "IN" would resolve to on the device, injected here by hand. */
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun millis(hour: Int, minute: Int, day: Int = 13): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun testFlightEvent(
        eventId: Long = 1L,
        title: String = "SQ509 Bengaluru to Singapore",
        airlineCode: String = "SQ",
        flightNumber: String = "509",
        departureMillis: Long = millis(11, 35),
        departureAirportName: String? = "Kempegowda International",
        departureIata: String? = "BLR",
        arrivalAirportName: String? = "Changi",
        arrivalIata: String? = "SIN",
        confirmationNumber: String? = null,
        seat: String? = null,
        departureTerminal: String? = null,
        departureLocalHm: String? = null,
    ): FlightEvent = FlightEvent(
        eventId = eventId,
        calendarId = 7L,
        title = title,
        airlineCode = airlineCode,
        flightNumber = flightNumber,
        departureMillis = departureMillis,
        arrivalMillis = departureMillis + 4 * 60 * 60_000L,
        locationText = null,
        departureAirportName = departureAirportName,
        departureIata = departureIata,
        arrivalAirportName = arrivalAirportName,
        arrivalIata = arrivalIata,
        confirmationNumber = confirmationNumber,
        seat = seat,
        departureTerminal = departureTerminal,
        arrivalTerminal = null,
        departureLocalHm = departureLocalHm,
    )

    private fun testFlightStatus(
        status: String = "scheduled",
        departureScheduledUtcMillis: Long? = null,
        departureEstimatedUtcMillis: Long? = null,
        departureTerminal: String? = null,
        departureGate: String? = null,
        departureDelayedMinutes: Int? = null,
        departureIata: String? = null,
        arrivalIata: String? = null,
        departureAirportName: String? = null,
        arrivalAirportName: String? = null,
        source: String? = "schedules",
        departureUtcOffsetMinutes: Int? = null,
    ): FlightStatus = FlightStatus(
        designator = "SQ509",
        status = status,
        departureScheduledUtcMillis = departureScheduledUtcMillis,
        departureEstimatedUtcMillis = departureEstimatedUtcMillis,
        departureTerminal = departureTerminal,
        departureGate = departureGate,
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
        departureDelayedMinutes = departureDelayedMinutes,
        departureAirportName = departureAirportName,
        arrivalAirportName = arrivalAirportName,
        departureIata = departureIata,
        arrivalIata = arrivalIata,
        source = source,
        departureUtcOffsetMinutes = departureUtcOffsetMinutes,
    )

    private fun preview(
        flight: FlightEvent = testFlightEvent(),
        status: FlightStatus? = null,
        fetchedAtMillis: Long? = null,
        error: String? = null,
        layoverFromDesignator: String? = null,
        layoverMinutes: Int? = null,
        routeDistanceKm: Int? = null,
    ): FlightPreview = FlightPreview(
        eventId = flight.eventId,
        flight = flight,
        status = status,
        fetchedAtMillis = fetchedAtMillis,
        error = error,
        layoverFromDesignator = layoverFromDesignator,
        layoverMinutes = layoverMinutes,
        routeDistanceKm = routeDistanceKm,
    )

    private fun calendarSnapshot(
        destinationLabel: String?,
        eventStartEpochMillis: Long?,
        flightPreview: FlightPreview?,
    ): CommuteSnapshot = CommuteSnapshot(
        direction = Direction.TO_WORK,
        durationSeconds = 0L,
        durationNoTrafficSeconds = 0L,
        distanceMeters = 0L,
        mapImagePath = null,
        fetchedAtEpochMillis = 0L,
        lastFetchFailed = false,
        lastErrorMessage = null,
        destinationLabel = destinationLabel,
        mode = SnapshotMode.CALENDAR_EMPTY,
        eventStartEpochMillis = eventStartEpochMillis,
        flightPreview = flightPreview,
    )

    @Test
    fun flightPreviewTitleLine_usesTheStatusIataWhenItCameBack() {
        val subject = preview(
            flight = testFlightEvent(departureIata = "BOM", arrivalIata = "DXB"),
            status = testFlightStatus(departureIata = "BLR", arrivalIata = "SIN"),
        )
        assertEquals("Tue 13 Oct · SQ509 BLR to SIN", flightPreviewTitleLine(subject, zone))
    }

    @Test
    fun flightPreviewTitleLine_fallsBackToTheCalendarIata() {
        val subject = preview(status = testFlightStatus(departureIata = null, arrivalIata = null))
        assertEquals("Tue 13 Oct · SQ509 BLR to SIN", flightPreviewTitleLine(subject, zone))
    }

    @Test
    fun flightPreviewTitleLine_fallsBackToAirportNames() {
        val subject = preview(flight = testFlightEvent(departureIata = null, arrivalIata = null))
        assertEquals(
            "Tue 13 Oct · SQ509 Kempegowda International to Changi",
            flightPreviewTitleLine(subject, zone),
        )
    }

    @Test
    fun flightPreviewTitleLine_namesTheKnownEndWhenOnlyOneIsKnown() {
        val subject = preview(
            flight = testFlightEvent(
                departureIata = null,
                departureAirportName = null,
                arrivalIata = "SIN",
            ),
        )
        assertEquals("Tue 13 Oct · SQ509 to SIN", flightPreviewTitleLine(subject, zone))
    }

    @Test
    fun flightPreviewTitleLine_readsFlightWhenNothingIsKnown() {
        val subject = preview(
            flight = testFlightEvent(
                airlineCode = "",
                flightNumber = "",
                departureIata = null,
                departureAirportName = null,
                arrivalIata = null,
                arrivalAirportName = null,
            ),
        )
        assertEquals("Tue 13 Oct · Flight", flightPreviewTitleLine(subject, zone))
    }

    @Test
    fun flightPreviewTitleLine_datesFromTheLiveScheduleWhenItMovedToAnotherDay() {
        val subject = preview(status = testFlightStatus(departureScheduledUtcMillis = millis(1, 5, day = 14)))
        assertEquals("Wed 14 Oct · SQ509 BLR to SIN", flightPreviewTitleLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_fullRow() {
        val subject = preview(
            flight = testFlightEvent(confirmationNumber = "ABC123", seat = "14A"),
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                departureTerminal = "2",
                departureGate = "5",
                departureDelayedMinutes = 6,
            ),
        )
        assertEquals(
            "Departs 11:35 am · T2 · Gate 5 · PNR ABC123 · Seat 14A · +6 min",
            flightPreviewDetailLine(subject, zone),
        )
    }

    @Test
    fun flightPreviewDetailLine_calendarOnlyRowIsJustTheDepartureTime() {
        assertEquals("Departs 11:35 am", flightPreviewDetailLine(preview(), zone))
    }

    @Test
    fun flightPreviewDetailLine_calendarTerminalStandsInUntilTheStatusHasOne() {
        val subject = preview(flight = testFlightEvent(departureTerminal = "2"))
        assertEquals("Departs 11:35 am · T2", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_movedEstimateAndDelay() {
        val subject = preview(
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                departureEstimatedUtcMillis = millis(11, 41),
                departureDelayedMinutes = 6,
            ),
        )
        assertEquals("Departs 11:35 am -> 11:41 am · Gate TBA · +6 min", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_estimateWithinAMinuteIsNotShown() {
        val subject = preview(
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                departureEstimatedUtcMillis = millis(11, 35) + 59_000L,
            ),
        )
        assertEquals("Departs 11:35 am · Gate TBA", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_earlyDepartureShowsANegativeDelay() {
        val subject = preview(status = testFlightStatus(departureDelayedMinutes = -12))
        assertEquals("Departs 11:35 am · Gate TBA · -12 min", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_statusWordStandsInForAMissingDelay() {
        val subject = preview(status = testFlightStatus(status = "cancelled"))
        assertEquals("Departs 11:35 am · Gate TBA · Cancelled", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_scheduledStatusWordIsNeverShown() {
        val subject = preview(status = testFlightStatus(status = "scheduled"))
        assertEquals("Departs 11:35 am · Gate TBA", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_timetableSourceSaysGateOnTheDay() {
        val subject = preview(status = testFlightStatus(source = "routes"))
        assertEquals("Departs 11:35 am · Gate on the day", flightPreviewDetailLine(subject, zone))
    }

    /** The row carries no IATA code beside the clock, so the zone it is read in has to be named. */
    @Test
    fun flightPreviewDetailLine_departsInTheDepartureZoneAndNamesIt() {
        val subject = preview(
            flight = testFlightEvent(departureMillis = millis(6, 5)),
            status = testFlightStatus(departureScheduledUtcMillis = millis(6, 5), departureUtcOffsetMinutes = 330),
        )
        assertEquals(
            "Departs 11:35 am IST · 6:05 am UTC · Gate TBA",
            flightPreviewDetailLine(subject, zone, listOf(kolkata)),
        )
    }

    /** No country, no candidates, so the numeric form stands in and the clock is still located. */
    @Test
    fun flightPreviewDetailLine_numericZoneWhenNoCandidateNamesTheOffset() {
        val subject = preview(
            flight = testFlightEvent(departureMillis = millis(6, 5)),
            status = testFlightStatus(departureScheduledUtcMillis = millis(6, 5), departureUtcOffsetMinutes = 330),
        )
        assertEquals("Departs 11:35 am +5:30 · 6:05 am UTC · Gate TBA", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewDetailLine_noPhoneTimeWhenTheAirportSharesTheDeviceOffset() {
        val subject = preview(
            flight = testFlightEvent(departureMillis = millis(6, 5)),
            status = testFlightStatus(departureScheduledUtcMillis = millis(6, 5), departureUtcOffsetMinutes = 0),
        )
        assertEquals("Departs 6:05 am UTC · Gate TBA", flightPreviewDetailLine(subject, zone, listOf(zone)))
    }

    @Test
    fun flightPreviewDetailLine_movedEstimateAlsoReadsInTheDepartureZone() {
        val subject = preview(
            flight = testFlightEvent(departureMillis = millis(6, 5)),
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(6, 5),
                departureEstimatedUtcMillis = millis(6, 11),
                departureUtcOffsetMinutes = 330,
            ),
        )
        assertEquals(
            "Departs 11:35 am -> 11:41 am IST · 6:05 am UTC · Gate TBA · +6 min",
            flightPreviewDetailLine(subject, zone, listOf(kolkata)),
        )
    }

    @Test
    fun flightPreviewDetailLine_calendarOnlyUsesThePrintedRouteTime() {
        val subject = preview(flight = testFlightEvent(departureMillis = millis(6, 5), departureLocalHm = "11:35"))
        assertEquals("Departs 11:35 am", flightPreviewDetailLine(subject, zone))
    }

    @Test
    fun flightPreviewBlock_timeUsesTheDepartureZoneAndNamesIt() {
        val subject = preview(
            flight = testFlightEvent(departureMillis = millis(6, 5)),
            status = testFlightStatus(departureScheduledUtcMillis = millis(6, 5), departureUtcOffsetMinutes = 330),
        )
        val block = flightPreviewBlock(
            preview = subject,
            eventStartEpochMillis = null,
            nowEpochMillis = millis(5, 0),
            hasFlightStatusKey = true,
            zone = zone,
            departureZones = listOf(kolkata),
        )
        assertEquals("11:35 am IST · 6:05 am UTC", block.time)
    }

    /** The zone name and the phone reading both land on the row, so its longest form has to fit. */
    @Test
    fun flightPreviewDetailLine_staysInsideTheTwoLineWidth() {
        val subject = preview(
            flight = testFlightEvent(
                departureMillis = millis(6, 5),
                departureTerminal = "2",
                confirmationNumber = "FOICCE",
                seat = "14A",
            ),
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(6, 5),
                departureEstimatedUtcMillis = millis(8, 5),
                departureGate = "D2 D3",
                departureUtcOffsetMinutes = 660,
                departureDelayedMinutes = 120,
            ),
        )
        val line = flightPreviewDetailLine(subject, zone, listOf(ZoneId.of("Australia/Sydney")))
        assertEquals(
            "Departs 5:05 pm -> 7:05 pm AEDT · 6:05 am UTC · T2 · Gate D2 D3 · PNR FOICCE · Seat 14A · +120 min",
            line,
        )
        assertTrue(line.length <= FLIGHT_PREVIEW_LINE_MAX_CHARS)
    }

    @Test
    fun flightPreviewNoteLine_timetableOnlyForARoutesStatus() {
        val subject = preview(status = testFlightStatus(source = "routes"), fetchedAtMillis = millis(9, 0))
        assertEquals("Timetable only", flightPreviewNoteLine(subject, hasFlightStatusKey = true))
    }

    @Test
    fun flightPreviewNoteLine_statusUnavailableWhenOnlyAnErrorIsStored() {
        val subject = preview(fetchedAtMillis = millis(9, 0), error = "AirLabs quota exceeded")
        assertEquals("Status unavailable", flightPreviewNoteLine(subject, hasFlightStatusKey = true))
    }

    @Test
    fun flightPreviewNoteLine_anErrorBesideAGoodStatusKeepsTheRowQuiet() {
        val subject = preview(
            status = testFlightStatus(departureGate = "5"),
            fetchedAtMillis = millis(9, 0),
            error = "Network error",
        )
        assertNull(flightPreviewNoteLine(subject, hasFlightStatusKey = true))
    }

    @Test
    fun flightPreviewNoteLine_invitesTheKeyWhenNothingWasEverFetched() {
        assertEquals(
            "Add AirLabs key for gate and delays",
            flightPreviewNoteLine(preview(), hasFlightStatusKey = false),
        )
    }

    @Test
    fun flightPreviewNoteLine_silentWhenAKeyIsConfiguredAndNoFetchHasLanded() {
        assertNull(flightPreviewNoteLine(preview(), hasFlightStatusKey = true))
    }

    @Test
    fun flightPreviewIdentity_designatorAndRoute() {
        assertEquals("SQ509 BLR to SIN", flightPreviewIdentity(preview()))
    }

    @Test
    fun flightPreviewBlock_theSpecsMergedBlock() {
        val subject = preview(
            flight = testFlightEvent(confirmationNumber = "FOICCE"),
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                departureTerminal = "2",
                departureGate = "D2",
            ),
        )
        val block = flightPreviewBlock(
            preview = subject,
            eventStartEpochMillis = millis(11, 35),
            nowEpochMillis = millis(5, 30),
            hasFlightStatusKey = true,
            zone = zone,
        )
        assertEquals("Next flight", block.heading)
        assertEquals("in 6h 5m", block.countdown)
        assertEquals("SQ509 BLR to SIN", block.identity)
        assertEquals("11:35 am", block.time)
        assertEquals("T2 · Gate D2 · PNR FOICCE", block.detail)
        assertNull(block.caption)
        assertNull(block.calendarLine)
    }

    @Test
    fun flightPreviewBlock_countsToTheFlightNotTheCalendarEntry() {
        val subject = preview(status = testFlightStatus(departureScheduledUtcMillis = millis(11, 35)))
        val block = flightPreviewBlock(
            preview = subject,
            eventStartEpochMillis = millis(10, 30),
            nowEpochMillis = millis(10, 5),
            hasFlightStatusKey = true,
            zone = zone,
        )
        assertEquals("in 1h 30m", block.countdown)
        assertEquals("11:35 am", block.time)
        assertEquals("Calendar 10:30 am", block.calendarLine)
    }

    @Test
    fun flightPreviewBlock_noSecondClockWhenTheCalendarAgreesWithinFiveMinutes() {
        val block = flightPreviewBlock(
            preview = preview(),
            eventStartEpochMillis = millis(11, 31),
            nowEpochMillis = millis(9, 0),
            hasFlightStatusKey = true,
            zone = zone,
        )
        assertNull(block.calendarLine)
    }

    @Test
    fun flightPreviewBlock_datedHeadingOnlyWhenTwoOrMoreDaysOut() {
        val subject = preview(flight = testFlightEvent(departureMillis = millis(11, 35, day = 16)))
        assertEquals(
            "Next flight",
            flightPreviewBlock(subject, null, millis(9, 0, day = 15), true, zone).heading,
        )
        assertEquals(
            "Fri 16 Oct",
            flightPreviewBlock(subject, null, millis(9, 0, day = 13), true, zone).heading,
        )
    }

    @Test
    fun flightPreviewBlock_connectionCaptionNamesTheArrivingLeg() {
        val subject = preview(layoverFromDesignator = "SQ509", layoverMinutes = 130)
        val block = flightPreviewBlock(subject, null, millis(9, 0), true, zone)
        assertEquals("Layover 2 h 10 min after SQ509", block.caption)
    }

    @Test
    fun flightPreviewBlock_noteLineFillsTheCaptionWhenThereIsNoConnection() {
        val subject = preview(status = testFlightStatus(source = "routes"), fetchedAtMillis = millis(9, 0))
        assertEquals("Timetable only", flightPreviewBlock(subject, null, millis(8, 0), true, zone).caption)
    }

    @Test
    fun flightPreviewBlock_gateTbaRuleAppliesToTheDetailLine() {
        val subject = preview(status = testFlightStatus(departureTerminal = "2"))
        assertEquals("T2 · Gate TBA", flightPreviewBlock(subject, null, millis(9, 0), true, zone).detail)
    }

    @Test
    fun flightPreviewBlock_detailIsNullWhenNothingWasEverFetchedAndNoTerminalIsKnown() {
        assertNull(flightPreviewBlock(preview(), null, millis(9, 0), true, zone).detail)
    }

    @Test
    fun headlineIsPreviewedFlight_trueWhenTitleAndStartBothMatch() {
        val subject = preview()
        assertTrue(
            headlineIsPreviewedFlight(
                calendarSnapshot(subject.flight.title, subject.flight.departureMillis, subject),
            ),
        )
    }

    @Test
    fun headlineIsPreviewedFlight_falseWhenEitherHalfDiffers() {
        val subject = preview()
        assertFalse(
            headlineIsPreviewedFlight(calendarSnapshot("Dentist", subject.flight.departureMillis, subject)),
        )
        assertFalse(
            headlineIsPreviewedFlight(calendarSnapshot(subject.flight.title, millis(9, 0), subject)),
        )
    }

    @Test
    fun headlineIsPreviewedFlight_falseWithNoPreviewOrNoHeadline() {
        val subject = preview()
        assertFalse(headlineIsPreviewedFlight(calendarSnapshot(subject.flight.title, subject.flight.departureMillis, null)))
        assertFalse(headlineIsPreviewedFlight(calendarSnapshot(subject.flight.title, null, subject)))
    }

    @Test
    fun upcomingEventsWithoutPreview_dropsThePreviewedFlightsOwnEvent() {
        val flight = testFlightEvent()
        val events = listOf(
            UpcomingEvent(title = flight.title, startEpochMillis = flight.departureMillis),
            UpcomingEvent(title = "Standup", startEpochMillis = millis(9, 30)),
        )
        assertEquals(
            listOf(UpcomingEvent(title = "Standup", startEpochMillis = millis(9, 30))),
            upcomingEventsWithoutPreview(events, preview(flight)),
        )
    }

    @Test
    fun upcomingEventsWithoutPreview_keepsAnEventThatOnlyMatchesOnOneHalf() {
        val flight = testFlightEvent()
        val events = listOf(
            UpcomingEvent(title = flight.title, startEpochMillis = millis(9, 30)),
            UpcomingEvent(title = "Standup", startEpochMillis = flight.departureMillis),
        )
        assertEquals(events, upcomingEventsWithoutPreview(events, preview(flight)))
    }

    @Test
    fun upcomingEventsWithoutPreview_keepsEverythingWhenThereIsNoPreview() {
        val events = listOf(UpcomingEvent(title = "Standup", startEpochMillis = millis(9, 30)))
        assertEquals(events, upcomingEventsWithoutPreview(events, null))
    }

    @Test
    fun isWindDown_falseWhenThePreviewedFlightWasTheOnlyUpcomingEvent() {
        val flight = testFlightEvent()
        val snapshot = calendarEmptySnapshot(
            upcomingEvents = listOf(
                UpcomingEvent(title = flight.title, startEpochMillis = flight.departureMillis),
            ),
            flightPreview = preview(flight),
        )
        assertFalse(isWindDown(snapshot))
    }

    @Test
    fun isWindDown_trueWhenANonFlightEventRemains() {
        val flight = testFlightEvent()
        val snapshot = calendarEmptySnapshot(
            upcomingEvents = listOf(
                UpcomingEvent(title = flight.title, startEpochMillis = flight.departureMillis),
                UpcomingEvent(title = "Standup", startEpochMillis = millis(9, 30)),
            ),
            flightPreview = preview(flight),
        )
        assertTrue(isWindDown(snapshot))
    }

    private fun calendarEmptySnapshot(
        upcomingEvents: List<UpcomingEvent> = emptyList(),
        flightPreview: FlightPreview? = null,
    ): CommuteSnapshot = CommuteSnapshot(
        direction = Direction.TO_WORK,
        durationSeconds = 0L,
        durationNoTrafficSeconds = 0L,
        distanceMeters = 0L,
        mapImagePath = null,
        fetchedAtEpochMillis = 0L,
        lastFetchFailed = false,
        lastErrorMessage = null,
        mode = SnapshotMode.CALENDAR_EMPTY,
        upcomingEvents = upcomingEvents,
        flightPreview = flightPreview,
    )
}
