package com.crpakala.commutewidget.schedule

import com.crpakala.commutewidget.data.SnapshotMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldScheduleCalendarTick] is the shared schedule/cancel decision used both right after a
 * calendar refresh ([com.crpakala.commutewidget.engine.CommuteRefresher]) and right before a tick
 * fires ([CalendarTickWorker]). Only a located calendar event with the tick enabled schedules a
 * future tick - COMMUTE mode, an unlocated event, and a disabled tick all cancel instead, keeping
 * the chain fully event-driven with zero idle wakeups.
 */
class CalendarTickWorkerTest {
    @Test
    fun locatedCalendarEventWithTickEnabled_schedules() {
        assertTrue(shouldScheduleCalendarTick(SnapshotMode.CALENDAR_EVENT, calendarTickEnabled = true))
    }

    @Test
    fun locatedCalendarEventWithTickDisabled_doesNotSchedule() {
        assertFalse(shouldScheduleCalendarTick(SnapshotMode.CALENDAR_EVENT, calendarTickEnabled = false))
    }

    @Test
    fun commuteMode_neverSchedulesRegardlessOfTickSetting() {
        assertFalse(shouldScheduleCalendarTick(SnapshotMode.COMMUTE, calendarTickEnabled = true))
        assertFalse(shouldScheduleCalendarTick(SnapshotMode.COMMUTE, calendarTickEnabled = false))
    }

    @Test
    fun calendarEmpty_neverSchedulesRegardlessOfTickSetting() {
        assertFalse(shouldScheduleCalendarTick(SnapshotMode.CALENDAR_EMPTY, calendarTickEnabled = true))
        assertFalse(shouldScheduleCalendarTick(SnapshotMode.CALENDAR_EMPTY, calendarTickEnabled = false))
    }
}
