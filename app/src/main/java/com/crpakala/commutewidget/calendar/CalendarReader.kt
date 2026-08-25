package com.crpakala.commutewidget.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

class CalendarReader(private val context: Context) {
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun listCalendars(): List<DeviceCalendar> {
        if (!hasPermission()) {
            return emptyList()
        }

        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CALENDAR_PROJECTION,
                "${CalendarContract.Calendars.VISIBLE} = ?",
                arrayOf("1"),
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, " +
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                buildList {
                    val idColumn = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                    val displayNameColumn = cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    )
                    val accountNameColumn = cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.ACCOUNT_NAME,
                    )

                    while (cursor.moveToNext()) {
                        add(
                            DeviceCalendar(
                                id = cursor.getLong(idColumn),
                                displayName = cursor.getString(displayNameColumn).orEmpty(),
                                accountName = cursor.getString(accountNameColumn).orEmpty(),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun nextEventWithLocation(
        selectedCalendarIds: Set<Long>,
        nowEpochMillis: Long,
        lookaheadMinutes: Int,
    ): UpcomingEvent? {
        if (!hasPermission() || selectedCalendarIds.isEmpty()) {
            return null
        }

        val queryStartMillis = nowEpochMillis - GRACE_PERIOD_MILLIS
        val queryEndMillis = nowEpochMillis + lookaheadMinutes.toLong() * MILLIS_PER_MINUTE
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(queryStartMillis.toString())
            .appendPath(queryEndMillis.toString())
            .build()

        val rows = runCatching {
            context.contentResolver.query(
                instancesUri,
                INSTANCE_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    val calendarIdColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val locationColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                    val beginColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val allDayColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                    val statusColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
                    val attendeeStatusColumn = cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                    )

                    while (cursor.moveToNext()) {
                        add(
                            RawInstance(
                                calendarId = cursor.getLong(calendarIdColumn),
                                title = cursor.getString(titleColumn),
                                location = cursor.getString(locationColumn),
                                beginEpochMillis = cursor.getLong(beginColumn),
                                endEpochMillis = cursor.getLong(endColumn),
                                allDay = cursor.getInt(allDayColumn) != 0,
                                status = cursor.getInt(statusColumn),
                                selfAttendeeStatus = cursor.getInt(attendeeStatusColumn),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())

        return selectEvent(rows, selectedCalendarIds, nowEpochMillis)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val GRACE_PERIOD_MILLIS = 15 * MILLIS_PER_MINUTE

        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )

        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
    }
}

internal data class RawInstance(
    val calendarId: Long,
    val title: String?,
    val location: String?,
    val beginEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean,
    val status: Int,
    val selfAttendeeStatus: Int,
)

internal fun selectEvent(
    rows: List<RawInstance>,
    selectedCalendarIds: Set<Long>,
    nowEpochMillis: Long,
): UpcomingEvent? =
    rows.asSequence()
        .filter { it.calendarId in selectedCalendarIds }
        .filter { !it.location.isNullOrBlank() }
        .filter { !it.allDay }
        .filter { it.status != CalendarContract.Events.STATUS_CANCELED }
        .filter { it.selfAttendeeStatus != CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED }
        .filter { it.endEpochMillis > nowEpochMillis }
        .minWithOrNull(compareBy<RawInstance> { it.beginEpochMillis }.thenBy { it.endEpochMillis })
        ?.let { row ->
            UpcomingEvent(
                title = row.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Event",
                location = row.location!!.trim(),
                startEpochMillis = row.beginEpochMillis,
                endEpochMillis = row.endEpochMillis,
                calendarId = row.calendarId,
            )
        }
