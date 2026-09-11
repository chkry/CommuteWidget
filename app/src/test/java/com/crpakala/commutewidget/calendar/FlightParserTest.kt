package com.crpakala.commutewidget.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightParserTest {
    @Test
    fun gmailForm_withRouteConfirmationSeatTerminal_parsesAllFields() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF 401)",
            location = "Melbourne Airport (MEL)",
            description = "Melbourne (MEL) - Sydney (SYD)\nConfirmation Number: ABC12345\nSeat: 14A\nTerminal 2",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("QF", flight?.airlineCode)
        assertEquals("401", flight?.flightNumber)
        assertEquals("QF401", flight?.designator)
        assertEquals(1_000L, flight?.departureMillis)
        assertEquals(5_000L, flight?.arrivalMillis)
        assertEquals("Melbourne Airport (MEL)", flight?.locationText)
        assertEquals("Melbourne Airport", flight?.departureAirportName)
        assertEquals("MEL", flight?.departureIata)
        assertEquals("Sydney", flight?.arrivalAirportName)
        assertEquals("SYD", flight?.arrivalIata)
        assertEquals("ABC12345", flight?.confirmationNumber)
        assertEquals("14A", flight?.seat)
        assertEquals("2", flight?.departureTerminal)
        assertNull(flight?.arrivalTerminal)
    }

    @Test
    fun designatorWithoutSpaceInTitle_parses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Perth (JQ507)",
            location = null,
            description = "Boarding pass attached.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("JQ", flight?.airlineCode)
        assertEquals("507", flight?.flightNumber)
        assertEquals("Perth", flight?.arrivalAirportName)
        assertNull(flight?.arrivalIata)
    }

    @Test
    fun designatorOnlyInDescription_afterFlightLabel_parses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Team sync",
            location = null,
            description = "Your flight: JQ 233 departs at 6am from the airport.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("JQ", flight?.airlineCode)
        assertEquals("233", flight?.flightNumber)
    }

    @Test
    fun allDayEvent_isRejected() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF401)",
            location = "Melbourne Airport (MEL)",
            description = "Confirmation Number: ABC12345",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = true,
        )

        assertNull(flight)
    }

    @Test
    fun meetingWithRoomCapacityCode_isRejected() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Review Q4 plan",
            location = "T2 (4)",
            description = null,
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertNull(flight)
    }

    @Test
    fun htmlTagsInDescription_areStrippedBeforeMatching() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF401)",
            location = null,
            description = "<div>Confirmation Number: <b>XYZ98765</b></div><br/><p>Seat: 7B</p>",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("XYZ98765", flight?.confirmationNumber)
        assertEquals("7B", flight?.seat)
    }

    @Test
    fun lowercasePnrLabel_parsesAndUppercasesValue() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Auckland (QF 145)",
            location = null,
            description = "pnr: abc123",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("ABC123", flight?.confirmationNumber)
    }

    @Test
    fun missingConfirmationLabel_givesNullConfirmation() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF401)",
            location = null,
            description = "Boarding starts 45 minutes before departure. Terminal 3.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertNull(flight?.confirmationNumber)
    }

    @Test
    fun twoCharCodeWithDigit_parses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Nadi (3K 121)",
            location = null,
            description = "Departure from the airport gate soon.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("3K", flight?.airlineCode)
        assertEquals("121", flight?.flightNumber)
    }

    @Test
    fun bareRouteCodesWithDash_parseDepartureAndArrivalIata() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF401)",
            location = null,
            description = "Route: MEL - SYD, confirmation to follow.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("MEL", flight?.departureIata)
        assertEquals("SYD", flight?.arrivalIata)
    }

    @Test
    fun hotelConfirmationWithCheckInTime_isRejected() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Hilton Melbourne",
            location = "190 Normanby Rd, South Wharf",
            description = "Confirmation number: ABC12345\nCheck-in at 3 pm, check-out at 11 am.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertNull(flight)
    }

    @Test
    fun quarterPlanningTitle_isRejected() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Q1 2026 planning",
            location = null,
            description = "Confirmation number: ABC12345 for the offsite venue.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertNull(flight)
    }

    @Test
    fun delegateReviewWithRoomCode_isRejected() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Delegate review (T2 4)",
            location = "T2 (4)",
            description = "Delegate list attached.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertNull(flight)
    }

    @Test
    fun flightLabelledDesignatorInDescriptionOnly_parses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Team sync",
            location = null,
            description = "Flight QF401 departs 6:00 am.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("QF", flight?.airlineCode)
        assertEquals("401", flight?.flightNumber)
    }

    @Test
    fun airlineLineAboveRoutePair_parses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Trip to Sydney",
            location = null,
            description = "Qantas QF 401\nMelbourne (MEL) - Sydney (SYD)\nTerminal 2",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("QF", flight?.airlineCode)
        assertEquals("401", flight?.flightNumber)
        assertEquals("MEL", flight?.departureIata)
        assertEquals("SYD", flight?.arrivalIata)
    }

    @Test
    fun titleParenDesignatorWithoutFlightWord_parses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Sydney (QF 401)",
            location = null,
            description = "Boarding closes 20 minutes before departure.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("QF", flight?.airlineCode)
        assertEquals("401", flight?.flightNumber)
    }

    /**
     * The owner's own Gmail-added event, verbatim: bare IATA codes in both the location and the
     * route line, local times and a "(local time)" aside between the code and the dash, and the
     * airline code only in the title.
     */
    @Test
    fun ownerGmailFlight_bareCodesAndLocalTimes_parsesEveryField() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = "Bengaluru BLR",
            description = "Singapore Air flight 509\n" +
                "Bengaluru BLR 11:35 (local time) - Singapore SIN 19:00 (local time)\n\n" +
                "Confirmation number: FOICCE",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("SQ509", flight?.designator)
        assertEquals("Bengaluru", flight?.departureAirportName)
        assertEquals("BLR", flight?.departureIata)
        assertEquals("Singapore", flight?.arrivalAirportName)
        assertEquals("SIN", flight?.arrivalIata)
        assertEquals("FOICCE", flight?.confirmationNumber)
        assertEquals("Bengaluru BLR", flight?.locationText)
    }

    @Test
    fun locationWithBareTrailingCode_parsesNameAndIata() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF 401)",
            location = "Melbourne Airport MEL",
            description = "Boarding closes 20 minutes before departure.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("Melbourne Airport", flight?.departureAirportName)
        assertEquals("MEL", flight?.departureIata)
    }

    @Test
    fun locationBareCodeExtraction_isWholeWordAndLettersOnly() {
        val room = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = "Room 3 BLR",
            description = "Boarding closes 20 minutes before departure.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )
        assertNull(room?.departureIata)

        val glued = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = "Bengaluru BLRX",
            description = "Boarding closes 20 minutes before departure.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )
        assertNull(glued?.departureIata)
    }

    @Test
    fun descriptionRouteWithBareCodes_parsesBothNamesAndCodes() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = null,
            description = "Bengaluru BLR - Singapore SIN",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("Bengaluru", flight?.departureAirportName)
        assertEquals("BLR", flight?.departureIata)
        assertEquals("Singapore", flight?.arrivalAirportName)
        assertEquals("SIN", flight?.arrivalIata)
    }

    @Test
    fun descriptionRouteLocalTimeAside_isNeverReadAsAnAirportCode() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = null,
            description = "Bengaluru BLR 11:35 (local time) - Singapore SIN 19:00 (local time)",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("BLR", flight?.departureIata)
        assertEquals("SIN", flight?.arrivalIata)
    }

    /** The two printed times are the only airport-local clocks a card has before any status lands. */
    @Test
    fun ownerGmailFlight_routeLineTimes_areKeptAsPrinted() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = "Bengaluru BLR",
            description = "Singapore Air flight 509\n" +
                "Bengaluru BLR 11:35 (local time) - Singapore SIN 19:00 (local time)\n\n" +
                "Confirmation number: FOICCE",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("11:35", flight?.departureLocalHm)
        assertEquals("19:00", flight?.arrivalLocalHm)
    }

    @Test
    fun parenthesisedRouteWithTimes_keepsBothPrintedTimes() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = null,
            description = "Bengaluru (BLR) 11:35 (local time) - Singapore (SIN) 19:00 (local time)",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("BLR", flight?.departureIata)
        assertEquals("SIN", flight?.arrivalIata)
        assertEquals("11:35", flight?.departureLocalHm)
        assertEquals("19:00", flight?.arrivalLocalHm)
    }

    @Test
    fun parenthesisedRouteWithoutTimes_leavesBothPrintedTimesNull() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF 401)",
            location = null,
            description = "Melbourne (MEL) - Sydney (SYD)",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("MEL", flight?.departureIata)
        assertEquals("SYD", flight?.arrivalIata)
        assertNull(flight?.departureLocalHm)
        assertNull(flight?.arrivalLocalHm)
    }

    @Test
    fun bareRouteWithoutTimes_leavesBothPrintedTimesNull() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = null,
            description = "Bengaluru BLR - Singapore SIN",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertNull(flight?.departureLocalHm)
        assertNull(flight?.arrivalLocalHm)
    }

    @Test
    fun routeCodesOnlyWithNoNames_leavesBothPrintedTimesNull() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF 401)",
            location = null,
            description = "Boarding at gate 5. MEL to SYD.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("MEL", flight?.departureIata)
        assertNull(flight?.departureLocalHm)
        assertNull(flight?.arrivalLocalHm)
    }

    @Test
    fun designatorTakesAirlineCodeFromTitleGroup_whenDescriptionNamesOnlyTheNumber() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = null,
            description = "Singapore Air flight 509",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("SQ", flight?.airlineCode)
        assertEquals("509", flight?.flightNumber)
    }

    /** The device bug: Gmail joins the two label words with a non-breaking space. */
    @Test
    fun confirmationLabelJoinedByNonBreakingSpace_stillParses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = "Bengaluru BLR",
            description = "Confirmation\u00A0number:\u00A0FOICCE",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("FOICCE", flight?.confirmationNumber)
    }

    @Test
    fun confirmationLabelAsHtmlEntity_stillParses() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Singapore (SQ 509)",
            location = "Bengaluru BLR",
            description = "<div>Confirmation" + "&" + "nbsp;number: <b>FOICCE</b></div>",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertEquals("FOICCE", flight?.confirmationNumber)
    }

    @Test
    fun bareConfirmationLabel_doesNotCaptureAnOrdinaryWord() {
        val flight = FlightParser.parse(
            eventId = 1L,
            calendarId = 2L,
            title = "Flight to Sydney (QF401)",
            location = null,
            description = "Route: MEL - SYD, confirmation to follow.",
            beginMillis = 1_000L,
            endMillis = 5_000L,
            allDay = false,
        )

        assertNull(flight?.confirmationNumber)
    }
}
