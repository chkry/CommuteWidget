package com.crpakala.commutewidget.schedule

import com.crpakala.commutewidget.data.HealthDayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldScheduleWalkNotification] (schedule-time) and [shouldPostWalkNotification] (fire-time,
 * inside the mutex-guarded post-then-mark helper) are the two pure decision points guarding
 * [HealthWalkNotifyWorker]'s at-most-once-per-day notification.
 */
class HealthWalkNotifyWorkerTest {
    @Test
    fun startStillAhead_schedules() {
        assertTrue(shouldScheduleWalkNotification(startEpochMillis = 10_000L, nowEpochMillis = 5_000L))
    }

    @Test
    fun startAlreadyPast_doesNotSchedule() {
        assertFalse(shouldScheduleWalkNotification(startEpochMillis = 5_000L, nowEpochMillis = 10_000L))
    }

    @Test
    fun startExactlyNow_doesNotSchedule() {
        assertFalse(shouldScheduleWalkNotification(startEpochMillis = 10_000L, nowEpochMillis = 10_000L))
    }

    @Test
    fun freshDayStateNotNotifiedNotDismissed_posts() {
        val dayState = HealthDayState(date = "2026-08-31")
        assertTrue(shouldPostWalkNotification(dayState, today = "2026-08-31"))
    }

    @Test
    fun alreadyNotifiedToday_doesNotPostAgain() {
        val dayState = HealthDayState(date = "2026-08-31", walkNotified = true)
        assertFalse(shouldPostWalkNotification(dayState, today = "2026-08-31"))
    }

    @Test
    fun walkDismissed_doesNotPost() {
        val dayState = HealthDayState(date = "2026-08-31", walkDismissed = true)
        assertFalse(shouldPostWalkNotification(dayState, today = "2026-08-31"))
    }

    @Test
    fun dayStateForDifferentDate_doesNotPost() {
        val dayState = HealthDayState(date = "2026-08-30")
        assertFalse(shouldPostWalkNotification(dayState, today = "2026-08-31"))
    }

    @Test
    fun nullDayState_doesNotPost() {
        assertFalse(shouldPostWalkNotification(null, today = "2026-08-31"))
    }
}
