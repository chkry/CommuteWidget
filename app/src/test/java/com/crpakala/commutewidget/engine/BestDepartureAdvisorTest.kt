package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.BestDeparture
import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BestDepartureAdvisorTest {
    private val today = "2026-08-26"
    private val morningStart = 7 * 60
    private val morningEnd = 10 * 60
    private val eveningStart = 17 * 60
    private val eveningEnd = 20 * 60

    private fun target(nowMinuteOfDay: Int): BestDepartureTarget? =
        currentBestDepartureTarget(nowMinuteOfDay, morningStart, morningEnd, eveningStart, eveningEnd)

    @Test
    fun target_morningUntilMorningEndThenEvening() {
        assertEquals(Direction.TO_WORK, target(0)!!.direction)
        assertEquals(Direction.TO_WORK, target(morningEnd - 1)!!.direction)
        assertEquals(Direction.TO_HOME, target(morningEnd)!!.direction)
        assertEquals(Direction.TO_HOME, target(eveningEnd - 1)!!.direction)
        assertNull(target(eveningEnd))
    }

    @Test
    fun target_skipsInvalidWindows() {
        val onlyEvening = currentBestDepartureTarget(8 * 60, 600, 420, eveningStart, eveningEnd)
        assertEquals(Direction.TO_HOME, onlyEvening!!.direction)
        assertNull(currentBestDepartureTarget(8 * 60, 600, 420, 1200, 1020))
    }

    @Test
    fun compute_oncePerWindowPerDay() {
        val morningTarget = target(morningStart)!!
        val morningResult = BestDeparture(today, Direction.TO_WORK, 8 * 60, 2280L)
        assertTrue(shouldComputeBestDeparture(true, true, null, today, morningTarget, morningStart))
        assertFalse(shouldComputeBestDeparture(true, true, morningResult, today, morningTarget, morningStart))
        // The evening window recomputes even though a same-day morning result exists.
        val eveningTarget = target(eveningStart)!!
        assertTrue(shouldComputeBestDeparture(true, true, morningResult, today, eveningTarget, eveningStart))
        // A stale (yesterday) result recomputes.
        val stale = BestDeparture("2026-08-25", Direction.TO_WORK, 8 * 60, 2280L)
        assertTrue(shouldComputeBestDeparture(true, true, stale, today, morningTarget, morningStart))
    }

    @Test
    fun compute_respectsLeadWindowAndEnd() {
        val morningTarget = target(morningStart)!!
        assertFalse(shouldComputeBestDeparture(true, true, null, today, morningTarget, morningStart - 181))
        assertTrue(shouldComputeBestDeparture(true, true, null, today, morningTarget, morningStart - 180))
        assertTrue(shouldComputeBestDeparture(true, true, null, today, morningTarget, morningEnd - 1))
        assertFalse(shouldComputeBestDeparture(true, true, null, today, morningTarget, morningEnd))
    }

    @Test
    fun compute_requiresEnabledAndCommuteDay() {
        val morningTarget = target(morningStart)!!
        assertFalse(shouldComputeBestDeparture(false, true, null, today, morningTarget, morningStart))
        assertFalse(shouldComputeBestDeparture(true, false, null, today, morningTarget, morningStart))
    }

    @Test
    fun sampleInstants_thirtyMinuteStepsEndsInclusive() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 8, 26)
        val beforeSlot = ZonedDateTime.of(2026, 8, 26, 4, 0, 0, 0, zone).toInstant().toEpochMilli()
        val instants = departureSampleInstants(morningStart, morningEnd, beforeSlot, date, zone)
        assertEquals(7, instants.size)
        val first = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(instants.first()), zone)
        val last = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(instants.last()), zone)
        assertEquals(morningStart, first.hour * 60 + first.minute)
        assertEquals(morningEnd, last.hour * 60 + last.minute)
    }

    @Test
    fun sampleInstants_dropPastTimes() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 8, 26)
        val midSlot = ZonedDateTime.of(2026, 8, 26, 8, 10, 0, 0, zone).toInstant().toEpochMilli()
        val instants = departureSampleInstants(morningStart, morningEnd, midSlot, date, zone)
        val first = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(instants.first()), zone)
        assertEquals(8 * 60 + 30, first.hour * 60 + first.minute)
        assertEquals(4, instants.size)
    }

    @Test
    fun sampleInstants_invalidSlotIsEmpty() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 8, 26)
        assertEquals(emptyList<Long>(), departureSampleInstants(morningEnd, morningStart, 0L, date, zone))
    }

    @Test
    fun show_requiresMatchingTargetDirectionAndDay() {
        val morningResult = BestDeparture(today, Direction.TO_WORK, 8 * 60, 2280L)
        val morningTarget = target(morningStart)!!
        val eveningTarget = target(eveningStart)!!
        assertTrue(shouldShowBestDeparture(morningResult, true, true, today, morningTarget, showingCalendarEvent = false))
        // The morning result must not render against the evening window.
        assertFalse(shouldShowBestDeparture(morningResult, true, true, today, eveningTarget, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(morningResult, true, true, "2026-08-27", morningTarget, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(morningResult, true, true, today, null, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(morningResult, false, true, today, morningTarget, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(morningResult, true, false, today, morningTarget, showingCalendarEvent = false))
        assertFalse(shouldShowBestDeparture(null, true, true, today, morningTarget, showingCalendarEvent = false))
    }

    @Test
    fun show_hiddenWhileCalendarEventDisplayed() {
        val morningResult = BestDeparture(today, Direction.TO_WORK, 8 * 60, 2280L)
        assertFalse(
            shouldShowBestDeparture(morningResult, true, true, today, target(morningStart), showingCalendarEvent = true),
        )
    }
}
