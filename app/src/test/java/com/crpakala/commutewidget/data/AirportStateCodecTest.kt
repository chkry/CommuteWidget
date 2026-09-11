package com.crpakala.commutewidget.data

import com.crpakala.commutewidget.calendar.FlightEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirportStateCodecTest {
    private val flight = FlightEvent(
        eventId = 42L,
        calendarId = 1L,
        title = "Flight to SFO",
        airlineCode = "UA",
        flightNumber = "123",
        departureMillis = 1_700_000_000_000L,
        arrivalMillis = 1_700_020_000_000L,
        locationText = "SFO Airport",
        departureAirportName = "Bengaluru International",
        departureIata = "BLR",
        arrivalAirportName = "San Francisco International",
        arrivalIata = "SFO",
        confirmationNumber = "ABC123",
        seat = "14A",
        departureTerminal = "T2",
        arrivalTerminal = "I",
    )

    private val flightStatus = FlightStatus(
        designator = "UA123",
        status = "SCHEDULED",
        departureScheduledUtcMillis = 1_700_000_000_000L,
        departureEstimatedUtcMillis = 1_700_001_500_000L,
        departureTerminal = "T2",
        departureGate = "42",
        departureCheckInDesk = "A1-4",
        arrivalScheduledUtcMillis = 1_700_020_000_000L,
        arrivalEstimatedUtcMillis = null,
        arrivalTerminal = "I",
        arrivalGate = "G7",
        arrivalBaggageBelt = "3",
        aircraftModel = "Boeing 777",
        aircraftRegistration = "N12345",
        operatingAirline = "United Airlines",
        codeshareOf = null,
        lastUpdatedUtcMillis = 1_700_000_500_000L,
    )

    @Test
    fun airportStateJson_roundTripWithLastStatus() {
        val state = AirportState(
            eventId = 42L,
            localDate = "2026-09-10",
            phase = AirportPhase.RIDING,
            dismissed = false,
            rideTappedAtMillis = 1_700_000_100_000L,
            reachedAtMillis = null,
            lastStatus = flightStatus,
            lastStatusFetchedAtMillis = 1_700_000_600_000L,
            lastStatusError = null,
        )
        assertEquals(state, decodeAirportState(encodeAirportState(state)))
    }

    @Test
    fun airportStateJson_roundTripWithoutLastStatus() {
        val state = AirportState(
            eventId = 42L,
            localDate = "2026-09-10",
            phase = AirportPhase.OFFERED,
        )
        val decoded = decodeAirportState(encodeAirportState(state))
        assertEquals(state, decoded)
        assertNull(decoded?.lastStatus)
    }

    @Test
    fun airportSnapshotJson_roundTrip() {
        val snapshot = AirportSnapshot(
            flight = flight,
            phase = AirportPhase.OFFERED,
            windowStartMillis = 1_699_990_000_000L,
            arriveByMillis = 1_699_998_000_000L,
            originLabel = "Home",
            airportLat = 12.9716,
            airportLng = 77.5946,
            travelMinutes = 45,
            distanceMeters = 32000,
            leaveByMillis = 1_699_996_000_000L,
            bestDepartureMillis = 1_699_995_000_000L,
            bestTravelMinutes = 40,
            status = flightStatus,
            statusFetchedAtMillis = 1_700_000_600_000L,
            statusError = null,
        )
        val encoded = commuteJson.encodeToString(AirportSnapshot.serializer(), snapshot)
        val decoded = commuteJson.decodeFromString(AirportSnapshot.serializer(), encoded)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun commuteSnapshotJson_legacyFormatWithoutAirportKeyDecodesAirportAsNull() {
        val legacyJson = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 1800,
              "durationNoTrafficSeconds": 1500,
              "distanceMeters": 12000,
              "mapImagePath": "/cache/map.png",
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null
            }
        """.trimIndent()
        val decoded = decodeCommuteSnapshot(legacyJson)
        requireNotNull(decoded)
        assertNull(decoded.airport)
    }

    @Test
    fun flightPreviewJson_roundTripWithStatus() {
        val preview = FlightPreview(
            eventId = 42L,
            flight = flight,
            status = flightStatus,
            fetchedAtMillis = 1_700_000_600_000L,
            error = null,
        )
        assertEquals(preview, decodeFlightPreview(encodeFlightPreview(preview)))
    }

    @Test
    fun flightPreviewJson_roundTripWithErrorAndNoStatus() {
        val preview = FlightPreview(
            eventId = 42L,
            flight = flight,
            status = null,
            fetchedAtMillis = 1_700_000_600_000L,
            error = "AirLabs quota exceeded",
        )
        val decoded = decodeFlightPreview(encodeFlightPreview(preview))
        assertEquals(preview, decoded)
        assertNull(decoded?.status)
    }

    @Test
    fun flightPreviewJson_neverFetchedRoundTrips() {
        val preview = FlightPreview(eventId = 42L, flight = flight)
        val decoded = decodeFlightPreview(encodeFlightPreview(preview))
        assertEquals(preview, decoded)
        assertNull(decoded?.fetchedAtMillis)
    }

    @Test
    fun flightPreviewJson_garbageBlankAndNullDecodeToNull() {
        assertNull(decodeFlightPreview("{not json}"))
        assertNull(decodeFlightPreview(""))
        assertNull(decodeFlightPreview(null))
    }

    @Test
    fun commuteSnapshotJson_legacyFormatWithoutFlightPreviewKeyDecodesAsNull() {
        val legacyJson = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 0,
              "durationNoTrafficSeconds": 0,
              "distanceMeters": 0,
              "mapImagePath": null,
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "mode": "CALENDAR_EMPTY",
              "upcomingEvents": [
                { "title": "Standup", "startEpochMillis": 1700003600000 }
              ]
            }
        """.trimIndent()
        val decoded = decodeCommuteSnapshot(legacyJson)
        requireNotNull(decoded)
        assertNull(decoded.flightPreview)
        assertEquals(1, decoded.upcomingEvents.size)
    }

    @Test
    fun commuteSnapshotJson_flightPreviewRoundTrips() {
        val snapshot = CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 0L,
            durationNoTrafficSeconds = 0L,
            distanceMeters = 0L,
            mapImagePath = null,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            mode = SnapshotMode.CALENDAR_EMPTY,
            flightPreview = FlightPreview(
                eventId = 42L,
                flight = flight,
                status = flightStatus,
                fetchedAtMillis = 1_700_000_600_000L,
            ),
        )
        assertEquals(snapshot, decodeCommuteSnapshot(encodeCommuteSnapshot(snapshot)))
    }

    @Test
    fun airportStateJson_layoverPhaseRoundTrips() {
        val state = AirportState(eventId = 42L, localDate = "2026-09-10", phase = AirportPhase.LAYOVER)
        assertEquals(AirportPhase.LAYOVER, decodeAirportState(encodeAirportState(state))?.phase)
    }

    @Test
    fun airportStateJson_legacyPhaseWithoutLayoverStillDecodes() {
        val legacyJson = """
            { "eventId": 42, "localDate": "2026-09-10", "phase": "REACHED" }
        """.trimIndent()
        val decoded = decodeAirportState(legacyJson)
        requireNotNull(decoded)
        assertEquals(AirportPhase.REACHED, decoded.phase)
    }

    @Test
    fun airportSnapshotJson_layoverAndRouteDistanceRoundTrip() {
        val snapshot = AirportSnapshot(
            flight = flight,
            phase = AirportPhase.LAYOVER,
            windowStartMillis = 1_699_990_000_000L,
            arriveByMillis = 1_699_998_000_000L,
            originLabel = null,
            airportLat = null,
            airportLng = null,
            travelMinutes = null,
            distanceMeters = null,
            leaveByMillis = null,
            bestDepartureMillis = null,
            bestTravelMinutes = null,
            status = flightStatus,
            statusFetchedAtMillis = 1_700_000_600_000L,
            statusError = null,
            layover = true,
            layoverFromDesignator = "SQ509",
            layoverFromArrivalMillis = 1_699_990_000_000L,
            routeDistanceKm = 3_172,
        )
        val encoded = commuteJson.encodeToString(AirportSnapshot.serializer(), snapshot)
        assertEquals(snapshot, commuteJson.decodeFromString(AirportSnapshot.serializer(), encoded))
    }

    @Test
    fun airportSnapshotJson_legacyWithoutLayoverFieldsDecodesToDefaults() {
        val legacy = AirportSnapshot(
            flight = flight,
            phase = AirportPhase.OFFERED,
            windowStartMillis = 1_699_990_000_000L,
            arriveByMillis = 1_699_998_000_000L,
            originLabel = "Home",
            airportLat = 12.9716,
            airportLng = 77.5946,
            travelMinutes = 45,
            distanceMeters = 32000,
            leaveByMillis = 1_699_996_000_000L,
            bestDepartureMillis = null,
            bestTravelMinutes = null,
            status = null,
            statusFetchedAtMillis = null,
            statusError = null,
        )
        val legacyJson = commuteJson.encodeToString(AirportSnapshot.serializer(), legacy)
            .replace(""","layover":false""", "")
            .replace(""","layoverFromDesignator":null""", "")
            .replace(""","layoverFromArrivalMillis":null""", "")
            .replace(""","routeDistanceKm":null""", "")
        val decoded = commuteJson.decodeFromString(AirportSnapshot.serializer(), legacyJson)

        assertEquals(legacy, decoded)
        assertNull(decoded.layoverFromDesignator)
        assertNull(decoded.routeDistanceKm)
    }

    @Test
    fun flightPreviewJson_layoverAndRouteDistanceRoundTrip() {
        val preview = FlightPreview(
            eventId = 42L,
            flight = flight,
            status = flightStatus,
            fetchedAtMillis = 1_700_000_600_000L,
            layoverFromDesignator = "SQ509",
            layoverMinutes = 130,
            routeDistanceKm = 3_172,
        )
        assertEquals(preview, decodeFlightPreview(encodeFlightPreview(preview)))
    }

    @Test
    fun flightPreviewJson_legacyWithoutLayoverFieldsDecodesToDefaults() {
        val legacyJson = """
            {
              "eventId": 42,
              "flight": ${commuteJson.encodeToString(FlightEvent.serializer(), flight)},
              "status": null,
              "fetchedAtMillis": 1700000600000,
              "error": null
            }
        """.trimIndent()
        val decoded = decodeFlightPreview(legacyJson)
        requireNotNull(decoded)
        assertNull(decoded.layoverFromDesignator)
        assertNull(decoded.layoverMinutes)
        assertNull(decoded.routeDistanceKm)
        assertEquals(42L, decoded.eventId)
    }

    private val fleetRecord = AircraftRecord(
        reg = "9V-SMA",
        model = "Airbus A350-900",
        manufacturer = "Airbus",
        typeIcao = "A359",
        typeIata = "359",
        engineType = "jet",
        engineCount = 2,
        builtYear = 2016,
        ageYears = 9,
        serialNumber = "0555",
        fetchedAtMillis = 1_700_000_600_000L,
    )

    private val delayStats = AirportDelayStats(
        iata = "BLR",
        delayedCount = 4,
        averageDelayMinutes = 106,
        maxDelayMinutes = 130,
        fetchedAtMillis = 1_700_000_600_000L,
    )

    @Test
    fun aircraftFleetJson_roundTripsAFullRecord() {
        val fleet = mapOf("9V-SMA" to fleetRecord)
        assertEquals(fleet, decodeAircraftFleet(encodeAircraftFleet(fleet)))
    }

    @Test
    fun aircraftFleetJson_roundTripsARecordWithOnlyARegistration() {
        val empty = AircraftRecord(reg = "9V-DHA", fetchedAtMillis = 1_700_000_600_000L)
        val decoded = decodeAircraftFleet(encodeAircraftFleet(mapOf("9V-DHA" to empty)))

        assertEquals(empty, decoded["9V-DHA"])
        assertNull(decoded["9V-DHA"]?.model)
        assertNull(decoded["9V-DHA"]?.engineCount)
    }

    @Test
    fun aircraftFleetJson_garbageBlankAndNullDecodeToEmpty() {
        assertEquals(emptyMap<String, AircraftRecord>(), decodeAircraftFleet("{not json}"))
        assertEquals(emptyMap<String, AircraftRecord>(), decodeAircraftFleet(""))
        assertEquals(emptyMap<String, AircraftRecord>(), decodeAircraftFleet(null))
    }

    @Test
    fun aircraftFleetJson_legacyRecordWithoutOptionalFieldsDecodes() {
        val legacyJson = """
            { "9V-SMA": { "reg": "9V-SMA", "fetchedAtMillis": 1700000600000 } }
        """.trimIndent()
        val decoded = decodeAircraftFleet(legacyJson)

        assertEquals("9V-SMA", decoded["9V-SMA"]?.reg)
        assertNull(decoded["9V-SMA"]?.serialNumber)
    }

    @Test
    fun airportDelayStatsJson_roundTrips() {
        val stats = mapOf("BLR" to delayStats)
        assertEquals(stats, decodeAirportDelayStats(encodeAirportDelayStats(stats)))
    }

    @Test
    fun airportDelayStatsJson_garbageBlankAndNullDecodeToEmpty() {
        assertEquals(emptyMap<String, AirportDelayStats>(), decodeAirportDelayStats("{not json}"))
        assertEquals(emptyMap<String, AirportDelayStats>(), decodeAirportDelayStats(""))
        assertEquals(emptyMap<String, AirportDelayStats>(), decodeAirportDelayStats(null))
    }

    @Test
    fun airportSnapshotJson_aircraftAndDelayStatsRoundTrip() {
        val snapshot = AirportSnapshot(
            flight = flight,
            phase = AirportPhase.REACHED,
            windowStartMillis = 1_699_990_000_000L,
            arriveByMillis = 1_699_998_000_000L,
            originLabel = null,
            airportLat = null,
            airportLng = null,
            travelMinutes = null,
            distanceMeters = null,
            leaveByMillis = null,
            bestDepartureMillis = null,
            bestTravelMinutes = null,
            status = flightStatus,
            statusFetchedAtMillis = 1_700_000_600_000L,
            statusError = null,
            aircraft = fleetRecord,
            departureDelayStats = delayStats,
        )
        val encoded = commuteJson.encodeToString(AirportSnapshot.serializer(), snapshot)

        assertEquals(snapshot, commuteJson.decodeFromString(AirportSnapshot.serializer(), encoded))
    }

    @Test
    fun airportSnapshotJson_legacyWithoutAircraftAndDelayStatsDecodesToNull() {
        val legacy = AirportSnapshot(
            flight = flight,
            phase = AirportPhase.REACHED,
            windowStartMillis = 1_699_990_000_000L,
            arriveByMillis = 1_699_998_000_000L,
            originLabel = null,
            airportLat = null,
            airportLng = null,
            travelMinutes = null,
            distanceMeters = null,
            leaveByMillis = null,
            bestDepartureMillis = null,
            bestTravelMinutes = null,
            status = flightStatus,
            statusFetchedAtMillis = 1_700_000_600_000L,
            statusError = null,
        )
        val legacyJson = commuteJson.encodeToString(AirportSnapshot.serializer(), legacy)
            .replace(""","aircraft":null""", "")
            .replace(""","departureDelayStats":null""", "")
        val decoded = commuteJson.decodeFromString(AirportSnapshot.serializer(), legacyJson)

        assertEquals(legacy, decoded)
        assertNull(decoded.aircraft)
        assertNull(decoded.departureDelayStats)
    }

    @Test
    fun flightStatusJson_newDetailFieldsRoundTripAndLegacyOnesDecodeToNull() {
        val detailed = flightStatus.copy(
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
        val encoded = commuteJson.encodeToString(FlightStatus.serializer(), detailed)
        assertEquals(detailed, commuteJson.decodeFromString(FlightStatus.serializer(), encoded))

        val legacyJson = commuteJson.encodeToString(FlightStatus.serializer(), flightStatus)
            .replace(""","airlineIata":null""", "")
            .replace(""","airlineIcao":null""", "")
            .replace(""","aircraftEngineCount":null""", "")
            .replace(""","transponderCode":null""", "")
        val legacy = commuteJson.decodeFromString(FlightStatus.serializer(), legacyJson)
        assertEquals(flightStatus, legacy)
        assertNull(legacy.airlineIata)
        assertNull(legacy.transponderCode)
        assertNull(legacy.aircraftEngineCount)
    }

    @Test
    fun flightStatus_departureDelayMinutes_twentyFiveMinuteDelay() {
        val status = flightStatus.copy(
            departureScheduledUtcMillis = 1_700_000_000_000L,
            departureEstimatedUtcMillis = 1_700_000_000_000L + 25 * 60_000L,
        )
        assertEquals(25, status.departureDelayMinutes)
    }

    @Test
    fun flightStatus_departureDelayMinutes_missingEstimateIsNull() {
        val status = flightStatus.copy(departureEstimatedUtcMillis = null)
        assertNull(status.departureDelayMinutes)
    }
}
