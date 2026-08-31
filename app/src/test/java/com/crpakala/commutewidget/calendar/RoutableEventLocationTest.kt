package com.crpakala.commutewidget.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isRoutableEventLocation] is the marker check backing [RawInstance.routableLocation] - see
 * [selectTodayEvent]'s doc for how a rejected location becomes "no location" throughout the
 * calendar-mode pipeline. Conservative by design: only literal known virtual-meeting markers
 * reject, so real addresses are never mistaken for junk.
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
}
