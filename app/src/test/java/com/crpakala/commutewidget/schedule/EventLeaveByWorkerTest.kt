package com.crpakala.commutewidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision logic behind [EventLeaveByScheduler]/[EventLeaveByWorker]. The worker/scheduler
 * classes themselves touch WorkManager, NotificationManager, and DataStore, so - matching the
 * rest of this module's test suite (see [CommuteSchedulerTest]) - only the extracted pure
 * functions are exercised directly here.
 */
class EventLeaveByWorkerTest {
    @Test
    fun shouldScheduleEventLeaveBy_neverNotified_schedules() {
        assertTrue(shouldScheduleEventLeaveBy(eventKey = "1000|Client meeting", notifiedKey = null))
    }

    @Test
    fun shouldScheduleEventLeaveBy_differentEventKey_schedules() {
        assertTrue(shouldScheduleEventLeaveBy(eventKey = "1000|Client meeting", notifiedKey = "999|Other event"))
    }

    @Test
    fun shouldScheduleEventLeaveBy_sameEventKeyAlreadyNotified_doesNotSchedule() {
        assertFalse(shouldScheduleEventLeaveBy(eventKey = "1000|Client meeting", notifiedKey = "1000|Client meeting"))
    }

    @Test
    fun eventLeaveByDriveMinutes_roundsUp() {
        // 25 minutes 1 second rounds up to 26.
        assertEquals(26, eventLeaveByDriveMinutes(1_501L))
    }

    @Test
    fun eventLeaveByDriveMinutes_exactMinuteBoundaryDoesNotRoundUp() {
        assertEquals(25, eventLeaveByDriveMinutes(1_500L))
    }

    @Test
    fun eventLeaveByDriveMinutes_zeroDuration_isZero() {
        assertEquals(0, eventLeaveByDriveMinutes(0L))
    }

    @Test
    fun isEventLeaveByDue_strictlyPast_isDue() {
        assertTrue(isEventLeaveByDue(leaveByEpochMillis = 1_000L, nowEpochMillis = 1_500L))
    }

    @Test
    fun isEventLeaveByDue_exactlyNow_isDue() {
        assertTrue(isEventLeaveByDue(leaveByEpochMillis = 1_000L, nowEpochMillis = 1_000L))
    }

    @Test
    fun isEventLeaveByDue_future_isNotDue() {
        assertFalse(isEventLeaveByDue(leaveByEpochMillis = 2_000L, nowEpochMillis = 1_000L))
    }

    @Test
    fun minuteOfDayFor_convertsEpochMillisInZone() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val epochMillis = java.time.ZonedDateTime.of(2026, 8, 26, 14, 40, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(14 * 60 + 40, minuteOfDayFor(epochMillis, zone))
    }
}
