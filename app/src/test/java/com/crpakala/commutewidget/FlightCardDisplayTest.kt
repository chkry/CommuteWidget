package com.crpakala.commutewidget

import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AircraftRecord
import com.crpakala.commutewidget.data.AirportDelayStats
import com.crpakala.commutewidget.data.AirportPhase
import com.crpakala.commutewidget.data.AirportSnapshot
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The flight card's text, one assertion per rendered string. Sample data throughout is the spec's:
 * SQ509 Singapore Airlines BLR to SIN, 11:35 am to 4:30 pm, 4 h 55 min, 3,140 km, T2 Gate D2,
 * Belt 4, PNR FOICCE, Seat 14A, Airbus A350-900 reg 9V-SMA.
 */
class FlightCardDisplayTest {
    private val zone = ZoneId.of("UTC")

    /** The owner's own device zone, +5:30, used wherever a test has to tell it from an airport's. */
    private val india = ZoneId.of("Asia/Kolkata")

    /**
     * The candidate zones the device would get from ICU for "SG", "IN" and "AU". Injected by hand
     * because android.icu does not exist on this JVM, which is why every helper takes them.
     */
    private val singapore = ZoneId.of("Asia/Singapore")
    private val sydney = ZoneId.of("Australia/Sydney")
    private val dublin = ZoneId.of("Europe/Dublin")

    /** Southern summer and northern summer, for the zones whose name changes with the season. */
    private fun januaryMillis(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2027, 1, 15, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun julyMillis(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2027, 7, 15, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun millis(hour: Int, minute: Int, day: Int = 10): Long =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun testFlightEvent(
        eventId: Long = 1L,
        calendarId: Long = 1L,
        title: String = "Flight SQ509",
        airlineCode: String = "SQ",
        flightNumber: String = "509",
        departureMillis: Long = millis(11, 35),
        arrivalMillis: Long = millis(16, 30),
        locationText: String? = null,
        departureAirportName: String? = "Kempegowda Intl",
        departureIata: String? = "BLR",
        arrivalAirportName: String? = "Changi",
        arrivalIata: String? = "SIN",
        confirmationNumber: String? = null,
        seat: String? = null,
        departureTerminal: String? = null,
        arrivalTerminal: String? = null,
        departureLocalHm: String? = null,
        arrivalLocalHm: String? = null,
    ): FlightEvent = FlightEvent(
        eventId = eventId,
        calendarId = calendarId,
        title = title,
        airlineCode = airlineCode,
        flightNumber = flightNumber,
        departureMillis = departureMillis,
        arrivalMillis = arrivalMillis,
        locationText = locationText,
        departureAirportName = departureAirportName,
        departureIata = departureIata,
        arrivalAirportName = arrivalAirportName,
        arrivalIata = arrivalIata,
        confirmationNumber = confirmationNumber,
        seat = seat,
        departureTerminal = departureTerminal,
        arrivalTerminal = arrivalTerminal,
        departureLocalHm = departureLocalHm,
        arrivalLocalHm = arrivalLocalHm,
    )

    private fun testFlightStatus(
        designator: String = "SQ509",
        status: String = "scheduled",
        departureScheduledUtcMillis: Long? = null,
        departureEstimatedUtcMillis: Long? = null,
        departureTerminal: String? = null,
        departureGate: String? = null,
        departureCheckInDesk: String? = null,
        arrivalScheduledUtcMillis: Long? = null,
        arrivalEstimatedUtcMillis: Long? = null,
        arrivalTerminal: String? = null,
        arrivalGate: String? = null,
        arrivalBaggageBelt: String? = null,
        aircraftModel: String? = null,
        aircraftRegistration: String? = null,
        operatingAirline: String? = null,
        codeshareOf: String? = null,
        lastUpdatedUtcMillis: Long? = null,
        departureActualUtcMillis: Long? = null,
        arrivalActualUtcMillis: Long? = null,
        departureDelayedMinutes: Int? = null,
        arrivalDelayedMinutes: Int? = null,
        durationMinutes: Int? = null,
        airlineName: String? = null,
        departureAirportName: String? = null,
        departureCity: String? = null,
        arrivalAirportName: String? = null,
        arrivalCity: String? = null,
        departureIata: String? = null,
        arrivalIata: String? = null,
        aircraftIcao: String? = null,
        percentComplete: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        altitudeMeters: Int? = null,
        speedKmh: Int? = null,
        headingDegrees: Int? = null,
        source: String? = "schedules",
        departureUtcOffsetMinutes: Int? = null,
        arrivalUtcOffsetMinutes: Int? = null,
        departureCountry: String? = null,
        arrivalCountry: String? = null,
        airlineIata: String? = null,
        airlineIcao: String? = null,
        aircraftManufacturer: String? = null,
        aircraftSerialNumber: String? = null,
        aircraftEngineType: String? = null,
        aircraftEngineCount: Int? = null,
        aircraftBuiltYear: Int? = null,
        aircraftAgeYears: Int? = null,
        verticalSpeedKmh: Int? = null,
        transponderCode: String? = null,
    ): FlightStatus = FlightStatus(
        designator = designator,
        status = status,
        departureScheduledUtcMillis = departureScheduledUtcMillis,
        departureEstimatedUtcMillis = departureEstimatedUtcMillis,
        departureTerminal = departureTerminal,
        departureGate = departureGate,
        departureCheckInDesk = departureCheckInDesk,
        arrivalScheduledUtcMillis = arrivalScheduledUtcMillis,
        arrivalEstimatedUtcMillis = arrivalEstimatedUtcMillis,
        arrivalTerminal = arrivalTerminal,
        arrivalGate = arrivalGate,
        arrivalBaggageBelt = arrivalBaggageBelt,
        aircraftModel = aircraftModel,
        aircraftRegistration = aircraftRegistration,
        operatingAirline = operatingAirline,
        codeshareOf = codeshareOf,
        lastUpdatedUtcMillis = lastUpdatedUtcMillis,
        departureActualUtcMillis = departureActualUtcMillis,
        arrivalActualUtcMillis = arrivalActualUtcMillis,
        departureDelayedMinutes = departureDelayedMinutes,
        arrivalDelayedMinutes = arrivalDelayedMinutes,
        durationMinutes = durationMinutes,
        airlineName = airlineName,
        departureAirportName = departureAirportName,
        departureCity = departureCity,
        arrivalAirportName = arrivalAirportName,
        arrivalCity = arrivalCity,
        departureIata = departureIata,
        arrivalIata = arrivalIata,
        aircraftIcao = aircraftIcao,
        percentComplete = percentComplete,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        speedKmh = speedKmh,
        headingDegrees = headingDegrees,
        source = source,
        departureUtcOffsetMinutes = departureUtcOffsetMinutes,
        arrivalUtcOffsetMinutes = arrivalUtcOffsetMinutes,
        departureCountry = departureCountry,
        arrivalCountry = arrivalCountry,
        airlineIata = airlineIata,
        airlineIcao = airlineIcao,
        aircraftManufacturer = aircraftManufacturer,
        aircraftSerialNumber = aircraftSerialNumber,
        aircraftEngineType = aircraftEngineType,
        aircraftEngineCount = aircraftEngineCount,
        aircraftBuiltYear = aircraftBuiltYear,
        aircraftAgeYears = aircraftAgeYears,
        verticalSpeedKmh = verticalSpeedKmh,
        transponderCode = transponderCode,
    )

    private fun testAirportSnapshot(
        flight: FlightEvent = testFlightEvent(),
        phase: AirportPhase = AirportPhase.REACHED,
        windowStartMillis: Long = 0L,
        arriveByMillis: Long = 0L,
        originLabel: String? = null,
        airportLat: Double? = null,
        airportLng: Double? = null,
        travelMinutes: Int? = null,
        distanceMeters: Int? = null,
        leaveByMillis: Long? = null,
        bestDepartureMillis: Long? = null,
        bestTravelMinutes: Int? = null,
        status: FlightStatus? = null,
        statusFetchedAtMillis: Long? = null,
        statusError: String? = null,
        reachedOffered: Boolean = false,
        layover: Boolean = false,
        layoverFromDesignator: String? = null,
        layoverFromArrivalMillis: Long? = null,
        routeDistanceKm: Int? = null,
        aircraft: AircraftRecord? = null,
        departureDelayStats: AirportDelayStats? = null,
    ): AirportSnapshot = AirportSnapshot(
        flight = flight,
        phase = phase,
        windowStartMillis = windowStartMillis,
        arriveByMillis = arriveByMillis,
        originLabel = originLabel,
        airportLat = airportLat,
        airportLng = airportLng,
        travelMinutes = travelMinutes,
        distanceMeters = distanceMeters,
        leaveByMillis = leaveByMillis,
        bestDepartureMillis = bestDepartureMillis,
        bestTravelMinutes = bestTravelMinutes,
        status = status,
        statusFetchedAtMillis = statusFetchedAtMillis,
        statusError = statusError,
        reachedOffered = reachedOffered,
        layover = layover,
        layoverFromDesignator = layoverFromDesignator,
        layoverFromArrivalMillis = layoverFromArrivalMillis,
        routeDistanceKm = routeDistanceKm,
        aircraft = aircraft,
        departureDelayStats = departureDelayStats,
    )

    /** The spec's on-time REACHED card: SQ509 Singapore Airlines, T2 Gate D2, belt 4, A350. */
    private fun onTimeAirport(
        phase: AirportPhase = AirportPhase.REACHED,
        status: FlightStatus? = testFlightStatus(
            departureScheduledUtcMillis = millis(11, 35),
            arrivalScheduledUtcMillis = millis(16, 30),
            departureTerminal = "2",
            departureGate = "D2",
            departureCheckInDesk = "E",
            arrivalTerminal = "3",
            arrivalBaggageBelt = "4",
            durationMinutes = 295,
            airlineName = "Singapore Airlines",
            departureAirportName = "Kempegowda Intl",
            departureCity = "Bengaluru",
            arrivalAirportName = "Changi",
            arrivalCity = "Singapore",
            departureIata = "BLR",
            arrivalIata = "SIN",
            aircraftIcao = "A359",
            aircraftRegistration = "9V-SMA",
        ),
        routeDistanceKm: Int? = 3_140,
    ): AirportSnapshot = testAirportSnapshot(
        flight = testFlightEvent(confirmationNumber = "FOICCE", seat = "14A"),
        phase = phase,
        status = status,
        statusFetchedAtMillis = millis(3, 20),
        routeDistanceKm = routeDistanceKm,
    )

    @Test
    fun capitaliseStatusWord_leavesAlreadyCapitalisedAlone() {
        assertEquals("Scheduled", capitaliseStatusWord("scheduled"))
        assertEquals("On time", capitaliseStatusWord("On time"))
    }

    @Test
    fun flightCardAirlineLine_nameThenBothCodes() {
        assertEquals("Singapore Airlines · SQ / SIA", flightCardAirlineLine(fullStatus))
    }

    @Test
    fun flightCardAirlineLine_nameAloneWhenNoCodesLanded() {
        assertEquals("Singapore Airlines", flightCardAirlineLine(testFlightStatus(airlineName = "Singapore Airlines")))
    }

    @Test
    fun flightCardAirlineLine_namesTheOperatingCarrierOnACodeshare() {
        val status = testFlightStatus(
            airlineName = "KLM",
            airlineIata = "KL",
            operatingAirline = "IndiGo",
            codeshareOf = "6E7005",
        )

        assertEquals("KLM · KL · operated by IndiGo · codeshare of 6E7005", flightCardAirlineLine(status))
    }

    @Test
    fun flightCardAirlineLine_fallsBackToTheOperatingCarrierAsTheName() {
        assertEquals("IndiGo", flightCardAirlineLine(testFlightStatus(operatingAirline = "IndiGo")))
    }

    @Test
    fun flightCardAirlineLine_nullWhenNothingIsKnown() {
        assertNull(flightCardAirlineLine(null))
        assertNull(flightCardAirlineLine(testFlightStatus()))
    }

    @Test
    fun flightStatusChip_scheduledWithNoDelayIsQuietlyOnTime() {
        val chip = flightStatusChip("scheduled", null, "schedules")
        assertEquals("On time", chip.label)
        assertEquals(FlightChipTone.QUIET, chip.tone)
    }

    @Test
    fun flightStatusChip_scheduledWithADelayIsDelayed() {
        val chip = flightStatusChip("scheduled", 25, "schedules")
        assertEquals("Delayed", chip.label)
        assertEquals(FlightChipTone.WARN, chip.tone)
    }

    @Test
    fun flightStatusChip_subMinuteDelayStaysOnTime() {
        assertEquals("On time", flightStatusChip("scheduled", 0, "schedules").label)
        assertEquals("On time", flightStatusChip("scheduled", -5, "schedules").label)
    }

    @Test
    fun flightStatusChip_activeAndEnRouteAreInTheAir() {
        val active = flightStatusChip("active", null, "flight")
        assertEquals("In the air", active.label)
        assertEquals(FlightChipTone.LIVE, active.tone)
        assertEquals("In the air", flightStatusChip("en-route", null, "flight").label)
    }

    @Test
    fun flightStatusChip_landedIsLive() {
        val chip = flightStatusChip("landed", null, "flight")
        assertEquals("Landed", chip.label)
        assertEquals(FlightChipTone.LIVE, chip.tone)
    }

    @Test
    fun flightStatusChip_delayedWord() {
        val chip = flightStatusChip("delayed", null, "schedules")
        assertEquals("Delayed", chip.label)
        assertEquals(FlightChipTone.WARN, chip.tone)
    }

    @Test
    fun flightStatusChip_cancelledIsAnError() {
        val chip = flightStatusChip("cancelled", null, "schedules")
        assertEquals("Cancelled", chip.label)
        assertEquals(FlightChipTone.ERROR, chip.tone)
    }

    @Test
    fun flightStatusChip_divertedRedirectedAndIncidentAllReadDiverted() {
        listOf("diverted", "redirected", "incident").forEach {
            val chip = flightStatusChip(it, null, "flight")
            assertEquals("Diverted", chip.label)
            assertEquals(FlightChipTone.ERROR, chip.tone)
        }
    }

    @Test
    fun flightStatusChip_routesSourceIsAlwaysTimetable() {
        assertEquals("Timetable", flightStatusChip("scheduled", null, "routes").label)
        assertEquals("Timetable", flightStatusChip("active", null, "routes").label)
        assertEquals(FlightChipTone.QUIET, flightStatusChip("cancelled", 30, "routes").tone)
    }

    @Test
    fun flightStatusChip_neverFetchedInvitesTheTap() {
        val chip = flightStatusChip(null, null, null)
        assertEquals("Tap", chip.label)
        assertEquals(FlightChipTone.QUIET, chip.tone)
        assertEquals("Tap", flightStatusChip("something new", null, "flight").label)
    }

    @Test
    fun flightCardIsAirborne_onlyForActiveAndEnRoute() {
        assertTrue(flightCardIsAirborne(testFlightStatus(status = "active")))
        assertTrue(flightCardIsAirborne(testFlightStatus(status = "En-Route")))
        assertFalse(flightCardIsAirborne(testFlightStatus(status = "scheduled")))
        assertFalse(flightCardIsAirborne(null))
    }

    @Test
    fun unknownGateLabel_saysTbaLiveAndOnTheDayFromTheTimetable() {
        assertEquals("Gate TBA", unknownGateLabel("schedules"))
        assertEquals("Gate TBA", unknownGateLabel(null))
        assertEquals("Gate on the day", unknownGateLabel("routes"))
    }

    @Test
    fun formatTerminalGateOrTba_keepsTheGateSlotFilled() {
        assertEquals("T2 · Gate D2", formatTerminalGateOrTba("2", "D2", "schedules"))
        assertEquals("T2 · Gate TBA", formatTerminalGateOrTba("2", null, "schedules"))
        assertEquals("Gate on the day", formatTerminalGateOrTba(null, null, "routes"))
    }

    @Test
    fun formatTerminalGate_omitsMissingHalf() {
        assertEquals("T1 · Gate 7", formatTerminalGate("1", "7"))
        assertEquals("T1", formatTerminalGate("1", null))
        assertEquals("Gate 7", formatTerminalGate(null, "7"))
        assertNull(formatTerminalGate(null, null))
    }

    @Test
    fun formatArrivalClockTime_suffixesTheNextLocalDay() {
        assertEquals("4:30 pm", formatArrivalClockTime(millis(11, 35), millis(16, 30), zone))
        assertEquals("5:30 am +1", formatArrivalClockTime(millis(18, 40), millis(5, 30, day = 11), zone))
        assertEquals("5:30 am +2", formatArrivalClockTime(millis(18, 40), millis(5, 30, day = 12), zone))
    }

    @Test
    fun formatRouteDistanceKm_thousandsSeparator() {
        assertEquals("3,140 km", formatRouteDistanceKm(3_140))
        assertEquals("940 km", formatRouteDistanceKm(940))
    }

    @Test
    fun progressBarCells_thirteenCellsRoundedToTheNearest() {
        val bar = progressBarCells(62)
        assertEquals("████████", bar.filled)
        assertEquals("░░░░░", bar.empty)
        assertEquals(FLIGHT_PROGRESS_CELLS, bar.filled.length + bar.empty.length)
    }

    @Test
    fun progressBarCells_clampsTheEnds() {
        assertEquals("", progressBarCells(-10).filled)
        assertEquals("", progressBarCells(140).empty)
        assertEquals(FLIGHT_PROGRESS_CELLS, progressBarCells(100).filled.length)
    }

    @Test
    fun flightRemainingLabel_countsDownLocally() {
        val now = millis(15, 3)
        assertEquals("1 h 52 min left", flightRemainingLabel(millis(16, 55), now))
        assertEquals("Arriving", flightRemainingLabel(millis(15, 3), now))
    }

    @Test
    fun flightCardTelemetryLine_speedAndAltitude() {
        val status = testFlightStatus(speedKmh = 890, altitudeMeters = 11_000)
        assertEquals("890 km/h · 11,000 m", flightCardTelemetryLine(status))
        assertNull(flightCardTelemetryLine(testFlightStatus()))
        assertNull(flightCardTelemetryLine(null))
    }

    @Test
    fun flightCardTelemetryLine_vectorsAppendTheVerticalSpeedHeadingAndSquawk() {
        assertEquals(
            "819 km/h · 11,640 m · -3 km/h · 242 deg · Squawk 2000",
            flightCardTelemetryLine(fullStatus, vectors = true),
        )
    }

    @Test
    fun flightCardTelemetryLine_vectorsSkipTheSquawkWhenNoneWasSent() {
        assertEquals(
            "819 km/h · 11,640 m · -3 km/h · 242 deg",
            flightCardTelemetryLine(fullStatus.copy(transponderCode = null), vectors = true),
        )
    }

    @Test
    fun flightDistanceProgressLine_cutsTheRouteAtThePercent() {
        assertEquals("858 km flown · 2,321 km to go", flightDistanceProgressLine(3_179, 27))
        assertEquals("0 km flown · 3,179 km to go", flightDistanceProgressLine(3_179, 0))
        assertEquals("3,179 km flown · 0 km to go", flightDistanceProgressLine(3_179, 100))
    }

    @Test
    fun flightDistanceProgressLine_clampsThePercentAndNeedsARoute() {
        assertEquals("3,179 km flown · 0 km to go", flightDistanceProgressLine(3_179, 140))
        assertEquals("0 km flown · 3,179 km to go", flightDistanceProgressLine(3_179, -20))
        assertNull(flightDistanceProgressLine(null, 27))
    }

    @Test
    fun flightCardTelemetryLine_vectorsAndSquawkAreAbsentFromTheWideLine() {
        assertEquals("819 km/h · 11,640 m", flightCardTelemetryLine(fullStatus))
    }

    @Test
    fun flightCardZoneShiftLine_destinationAhead() {
        assertEquals(
            "SIN is 2 h 30 min ahead of BLR",
            flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = 480)),
        )
    }

    @Test
    fun flightCardZoneShiftLine_destinationBehind() {
        assertEquals(
            "SIN is 4 h 30 min behind BLR",
            flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = 60)),
        )
    }

    @Test
    fun flightCardZoneShiftLine_halfHourAndWholeHourShiftsReadDifferently() {
        assertEquals(
            "SIN is 1 h 30 min behind BLR",
            flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = 240)),
        )
        assertEquals(
            "SIN is 3 h ahead of BLR",
            flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = 510)),
        )
        assertEquals(
            "SIN is 30 min ahead of BLR",
            flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = 360)),
        )
    }

    @Test
    fun flightCardZoneShiftLine_sameZone() {
        assertEquals(
            "SIN and BLR share a time zone",
            flightCardZoneShiftLine(zoneStatus(departure = 480, arrival = 480)),
        )
    }

    @Test
    fun flightCardZoneShiftLine_nullWheneverAnEndCannotBeNamedOrLocated() {
        assertNull(flightCardZoneShiftLine(null))
        assertNull(flightCardZoneShiftLine(zoneStatus(departure = null, arrival = 480)))
        assertNull(flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = null)))
        assertNull(flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = 480, departureIata = null)))
        assertNull(flightCardZoneShiftLine(zoneStatus(departure = 330, arrival = 480, arrivalIata = null)))
    }

    /** The sentence rides a 160 dp cell at 10 sp, which holds 35 characters at text scale 115. */
    @Test
    fun flightCardZoneShiftLine_staysInsideItsCell() {
        val widest = flightCardZoneShiftLine(
            zoneStatus(departure = -480, arrival = 765, departureIata = "LAX", arrivalIata = "AKL"),
        )
        assertEquals("AKL is 20 h 45 min ahead of LAX", widest)
        assertTrue(widest!!.length <= 35)
    }

    @Test
    fun flightCardFleetLine_everyDetailTheRecordCarries() {
        assertEquals(
            "Fleet: Boeing, MSN 61234, 2 x jet, built 2021 (4 y)",
            flightCardFleetLine(null, fleetRecord),
        )
    }

    @Test
    fun flightCardFleetLine_dropsTheHalvesNeitherSourceCarries() {
        assertEquals(
            "Fleet: Boeing, built 2021",
            flightCardFleetLine(
                null,
                fleetRecord.copy(serialNumber = null, engineCount = null, engineType = null, ageYears = null),
            ),
        )
        assertEquals(
            "Fleet: MSN 61234, 4 y old",
            flightCardFleetLine(
                null,
                fleetRecord.copy(manufacturer = null, engineCount = null, engineType = null, builtYear = null),
            ),
        )
    }

    @Test
    fun flightCardFleetLine_readsTheStatusWhenNoRecordLanded() {
        assertEquals(
            "Fleet: Airbus, MSN 0555, 2 x jet, built 2016 (9 y)",
            flightCardFleetLine(fullStatus, null),
        )
    }

    @Test
    fun flightCardFleetLine_recordOutranksTheStatusDetailByDetail() {
        assertEquals(
            "Fleet: Boeing, MSN 61234, 2 x jet, built 2021 (4 y)",
            flightCardFleetLine(fullStatus, fleetRecord),
        )
    }

    @Test
    fun flightCardFleetLine_printsWhateverAirLabsPutsInTheEngineField() {
        // "jet" is all the plan ever sends today; a type string is printed verbatim either way.
        assertEquals(
            "Fleet: 2 x jet",
            flightCardFleetLine(testFlightStatus(aircraftEngineType = "jet", aircraftEngineCount = 2), null),
        )
        assertEquals(
            "Fleet: 2 x GE9X",
            flightCardFleetLine(testFlightStatus(aircraftEngineType = "GE9X", aircraftEngineCount = 2), null),
        )
        assertEquals(
            "Fleet: turboprop",
            flightCardFleetLine(testFlightStatus(aircraftEngineType = "turboprop"), null),
        )
    }

    /** A bare count with no type reads as a count, never as a stray number in the comma list. */
    @Test
    fun flightCardFleetLine_countWithoutATypeSaysEngines() {
        assertEquals("Fleet: 4 engines", flightCardFleetLine(testFlightStatus(aircraftEngineCount = 4), null))
        assertEquals("Fleet: 1 engine", flightCardFleetLine(testFlightStatus(aircraftEngineCount = 1), null))
    }

    @Test
    fun flightCardFleetLine_nullWhenEveryDetailIsNull() {
        assertNull(flightCardFleetLine(null, null))
        assertNull(flightCardFleetLine(testFlightStatus(), emptyFleetRecord))
    }

    @Test
    fun flightCardCompactAirlineCell_airlineKeepsTheCellWhenThereIsNoBooking() {
        assertEquals("Singapore Airlines", flightCardCompactAirlineCell("Singapore Airlines", null))
        assertNull(flightCardCompactAirlineCell(null, null))
    }

    @Test
    fun flightCardCompactAirlineCell_airlineJoinsAShortBooking() {
        assertEquals(
            "Singapore Airlines · PNR FOICCE",
            flightCardCompactAirlineCell("Singapore Airlines", "PNR FOICCE"),
        )
    }

    @Test
    fun flightCardCompactAirlineCell_bookingWinsTheCellWhenBothWouldNotFit() {
        val cell = flightCardCompactAirlineCell("Singapore Airlines", "PNR FOICCE · Seat 14A")
        assertEquals("PNR FOICCE · Seat 14A", cell)
        assertTrue(cell!!.length <= FLIGHT_COMPACT_CELL_MAX_CHARS)
    }

    @Test
    fun flightCardCompactAirlineCell_bookingAloneWhenNoAirlineLanded() {
        assertEquals("PNR FOICCE", flightCardCompactAirlineCell(null, "PNR FOICCE"))
    }

    private fun zoneStatus(
        departure: Int?,
        arrival: Int?,
        departureIata: String? = "BLR",
        arrivalIata: String? = "SIN",
    ): FlightStatus = testFlightStatus(
        departureIata = departureIata,
        arrivalIata = arrivalIata,
        departureUtcOffsetMinutes = departure,
        arrivalUtcOffsetMinutes = arrival,
    )

    @Test
    fun flightCardPnrSeatLine_omittedWhenBothNull() {
        val airport = testAirportSnapshot(flight = testFlightEvent(confirmationNumber = null, seat = null))
        assertNull(flightCardPnrSeatLine(airport))
    }

    @Test
    fun flightCardPnrSeatLine_combinesBothWhenPresent() {
        val airport = testAirportSnapshot(flight = testFlightEvent(confirmationNumber = "FOICCE", seat = "14A"))
        assertEquals("PNR FOICCE · Seat 14A", flightCardPnrSeatLine(airport))
    }

    @Test
    fun formatFlightDuration_splitsHoursAndMinutes() {
        assertEquals("1 h 35 min", formatFlightDuration(95))
        assertEquals("2 h 5 min", formatFlightDuration(125))
        assertEquals("45 min", formatFlightDuration(45))
        assertEquals("2 h", formatFlightDuration(120))
    }

    @Test
    fun flightCardAircraftRow_airlineOnly() {
        val row = flightCardAircraftRow(testFlightStatus(airlineName = "Singapore Airlines"))
        assertEquals("Singapore Airlines", row.first)
        assertNull(row.second)
    }

    @Test
    fun flightCardAircraftRow_fallsBackToTheOperatingAirline() {
        val status = testFlightStatus(airlineName = null, operatingAirline = "Scoot")
        assertEquals("Scoot", flightCardAircraftRow(status).first)
    }

    @Test
    fun flightCardAircraftRow_aircraftOnly() {
        val row = flightCardAircraftRow(testFlightStatus(aircraftModel = "737 MAX 8"))
        assertNull(row.first)
        assertEquals("737 MAX 8", row.second)
    }

    @Test
    fun flightCardAircraftRow_bothCells() {
        val status = testFlightStatus(
            airlineName = "Singapore Airlines",
            aircraftModel = "737 MAX 8",
            aircraftRegistration = "9V-SMA",
        )
        assertEquals("Singapore Airlines" to "737 MAX 8 · 9V-SMA", flightCardAircraftRow(status))
    }

    @Test
    fun flightCardAircraftRow_resolvesTheIcaoTypeCode() {
        val status = testFlightStatus(aircraftIcao = "A359", aircraftRegistration = "9V-SMA")
        assertEquals("Airbus A350-900 · 9V-SMA", flightCardAircraftRow(status).second)
    }

    @Test
    fun flightCardAircraftRow_bothCellsNullWhenNothingIsKnown() {
        assertEquals(null to null, flightCardAircraftRow(null))
        assertEquals(null to null, flightCardAircraftRow(testFlightStatus()))
    }

    @Test
    fun flightCardGrid_onTimeReachedVariant() {
        val grid = flightCardGrid(onTimeAirport(), zone)
        assertEquals("SQ509", grid.designator)
        assertEquals("On time", grid.chip.label)
        assertEquals(FlightChipTone.QUIET, grid.chip.tone)
        assertEquals("BLR", grid.originCode)
        assertEquals("SIN", grid.destinationCode)
        assertEquals("4 h 55 min", grid.duration)
        assertEquals("3,140 km", grid.distance)
        assertEquals("11:35 am", grid.departureTime)
        assertEquals("4:30 pm", grid.arrivalTime)
        assertNull(grid.departureDelay)
        assertEquals("PNR FOICCE · Seat 14A", grid.bookingLine)
        assertEquals("SQ509 · BLR to SIN", grid.smallIdentity)
        assertEquals("Gate D2 · T2", grid.smallGate)
        assertNull(grid.layoverHeadline)
    }

    @Test
    fun flightCardGrid_delayedVariantMovesTheClockAndKeepsTheDelayInItsOwnCell() {
        val airport = onTimeAirport(
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                departureEstimatedUtcMillis = millis(12, 0),
                arrivalScheduledUtcMillis = millis(16, 30),
                arrivalEstimatedUtcMillis = millis(16, 55),
                departureTerminal = "2",
                departureGate = "D2",
                departureDelayedMinutes = 25,
                durationMinutes = 295,
                airlineName = "Singapore Airlines",
                departureIata = "BLR",
                arrivalIata = "SIN",
            ),
        )
        val grid = flightCardGrid(airport, zone)
        assertEquals("Delayed", grid.chip.label)
        assertEquals(FlightChipTone.WARN, grid.chip.tone)
        assertEquals("12:00 pm", grid.departureTime)
        assertEquals("4:55 pm", grid.arrivalTime)
        assertEquals("+25 min", grid.departureDelay)
        assertEquals("Sched 11:35 am", grid.departureScheduledNote)
        assertEquals("Sched 4:30 pm", grid.arrivalScheduledNote)
        assertEquals("PNR FOICCE · Seat 14A", grid.bookingLine)
    }

    @Test
    fun flightCardGrid_scheduledNotesAreAbsentWhenNeitherClockMoved() {
        val grid = flightCardGrid(onTimeAirport(), zone)
        assertNull(grid.departureScheduledNote)
        assertNull(grid.arrivalScheduledNote)
    }

    @Test
    fun flightCardGrid_scheduledNoteOnlyOnTheEndThatMoved() {
        val airport = onTimeAirport(
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                arrivalScheduledUtcMillis = millis(16, 30),
                arrivalEstimatedUtcMillis = millis(16, 12),
                departureIata = "BLR",
                arrivalIata = "SIN",
            ),
        )
        val grid = flightCardGrid(airport, zone)
        assertNull(grid.departureScheduledNote)
        assertEquals("Sched 4:30 pm", grid.arrivalScheduledNote)
        assertEquals("4:12 pm", grid.arrivalTime)
    }

    @Test
    fun flightCardGrid_scheduledNoteIgnoresASubMinuteEstimate() {
        val airport = onTimeAirport(
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                departureEstimatedUtcMillis = millis(11, 35) + 40_000L,
                arrivalScheduledUtcMillis = millis(16, 30),
                departureIata = "BLR",
                arrivalIata = "SIN",
            ),
        )
        assertNull(flightCardGrid(airport, zone).departureScheduledNote)
    }

    @Test
    fun flightCardGrid_scheduledArrivalNoteKeepsItsOwnDaySuffix() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(departureMillis = millis(20, 10), arrivalMillis = millis(2, 15, day = 11)),
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(20, 10),
                arrivalScheduledUtcMillis = millis(2, 15, day = 11),
                arrivalEstimatedUtcMillis = millis(1, 50, day = 11),
                departureIata = "BLR",
                arrivalIata = "SIN",
            ),
        )
        val grid = flightCardGrid(airport, zone)
        assertEquals("1:50 am +1", grid.arrivalTime)
        assertEquals("Sched 2:15 am +1", grid.arrivalScheduledNote)
    }

    @Test
    fun flightCardGrid_timetableOnlyVariantSaysGateOnTheDay() {
        val airport = onTimeAirport(
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                arrivalScheduledUtcMillis = millis(16, 30),
                departureTerminal = "2",
                departureGate = null,
                durationMinutes = 295,
                airlineName = "Singapore Airlines",
                departureIata = "BLR",
                arrivalIata = "SIN",
                source = "routes",
            ),
        )
        val grid = flightCardGrid(airport, zone)
        assertEquals("Timetable", grid.chip.label)
        assertEquals("Gate on the day · T2", grid.smallGate)
        assertEquals("11:35 am", grid.departureTime)
    }

    @Test
    fun flightCardGrid_noStatusFallsBackToTheCalendarAndInvitesTheTap() {
        val grid = flightCardGrid(testAirportSnapshot(), zone)
        assertEquals("Tap", grid.chip.label)
        assertEquals("11:35 am", grid.departureTime)
        assertEquals("4:30 pm", grid.arrivalTime)
        assertEquals("4 h 55 min", grid.duration)
        assertNull(grid.distance)
        assertEquals("Gate TBA", grid.smallGate)
        assertEquals("SQ509 · Tap for status", grid.smallIdentity)
        assertNull(grid.bookingLine)
        assertNull(grid.departureScheduledNote)
        assertNull(grid.arrivalScheduledNote)
    }

    @Test
    fun flightCardGrid_earlyDepartureShowsANegativeDelay() {
        val airport = onTimeAirport(
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(11, 35),
                departureDelayedMinutes = -5,
                departureIata = "BLR",
                arrivalIata = "SIN",
            ),
        )
        assertEquals("-5 min", flightCardGrid(airport, zone).departureDelay)
    }

    @Test
    fun flightCardGrid_layoverHeaderAndCaption() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(
                airlineCode = "SQ",
                flightNumber = "232",
                departureMillis = millis(18, 40),
                arrivalMillis = millis(5, 30, day = 11),
                departureIata = "SIN",
                arrivalIata = "SYD",
                confirmationNumber = "FOICCE",
                seat = "32K",
                departureTerminal = "3",
            ),
            phase = AirportPhase.LAYOVER,
            status = testFlightStatus(
                designator = "SQ232",
                departureScheduledUtcMillis = millis(18, 40),
                arrivalScheduledUtcMillis = millis(5, 30, day = 11),
                departureTerminal = "3",
                departureGate = "A11",
                durationMinutes = 470,
                departureIata = "SIN",
                arrivalIata = "SYD",
            ),
            layover = true,
            layoverFromDesignator = "SQ509",
            layoverFromArrivalMillis = millis(16, 30),
            routeDistanceKm = 6_300,
        )
        val grid = flightCardGrid(airport, zone)
        assertEquals("Layover 2 h 10 min", grid.layoverHeadline)
        assertEquals("SQ232 to SYD", grid.layoverRoute)
        assertEquals("SQ509 landed 4:30 pm", grid.layoverCaption)
        assertEquals("SIN", grid.originCode)
        assertEquals("SYD", grid.destinationCode)
        assertEquals("7 h 50 min", grid.duration)
        assertEquals("6,300 km", grid.distance)
        assertEquals("6:40 pm", grid.departureTime)
        assertEquals("5:30 am +1", grid.arrivalTime)
        assertEquals("Gate A11 · 6:40 pm", grid.smallGate)
        assertEquals("On time", grid.chip.label)
    }

    @Test
    fun flightCardGrid_layoverStringsAreAbsentOnANonLayoverCard() {
        val grid = flightCardGrid(onTimeAirport(), zone)
        assertNull(grid.layoverHeadline)
        assertNull(grid.layoverRoute)
        assertNull(grid.layoverCaption)
    }

    @Test
    fun flightAirborneGrid_theSpecsAirborneVariant() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(confirmationNumber = "FOICCE", seat = "14A"),
            phase = AirportPhase.REACHED,
            status = testFlightStatus(
                status = "active",
                departureScheduledUtcMillis = millis(11, 35),
                departureActualUtcMillis = millis(11, 41),
                arrivalScheduledUtcMillis = millis(16, 30),
                arrivalEstimatedUtcMillis = millis(16, 55),
                arrivalTerminal = "3",
                arrivalGate = "B7",
                arrivalBaggageBelt = "4",
                arrivalDelayedMinutes = 25,
                airlineName = "Singapore Airlines",
                departureAirportName = "Kempegowda Intl",
                arrivalAirportName = "Changi",
                departureIata = "BLR",
                arrivalIata = "SIN",
                aircraftIcao = "A359",
                aircraftRegistration = "9V-SMA",
                percentComplete = 62,
                altitudeMeters = 11_000,
                speedKmh = 890,
                source = "flight",
            ),
            statusFetchedAtMillis = millis(3, 20),
            routeDistanceKm = 3_140,
        )
        val grid = flightAirborneGrid(airport, nowEpochMillis = millis(15, 3), zone = zone)
        assertEquals("SQ509", grid.designator)
        assertEquals("In the air", grid.chip.label)
        assertEquals(FlightChipTone.LIVE, grid.chip.tone)
        assertEquals("BLR", grid.originCode)
        assertEquals("SIN", grid.destinationCode)
        assertEquals("62%", grid.percentLabel)
        assertEquals("████████", grid.progress.filled)
        assertEquals("░░░░░", grid.progress.empty)
        assertEquals("1 h 52 min left", grid.remaining)
        assertEquals("Left 11:41 am", grid.departedLabel)
        assertEquals("4:55 pm", grid.arrivalTime)
        assertEquals("+25 min", grid.arrivalDelay)
        assertEquals("4 h 55 min", grid.totalDuration)
        assertEquals("Sched 11:35 am", grid.departureScheduledNote)
        assertEquals("Sched 4:30 pm", grid.arrivalScheduledNote)
        assertEquals("1,947 km flown · 1,193 km to go", grid.distanceProgress)
        assertEquals("890 km/h · 11,000 m", grid.telemetry)
        assertEquals("890 km/h · 11,000 m · 1,947 km flown · 1,193 km to go", grid.telemetryLive)
        assertEquals("890 km/h · 11,000 m", grid.telemetryDetail)
        assertEquals("T3 · Belt 4", grid.arrivalBelt)
        assertEquals("PNR FOICCE · Seat 14A", grid.bookingLine)
        assertEquals("BLR to SIN · 4:55 pm", grid.smallRoute)
    }

    @Test
    fun flightAirborneGrid_derivesThePercentWhenTheProviderSentNone() {
        val airport = testAirportSnapshot(
            phase = AirportPhase.REACHED,
            status = testFlightStatus(
                status = "en-route",
                departureActualUtcMillis = millis(11, 35),
                arrivalScheduledUtcMillis = millis(16, 35),
                percentComplete = null,
                departureIata = "BLR",
                arrivalIata = "SIN",
                source = "flight",
            ),
        )
        val grid = flightAirborneGrid(airport, nowEpochMillis = millis(14, 5), zone = zone)
        assertEquals("50%", grid.percentLabel)
        assertEquals("2 h 30 min left", grid.remaining)
        assertNull(grid.distanceProgress)
        assertNull(grid.totalDuration)
        assertNull(grid.departureScheduledNote)
    }

    @Test
    fun flightAirborneGrid_totalDurationPrefersTheProvidersOwnBlockTime() {
        val airport = testAirportSnapshot(
            status = testFlightStatus(
                status = "active",
                departureScheduledUtcMillis = millis(11, 35),
                arrivalScheduledUtcMillis = millis(16, 30),
                durationMinutes = 310,
                source = "flight",
            ),
        )
        assertEquals("5 h 10 min", flightAirborneGrid(airport, millis(13, 0), zone).totalDuration)
    }

    @Test
    fun flightAirborneGrid_scheduledNotesReadInTheirOwnAirportZones() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(departureMillis = millis(6, 5), arrivalMillis = millis(11, 0)),
            status = testFlightStatus(
                status = "active",
                departureScheduledUtcMillis = millis(6, 5),
                departureActualUtcMillis = millis(6, 19),
                arrivalScheduledUtcMillis = millis(11, 0),
                arrivalEstimatedUtcMillis = millis(10, 47),
                departureIata = "BLR",
                arrivalIata = "SIN",
                departureUtcOffsetMinutes = 330,
                arrivalUtcOffsetMinutes = 480,
                source = "flight",
            ),
        )
        val grid = flightAirborneGrid(airport, nowEpochMillis = millis(8, 0), zone = india)
        assertEquals("Left 11:49 am +5:30", grid.departedLabel)
        assertEquals("Sched 11:35 am +5:30", grid.departureScheduledNote)
        assertEquals("6:47 pm", grid.arrivalTime)
        assertEquals("Sched 7:00 pm +8", grid.arrivalScheduledNote)
    }

    /**
     * SQ509 as AirLabs really returns it: 06:05 UTC out of BLR (+5:30) and 11:00 UTC into SIN
     * (+8:00), which is 11:35 am and 7:00 pm on the boarding pass and 11:35 am and 4:30 pm on a
     * phone left in Indian time.
     */
    private fun offsetAirport(
        departureUtcOffsetMinutes: Int? = 330,
        arrivalUtcOffsetMinutes: Int? = 480,
        departureEstimatedUtcMillis: Long? = null,
    ): AirportSnapshot = testAirportSnapshot(
        flight = testFlightEvent(departureMillis = millis(6, 5), arrivalMillis = millis(11, 0)),
        status = testFlightStatus(
            departureScheduledUtcMillis = millis(6, 5),
            departureEstimatedUtcMillis = departureEstimatedUtcMillis,
            arrivalScheduledUtcMillis = millis(11, 0),
            departureTerminal = "2",
            departureGate = "D2",
            departureIata = "BLR",
            arrivalIata = "SIN",
            departureUtcOffsetMinutes = departureUtcOffsetMinutes,
            arrivalUtcOffsetMinutes = arrivalUtcOffsetMinutes,
        ),
    )

    /** The same leg airborne, as the owner watched it: left 11:49 am IST, due 6:56 pm SGT. */
    private fun airborneOffsetAirport(
        arrivalUtcOffsetMinutes: Int? = 480,
    ): AirportSnapshot = testAirportSnapshot(
        flight = testFlightEvent(departureMillis = millis(6, 5), arrivalMillis = millis(11, 0)),
        status = testFlightStatus(
            status = "active",
            departureScheduledUtcMillis = millis(6, 5),
            departureActualUtcMillis = millis(6, 19),
            arrivalScheduledUtcMillis = millis(11, 0),
            arrivalEstimatedUtcMillis = millis(10, 56),
            departureIata = "BLR",
            arrivalIata = "SIN",
            departureUtcOffsetMinutes = 330,
            arrivalUtcOffsetMinutes = arrivalUtcOffsetMinutes,
            source = "flight",
        ),
    )

    @Test
    fun flightCardGrid_airportOffsetsGiveBoardingPassTimes() {
        val grid = flightCardGrid(offsetAirport(), india)
        assertEquals("11:35 am", grid.departureTime)
        assertEquals("7:00 pm", grid.arrivalTime)
    }

    @Test
    fun flightCardGrid_withoutOffsetsEveryClockStaysInTheDeviceZone() {
        val grid = flightCardGrid(offsetAirport(null, null), india)
        assertEquals("11:35 am", grid.departureTime)
        assertEquals("4:30 pm", grid.arrivalTime)
    }

    @Test
    fun flightCardGrid_movedEstimateAlsoReadsInTheDepartureZone() {
        val grid = flightCardGrid(offsetAirport(departureEstimatedUtcMillis = millis(6, 35)), india)
        assertEquals("12:05 pm", grid.departureTime)
    }

    @Test
    fun flightCardGrid_arrivalRollsOverTheDayInItsOwnZoneOnly() {
        val status = testFlightStatus(
            departureScheduledUtcMillis = millis(10, 0),
            arrivalScheduledUtcMillis = millis(22, 0),
            departureUtcOffsetMinutes = -300,
            arrivalUtcOffsetMinutes = 180,
        )
        val airport = testAirportSnapshot(
            flight = testFlightEvent(departureMillis = millis(10, 0), arrivalMillis = millis(22, 0)),
            status = status,
        )
        assertEquals("1:00 am +1", flightCardGrid(airport, zone).arrivalTime)
        assertEquals(
            "10:00 pm",
            flightCardGrid(
                airport.copy(
                    status = status.copy(departureUtcOffsetMinutes = null, arrivalUtcOffsetMinutes = null),
                ),
                zone,
            ).arrivalTime,
        )
    }

    @Test
    fun flightCardGrid_calendarOnlyUsesThePrintedRouteTimes() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(
                departureMillis = millis(6, 5),
                arrivalMillis = millis(11, 0),
                departureLocalHm = "11:35",
                arrivalLocalHm = "19:00",
            ),
        )
        val grid = flightCardGrid(airport, india)
        assertEquals("11:35 am", grid.departureTime)
        assertEquals("7:00 pm", grid.arrivalTime)
    }

    @Test
    fun flightCardGrid_aStatusWithoutOffsetsOutranksThePrintedTimes() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(
                departureMillis = millis(6, 5),
                arrivalMillis = millis(11, 0),
                departureLocalHm = "11:35",
                arrivalLocalHm = "19:00",
            ),
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(6, 5),
                arrivalScheduledUtcMillis = millis(11, 0),
            ),
        )
        val grid = flightCardGrid(airport, zone)
        assertEquals("6:05 am", grid.departureTime)
        assertEquals("11:00 am", grid.arrivalTime)
    }

    @Test
    fun flightCardGrid_layoverCaptionLandsInTheDepartureAirportsZone() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(departureMillis = millis(13, 0), arrivalMillis = millis(17, 0)),
            phase = AirportPhase.LAYOVER,
            status = testFlightStatus(
                departureScheduledUtcMillis = millis(13, 0),
                arrivalScheduledUtcMillis = millis(17, 0),
                departureUtcOffsetMinutes = 480,
                arrivalUtcOffsetMinutes = 480,
            ),
            layover = true,
            layoverFromDesignator = "SQ509",
            layoverFromArrivalMillis = millis(11, 0),
        )
        assertEquals("SQ509 landed 7:00 pm", flightCardGrid(airport, india).layoverCaption)
    }

    @Test
    fun flightAirborneGrid_usesTheAirportOffsets() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(departureMillis = millis(6, 5), arrivalMillis = millis(11, 0)),
            status = testFlightStatus(
                status = "active",
                departureScheduledUtcMillis = millis(6, 5),
                departureActualUtcMillis = millis(6, 11),
                arrivalScheduledUtcMillis = millis(11, 0),
                arrivalEstimatedUtcMillis = millis(11, 25),
                departureIata = "BLR",
                arrivalIata = "SIN",
                departureUtcOffsetMinutes = 330,
                arrivalUtcOffsetMinutes = 480,
                source = "flight",
            ),
        )
        val grid = flightAirborneGrid(airport, nowEpochMillis = millis(9, 33), zone = zone)
        assertEquals("Left 11:41 am +5:30", grid.departedLabel)
        assertEquals("7:25 pm", grid.arrivalTime)
        assertEquals("7:25 pm +8", grid.arrivalZoneTime)
        assertEquals("BLR to SIN · 7:25 pm", grid.smallRoute)
    }

    @Test
    fun formatAirportClockTime_knownOffsetWinsOverTheDeviceZone() {
        assertEquals("11:35 am", formatAirportClockTime(millis(6, 5), 330, zone))
        assertEquals("6:05 am", formatAirportClockTime(millis(6, 5), null, zone))
        assertEquals("11:35 am", formatAirportClockTime(millis(6, 5), null, india))
    }

    /**
     * The table, not the JVM's short names. Android's CLDR carries no English short name for most
     * of these, so a formatter would answer "GMT+08:00" on the device and truncate the cell.
     */
    @Test
    fun zoneLabel_readsTheBundledAbbreviationTable() {
        assertEquals("IST", zoneLabel(330, listOf(india), millis(11, 0)))
        assertEquals("SGT", zoneLabel(480, listOf(singapore), millis(11, 0)))
        assertEquals("GST", zoneLabel(240, listOf(ZoneId.of("Asia/Dubai")), millis(11, 0)))
        assertEquals("JST", zoneLabel(540, listOf(ZoneId.of("Asia/Tokyo")), millis(11, 0)))
        assertEquals("SAST", zoneLabel(120, listOf(ZoneId.of("Africa/Johannesburg")), millis(11, 0)))
    }

    /** ICU's canonical id for a country is often the retired one, so the table carries both. */
    @Test
    fun zoneLabel_namesTheRetiredIdsIcuStillHandsBack() {
        assertEquals("IST", zoneLabel(330, listOf(ZoneId.of("Asia/Calcutta")), millis(11, 0)))
        assertEquals("ICT", zoneLabel(420, listOf(ZoneId.of("Asia/Saigon")), millis(11, 0)))
        assertEquals("MMT", zoneLabel(390, listOf(ZoneId.of("Asia/Rangoon")), millis(11, 0)))
    }

    @Test
    fun zoneLabel_picksStandardOrDaylightByTheInstant() {
        assertEquals("AEDT", zoneLabel(660, listOf(sydney), januaryMillis(11, 0)))
        assertEquals("AEST", zoneLabel(600, listOf(sydney), julyMillis(11, 0)))
        assertEquals("GMT", zoneLabel(0, listOf(dublin), januaryMillis(11, 0)))
        assertEquals("IST", zoneLabel(60, listOf(dublin), julyMillis(11, 0)))
        assertEquals("EST", zoneLabel(-300, listOf(ZoneId.of("America/New_York")), januaryMillis(11, 0)))
        assertEquals("EDT", zoneLabel(-240, listOf(ZoneId.of("America/New_York")), julyMillis(11, 0)))
    }

    /** A zone the table does not carry falls to the compact numeric form, never to "GMT+12:45". */
    @Test
    fun zoneLabel_compactNumericWhenTheZoneIsNotInTheTable() {
        assertEquals("+12:45", zoneLabel(765, listOf(ZoneId.of("Pacific/Chatham")), julyMillis(11, 0)))
        assertEquals("+11", zoneLabel(660, listOf(ZoneId.of("Pacific/Noumea")), julyMillis(11, 0)))
    }

    @Test
    fun clockCellFits_theWideCardsOwnBudgets() {
        assertTrue(clockCellFits("6:56 pm SGT", FLIGHT_CLOCK_CELL_CHARS_AIRBORNE))
        assertFalse(clockCellFits("11:56 pm AEDT", FLIGHT_CLOCK_CELL_CHARS_AIRBORNE))
        assertTrue(clockCellFits("11:35 am AEDT", FLIGHT_CLOCK_CELL_CHARS_GROUND))
        assertFalse(clockCellFits("11:15 am AEDT +1", FLIGHT_CLOCK_CELL_CHARS_GROUND))
        assertTrue(clockCellFits("Left 11:49 am IST", FLIGHT_NOTE_CELL_CHARS_AIRBORNE))
        assertTrue(clockCellFits("Sched 11:35 am IST", FLIGHT_NOTE_CELL_CHARS_AIRBORNE))
        assertFalse(clockCellFits("Sched 11:35 am +5:30", FLIGHT_NOTE_CELL_CHARS_AIRBORNE))
    }

    /** 4x2 cannot stack a cell, so it trades the zone's name for its offset rather than ellipsise. */
    @Test
    fun flightAirborneGrid_wideClockTradesTheNameForTheOffsetWhenTheCellIsTooNarrow() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(departureMillis = januaryMillis(6, 5), arrivalMillis = januaryMillis(12, 56)),
            status = testFlightStatus(
                status = "active",
                departureScheduledUtcMillis = januaryMillis(6, 5),
                departureActualUtcMillis = januaryMillis(6, 5),
                arrivalEstimatedUtcMillis = januaryMillis(12, 56),
                departureIata = "BLR",
                arrivalIata = "SYD",
                departureUtcOffsetMinutes = 330,
                arrivalUtcOffsetMinutes = 660,
                source = "flight",
            ),
        )
        val grid = flightAirborneGrid(
            airport,
            nowEpochMillis = januaryMillis(9, 0),
            zone = india,
            departureZones = listOf(india),
            arrivalZones = listOf(sydney),
        )
        assertEquals("11:56 pm AEDT", grid.arrivalZoneTime)
        assertEquals("11:56 pm +11", grid.arrivalZoneTimeWide)
    }

    /** When even the offset will not fit, 4x2 drops the label: the IATA code is directly above it. */
    @Test
    fun flightAirborneGrid_wideClockKeepsOnlyTheClockWhenNothingElseFits() {
        val airport = testAirportSnapshot(
            flight = testFlightEvent(departureMillis = millis(6, 5), arrivalMillis = millis(13, 40)),
            status = testFlightStatus(
                status = "active",
                departureScheduledUtcMillis = millis(6, 5),
                arrivalEstimatedUtcMillis = millis(13, 40),
                departureIata = "BLR",
                arrivalIata = "KTM",
                departureUtcOffsetMinutes = 330,
                arrivalUtcOffsetMinutes = 345,
                source = "flight",
            ),
        )
        val grid = flightAirborneGrid(airport, nowEpochMillis = millis(9, 0), zone = india)
        assertEquals("7:25 pm +5:45", grid.arrivalZoneTime)
        assertEquals("7:25 pm", grid.arrivalZoneTimeWide)
    }

    @Test
    fun zoneLabel_namesTheFirstCandidateThatIsOnThatOffset() {
        assertEquals("SGT", zoneLabel(480, listOf(singapore, india), millis(11, 0)))
        assertEquals("IST", zoneLabel(330, listOf(singapore, india), millis(11, 0)))
        assertEquals("AEST", zoneLabel(600, listOf(sydney), millis(11, 0)))
    }

    @Test
    fun zoneLabel_numericFormWhenNoCandidateCarriesThatOffset() {
        assertEquals("+8", zoneLabel(480, emptyList(), millis(11, 0)))
        assertEquals("+5:30", zoneLabel(330, emptyList(), millis(11, 0)))
        assertEquals("-4", zoneLabel(-240, emptyList(), millis(11, 0)))
        assertEquals("+8", zoneLabel(480, listOf(india, sydney), millis(11, 0)))
    }

    @Test
    fun phoneZoneLabel_namesTheDeviceZoneItself() {
        assertEquals("IST", phoneZoneLabel(india, millis(11, 0)))
        assertEquals("SGT", phoneZoneLabel(singapore, millis(11, 0)))
    }

    @Test
    fun phoneClockLine_readsTheAirportClockBackInThePhoneZone() {
        assertEquals("4:26 pm IST", phoneClockLine(millis(10, 56), 480, india))
    }

    @Test
    fun phoneClockLine_nullWhenTheAirportIsAlreadyOnThePhonesOffset() {
        assertNull(phoneClockLine(millis(6, 19), 330, india))
        assertNull(phoneClockLine(millis(6, 19), null, india))
    }

    @Test
    fun phoneClockLine_carriesTheArrivalDayMarkerReadAtHome() {
        assertEquals(
            "8:00 am IST +1",
            phoneClockLine(millis(2, 30, day = 11), 480, india, departureMillis = millis(18, 0)),
        )
    }

    /** The owner's own 4x6: BLR is the phone's zone, SIN is not, so only the arrival is translated. */
    @Test
    fun flightAirborneGrid_labelsEveryClockAndTranslatesTheArrivalHome() {
        val grid = flightAirborneGrid(
            airborneOffsetAirport(),
            nowEpochMillis = millis(8, 51),
            zone = india,
            departureZones = listOf(india),
            arrivalZones = listOf(singapore),
        )
        assertEquals("Left 11:49 am IST", grid.departedLabel)
        assertEquals("6:56 pm SGT", grid.arrivalZoneTime)
        assertEquals("6:56 pm", grid.arrivalTime)
        assertEquals("Sched 11:35 am IST", grid.departureScheduledNote)
        assertEquals("Sched 7:00 pm SGT", grid.arrivalScheduledNote)
        assertNull(grid.departurePhoneTime)
        assertEquals("4:26 pm IST", grid.arrivalPhoneTime)
        assertEquals("Phone time", grid.phoneTimeCaption)
    }

    /** Same leg, phone already in Singapore: the translated end swaps to the departure. */
    @Test
    fun flightAirborneGrid_phoneRowSwapsEndsOnceThePhoneIsInTheArrivalZone() {
        val grid = flightAirborneGrid(
            airborneOffsetAirport(),
            nowEpochMillis = millis(8, 51),
            zone = singapore,
            departureZones = listOf(india),
            arrivalZones = listOf(singapore),
        )
        assertEquals("2:19 pm SGT", grid.departurePhoneTime)
        assertNull(grid.arrivalPhoneTime)
        assertEquals("Phone time", grid.phoneTimeCaption)
    }

    @Test
    fun flightAirborneGrid_noPhoneRowWhenBothEndsAreOnThePhonesOwnOffset() {
        val grid = flightAirborneGrid(
            airborneOffsetAirport(arrivalUtcOffsetMinutes = 330),
            nowEpochMillis = millis(8, 51),
            zone = india,
            departureZones = listOf(india),
            arrivalZones = listOf(india),
        )
        assertNull(grid.departurePhoneTime)
        assertNull(grid.arrivalPhoneTime)
        assertNull(grid.phoneTimeCaption)
    }

    @Test
    fun flightCardGrid_labelsBothClocksAndTranslatesTheArrivalHome() {
        val grid = flightCardGrid(offsetAirport(), india, listOf(india), listOf(singapore))
        assertEquals("11:35 am IST", grid.departureZoneTime)
        assertEquals("7:00 pm SGT", grid.arrivalZoneTime)
        assertEquals("11:35 am", grid.departureTime)
        assertNull(grid.departurePhoneTime)
        assertEquals("4:30 pm IST", grid.arrivalPhoneTime)
        assertEquals("Phone time", grid.phoneTimeCaption)
    }

    @Test
    fun flightCardGrid_noZoneLabelsWhenNoOffsetLanded() {
        val grid = flightCardGrid(offsetAirport(null, null), india, listOf(india), listOf(singapore))
        assertEquals("11:35 am", grid.departureZoneTime)
        assertEquals("4:30 pm", grid.arrivalZoneTime)
        assertNull(grid.phoneTimeCaption)
    }

    @Test
    fun utcOffsetLabel_wholeHoursHalfHoursAndNegatives() {
        assertEquals("+8", utcOffsetLabel(480))
        assertEquals("+5:30", utcOffsetLabel(330))
        assertEquals("-4", utcOffsetLabel(-240))
        assertEquals("-3:30", utcOffsetLabel(-210))
        assertEquals("+0", utcOffsetLabel(0))
    }

    @Test
    fun formatPrintedClockTime_readsTheCalendarsOwnClock() {
        assertEquals("11:35 am", formatPrintedClockTime("11:35"))
        assertEquals("7:00 pm", formatPrintedClockTime("19:00"))
        assertEquals("12:05 am", formatPrintedClockTime("0:05"))
        assertNull(formatPrintedClockTime("local time"))
        assertNull(formatPrintedClockTime("25:00"))
    }

    @Test
    fun flightCardFooter_updatedWhenFetched() {
        val airport = testAirportSnapshot(statusFetchedAtMillis = millis(9, 5), status = testFlightStatus(source = null))
        assertEquals("Updated 9:05 am", flightCardFooter(airport, zone))
    }

    @Test
    fun flightCardFooter_errorWhenNeverSucceeded() {
        val airport = testAirportSnapshot(statusFetchedAtMillis = null, status = null, statusError = "Network error")
        assertEquals("Status unavailable - Network error", flightCardFooter(airport, zone))
    }

    @Test
    fun flightCardFooter_tapForLiveStatusWhenNeverFetched() {
        val airport = testAirportSnapshot(statusFetchedAtMillis = null, status = null, statusError = null)
        assertEquals("Tap for live status", flightCardFooter(airport, zone))
    }

    @Test
    fun flightCardFooter_updatedWinsOverStaleErrorWhenStatusKept() {
        val airport = testAirportSnapshot(
            statusFetchedAtMillis = millis(9, 5),
            status = testFlightStatus(source = null),
            statusError = "AirLabs quota exceeded",
        )
        assertEquals("Updated 9:05 am", flightCardFooter(airport, zone))
    }

    @Test
    fun flightCardFooter_bothStampsAndNeverTheSource() {
        val airport = testAirportSnapshot(
            statusFetchedAtMillis = millis(3, 20),
            status = testFlightStatus(lastUpdatedUtcMillis = millis(3, 5), source = "schedules"),
        )
        assertEquals("Updated 3:20 am · airline data 3:05 am", flightCardFooter(airport, zone))
    }

    @Test
    fun flightCardFooter_updatedAloneWhenTheAirlineStampedNothing() {
        val airport = testAirportSnapshot(
            statusFetchedAtMillis = millis(3, 20),
            status = testFlightStatus(source = "routes"),
        )
        assertEquals("Updated 3:20 am", flightCardFooter(airport, zone))
    }

    @Test
    fun airportOfferedPillLine_leaveByAndBest() {
        val airport = testAirportSnapshot(
            phase = AirportPhase.OFFERED,
            leaveByMillis = millis(3, 27),
            bestDepartureMillis = millis(3, 10),
        )
        assertEquals("Leave by 3:27 am · Best 3:10 am", airportOfferedPillLine(airport, zone))
    }

    @Test
    fun airportOfferedPillLine_eitherHalfAloneAndNullWhenNeitherIsKnown() {
        assertEquals(
            "Leave by 3:27 am",
            airportOfferedPillLine(testAirportSnapshot(phase = AirportPhase.OFFERED, leaveByMillis = millis(3, 27)), zone),
        )
        assertEquals(
            "Best 3:10 am",
            airportOfferedPillLine(testAirportSnapshot(phase = AirportPhase.OFFERED, bestDepartureMillis = millis(3, 10)), zone),
        )
        assertNull(airportOfferedPillLine(testAirportSnapshot(phase = AirportPhase.OFFERED), zone))
    }

    @Test
    fun airportRendersFlightCard_everyPhaseButRiding() {
        assertTrue(airportRendersFlightCard(AirportPhase.OFFERED))
        assertTrue(airportRendersFlightCard(AirportPhase.LAYOVER))
        assertTrue(airportRendersFlightCard(AirportPhase.REACHED))
        assertFalse(airportRendersFlightCard(AirportPhase.RIDING))
    }

    @Test
    fun airportBestPillText_withTravelMinutes() {
        assertEquals("Best 3:30 pm · 45 min", airportBestPillText(millis(15, 30), 45, zone))
    }

    @Test
    fun airportBestPillText_withoutTravelMinutes() {
        assertEquals("Best 3:30 pm", airportBestPillText(millis(15, 30), null, zone))
    }

    @Test
    fun airportBestPillText_nullWhenNoBestDeparture() {
        assertNull(airportBestPillText(null, 45, zone))
    }

    @Test
    fun airportShowsReachedPill_trueWhenReachedOfferedFlagSet() {
        val airport = testAirportSnapshot(phase = AirportPhase.OFFERED, arriveByMillis = 10_000L, reachedOffered = true)
        assertTrue(airportShowsReachedPill(airport, nowEpochMillis = 0L))
    }

    @Test
    fun airportShowsReachedPill_offeredStillWaitsForTheArriveByTarget() {
        val airport = testAirportSnapshot(phase = AirportPhase.OFFERED, arriveByMillis = 10_000L, reachedOffered = false)
        assertTrue(airportShowsReachedPill(airport, nowEpochMillis = 10_000L))
        assertFalse(airportShowsReachedPill(airport, nowEpochMillis = 9_999L))
    }

    @Test
    fun airportShowsReachedPill_ridingAlwaysShowsItFarFromTheAirportAndBeforeTheTarget() {
        val airport = testAirportSnapshot(
            phase = AirportPhase.RIDING,
            arriveByMillis = millis(6, 0),
            reachedOffered = false,
        )
        assertTrue(airportShowsReachedPill(airport, nowEpochMillis = millis(0, 30)))
    }

    @Test
    fun airportShowsReachedPill_falseWheneverTheCardIsShowing() {
        val reached = testAirportSnapshot(phase = AirportPhase.REACHED, arriveByMillis = 0L, reachedOffered = true)
        assertFalse(airportShowsReachedPill(reached, nowEpochMillis = 999_999L))
        val layover = testAirportSnapshot(phase = AirportPhase.LAYOVER, arriveByMillis = 0L, reachedOffered = true)
        assertFalse(airportShowsReachedPill(layover, nowEpochMillis = 999_999L))
    }

    @Test
    fun toAirportPillLabel_offeredAndRiding() {
        assertEquals("To Airport", toAirportPillLabel(AirportPhase.OFFERED))
        assertEquals("Riding", toAirportPillLabel(AirportPhase.RIDING))
        assertEquals("To Airport", toAirportPillLabel(AirportPhase.LAYOVER))
        assertEquals("To Airport", toAirportPillLabel(AirportPhase.REACHED))
    }

    @Test
    fun airportMapTitleLines_flightOverTheAirportName() {
        val snapshot = airportMapSnapshot(
            destinationLabel = "Kempegowda International Airport",
            airport = testAirportSnapshot(phase = AirportPhase.RIDING, status = testFlightStatus(arrivalIata = "SIN")),
        )
        assertEquals("SQ509 to SIN" to "Kempegowda International Airport", airportMapTitleLines(snapshot))
    }

    @Test
    fun airportMapTitleLines_fallsBackToTheCalendarIataThenTheFirstWordOfTheName() {
        val calendarIata = airportMapSnapshot(
            destinationLabel = null,
            airport = testAirportSnapshot(phase = AirportPhase.RIDING),
        )
        assertEquals("SQ509 to SIN" to null, airportMapTitleLines(calendarIata))
        val namedOnly = airportMapSnapshot(
            destinationLabel = "  ",
            airport = testAirportSnapshot(
                phase = AirportPhase.RIDING,
                flight = testFlightEvent(arrivalIata = null, arrivalAirportName = "Sydney Kingsford Smith"),
            ),
        )
        assertEquals("SQ509 to Sydney" to null, airportMapTitleLines(namedOnly))
    }

    @Test
    fun airportMapTitleLines_genericWhenNothingNamesTheDestination() {
        val nothing = airportMapSnapshot(
            destinationLabel = "Melbourne Airport",
            airport = testAirportSnapshot(
                phase = AirportPhase.RIDING,
                flight = testFlightEvent(arrivalIata = null, arrivalAirportName = null),
            ),
        )
        assertEquals("SQ509 to flight" to "Melbourne Airport", airportMapTitleLines(nothing))
        assertEquals("Airport" to null, airportMapTitleLines(airportMapSnapshot(destinationLabel = "x", airport = null)))
    }

    private fun airportMapSnapshot(destinationLabel: String?, airport: AirportSnapshot?): CommuteSnapshot =
        CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 0L,
            durationNoTrafficSeconds = 0L,
            distanceMeters = 0L,
            mapImagePath = null,
            fetchedAtEpochMillis = 0L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            destinationLabel = destinationLabel,
            mode = SnapshotMode.AIRPORT,
            airport = airport,
        )

    private val fullStatus = testFlightStatus(
        status = "en-route",
        departureTerminal = "2",
        departureGate = "D2 D3",
        departureCheckInDesk = "E",
        arrivalTerminal = "1",
        arrivalGate = "D41",
        arrivalBaggageBelt = "4",
        aircraftModel = "Airbus A350-900",
        aircraftRegistration = "9V-SMA",
        departureDelayedMinutes = 32,
        arrivalDelayedMinutes = 41,
        airlineName = "Singapore Airlines",
        departureAirportName = "Kempegowda International Airport",
        departureCity = "Bengaluru",
        arrivalAirportName = "Singapore Changi Airport",
        arrivalCity = "Singapore",
        departureIata = "BLR",
        arrivalIata = "SIN",
        altitudeMeters = 11_640,
        speedKmh = 819,
        headingDegrees = 242,
        percentComplete = 48,
        departureCountry = "IN",
        arrivalCountry = "SG",
        airlineIata = "SQ",
        airlineIcao = "SIA",
        aircraftManufacturer = "Airbus",
        aircraftSerialNumber = "0555",
        aircraftEngineType = "jet",
        aircraftEngineCount = 2,
        aircraftBuiltYear = 2016,
        aircraftAgeYears = 9,
        verticalSpeedKmh = -3,
        transponderCode = "2000",
    )

    private val fleetRecord = AircraftRecord(
        reg = "9V-DHA",
        model = "Boeing 737 MAX 8",
        manufacturer = "Boeing",
        typeIcao = "B38M",
        typeIata = "7M8",
        engineType = "jet",
        engineCount = 2,
        builtYear = 2021,
        ageYears = 4,
        serialNumber = "61234",
        fetchedAtMillis = 1_700_000_000_000L,
    )

    private val emptyFleetRecord = AircraftRecord(reg = "9V-DHA", fetchedAtMillis = 1_700_000_000_000L)

    private val blrDelays = AirportDelayStats(
        iata = "BLR",
        delayedCount = 4,
        averageDelayMinutes = 106,
        maxDelayMinutes = 130,
        fetchedAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun flightCardGroundBlock_bothEndsFromTheStatus() {
        val block = flightCardGroundBlock(testAirportSnapshot(status = fullStatus))

        assertEquals("BLR", block.originCode)
        assertEquals("SIN", block.destinationCode)
        assertEquals("Terminal 2", block.departureTerminal)
        assertEquals("Terminal 1", block.arrivalTerminal)
        assertEquals("Gate D2 D3", block.departureGate)
        assertEquals("Gate D41", block.arrivalGate)
        assertEquals("Check-in E", block.checkIn)
        assertEquals("Belt 4", block.belt)
    }

    @Test
    fun flightCardGroundBlock_tbaHoldsEveryUnpublishedSlot() {
        val block = flightCardGroundBlock(testAirportSnapshot())

        assertEquals("Terminal TBA", block.departureTerminal)
        assertEquals("Terminal TBA", block.arrivalTerminal)
        assertEquals("Gate TBA", block.departureGate)
        assertEquals("Gate TBA", block.arrivalGate)
        assertEquals("Check-in TBA", block.checkIn)
        assertEquals("Belt TBA", block.belt)
    }

    @Test
    fun flightCardGroundBlock_timetableSourceSaysOnTheDay() {
        val block = flightCardGroundBlock(testAirportSnapshot(status = testFlightStatus(source = "routes")))

        assertEquals("Gate on the day", block.departureGate)
        assertEquals("Gate on the day", block.arrivalGate)
    }

    @Test
    fun flightCardGroundBlock_fallsBackToTheCalendarTerminals() {
        val flight = testFlightEvent(departureTerminal = "2", arrivalTerminal = "1")

        val block = flightCardGroundBlock(testAirportSnapshot(flight = flight))

        assertEquals("Terminal 2", block.departureTerminal)
        assertEquals("Terminal 1", block.arrivalTerminal)
    }

    @Test
    fun flightCardGroundLine_bothEndsInOneLine() {
        assertEquals(
            "T2 · Gate D2 D3 -> T1 · Gate D41 · Belt 4",
            flightCardGroundLine(testAirportSnapshot(status = fullStatus)),
        )
    }

    @Test
    fun flightCardGroundLine_keepsItsShapeWhenNothingIsPublished() {
        assertEquals("Gate TBA -> Gate TBA · Belt TBA", flightCardGroundLine(testAirportSnapshot()))
    }

    @Test
    fun flightCardGroundLine_carriesTheCalendarTerminals() {
        val flight = testFlightEvent(departureTerminal = "2", arrivalTerminal = "3")

        assertEquals(
            "T2 · Gate TBA -> T3 · Gate TBA · Belt TBA",
            flightCardGroundLine(testAirportSnapshot(flight = flight)),
        )
    }

    @Test
    fun flightCardGrid_arrivalDelayCellCarriesTheArrivalDelay() {
        val grid = flightCardGrid(testAirportSnapshot(status = fullStatus), zone)

        assertEquals("+32 min", grid.departureDelay)
        assertEquals("+41 min", grid.arrivalDelay)
    }

    @Test
    fun flightCardGrid_arrivalDelayCellEmptyUnderAMinute() {
        val grid = flightCardGrid(testAirportSnapshot(status = testFlightStatus(arrivalDelayedMinutes = 0)), zone)

        assertNull(grid.arrivalDelay)
    }

    @Test
    fun flightCardAircraftLine_typeAndRegistrationOnly() {
        assertEquals("Airbus A350-900 · 9V-SMA", flightCardAircraftLine(fullStatus, fleetRecord))
    }

    @Test
    fun flightCardAircraftLine_fallsBackToTheFleetRecord() {
        assertEquals("Boeing 737 MAX 8 · 9V-DHA", flightCardAircraftLine(null, fleetRecord))
    }

    @Test
    fun flightCardAircraftLine_statusOnlyWhenTheFleetRecordIsEmpty() {
        val status = testFlightStatus(aircraftIcao = "A359", aircraftRegistration = "9V-SMA")

        assertEquals("Airbus A350-900 · 9V-SMA", flightCardAircraftLine(status, emptyFleetRecord))
    }

    @Test
    fun flightCardAircraftLine_leavesTheBuildYearAndTheEnginesToTheFleetLine() {
        val status = testFlightStatus(aircraftModel = "Airbus A320", aircraftAgeYears = 3, aircraftEngineCount = 2)

        assertEquals("Airbus A320", flightCardAircraftLine(status, null))
        assertEquals("Fleet: 2 engines, 3 y old", flightCardFleetLine(status, null))
    }

    @Test
    fun flightCardAircraftLine_manufacturerStandsInWhenNoTypeResolves() {
        val status = testFlightStatus(aircraftManufacturer = "Embraer", aircraftRegistration = "VT-ABC")

        assertEquals("Embraer · VT-ABC", flightCardAircraftLine(status, null))
    }

    @Test
    fun flightCardAircraftLine_nullWhenNothingIsKnown() {
        assertNull(flightCardAircraftLine(null, null))
        assertNull(flightCardAircraftLine(testFlightStatus(), null))
    }

    @Test
    fun flightCardAirportLines_bothNamesWithTheirCountryCodes() {
        assertEquals(
            listOf("Kempegowda International Airport, IN", "Singapore Changi Airport, SG"),
            flightCardAirportLines(fullStatus, testFlightEvent()),
        )
    }

    @Test
    fun flightCardAirportLines_nameAloneWhenNoCountryLanded() {
        assertEquals(
            listOf("Kempegowda International Airport", "Singapore Changi Airport"),
            flightCardAirportLines(
                fullStatus.copy(departureCountry = null, arrivalCountry = null),
                testFlightEvent(),
            ),
        )
    }

    @Test
    fun flightCardAirportLines_fallsBackToTheCalendarNames() {
        assertEquals(listOf("Kempegowda Intl", "Changi"), flightCardAirportLines(null, testFlightEvent()))
    }

    @Test
    fun flightCardAirportLines_emptyWhenNothingNamesEitherEnd() {
        val flight = testFlightEvent(departureAirportName = null, arrivalAirportName = null)

        assertEquals(emptyList<String>(), flightCardAirportLines(null, flight))
    }

    @Test
    fun flightCardBoardDelayLine_countAndAverage() {
        assertEquals("BLR board: 4 delayed 30+ min, avg 1 h 46 min", flightCardBoardDelayLine(blrDelays))
    }

    @Test
    fun flightCardBoardDelayLine_countOnlyWhenNoAverageWasComputed() {
        assertEquals(
            "BLR board: 4 delayed 30+ min",
            flightCardBoardDelayLine(blrDelays.copy(averageDelayMinutes = 0)),
        )
    }

    @Test
    fun flightCardBoardDelayLine_nullWhenTheBoardIsRunning() {
        assertNull(flightCardBoardDelayLine(null))
        assertNull(flightCardBoardDelayLine(blrDelays.copy(delayedCount = 0)))
    }

    /**
     * Every dense line the card can build, each at the longest the data allows, against the 70
     * characters an 11 sp line holds at 314 dp. This is the guard on the truncation bug: no fact
     * line may ellipsise. The route here is the longest an A350 can fly, so the flown and to-go
     * pair is at its widest too.
     */
    @Test
    fun denseLines_allStayInsideTheLineBudget() {
        val airport = testAirportSnapshot(status = fullStatus, routeDistanceKm = 12_970)
        val grid = flightAirborneGrid(airport, nowEpochMillis = millis(13, 0), zone = zone)
        val lines = listOfNotNull(
            flightCardAircraftLine(fullStatus, fleetRecord),
            flightCardAirlineLine(fullStatus),
            flightCardTelemetryLine(fullStatus, vectors = true),
            flightCardFleetLine(fullStatus, fleetRecord),
            grid.telemetryLive,
            grid.distanceProgress,
        ) + flightCardAirportLines(fullStatus, testFlightEvent())
        lines.forEach { assertTrue(it, it.length <= FLIGHT_FACT_LINE_MAX_CHARS) }
        assertEquals("Airbus A350-900 · 9V-SMA", lines[0])
        assertEquals("819 km/h · 11,640 m · -3 km/h · 242 deg · Squawk 2000", lines[2])
        assertEquals("Fleet: Boeing, MSN 61234, 2 x jet, built 2021 (4 y)", lines[3])
        assertEquals("819 km/h · 11,640 m · 6,226 km flown · 6,744 km to go", lines[4])
    }

    /**
     * The one line that can outrun a single row: a codeshare operated by a third carrier runs to 71
     * characters. It is why the facts paragraph renders at two lines rather than one - it wraps
     * there instead of losing its tail, and two lines is enough for anything the line can build.
     */
    @Test
    fun flightCardAirlineLine_codeshareOverrunsOneLineAndFitsTwo() {
        val line = flightCardAirlineLine(
            fullStatus.copy(operatingAirline = "IndiGo", codeshareOf = "6E7005"),
        )
        assertEquals("Singapore Airlines · SQ / SIA · operated by IndiGo · codeshare of 6E7005", line)
        assertTrue(line!!.length > FLIGHT_FACT_LINE_MAX_CHARS)
        assertTrue(line.length <= 2 * FLIGHT_FACT_LINE_MAX_CHARS)
    }

    @Test
    fun flightCardBoardDelayLine_staysInsideTheLineBudget() {
        val line = flightCardBoardDelayLine(blrDelays.copy(delayedCount = 12, averageDelayMinutes = 130))
        assertEquals("BLR board: 12 delayed 30+ min, avg 2 h 10 min", line)
        assertTrue(line!!.length <= FLIGHT_FACT_LINE_MAX_CHARS)
    }

    @Test
    fun mapAreaPlaceholderLines_airportShowsAirportNameAndDepartureTime() {
        val start = millis(10, 0)
        val snapshot = CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 0L,
            durationNoTrafficSeconds = 0L,
            distanceMeters = 0L,
            mapImagePath = null,
            fetchedAtEpochMillis = 0L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            destinationLabel = "Melbourne Airport",
            mode = SnapshotMode.AIRPORT,
            eventStartEpochMillis = start,
        )
        assertEquals(listOf("Melbourne Airport", "10:00 am"), mapAreaPlaceholderLines(snapshot, zone))
    }
}
