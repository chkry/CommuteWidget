package com.crpakala.commutewidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DayLabelsTest {
    @Test
    fun dayLabelsAreDistinctTwoLetterCodes() {
        val labels = (1..7).map { dayLabel(it) }
        assertEquals(listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"), labels)
        assertEquals(7, labels.toSet().size)
    }

    @Test
    fun compactDaysListHandlesEdgeCases() {
        assertEquals("No days", compactDaysList(emptySet()))
        assertEquals("Every day", compactDaysList((1..7).toSet()))
        assertEquals("Mo We Fr", compactDaysList(setOf(1, 3, 5)))
        assertEquals("Mo Tu We Th Fr", compactDaysList(setOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun compactWeekdayRangeCompressesContiguousBlocks() {
        assertEquals("No days", compactWeekdayRange(emptySet()))
        assertEquals("Every day", compactWeekdayRange((1..7).toSet()))
        assertEquals("Mo-Fr", compactWeekdayRange(setOf(1, 2, 3, 4, 5)))
        assertEquals("Sa-Su", compactWeekdayRange(setOf(6, 7)))
        assertEquals("Mo", compactWeekdayRange(setOf(1)))
    }

    @Test
    fun compactWeekdayRangeFallsBackToListForNonContiguousDays() {
        assertEquals("Mo We Fr", compactWeekdayRange(setOf(1, 3, 5)))
    }
}
