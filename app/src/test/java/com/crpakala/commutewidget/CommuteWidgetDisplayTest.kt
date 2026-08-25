package com.crpakala.commutewidget

import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteWidgetDisplayTest {
    @Test
    fun destinationDisplayLabel_fallsBackToWorkWhenSnapshotLabelMissing() {
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_WORK, null))
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_WORK, ""))
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_WORK, "   "))
    }

    @Test
    fun destinationDisplayLabel_fallsBackToHomeWhenSnapshotLabelMissing() {
        assertEquals("To Home", destinationDisplayLabel(Direction.TO_HOME, null))
    }

    @Test
    fun destinationDisplayLabel_prefixesSnapshotLabel() {
        assertEquals("To Gym", destinationDisplayLabel(Direction.TO_WORK, "Gym"))
        assertEquals("To Client meeting", destinationDisplayLabel(Direction.TO_HOME, "Client meeting"))
        assertEquals("To Work", destinationDisplayLabel(Direction.TO_HOME, "Work"))
    }

    @Test
    fun destinationDisplayLabel_trimsSnapshotLabel() {
        assertEquals("To Gym", destinationDisplayLabel(Direction.TO_WORK, "  Gym  "))
    }

    @Test
    fun formatLeaveByLine_formatsMorningAndAfternoon() {
        assertEquals("Leave by 8:42 am", formatLeaveByLine(8 * 60 + 42))
        assertEquals("Leave by 1:05 pm", formatLeaveByLine(13 * 60 + 5))
    }

    @Test
    fun formatLeaveByLine_midnightAndNoonAreTwelve() {
        assertEquals("Leave by 12:00 am", formatLeaveByLine(0))
        assertEquals("Leave by 12:00 pm", formatLeaveByLine(12 * 60))
    }

    @Test
    fun formatLeaveByLine_padsSingleDigitMinutes() {
        assertEquals("Leave by 9:05 am", formatLeaveByLine(9 * 60 + 5))
    }

    @Test
    fun formatLeaveByLine_clampsOutOfRange() {
        assertEquals("Leave by 12:00 am", formatLeaveByLine(-1))
        assertEquals("Leave by 11:59 pm", formatLeaveByLine(24 * 60))
    }

    @Test
    fun isLeaveByPast_onlyAfterTheLeaveByMinute() {
        val leaveBy = 8 * 60 + 42
        assertFalse(isLeaveByPast(leaveBy, leaveBy - 1))
        assertFalse(isLeaveByPast(leaveBy, leaveBy))
        assertTrue(isLeaveByPast(leaveBy, leaveBy + 1))
    }

    @Test
    fun favouriteChipsToShow_capsWideRowAtTwo() {
        val favourites = listOf(
            favourite("Gym"),
            favourite("School"),
            favourite("Airport"),
            favourite("Clinic"),
        )
        val shown = favouriteChipsToShow(favourites, WIDE_MAX_FAVOURITE_CHIPS)
        assertEquals(listOf("Gym", "School"), shown.map { it.label })
    }

    @Test
    fun favouriteChipsToShow_preservesOrderAndAllowsAllOnLarge() {
        val favourites = listOf(favourite("Gym"), favourite("School"), favourite("Airport"))
        assertEquals(favourites, favouriteChipsToShow(favourites, favourites.size))
        assertEquals(emptyList<Favourite>(), favouriteChipsToShow(favourites, 0))
    }

    private fun favourite(label: String): Favourite {
        return Favourite(label = label, place = Place(address = label, lat = 0.0, lng = 0.0))
    }
}
