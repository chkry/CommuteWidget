package com.crpakala.commutewidget.api

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FlightStatusMapperTest {

    private val schedulesFixture = resource("airlabs/schedules_qf401_fixture.json")
    private val flightFixture = resource("airlabs/flight_qf401_fixture.json")
    private val routesFixture = resource("airlabs/routes_sq509_fixture.json")
    private val emptySchedulesFixture = resource("airlabs/schedules_sq509_fixture.json")
    private val preDepartureFlightFixture = resource("airlabs/flight_sq509_prev_fixture.json")
    private val fleetFixture = resource("airlabs/fleet_reg_fixture.json")
    private val delaysFixture = resource("airlabs/delays_blr_fixture.json")
    private val localDate = LocalDate.parse("2026-09-11")
    private val routesLocalDate = LocalDate.parse("2026-10-13")

    @Test
    fun fromSchedulesJson_mapsScheduledFlight() {
        val status = FlightStatusMapper.fromSchedulesJson(schedulesFixture, "QF401", localDate)

        assertNotNull(status)
        assertEquals("scheduled", status!!.status)
        assertEquals("1", status.departureGate)
        assertEquals("3", status.departureTerminal)
        assertEquals("8", status.arrivalGate)
        assertEquals(1789070400000L, status.departureScheduledUtcMillis)
        assertEquals(1789076100000L, status.arrivalScheduledUtcMillis)
        assertEquals(95, status.durationMinutes)
        assertEquals("SYD", status.departureIata)
        assertEquals("MEL", status.arrivalIata)
        assertEquals("schedules", status.source)
        assertNull(status.departureEstimatedUtcMillis)
        assertNull(status.arrivalEstimatedUtcMillis)
    }

    @Test
    fun fromFlightJson_mapsLandedFlight() {
        val status = FlightStatusMapper.fromFlightJson(flightFixture, "QF401")

        assertNotNull(status)
        assertEquals("landed", status!!.status)
        assertEquals(6, status.departureDelayedMinutes)
        assertEquals(1788984360000L, status.departureActualUtcMillis)
        assertEquals(100, status.percentComplete)
        assertEquals("Qantas Airways", status.airlineName)
        assertEquals("Sydney International Airport", status.departureAirportName)
        assertEquals(1788991353000L, status.lastUpdatedUtcMillis)
    }

    @Test
    fun departureDelayMinutes_prefersApiFieldOverArithmetic() {
        val status = FlightStatusMapper.fromFlightJson(flightFixture, "QF401")!!

        assertEquals(6, status.departureDelayMinutes)
    }

    @Test
    fun departureDelayMinutes_fallsBackToArithmeticWhenApiFieldNull() {
        val status = FlightStatusMapper.fromFlightJson(flightFixture, "QF401")!!.copy(departureDelayedMinutes = null)

        assertEquals(6, status.departureDelayMinutes)
    }

    @Test
    fun fromSchedulesJson_selectsElementMatchingLocalDate() {
        val body = """
            {
              "response": [
                {
                  "flight_iata": "QF401",
                  "dep_iata": "SYD",
                  "dep_gate": "9",
                  "dep_time": "2026-09-10 06:00",
                  "dep_time_ts": 1788984000,
                  "status": "scheduled"
                },
                {
                  "flight_iata": "QF401",
                  "dep_iata": "SYD",
                  "dep_gate": "1",
                  "dep_time": "2026-09-11 06:00",
                  "dep_time_ts": 1789070400,
                  "status": "scheduled"
                }
              ]
            }
        """.trimIndent()

        val status = FlightStatusMapper.fromSchedulesJson(body, "QF401", localDate)

        assertNotNull(status)
        assertEquals("1", status!!.departureGate)
    }

    @Test
    fun fromSchedulesJson_emptyResponseArrayReturnsNull() {
        assertNull(FlightStatusMapper.fromSchedulesJson("""{"response": []}""", "QF401", localDate))
    }

    @Test
    fun merge_keepsScheduleGateWhenLiveGateNull_andTakesLiveActualTimes() {
        val schedule = FlightStatusMapper.fromSchedulesJson(schedulesFixture, "QF401", localDate)!!
        val live = FlightStatusMapper.fromFlightJson(flightFixture, "QF401")!!.copy(departureGate = null)

        val merged = FlightStatusMapper.merge(schedule, live)

        assertEquals(schedule.departureGate, merged.departureGate)
        assertEquals(live.departureActualUtcMillis, merged.departureActualUtcMillis)
    }

    /** Sydney and Melbourne are both +10:00 in September, which the fixture's two stamp pairs say. */
    @Test
    fun fromSchedulesJson_carriesBothAirportUtcOffsets() {
        val status = FlightStatusMapper.fromSchedulesJson(schedulesFixture, "QF401", localDate)!!

        assertEquals(600, status.departureUtcOffsetMinutes)
        assertEquals(600, status.arrivalUtcOffsetMinutes)
    }

    @Test
    fun fromFlightJson_carriesBothAirportUtcOffsets() {
        val status = FlightStatusMapper.fromFlightJson(flightFixture, "QF401")!!

        assertEquals(600, status.departureUtcOffsetMinutes)
        assertEquals(600, status.arrivalUtcOffsetMinutes)
    }

    /** The timetable's bare "HH:mm" pairs: BLR is +5:30 and SIN +8:00. */
    @Test
    fun fromRoutesJson_carriesBothAirportUtcOffsets() {
        val status = FlightStatusMapper.fromRoutesJson(routesFixture, "SQ509", routesLocalDate, "BLR")!!

        assertEquals(330, status.departureUtcOffsetMinutes)
        assertEquals(480, status.arrivalUtcOffsetMinutes)
    }

    @Test
    fun fromRoutesJson_offsetBehindUtcIsNormalisedNegative() {
        val body = """
            {
              "response": [
                {
                  "flight_iata": "SQ509",
                  "dep_iata": "BLR",
                  "dep_time": "23:30",
                  "dep_time_utc": "03:30"
                }
              ]
            }
        """.trimIndent()

        val status = FlightStatusMapper.fromRoutesJson(body, "SQ509", routesLocalDate, "BLR")

        assertEquals(-240, status!!.departureUtcOffsetMinutes)
        assertNull(status.arrivalUtcOffsetMinutes)
    }

    @Test
    fun merge_keepsScheduleOffsetsWhenTheLiveOnesAreNull() {
        val schedule = FlightStatusMapper.fromSchedulesJson(schedulesFixture, "QF401", localDate)!!
        val live = FlightStatusMapper.fromFlightJson(flightFixture, "QF401")!!
            .copy(departureUtcOffsetMinutes = null, arrivalUtcOffsetMinutes = null)

        val merged = FlightStatusMapper.merge(schedule, live)

        assertEquals(600, merged.departureUtcOffsetMinutes)
        assertEquals(600, merged.arrivalUtcOffsetMinutes)
    }

    @Test
    fun errorMessage_returnsMessageForErrorBody() {
        val body = """{"error":{"message":"Unknown api_key","code":"unauthorized"}}"""

        assertEquals("Unknown api_key", FlightStatusMapper.errorMessage(body))
    }

    @Test
    fun errorMessage_returnsNullForNormalBody() {
        assertNull(FlightStatusMapper.errorMessage(schedulesFixture))
    }

    @Test
    fun fromFlightJson_malformedTimestampYieldsNullFieldWithoutThrowing() {
        val body = """{"response": {"flight_iata": "QF401", "status": "scheduled", "dep_time_utc": "not-a-date"}}"""

        val status = FlightStatusMapper.fromFlightJson(body, "QF401")

        assertNotNull(status)
        assertNull(status!!.departureScheduledUtcMillis)
    }

    @Test
    fun fromSchedulesJson_unknownExtraKeysAreIgnored() {
        val body = """
            {
              "response": [
                {
                  "flight_iata": "QF401",
                  "dep_time": "2026-09-11 06:00",
                  "status": "scheduled",
                  "unexpected_field": { "nested": true, "ignored": [1, 2, 3] }
                }
              ]
            }
        """.trimIndent()

        val status = FlightStatusMapper.fromSchedulesJson(body, "QF401", localDate)

        assertNotNull(status)
    }

    @Test
    fun fromRoutesJson_mapsTimetableFlight() {
        val status = FlightStatusMapper.fromRoutesJson(routesFixture, "SQ509", routesLocalDate, "BLR")

        assertNotNull(status)
        val expectedDeparture = ZonedDateTime.of(2026, 10, 13, 6, 5, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expectedDeparture, status!!.departureScheduledUtcMillis)
        assertEquals(expectedDeparture + 295 * 60_000L, status.arrivalScheduledUtcMillis)
        assertEquals("2", status.departureTerminal)
        assertNull(status.arrivalTerminal)
        assertEquals("BLR", status.departureIata)
        assertEquals("SIN", status.arrivalIata)
        assertEquals("routes", status.source)
        assertEquals("scheduled", status.status)
    }

    @Test
    fun fromRoutesJson_mondayDateStillScheduled() {
        val status = FlightStatusMapper.fromRoutesJson(routesFixture, "SQ509", LocalDate.parse("2026-10-12"), "BLR")

        assertNotNull(status)
        assertEquals("scheduled", status!!.status)
    }

    @Test
    fun fromRoutesJson_dayNotInDaysListYieldsNotScheduled() {
        val body = """
            {
              "response": [
                {
                  "flight_iata": "SQ509",
                  "dep_iata": "BLR",
                  "dep_time": "11:35",
                  "dep_time_utc": "06:05",
                  "arr_iata": "SIN",
                  "arr_time": "19:00",
                  "arr_time_utc": "11:00",
                  "duration": 295,
                  "days": ["mon", "wed"]
                }
              ]
            }
        """.trimIndent()

        val status = FlightStatusMapper.fromRoutesJson(body, "SQ509", routesLocalDate, "BLR")

        assertNotNull(status)
        assertEquals("not scheduled", status!!.status)
    }

    @Test
    fun fromRoutesJson_negativeOffsetCrossesUtcDate() {
        val body = """
            {
              "response": [
                {
                  "flight_iata": "SQ509",
                  "dep_iata": "BLR",
                  "dep_time": "23:30",
                  "dep_time_utc": "03:30"
                }
              ]
            }
        """.trimIndent()

        val status = FlightStatusMapper.fromRoutesJson(body, "SQ509", routesLocalDate, "BLR")

        assertNotNull(status)
        val expected = ZonedDateTime.of(2026, 10, 14, 3, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, status!!.departureScheduledUtcMillis)
    }

    @Test
    fun fromSchedulesJson_emptyFixtureMapsToNull() {
        assertNull(FlightStatusMapper.fromSchedulesJson(emptySchedulesFixture, "SQ509", routesLocalDate))
    }

    @Test
    fun fromRoutesJson_selectsElementMatchingDepartureIata() {
        val body = """
            {
              "response": [
                { "flight_iata": "SQ509", "dep_iata": "BLR", "arr_iata": "SIN", "dep_time": "11:35", "dep_time_utc": "06:05" },
                { "flight_iata": "SQ509", "dep_iata": "SIN", "arr_iata": "BLR", "dep_time": "21:00", "dep_time_utc": "13:00" }
              ]
            }
        """.trimIndent()

        val status = FlightStatusMapper.fromRoutesJson(body, "SQ509", routesLocalDate, "SIN")

        assertNotNull(status)
        assertEquals("SIN", status!!.departureIata)
        assertEquals("BLR", status.arrivalIata)
    }

    /**
     * The real pre-departure capture: `flight` answers for a scheduled leg with the arrival gate,
     * the airline's own name and both airport names, none of which `schedules` carries. It names no
     * airframe yet, which is why the fleet lookup waits for a registration.
     */
    @Test
    fun fromFlightJson_preDepartureCarriesArrivalGateAirlineAndAirportNames() {
        val status = FlightStatusMapper.fromFlightJson(preDepartureFlightFixture, "SQ509")

        assertNotNull(status)
        assertEquals("scheduled", status!!.status)
        assertEquals("D41", status.arrivalGate)
        assertEquals("D2 D3", status.departureGate)
        assertEquals("Singapore Airlines", status.airlineName)
        assertEquals("SQ", status.airlineIata)
        assertEquals("SIA", status.airlineIcao)
        assertEquals("Kempegowda International Airport", status.departureAirportName)
        assertEquals("Bengaluru", status.departureCity)
        assertEquals("IN", status.departureCountry)
        assertEquals("Singapore Changi Airport", status.arrivalAirportName)
        assertEquals("Singapore", status.arrivalCity)
        assertEquals("SG", status.arrivalCountry)
        assertNull(status.aircraftRegistration)
        assertNull(status.aircraftIcao)
        assertNull(status.arrivalBaggageBelt)
    }

    @Test
    fun merge_takesTheFlightEndpointArrivalGateWhenTheScheduleHasNone() {
        val schedule = FlightStatusMapper.fromSchedulesJson(schedulesFixture, "QF401", localDate)!!
            .copy(arrivalGate = null, airlineName = null)
        val live = FlightStatusMapper.fromFlightJson(preDepartureFlightFixture, "SQ509")!!

        val merged = FlightStatusMapper.merge(schedule, live)

        assertEquals("D41", merged.arrivalGate)
        assertEquals("Singapore Airlines", merged.airlineName)
        assertEquals("Bengaluru", merged.departureCity)
    }

    /** The owner's plan returns the tail and nothing else; the record is still cached so the miss costs one query. */
    @Test
    fun fromFleetJson_mapsRegistrationAndLeavesTheRestNull() {
        val record = FlightStatusMapper.fromFleetJson(fleetFixture, "9V-DHA", 1_700_000_000_000L)

        assertNotNull(record)
        assertEquals("9V-DHA", record!!.reg)
        assertEquals(1_700_000_000_000L, record.fetchedAtMillis)
        assertNull(record.model)
        assertNull(record.manufacturer)
        assertNull(record.typeIcao)
        assertNull(record.engineType)
        assertNull(record.engineCount)
        assertNull(record.builtYear)
        assertNull(record.ageYears)
        assertNull(record.serialNumber)
    }

    @Test
    fun fromFleetJson_emptyResponseStillYieldsARecordForTheRequestedRegistration() {
        val record = FlightStatusMapper.fromFleetJson("""{"response": []}""", "9V-SMA", 1_700_000_000_000L)

        assertNotNull(record)
        assertEquals("9V-SMA", record!!.reg)
        assertNull(record.model)
    }

    @Test
    fun fromFleetJson_garbageBodyIsNull() {
        assertNull(FlightStatusMapper.fromFleetJson("{not json}", "9V-SMA", 1_700_000_000_000L))
    }

    /** Four rows delayed 130, 130, 130 and 32 minutes: average 105.5 rounds to 106, worst is 130. */
    @Test
    fun fromDelaysJson_aggregatesCountAverageAndMax() {
        val stats = FlightStatusMapper.fromDelaysJson(delaysFixture, "blr", 1_700_000_000_000L)

        assertNotNull(stats)
        assertEquals("BLR", stats!!.iata)
        assertEquals(4, stats.delayedCount)
        assertEquals(106, stats.averageDelayMinutes)
        assertEquals(130, stats.maxDelayMinutes)
        assertEquals(1_700_000_000_000L, stats.fetchedAtMillis)
    }

    @Test
    fun fromDelaysJson_emptyBoardIsNull() {
        assertNull(FlightStatusMapper.fromDelaysJson("""{"response": []}""", "BLR", 1_700_000_000_000L))
        assertNull(FlightStatusMapper.fromDelaysJson("{not json}", "BLR", 1_700_000_000_000L))
    }

    @Test
    fun fromFlightJson_readsASquawkSentAsANumber() {
        val body = """{"response":{"flight_iata":"SQ509","status":"en-route","squawk":2000,"v_speed":-3.4}}"""

        val status = FlightStatusMapper.fromFlightJson(body, "SQ509")

        assertNotNull(status)
        assertEquals("2000", status!!.transponderCode)
        assertEquals(-3, status.verticalSpeedKmh)
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Missing test resource $name" }
            .bufferedReader()
            .use { it.readText() }
}
