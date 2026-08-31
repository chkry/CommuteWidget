package com.crpakala.commutewidget.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isRoutableEventLocation] is the marker check backing [RawInstance.routableLocation] - see
 * [selectTodayEvent]'s doc for how a rejected location becomes "no location" throughout the
 * calendar-mode pipeline. Conservative by design: only literal known virtual-meeting markers and
 * a trailing bare-integer parenthesized suffix (the Outlook room-resource capacity marker) reject,
 * so real addresses are never mistaken for junk.
 */
class RoutableEventLocationTest {
    @Test
    fun teamsUrl_isRejected() {
        assertFalse(isRoutableEventLocation("https://teams.microsoft.com/l/meetup-join/abc123"))
    }

    @Test
    fun bareZoomLink_withoutScheme_isRejected() {
        assertFalse(isRoutableEventLocation("zoom.us/j/1234567890"))
    }

    @Test
    fun meetGoogleLink_isRejected() {
        assertFalse(isRoutableEventLocation("meet.google.com/abc-defg-hij"))
    }

    @Test
    fun webexLink_isRejected() {
        assertFalse(isRoutableEventLocation("company.webex.com/meet/jane.doe"))
    }

    @Test
    fun literalMicrosoftTeamsMeeting_isRejected_anyCase() {
        assertFalse(isRoutableEventLocation("Microsoft Teams Meeting"))
        assertFalse(isRoutableEventLocation("MICROSOFT TEAMS MEETING"))
        assertFalse(isRoutableEventLocation("microsoft teams meeting"))
    }

    @Test
    fun mixedRoomAndUrl_isRejected() {
        assertFalse(isRoutableEventLocation("Conf Room 4B / https://teams.microsoft.com/l/meetup-join/abc123"))
    }

    @Test
    fun streetAddressWithCommas_isAccepted() {
        assertTrue(isRoutableEventLocation("123 Main Street, Koramangala, Bengaluru, KA 560034"))
    }

    @Test
    fun mallName_isAccepted() {
        assertTrue(isRoutableEventLocation("Phoenix MarketCity Mall"))
    }

    @Test
    fun bareCityName_isAccepted() {
        assertTrue(isRoutableEventLocation("Bengaluru"))
    }

    @Test
    fun outlookRoomResourceWithCapacitySuffix_isRejected() {
        // The regression this pins: an Outlook conference-room resource that never geocodes.
        assertFalse(isRoutableEventLocation("PA HQ-1-FOREST (4)"))
    }

    @Test
    fun conferenceRoomWithCapacitySuffix_isRejected() {
        assertFalse(isRoutableEventLocation("Conference Room (12)"))
    }

    @Test
    fun plotWithNonNumericParentheticalSuffix_isAccepted() {
        // The final parenthesized group is "Phase 2", not a bare integer, so this must still
        // route - precision matters here more than recall.
        assertTrue(isRoutableEventLocation("Plot 12 (Phase 2)"))
    }

    @Test
    fun plainStreetAddress_isAccepted() {
        assertTrue(isRoutableEventLocation("42 Residency Road, Bengaluru"))
    }

    @Test
    fun midStringParenthesesWithoutTrailingIntegerSuffix_isAccepted() {
        // Parentheses appear mid-string, not as a trailing bare-integer suffix, so this must
        // still route.
        assertTrue(isRoutableEventLocation("Cafe (near park) Road"))
    }
}
