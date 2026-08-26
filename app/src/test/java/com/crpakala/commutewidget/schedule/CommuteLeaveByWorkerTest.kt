package com.crpakala.commutewidget.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 FIX-16: [shouldScheduleCommuteLeaveByAlarm] decides whether
 * [com.crpakala.commutewidget.engine.CommuteRefresher] should arrange a future precise wake-up
 * for the commute leave-by notification, as opposed to relying solely on the in-refresh
 * immediate-fire path ([com.crpakala.commutewidget.engine.shouldFireLeaveByNotification]). Only a
 * still-ahead leave-by instant for a direction/date that has not already fired is worth an alarm -
 * a leave-by instant already in the past needs no future wake-up (the immediate-fire path already
 * covers "leave-by has arrived"), and a direction already notified today must never re-alarm.
 */
class CommuteLeaveByWorkerTest {
    @Test
    fun leaveByStillAheadAndNotYetNotified_schedules() {
        assertTrue(
            shouldScheduleCommuteLeaveByAlarm(
                alreadyNotifiedToday = false,
                leaveByEpochMillis = 10_000L,
                nowEpochMillis = 5_000L,
            ),
        )
    }

    @Test
    fun leaveByAlreadyPast_doesNotSchedule() {
        assertFalse(
            shouldScheduleCommuteLeaveByAlarm(
                alreadyNotifiedToday = false,
                leaveByEpochMillis = 5_000L,
                nowEpochMillis = 10_000L,
            ),
        )
    }

    @Test
    fun leaveByExactlyNow_doesNotSchedule() {
        assertFalse(
            shouldScheduleCommuteLeaveByAlarm(
                alreadyNotifiedToday = false,
                leaveByEpochMillis = 10_000L,
                nowEpochMillis = 10_000L,
            ),
        )
    }

    @Test
    fun alreadyNotifiedToday_neverSchedulesEvenWhenLeaveByIsAhead() {
        assertFalse(
            shouldScheduleCommuteLeaveByAlarm(
                alreadyNotifiedToday = true,
                leaveByEpochMillis = 10_000L,
                nowEpochMillis = 5_000L,
            ),
        )
    }
}
