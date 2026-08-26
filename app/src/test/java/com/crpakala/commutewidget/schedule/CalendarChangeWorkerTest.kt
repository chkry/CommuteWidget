package com.crpakala.commutewidget.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarChangeWorkerTest {
    @Test
    fun observe_requiresFeatureEnabledAndCalendarsSelected() {
        assertTrue(shouldObserveCalendar(calendarEnabled = true, hasSelectedCalendars = true))
        assertFalse(shouldObserveCalendar(calendarEnabled = true, hasSelectedCalendars = false))
        assertFalse(shouldObserveCalendar(calendarEnabled = false, hasSelectedCalendars = true))
        assertFalse(shouldObserveCalendar(calendarEnabled = false, hasSelectedCalendars = false))
    }
}
