package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.Direction

/**
 * v3 window-model resolution of what the widget should currently show. Computed fresh on every
 * refresh from the clock and the morning/evening window settings - there is no persisted mode
 * input, only [CommuteSnapshot.mode][com.crpakala.commutewidget.data.CommuteSnapshot] as output.
 */
sealed class WidgetMode {
    data class Commute(val direction: Direction) : WidgetMode()
    object Calendar : WidgetMode()
}

/**
 * Resolves the v3 window model. Inside the morning window on an enabled day is the To Work
 * commute; inside the evening window is the To Home commute; everything else - including a day
 * not present in [commuteDays] at all - is calendar mode. Overlapping windows resolve to the
 * morning commute (checked first). A window with `start >= end` is treated as absent, matching
 * the existing slot-tick invalid-range convention.
 */
internal fun resolveWidgetMode(
    dayOfWeekIso: Int,
    minuteOfDay: Int,
    commuteDays: Set<Int>,
    morningStart: Int,
    morningEnd: Int,
    eveningStart: Int,
    eveningEnd: Int,
): WidgetMode {
    if (dayOfWeekIso !in commuteDays) {
        return WidgetMode.Calendar
    }
    if (morningStart < morningEnd && minuteOfDay in morningStart until morningEnd) {
        return WidgetMode.Commute(Direction.TO_WORK)
    }
    if (eveningStart < eveningEnd && minuteOfDay in eveningStart until eveningEnd) {
        return WidgetMode.Commute(Direction.TO_HOME)
    }
    return WidgetMode.Calendar
}

/**
 * v5: destination resolution is purely this two-way [WidgetMode] split now that the favourite
 * override precedence is gone (see UX-AUDIT.md ruling d) - commute mode always uses its own
 * direction, calendar mode falls back to the next upcoming window's direction, or
 * [Direction.TO_WORK] when none remains today or on any enabled day.
 */
internal fun resolveDirectionForSnapshot(widgetMode: WidgetMode, nextWindowDirection: Direction?): Direction =
    when (widgetMode) {
        is WidgetMode.Commute -> widgetMode.direction
        WidgetMode.Calendar -> nextWindowDirection ?: Direction.TO_WORK
    }

/**
 * The next upcoming To Work / To Home window start, carrying enough information to populate a
 * quiet calendar-mode card ("Next: To Work at 7:00 am") when no calendar event remains today.
 */
internal data class NextWindow(
    val direction: Direction,
    val startMinuteOfDay: Int,
    /** Calendar days until the window's day: 0 = later today, 1 = tomorrow, up to 7. */
    val daysAhead: Int = 0,
) {
    val label: String get() = if (direction == Direction.TO_WORK) "To Work" else "To Home"
}

/**
 * Owner ruling (2026-08-31): the "Next up" card section may only advertise a window starting
 * later today or tomorrow. A window further out (e.g. Friday evening -> Monday morning, or a
 * non-commute day tomorrow) reads as an imminent commute on the card, which is misleading.
 */
internal fun NextWindow.withinCardHorizon(): Boolean = daysAhead <= 1

/**
 * Pure computation of the next To Work / To Home window start across [commuteDays], searching
 * forward from ([dayOfWeekIso], [minuteOfDay]) and wrapping the week (e.g. Friday evening ->
 * Monday morning). Invalid windows (`start >= end`) are excluded. Returns null when [commuteDays]
 * is empty or neither window is valid.
 */
internal fun nextWindow(
    dayOfWeekIso: Int,
    minuteOfDay: Int,
    commuteDays: Set<Int>,
    morningStart: Int,
    morningEnd: Int,
    eveningStart: Int,
    eveningEnd: Int,
): NextWindow? {
    if (commuteDays.isEmpty()) return null

    val windows = buildList {
        if (morningStart < morningEnd) add(NextWindow(Direction.TO_WORK, morningStart))
        if (eveningStart < eveningEnd) add(NextWindow(Direction.TO_HOME, eveningStart))
    }.sortedBy { it.startMinuteOfDay }
    if (windows.isEmpty()) return null

    if (dayOfWeekIso in commuteDays) {
        val todayCandidate = windows.firstOrNull { it.startMinuteOfDay > minuteOfDay }
        if (todayCandidate != null) return todayCandidate
    }

    for (offset in 1..7) {
        val candidateDayIso = ((dayOfWeekIso - 1 + offset) % 7) + 1
        if (candidateDayIso in commuteDays) {
            return windows.first().copy(daysAhead = offset)
        }
    }
    return null
}
