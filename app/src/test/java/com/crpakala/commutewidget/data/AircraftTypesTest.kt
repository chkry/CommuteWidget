package com.crpakala.commutewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ICAO type designator lookup for the flight card's aircraft line. */
class AircraftTypesTest {

    @Test
    fun aircraftTypeName_knownAirbusCodes() {
        assertEquals("Airbus A320", aircraftTypeName("A320"))
        assertEquals("Airbus A320neo", aircraftTypeName("A20N"))
        assertEquals("Airbus A321neo", aircraftTypeName("A21N"))
        assertEquals("Airbus A330-900neo", aircraftTypeName("A339"))
        assertEquals("Airbus A350-1000", aircraftTypeName("A35K"))
        assertEquals("Airbus A380-800", aircraftTypeName("A388"))
        assertEquals("Airbus A220-300", aircraftTypeName("BCS3"))
    }

    @Test
    fun aircraftTypeName_knownBoeingCodes() {
        assertEquals("Boeing 737-800", aircraftTypeName("B738"))
        assertEquals("Boeing 737 MAX 8", aircraftTypeName("B38M"))
        assertEquals("Boeing 747-8", aircraftTypeName("B748"))
        assertEquals("Boeing 777-300ER", aircraftTypeName("B77W"))
        assertEquals("Boeing 787-9", aircraftTypeName("B789"))
        assertEquals("Boeing 787-10", aircraftTypeName("B78X"))
    }

    @Test
    fun aircraftTypeName_knownRegionalCodes() {
        assertEquals("Embraer E190-E2", aircraftTypeName("E290"))
        assertEquals("Embraer E195-E2", aircraftTypeName("E295"))
        assertEquals("ATR 72-600", aircraftTypeName("AT76"))
        assertEquals("Dash 8 Q400", aircraftTypeName("DH8D"))
        assertEquals("Bombardier CRJ900", aircraftTypeName("CRJ9"))
        assertEquals("Sukhoi Superjet 100", aircraftTypeName("SU95"))
        assertEquals("COMAC C919", aircraftTypeName("C919"))
    }

    @Test
    fun aircraftTypeName_lookupIsCaseInsensitiveAndTrims() {
        assertEquals("Boeing 787-9", aircraftTypeName("b789"))
        assertEquals("Airbus A320neo", aircraftTypeName("a20n"))
        assertEquals("Boeing 737-800", aircraftTypeName(" B738 "))
    }

    @Test
    fun aircraftTypeName_unknownBlankAndNullAreNull() {
        assertNull(aircraftTypeName("ZZZZ"))
        assertNull(aircraftTypeName(""))
        assertNull(aircraftTypeName("   "))
        assertNull(aircraftTypeName(null))
    }

    @Test
    fun aircraftTypeNames_tableHasAtLeastSeventyEntries() {
        assertTrue("expected 70+, was ${aircraftTypeNames().size}", aircraftTypeNames().size >= 70)
    }

    @Test
    fun aircraftTypeNames_everyEntryIsUppercaseWithANonBlankName() {
        aircraftTypeNames().forEach { (code, name) ->
            assertEquals(code.uppercase(), code)
            assertTrue("blank name for $code", name.isNotBlank())
        }
    }

    @Test
    fun aircraftDisplayName_prefersTheProviderModelThenTheTypeCode() {
        val status = FlightStatus(
            designator = "QF401",
            status = "Scheduled",
            departureScheduledUtcMillis = null,
            departureEstimatedUtcMillis = null,
            departureTerminal = null,
            departureGate = null,
            departureCheckInDesk = null,
            arrivalScheduledUtcMillis = null,
            arrivalEstimatedUtcMillis = null,
            arrivalTerminal = null,
            arrivalGate = null,
            arrivalBaggageBelt = null,
            aircraftModel = "Boeing 737",
            aircraftRegistration = null,
            operatingAirline = null,
            codeshareOf = null,
            lastUpdatedUtcMillis = null,
            aircraftIcao = "B78X",
        )

        assertEquals("Boeing 737", status.aircraftDisplayName)
        assertEquals("Boeing 787-10", status.copy(aircraftModel = null).aircraftDisplayName)
        assertNull(status.copy(aircraftModel = null, aircraftIcao = null).aircraftDisplayName)
        assertNull(status.copy(aircraftModel = null, aircraftIcao = "ZZZZ").aircraftDisplayName)
    }
}
