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
