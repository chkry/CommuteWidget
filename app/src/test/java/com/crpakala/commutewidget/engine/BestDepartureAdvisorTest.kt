package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.BestDeparture
import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BestDepartureAdvisorTest {
    private val today = "2026-08-26"
    private val slotStart = 14 * 60
    private val slotEnd = 18 * 60

    @Test
    fun compute_onlyOncePerDay() {
        assertTrue(shouldComputeBestDeparture(true, null, today, slotStart, slotStart, slotEnd))
        assertTrue(shouldComputeBestDeparture(true, "2026-08-25", today, slotStart, slotStart, slotEnd))
        assertFalse(shouldComputeBestDeparture(true, today, today, slotStart, slotStart, slotEnd))
    }

    @Test
    fun compute_respectsLeadWindowAndSlotEnd() {
        assertFalse(shouldComputeBestDeparture(true, null, today, slotStart - 181, slotStart, slotEnd))
        assertTrue(shouldComputeBestDeparture(true, null, today, slotStart - 180, slotStart, slotEnd))
        assertTrue(shouldComputeBestDeparture(true, null, today, slotEnd - 1, slotStart, slotEnd))
        assertFalse(shouldComputeBestDeparture(true, null, today, slotEnd, slotStart, slotEnd))
    }

    @Test
    fun compute_requiresEnabledAndValidSlot() {
        assertFalse(shouldComputeBestDeparture(false, null, today, slotStart, slotStart, slotEnd))
        assertFalse(shouldComputeBestDeparture(true, null, today, slotStart, slotEnd, slotStart))
    }

    @Test
    fun sampleInstants_thirtyMinuteStepsEndsInclusive() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 8, 26)
        val morning = ZonedDateTime.of(2026, 8, 26, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val instants = departureSampleInstants(slotStart, slotEnd, morning, date, zone)
        assertEquals(9, instants.size)
        val first = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(instants.first()), zone)
        val last = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(instants.last()), zone)
        assertEquals(14 * 60, first.hour * 60 + first.minute)
        assertEquals(18 * 60, last.hour * 60 + last.minute)
    }

    @Test
    fun sampleInstants_dropPastTimes() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 8, 26)
        val midSlot = ZonedDateTime.of(2026, 8, 26, 16, 10, 0, 0, zone).toInstant().toEpochMilli()
        val instants = departureSampleInstants(slotStart, slotEnd, midSlot, date, zone)
        val first = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(instants.first()), zone)
        assertEquals(16 * 60 + 30, first.hour * 60 + first.minute)
        assertEquals(4, instants.size)
    }

    @Test
    fun sampleInstants_invalidSlotIsEmpty() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 8, 26)
        assertEquals(
            emptyList<Long>(),
            departureSampleInstants(slotEnd, slotStart, 0L, date, zone),
        )
    }

    @Test
    fun show_requiresSameDayAndSlotNotPassed() {
        val result = BestDeparture(today, Direction.TO_WORK, 15 * 60 + 30, 2280L)
        assertTrue(shouldShowBestDeparture(result, true, today, slotEnd, slotEnd, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(result, true, today, slotEnd + 1, slotEnd, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(result, true, "2026-08-27", slotStart, slotEnd, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(result, false, today, slotStart, slotEnd, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(null, true, today, slotStart, slotEnd, showingCalendarEvent = false))
    }

    @Test
    fun show_hiddenWhileCalendarEventDisplayed() {
        val result = BestDeparture(today, Direction.TO_WORK, 15 * 60 + 30, 2280L)
        assertFalse(shouldShowBestDeparture(result, true, today, slotStart, slotEnd, showingCalendarEvent = true))
    }
}
