package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.RouteResult
import com.crpakala.commutewidget.calendar.FlightEvent
import com.crpakala.commutewidget.data.AirportDeparture
import com.crpakala.commutewidget.data.AirportLocation
import com.crpakala.commutewidget.data.AirportPhase
import com.crpakala.commutewidget.data.AirportState
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.FlightStatus
import com.crpakala.commutewidget.data.HealthNudge
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** 2027-01-15T08:00:00Z, which is 2027-01-15T19:00+11:00 in Melbourne. */
private const val DEPARTURE = 1_800_000_000_000L
private const val WINDOW_START = DEPARTURE - 5 * HOUR
private const val ARRIVE_BY = DEPARTURE - 3 * HOUR

private val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
private val MELBOURNE_AIRPORT = LatLng(-37.6690, 144.8410)
private val BENGALURU_AIRPORT = LatLng(12.9499, 77.6682)
private val SINGAPORE_AIRPORT = LatLng(1.3644, 103.9915)

/** Pure snapshot-shaping and takeover tests for the airport leg of the refresh pipeline. */
class AirportRefreshTest {

    private fun flight(
        eventId: Long = 1L,
        locationText: String? = "Melbourne Airport (MEL)",
        departureAirportName: String? = "Melbourne Airport",
        departureIata: String? = "MEL",
        departureTerminal: String? = "T1",
    ): FlightEvent = FlightEvent(
        eventId = eventId,
        calendarId = 7L,
        title = "QF 401 to Sydney",
        airlineCode = "QF",
        flightNumber = "401",
        departureMillis = DEPARTURE,
        arrivalMillis = DEPARTURE + 90 * MINUTE,
        locationText = locationText,
        departureAirportName = departureAirportName,
        departureIata = departureIata,
        arrivalAirportName = "Sydney Airport",
        arrivalIata = "SYD",
        confirmationNumber = "ABC123",
        seat = "14A",
        departureTerminal = departureTerminal,
        arrivalTerminal = "T2",
    )

    private fun window(flight: FlightEvent = flight()): AirportWindow =
        AirportWindow(flight = flight, windowStartMillis = WINDOW_START, arriveByMillis = ARRIVE_BY)

    private fun state(
        eventId: Long = 1L,
        phase: AirportPhase = AirportPhase.OFFERED,
        lastStatus: FlightStatus? = null,
    ): AirportState = AirportState(
        eventId = eventId,
        localDate = "2027-01-15",
        phase = phase,
        lastStatus = lastStatus,
        lastStatusFetchedAtMillis = if (lastStatus == null) null else DEPARTURE - 4 * HOUR,
        lastStatusError = null,
    )

    private fun status(): FlightStatus = FlightStatus(
        designator = "QF401",
        status = "Scheduled",
        departureScheduledUtcMillis = DEPARTURE,
        departureEstimatedUtcMillis = DEPARTURE + 20 * MINUTE,
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

    private fun route(durationSeconds: Long = 1_800L): RouteResult = RouteResult(
        durationSeconds = durationSeconds,
        staticDurationSeconds = durationSeconds - 300L,
        distanceMeters = 24_500L,
        encodedPolyline = "abc",
        speedIntervals = emptyList(),
    )

    private fun health(): HealthComputation = HealthComputation(
        healthNudges = listOf(
            HealthNudge(
                kind = HealthNudgeKind.WATER,
                label = "Water",
                startMinuteOfDay = 600,
                endMinuteOfDay = 780,
            ),
        ),
        sleepEstimateMinutes = 400,
        shortSleepDay = true,
    )

    @Test
    fun airportSnapshotFrom_reachedCarriesStatusWithoutMapOrRoute() {
        val snapshot = airportSnapshotFrom(
            window = window(),
            state = state(phase = AirportPhase.REACHED, lastStatus = status()),
            direction = Direction.TO_WORK,
            zone = MELBOURNE,
            nowEpochMillis = ARRIVE_BY,
            healthComputation = health(),
        )

        assertEquals(SnapshotMode.AIRPORT, snapshot.mode)
        assertNull(snapshot.mapImagePath)
        assertEquals(0L, snapshot.durationSeconds)
        assertNull(snapshot.leaveByMinuteOfDay)
        assertFalse(snapshot.lastFetchFailed)
        val airport = snapshot.airport!!
        assertEquals(AirportPhase.REACHED, airport.phase)
        assertEquals("QF401", airport.status?.designator)
        assertEquals(DEPARTURE - 4 * HOUR, airport.statusFetchedAtMillis)
        assertNull(airport.travelMinutes)
        assertNull(airport.leaveByMillis)
    }

    @Test
    fun airportSnapshotFrom_offeredComputesLeaveByMinuteOfDayInZone() {
        val snapshot = airportSnapshotFrom(
            window = window(),
            state = state(),
            direction = Direction.TO_WORK,
            zone = MELBOURNE,
            nowEpochMillis = WINDOW_START,
            healthComputation = health(),
            originLabel = "Current location",
            airport = MELBOURNE_AIRPORT,
            route = route(),
            mapImagePath = "/data/map_a.png",
            best = AirportDeparture(
                eventId = 1L,
                bestDepartureMillis = WINDOW_START + HOUR,
                bestDurationSeconds = 1_500L,
                sampleCount = 4,
                computedAtMillis = WINDOW_START,
            ),
            reachedOffered = false,
        )

        // arriveBy is 16:00 Melbourne; a 30-minute drive leaves at 15:30 = minute 930.
        assertEquals(930, snapshot.leaveByMinuteOfDay)
        assertEquals("Melbourne Airport", snapshot.destinationLabel)
        assertEquals(MELBOURNE_AIRPORT.lat, snapshot.destinationLat!!, 0.0)
        assertEquals(DEPARTURE, snapshot.eventStartEpochMillis)
        assertEquals("/data/map_a.png", snapshot.mapImagePath)
        assertFalse(snapshot.lastFetchFailed)
        val airport = snapshot.airport!!
        assertEquals(ARRIVE_BY - 30 * MINUTE, airport.leaveByMillis)
        assertEquals(30, airport.travelMinutes)
        assertEquals(24_500, airport.distanceMeters)
        assertEquals(WINDOW_START + HOUR, airport.bestDepartureMillis)
        assertEquals(25, airport.bestTravelMinutes)
        assertEquals("Current location", airport.originLabel)
        assertEquals(listOf("Water"), snapshot.healthNudges.map { it.label })
    }

    @Test
    fun airportSnapshotFrom_geocodeFailureKeepsAirportModeWithoutCoordinates() {
        val snapshot = airportSnapshotFrom(
            window = window(),
            state = state(),
            direction = Direction.TO_WORK,
            zone = MELBOURNE,
            nowEpochMillis = WINDOW_START,
            healthComputation = health(),
            originLabel = "Home",
            reachedOffered = true,
            errorMessage = "Airport not found",
        )

        assertEquals(SnapshotMode.AIRPORT, snapshot.mode)
        assertTrue(snapshot.lastFetchFailed)
        assertEquals("Airport not found", snapshot.lastErrorMessage)
        assertNull(snapshot.destinationLat)
        assertNull(snapshot.destinationLng)
        assertNull(snapshot.mapImagePath)
        val airport = snapshot.airport!!
        assertNull(airport.airportLat)
        assertNull(airport.airportLng)
        assertNull(airport.travelMinutes)
        assertTrue(airport.reachedOffered)
    }

    @Test
    fun airportSnapshotFrom_ignoresBestDepartureFromAnotherFlight() {
        val snapshot = airportSnapshotFrom(
            window = window(),
            state = state(),
            direction = Direction.TO_WORK,
            zone = MELBOURNE,
            nowEpochMillis = WINDOW_START,
            healthComputation = HealthComputation(),
            airport = MELBOURNE_AIRPORT,
            route = route(),
            best = AirportDeparture(
                eventId = 99L,
                bestDepartureMillis = WINDOW_START + HOUR,
                bestDurationSeconds = 1_500L,
                sampleCount = 4,
                computedAtMillis = WINDOW_START,
            ),
        )

        val airport = snapshot.airport!!
        assertNull(airport.bestDepartureMillis)
        assertNull(airport.bestTravelMinutes)
    }

    @Test
    fun airportTakeoverApplies_requiresEveryCalendarGateAndAnOpenWindow() {
        assertTrue(airportTakeoverApplies(true, true, setOf(1L), window()))
        assertFalse(airportTakeoverApplies(false, true, setOf(1L), window()))
        assertFalse(airportTakeoverApplies(true, false, setOf(1L), window()))
        assertFalse(airportTakeoverApplies(true, true, emptySet(), window()))
        assertFalse(airportTakeoverApplies(true, true, setOf(1L), null))
    }

    /** The owner's own event: bare IATA code in the location text, terminal 2 from the status. */
    private fun ownerFlight(departureTerminal: String? = null): FlightEvent = flight(
        locationText = "Bengaluru BLR",
        departureAirportName = "Bengaluru",
        departureIata = "BLR",
        departureTerminal = departureTerminal,
    )

    @Test
    fun airportGeocodeQueries_withKnownTerminal_leadsWithTheTerminalAndEndsWithTheRawLocationText() {
        assertEquals(
            listOf(
                "BLR/T2" to "BLR airport terminal 2",
                "BLR" to "BLR airport",
                "" to "Bengaluru airport",
                "" to "Bengaluru BLR",
            ),
            airportGeocodeQueries(ownerFlight(), status().copy(departureTerminal = "2")),
        )
    }

    @Test
    fun airportGeocodeQueries_withoutAnyTerminal_startsAtThePlainAirport() {
        assertEquals(
            listOf(
                "BLR" to "BLR airport",
                "" to "Bengaluru airport",
                "" to "Bengaluru BLR",
            ),
            airportGeocodeQueries(ownerFlight(), null),
        )
    }

    @Test
    fun airportGeocodeQueries_statusTerminalOutranksTheCalendarTerminal() {
        assertEquals(
            "BLR/T2" to "BLR airport terminal 2",
            airportGeocodeQueries(ownerFlight(departureTerminal = "Terminal 1"), status().copy(departureTerminal = "T2")).first(),
        )
        assertEquals(
            "BLR/T1" to "BLR airport terminal 1",
            airportGeocodeQueries(ownerFlight(departureTerminal = "Terminal 1"), null).first(),
        )
    }

    @Test
    fun airportGeocodeQueries_locationTextOnly_isTheOnlyQueryAndCachesNowhere() {
        val located = flight(
            locationText = "Kempegowda International Airport",
            departureAirportName = null,
            departureIata = null,
            departureTerminal = null,
        )
        assertEquals(listOf("" to "Kempegowda International Airport"), airportGeocodeQueries(located, null))
    }

    @Test
    fun airportGeocodeQueries_flightWithNothingToGeocode_isEmpty() {
        val bare = flight(
            locationText = null,
            departureAirportName = null,
            departureIata = null,
            departureTerminal = null,
        )
        assertTrue(airportGeocodeQueries(bare, null).isEmpty())
    }

    @Test
    fun normalizedTerminal_digitLetterAndWordFormsAllAgree() {
        assertEquals("T2", normalizedTerminal("2"))
        assertEquals("T2", normalizedTerminal("T2"))
        assertEquals("T2", normalizedTerminal("Terminal 2"))
        assertEquals("T2", normalizedTerminal(" terminal t2 "))
        assertEquals("T2A", normalizedTerminal("terminal 2a"))
        assertNull(normalizedTerminal(null))
        assertNull(normalizedTerminal("   "))
        assertNull(normalizedTerminal("T"))
    }

    @Test
    fun geocodedAirportName_keepsThePlaceNameAndDropsTheAddress() {
        assertEquals(
            "Kempegowda International Airport Terminal 2",
            geocodedAirportName(
                "Kempegowda International Airport Terminal 2, Devanahalli, Karnataka 560300, India",
                "Bengaluru",
            ),
        )
        assertEquals("Bengaluru", geocodedAirportName("", "Bengaluru"))
    }

    @Test
    fun airportDisplayName_fallsBackFromNameToIataToGenericLabel() {
        assertEquals("Melbourne Airport", airportDisplayName(flight()))
        assertEquals("MEL", airportDisplayName(flight(departureAirportName = null)))
        assertEquals("Airport", airportDisplayName(flight(departureAirportName = null, departureIata = null)))
    }

    @Test
    fun airportDisplayName_prefersTheGeocodedNameOverTheCalendarText() {
        assertEquals(
            "Kempegowda International Airport",
            airportDisplayName(ownerFlight(), "Kempegowda International Airport"),
        )
        assertEquals("Bengaluru", airportDisplayName(ownerFlight(), " "))
    }

    @Test
    fun airportSnapshotFrom_destinationLabelCarriesTheGeocodedAirportName() {
        val snapshot = airportSnapshotFrom(
            window = window(ownerFlight()),
            state = state(),
            direction = Direction.TO_WORK,
            zone = MELBOURNE,
            nowEpochMillis = WINDOW_START,
            healthComputation = HealthComputation(),
            airport = BENGALURU_AIRPORT,
            airportName = "Kempegowda International Airport",
        )

        assertEquals("Kempegowda International Airport", snapshot.destinationLabel)
    }

    @Test
    fun airportEntryFor_terminalKeyAndPlainCodeAreSeparateEntries() {
        val cache = mapOf(
            "BLR" to AirportLocation("BLR", "Kempegowda International Airport", BENGALURU_AIRPORT.lat, BENGALURU_AIRPORT.lng),
            "BLR/T2" to AirportLocation("BLR", "Kempegowda International Airport Terminal 2", 13.1986, 77.7066),
        )

        assertEquals("Kempegowda International Airport Terminal 2", airportEntryFor(cache, "BLR/T2")?.name)
        assertEquals("Kempegowda International Airport", airportEntryFor(cache, "BLR")?.name)
        assertNull(airportEntryFor(cache, "BLR/T3"))
        assertNull(airportEntryFor(cache, null))
    }

    @Test
    fun airportLeaveByTitle_isStableForOneFlight() {
        assertEquals("QF401 - leave for Melbourne Airport", airportLeaveByTitle(flight()))
    }

    @Test
    fun airportOriginLabel_namesTheDeviceFixOrTheHomeFallback() {
        assertEquals("Current location", airportOriginLabel(ApiResult.Success(MELBOURNE_AIRPORT)))
        assertEquals("Home", airportOriginLabel(ApiResult.Failure("Location unavailable")))
    }

    // ---- routeDistanceKm ----

    @Test
    fun routeDistanceKm_bengaluruToSingapore_isAboutThirtyTwoHundredKilometres() {
        val km = routeDistanceKm(BENGALURU_AIRPORT, SINGAPORE_AIRPORT)
        assertTrue("expected 3100-3200 km, was $km", km!! in 3_100..3_200)
    }

    @Test
    fun routeDistanceKm_missingEitherEndIsNull() {
        assertNull(routeDistanceKm(null, SINGAPORE_AIRPORT))
        assertNull(routeDistanceKm(BENGALURU_AIRPORT, null))
        assertNull(routeDistanceKm(null, null))
    }

    @Test
    fun routeDistanceKm_samePointIsZero() {
        assertEquals(0, routeDistanceKm(BENGALURU_AIRPORT, BENGALURU_AIRPORT))
    }

    @Test
    fun flightRouteDistanceKm_readsBothEndsOutOfTheIataCache() {
        val cache = mapOf(
            "BLR" to AirportLocation("BLR", "Kempegowda", BENGALURU_AIRPORT.lat, BENGALURU_AIRPORT.lng),
            "SIN" to AirportLocation("SIN", "Changi", SINGAPORE_AIRPORT.lat, SINGAPORE_AIRPORT.lng),
        )
        val connecting = flight(departureIata = "BLR").copy(arrivalIata = "SIN")

        assertTrue(flightRouteDistanceKm(cache, connecting, null)!! in 3_100..3_200)
        assertNull(flightRouteDistanceKm(cache, connecting.copy(arrivalIata = null), null))
    }

    @Test
    fun flightRouteDistanceKm_fallsBackToTheStatusCodes() {
        val cache = mapOf(
            "BLR" to AirportLocation("BLR", "Kempegowda", BENGALURU_AIRPORT.lat, BENGALURU_AIRPORT.lng),
            "SIN" to AirportLocation("SIN", "Changi", SINGAPORE_AIRPORT.lat, SINGAPORE_AIRPORT.lng),
        )
        val bare = flight(departureIata = null).copy(arrivalIata = null)
        val statusWithCodes = status().copy(departureIata = "BLR", arrivalIata = "SIN")

        assertTrue(flightRouteDistanceKm(cache, bare, statusWithCodes)!! in 3_100..3_200)
    }

    @Test
    fun normalizedIata_uppercasesAndTrims() {
        assertEquals("MEL", normalizedIata(" mel "))
        assertNull(normalizedIata("   "))
        assertNull(normalizedIata(null))
    }

    // ---- layover snapshot fields ----

    @Test
    fun airportSnapshotFrom_layoverWindowNamesTheInboundFlightAndItsArrival() {
        val inbound = flight(eventId = 9L)
        val snapshot = airportSnapshotFrom(
            window = AirportWindow(
                flight = flight(),
                windowStartMillis = inbound.arrivalMillis,
                arriveByMillis = ARRIVE_BY,
                layover = true,
                connectionFrom = inbound,
            ),
            state = state(phase = AirportPhase.LAYOVER),
            direction = Direction.TO_WORK,
            zone = MELBOURNE,
            nowEpochMillis = WINDOW_START,
            healthComputation = HealthComputation(),
            routeDistanceKm = 3_172,
        )

        val airport = snapshot.airport!!
        assertTrue(airport.layover)
        assertEquals("QF401", airport.layoverFromDesignator)
        assertEquals(inbound.arrivalMillis, airport.layoverFromArrivalMillis)
        assertEquals(3_172, airport.routeDistanceKm)
        assertEquals(AirportPhase.LAYOVER, airport.phase)
    }

    @Test
    fun airportSnapshotFrom_ordinaryWindowCarriesNoLayoverFields() {
        val snapshot = airportSnapshotFrom(
            window = window(),
            state = state(),
            direction = Direction.TO_WORK,
            zone = MELBOURNE,
            nowEpochMillis = WINDOW_START,
            healthComputation = HealthComputation(),
        )

        val airport = snapshot.airport!!
        assertFalse(airport.layover)
        assertNull(airport.layoverFromDesignator)
        assertNull(airport.layoverFromArrivalMillis)
        assertNull(airport.routeDistanceKm)
    }

    @Test
    fun airportLocationFor_nullOrBlankIataIsNull() {
        assertNull(airportLocationFor(AIRPORT_CACHE, null))
        assertNull(airportLocationFor(AIRPORT_CACHE, "   "))
    }

    @Test
    fun airportLocationFor_missingIataIsNull() {
        assertNull(airportLocationFor(AIRPORT_CACHE, "SYD"))
        assertNull(airportLocationFor(emptyMap(), "MEL"))
    }

    @Test
    fun airportLocationFor_lookupIsCaseInsensitiveBothWays() {
        assertEquals(MELBOURNE_AIRPORT, airportLocationFor(AIRPORT_CACHE, "MEL"))
        assertEquals(MELBOURNE_AIRPORT, airportLocationFor(AIRPORT_CACHE, " mel "))
        assertEquals(
            MELBOURNE_AIRPORT,
            airportLocationFor(
                mapOf("mel" to AirportLocation("mel", "Melbourne Airport", -37.6690, 144.8410)),
                "MEL",
            ),
        )
    }
}

private val AIRPORT_CACHE = mapOf(
    "MEL" to AirportLocation(iata = "MEL", name = "Melbourne Airport", lat = -37.6690, lng = 144.8410),
)
