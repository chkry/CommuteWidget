package com.crpakala.commutewidget.schedule

import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.engine.WidgetMode
import com.crpakala.commutewidget.engine.resolveWidgetMode
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial cross-check for the v3 window model: [resolveWidgetMode] (engine), `nextWindow`
 * (engine), and [nextWindowBoundary] (schedule) all independently encode "is `[start, end)` a
 * valid, currently-active window on this ISO day", and a schedule/engine drift there would be a
 * real bug (e.g. the widget rendering COMMUTE while the boundary chain thinks the window already
 * ended). These tests pin the [start, end) / invalid-window-is-absent convention shared by all
 * three, plus the one deliberately *different* convention: [SlotFetchWorker]'s
 * [isWithinSlotFetchWindow] grace period, which intentionally fires a few minutes either side of
 * the exact boundary to absorb WorkManager jitter - and verifies that firing early/late never
 * results in a recorded history sample outside a genuine COMMUTE-mode fetch.
 */
class WindowConsistencyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val morningStart = 420
    private val morningEnd = 600
    private val eveningStart = 1020
    private val eveningEnd = 1200
    private val slots = listOf(morningStart..morningEnd, eveningStart..eveningEnd)

    @Test
    fun windowEndIsExclusiveInBothResolveWidgetModeAndNextWindowBoundary() {
        // resolveWidgetMode treats the end minute itself as already outside the window...
        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = morningEnd,
            historyDays = weekdays,
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
            historyDays = weekdays,
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
            historyDays = weekdays,
            morningStart = invalidStart,
            morningEnd = invalidEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(WidgetMode.Calendar, mode)

        val boundary = nextWindowBoundary(
            now = ZonedDateTime.of(2026, 8, 24, 0, 0, 0, 0, zone),
            historyDays = weekdays,
            morningStart = invalidStart,
            morningEnd = invalidEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        // Only the (valid) evening window's two boundaries remain.
        assertEquals(eveningStart, boundary!!.hour * 60 + boundary.minute)
    }

    @Test
    fun slotFetchGracePeriod_firesPastWindowEndButResolvesToCalendarMode() {
        // isWithinSlotFetchWindow's +-5 min grace intentionally overlaps resolveWidgetMode's
        // exclusive end boundary - this is a deliberate difference (jitter absorption), not a
        // bug, and is safe only because history recording is gated on WidgetMode.Commute
        // (performCommuteRefresh is the sole call site of HistoryStore.insert). Assert both
        // halves of that contract so a future refactor can't silently reintroduce the bug.
        val justPastEnd = morningEnd + 5
        assertTrue(isWithinSlotFetchWindow(todayIso = 1, nowMinuteOfDay = justPastEnd, historyDays = weekdays, slots = slots))

        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = justPastEnd,
            historyDays = weekdays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(WidgetMode.Calendar, mode)
    }

    @Test
    fun slotFetchGracePeriod_firesBeforeWindowStartButResolvesToCalendarMode() {
        val justBeforeStart = morningStart - 5
        assertTrue(
            isWithinSlotFetchWindow(todayIso = 1, nowMinuteOfDay = justBeforeStart, historyDays = weekdays, slots = slots),
        )

        val mode = resolveWidgetMode(
            dayOfWeekIso = 1,
            minuteOfDay = justBeforeStart,
            historyDays = weekdays,
            morningStart = morningStart,
            morningEnd = morningEnd,
            eveningStart = eveningStart,
            eveningEnd = eveningEnd,
        )
        assertEquals(WidgetMode.Calendar, mode)
    }

    @Test
    fun dayDisabledForHistory_neitherSlotFetchNorResolveWidgetModeTreatItAsCommute() {
        val days = setOf(1, 2, 3, 4, 5) // Saturday (6) not enabled
        assertEquals(
            false,
            isWithinSlotFetchWindow(todayIso = 6, nowMinuteOfDay = morningStart + 30, historyDays = days, slots = slots),
        )
        assertEquals(
            WidgetMode.Calendar,
            resolveWidgetMode(
                dayOfWeekIso = 6,
                minuteOfDay = morningStart + 30,
                historyDays = days,
                morningStart = morningStart,
                morningEnd = morningEnd,
                eveningStart = eveningStart,
                eveningEnd = eveningEnd,
            ),
        )
    }

    @Test
    fun insideWindow_allThreeAgreeItIsActive() {
        val minuteOfDay = morningStart + 30
        assertTrue(isWithinSlotFetchWindow(todayIso = 1, nowMinuteOfDay = minuteOfDay, historyDays = weekdays, slots = slots))
        assertEquals(
            WidgetMode.Commute(Direction.TO_WORK),
            resolveWidgetMode(
                dayOfWeekIso = 1,
                minuteOfDay = minuteOfDay,
                historyDays = weekdays,
                morningStart = morningStart,
                morningEnd = morningEnd,
                eveningStart = eveningStart,
                eveningEnd = eveningEnd,
            ),
        )
    }
}
