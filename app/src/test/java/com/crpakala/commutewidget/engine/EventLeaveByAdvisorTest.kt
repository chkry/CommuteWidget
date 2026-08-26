package com.crpakala.commutewidget.engine

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v4 event leave-by advisor: [eventDepartureProbe]/[eventLeaveByEpochMillis]/
 * [eventLeaveByMinuteOfDay] are the pure computations behind
 * [CommuteRefresher]'s located-event calendar path (see that file's
 * `performCalendarRefresh` for the side-effecting integration this cannot exercise without
 * Android framework dependencies).
 */
class EventLeaveByAdvisorTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun eventDepartureProbe_strictlyMoreThanThreshold_returnsPredictedDepartureTime() {
        val eventStart = 1_000_000_000L
        val now = eventStart - 61 * 60_000L // 61 minutes away, threshold 60

        val probe = eventDepartureProbe(eventStart, now, thresholdMinutes = 60, bufferMinutes = 10)

        assertEquals(eventStart - 10 * 60_000L, probe)
    }

    @Test
    fun eventDepartureProbe_exactlyAtThreshold_isRealtime() {
        val eventStart = 1_000_000_000L
        val now = eventStart - 60 * 60_000L // exactly 60 minutes away

        val probe = eventDepartureProbe(eventStart, now, thresholdMinutes = 60, bufferMinutes = 10)

        assertNull(probe)
    }

    @Test
    fun eventDepartureProbe_withinThreshold_isRealtime() {
        val eventStart = 1_000_000_000L
        val now = eventStart - 30 * 60_000L // 30 minutes away, threshold 60

        val probe = eventDepartureProbe(eventStart, now, thresholdMinutes = 60, bufferMinutes = 10)

        assertNull(probe)
    }

    @Test
    fun eventDepartureProbe_eventAlreadyStarted_isRealtime() {
        val eventStart = 1_000_000_000L
        val now = eventStart + 5 * 60_000L // event started 5 minutes ago

        val probe = eventDepartureProbe(eventStart, now, thresholdMinutes = 60, bufferMinutes = 10)

        assertNull(probe)
    }

    @Test
    fun eventDepartureProbe_appliesBufferToDeparture() {
        val eventStart = 2_000_000_000L
        val now = eventStart - 120 * 60_000L

        val probe = eventDepartureProbe(eventStart, now, thresholdMinutes = 60, bufferMinutes = 15)

        assertEquals(eventStart - 15 * 60_000L, probe)
    }

    @Test
    fun eventLeaveByEpochMillis_subtractsBufferAndDuration() {
        val eventStart = 1_000_000_000L
        // 10 minute buffer + 25 minute (1500s) drive.
        val result = eventLeaveByEpochMillis(eventStart, bufferMinutes = 10, durationSeconds = 1_500L)

        assertEquals(eventStart - 10 * 60_000L - 1_500_000L, result)
    }

    @Test
    fun eventLeaveByEpochMillis_zeroBufferAndDuration_equalsEventStart() {
        val eventStart = 1_000_000_000L

        assertEquals(eventStart, eventLeaveByEpochMillis(eventStart, bufferMinutes = 0, durationSeconds = 0L))
    }

    @Test
    fun eventLeaveByMinuteOfDay_sameDayReturnsLocalMinuteOfDay() {
        // 2026-08-26 14:40:00 IST.
        val leaveBy = zonedEpochMillis(2026, 8, 26, 14, 40)

        val result = eventLeaveByMinuteOfDay(leaveBy, zone, LocalDate.of(2026, 8, 26))

        assertEquals(14 * 60 + 40, result)
    }

    @Test
    fun eventLeaveByMinuteOfDay_justAfterMidnightIsNotClamped() {
        val leaveBy = zonedEpochMillis(2026, 8, 26, 0, 1)

        val result = eventLeaveByMinuteOfDay(leaveBy, zone, LocalDate.of(2026, 8, 26))

        assertEquals(1, result)
    }

    @Test
    fun eventLeaveByMinuteOfDay_beforeTodayClampsToZero() {
        // Pathologically long drive pushes leave-by to the previous calendar day.
        val leaveBy = zonedEpochMillis(2026, 8, 25, 23, 30)

        val result = eventLeaveByMinuteOfDay(leaveBy, zone, LocalDate.of(2026, 8, 26))

        assertEquals(0, result)
    }

    @Test
    fun eventLeaveByMinuteOfDay_exactlyMidnightIsNotClamped() {
        val leaveBy = zonedEpochMillis(2026, 8, 26, 0, 0)

        val result = eventLeaveByMinuteOfDay(leaveBy, zone, LocalDate.of(2026, 8, 26))

        assertEquals(0, result)
    }

    private fun zonedEpochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
