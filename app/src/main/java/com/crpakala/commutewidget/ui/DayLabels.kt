package com.crpakala.commutewidget.ui

/**
 * Two-letter weekday labels for [com.crpakala.commutewidget.data.AppSettings.commuteDays] and
 * [com.crpakala.commutewidget.data.CustomPill.days] (1..7, Monday=1, ISO day-of-week). Distinct
 * two-letter labels fix the pre-sprint-4 day chips' single-letter M/T/W/T/F/S/S duplicate-letter
 * ambiguity.
 */
internal fun dayLabel(day: Int): String = when (day) {
    1 -> "Mo"
    2 -> "Tu"
    3 -> "We"
    4 -> "Th"
    5 -> "Fr"
    6 -> "Sa"
    7 -> "Su"
    else -> "?"
}

internal val ALL_WEEK_DAYS: Set<Int> = (1..7).toSet()

/** Space-separated compact label, e.g. "Mo Tu We", with "Every day" / "No days" for the edge cases. */
internal fun compactDaysList(days: Set<Int>): String = when {
    days.isEmpty() -> "No days"
    days.containsAll(ALL_WEEK_DAYS) -> "Every day"
    else -> days.sorted().joinToString(" ") { dayLabel(it) }
}

/**
 * Range-compressed label for a single contiguous block of days, e.g. "Mo-Fr"; falls back to the
 * space-separated [compactDaysList] form for a non-contiguous selection.
 */
internal fun compactWeekdayRange(days: Set<Int>): String {
    if (days.isEmpty()) return "No days"
    if (days.containsAll(ALL_WEEK_DAYS)) return "Every day"
    val sorted = days.sorted()
    val isContiguous = sorted.size > 1 && sorted.zipWithNext().all { (a, b) -> b - a == 1 }
    return if (isContiguous) "${dayLabel(sorted.first())}-${dayLabel(sorted.last())}" else compactDaysList(days)
}
