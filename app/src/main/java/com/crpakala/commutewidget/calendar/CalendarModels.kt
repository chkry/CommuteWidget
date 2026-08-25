package com.crpakala.commutewidget.calendar

data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
)

data class UpcomingEvent(
    val title: String,
    val location: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val calendarId: Long,
)

/**
 * A calendar event remaining today, for the v3 calendar-mode widget card. Unlike [UpcomingEvent],
 * [location] is nullable: v3 calendar mode surfaces events with no location too (title/time only),
 * it just skips routing for them.
 */
data class TodayEvent(
    val title: String,
    val location: String?,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)
