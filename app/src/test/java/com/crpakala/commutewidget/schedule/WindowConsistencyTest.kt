package com.crpakala.commutewidget.schedule

import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.engine.WidgetMode
import com.crpakala.commutewidget.engine.nextWindow
import com.crpakala.commutewidget.engine.resolveWidgetMode
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adversarial cross-check for the v3 window model, updated for v5's `commuteDays` rename:
 * [resolveWidgetMode]/[nextWindow] (engine) and [nextWindowBoundary] (schedule) all independently
 * encode "is `[start, end)` a valid, currently-active window on this ISO day", and a
 * schedule/engine drift there would be a real bug (e.g. the widget rendering COMMUTE while the
 * boundary chain thinks the window already ended). These tests pin the [start, end) /
 * invalid-window-is-absent convention shared by all three.
 *
 * v5 removes the third participant this suite used to cross-check - the 10-minute
 * `isWithinSlotFetchWindow` grace period (formerly `SlotFetchWorker`), deleted along with the
 * history subsystem it fed. Those grace-period-specific assertions are dropped rather than
 * ported: there is no successor with a comparable "fires a few minutes either side of the
 * boundary" convention to pin.
 */
class WindowConsistencyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val morningStart = 420
    private val morningEnd = 600
    private val eveningStart = 1020
    private val eveningEnd = 1200

    @Test
    fun windowEndIsExclusiveInBothResolveWidgetModeAndNextWindowBoundary() {
        // resolveWidgetMode treats the end minute itself as already outside the window...
        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = morningEnd,
            commuteDays = weekdays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(WidgetMode.Calendar, mode)

        // ...and nextWindowBoundary agrees: from one minute before the end, the next boundary is
        // exactly the end minute, not some later instant - i.e. the boundary chain will fire
        // AUTO exactly when resolveWidgetMode flips to Calendar, not after.
        val justBeforeEnd = ZonedDateTime.of(2026, 8, 24, (morningEnd - 1) / 60, (morningEnd - 1) % 60, 0, 0, zone)
        val boundary = nextWindowBoundary(
            now = justBeforeEnd,
            commuteDays = weekdays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(morningEnd, boundary!!.hour * 60 + boundary.minute)
    }

    @Test
    fun invalidWindowIsTreatedAsAbsentByAllThreeFunctions() {
        val invalidStart = 420
        val invalidEnd = 420 // start == end: invalid per the shared convention

        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = 420,
            commuteDays = weekdays,
            morningStart = invalidStart,
            morningEnd = invalidEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(WidgetMode.Calendar, mode)

        val boundary = nextWindowBoundary(
            now = ZonedDateTime.of(2026, 8, 24, 0, 0, 0, 0, zone),
            commuteDays = weekdays,
            morningStart = invalidStart,
            morningEnd = invalidEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        // Only the (valid) evening window's two boundaries remain.
        assertEquals(eveningStart, boundary!!.hour * 60 + boundary.minute)

        val next = nextWindow(
            dayOfWeekIso = 1,
            minuteOfDay = 420,
            commuteDays = weekdays,
            morningStart = invalidStart,
            morningEnd = invalidEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(eveningStart, next!!.startMinuteOfDay)
    }

    @Test
    fun dayNotInCommuteDays_resolveWidgetModeAndNextWindowBoundaryBothSkipIt() {
        val days = setOf(1, 2, 3, 4, 5) // Saturday (6) not enabled

        assertEquals(
            WidgetMode.Calendar,
            resolveWidgetMode(
                dayOfWeekIso = 6,
                minuteOfDay = morningStart + 30,
                commuteDays = days,
                morningStart = morningStart,
                morningEnd = morningEnd,
                eveningStart = eveningStart,
                eveningEnd = eveningEnd,
            ),
        )

        // Saturday 2026-08-22, inside what would be the morning window on an enabled day.
        val saturdayInsideWindow = ZonedDateTime.of(2026, 8, 22, morningStart / 60, morningStart % 60, 0, 0, zone)
        val boundary = nextWindowBoundary(
            now = saturdayInsideWindow,
            commuteDays = days,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        // Skips Saturday's own (disabled) boundaries entirely and jumps to Monday morning start.
        assertEquals(morningStart, boundary!!.hour * 60 + boundary.minute)
        assertEquals(DayOfWeek.MONDAY, boundary.dayOfWeek)
    }

    @Test
    fun insideWindow_resolveWidgetModeAndNextWindowBoundaryAgreeItIsActive() {
        val minuteOfDay = morningStart + 30

        assertEquals(
            WidgetMode.Commute(Direction.TO_WORK),
            resolveWidgetMode(
                dayOfWeekIso = 1,
                minuteOfDay = minuteOfDay,
                commuteDays = weekdays,
                morningStart = morningStart,
                morningEnd = morningEnd,
                eveningStart = eveningStart,
                eveningEnd = eveningEnd,
            ),
        )

        val boundary = nextWindowBoundary(
            now = ZonedDateTime.of(2026, 8, 24, minuteOfDay / 60, minuteOfDay % 60, 0, 0, zone),
            commuteDays = weekdays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(morningEnd, boundary!!.hour * 60 + boundary.minute)
    }
}
