package com.crpakala.commutewidget.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import com.crpakala.commutewidget.data.UpcomingEvent as SnapshotUpcomingEvent
import java.time.Instant
import java.time.ZoneId

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

    /**
     * v3 calendar mode: the next event today, including unlocated events (unlike
     * [nextEventWithLocation], which the v2 tap-triggered calendar override used and which this
     * function does not replace - it stays available for existing callers). The window is
     * `[now - 15 min, max(end of local day, now + minLookaheadMinutes))`: rest-of-today
     * semantics, extended past local midnight by [minLookaheadMinutes] so a temporally imminent
     * event (a 12:30 am movie seen at 11:40 pm) still routes instead of being excluded purely
     * because the calendar date rolled over.
     */
    fun nextEventToday(
        selectedCalendarIds: Set<Long>,
        nowEpochMillis: Long,
        zoneId: ZoneId,
        minLookaheadMinutes: Int = 0,
    ): TodayEvent? {
        if (!hasPermission() || selectedCalendarIds.isEmpty()) {
            return null
        }

        val queryStartMillis = nowEpochMillis - TODAY_LOOKBACK_MILLIS
        val endOfDayMillis = calendarQueryEndEpochMillis(nowEpochMillis, zoneId, minLookaheadMinutes)
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(queryStartMillis.toString())
            .appendPath(endOfDayMillis.toString())
            .build()

        val rows = queryInstances(instancesUri)
        return selectTodayEvent(rows, selectedCalendarIds, nowEpochMillis)
    }

    /**
     * The wind-down card's "Next up" section: the next [limit] events from tomorrow's local
     * midnight onward, within [lookaheadDays] days. Permission and empty-selection guard as the
     * sibling functions.
     */
    fun upcomingEvents(
        selectedCalendarIds: Set<Long>,
        nowEpochMillis: Long,
        zone: ZoneId,
        limit: Int = UPCOMING_EVENT_LIMIT,
        lookaheadDays: Long = UPCOMING_LOOKAHEAD_DAYS,
    ): List<SnapshotUpcomingEvent> {
        if (!hasPermission() || selectedCalendarIds.isEmpty()) {
            return emptyList()
        }

        val queryRange = upcomingQueryRange(nowEpochMillis, zone, lookaheadDays)
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(queryRange.first.toString())
            .appendPath((queryRange.last + 1).toString())
            .build()

        return selectUpcomingEvents(queryInstances(instancesUri), selectedCalendarIds, limit, fromEpochMillis = queryRange.first)
    }

    fun todaySummary(
        selectedCalendarIds: Set<Long>,
        nowEpochMillis: Long,
        zone: ZoneId,
    ): TodaySummary? {
        if (!hasPermission() || selectedCalendarIds.isEmpty()) {
            return null
        }

        val endOfDayMillis = Instant.ofEpochMilli(nowEpochMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(nowEpochMillis.toString())
            .appendPath(endOfDayMillis.toString())
            .build()

        return selectTodaySummary(queryInstances(instancesUri), selectedCalendarIds, nowEpochMillis)
    }

    private fun queryInstances(instancesUri: Uri): List<RawInstance> {
        return runCatching {
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
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val GRACE_PERIOD_MILLIS = 15 * MILLIS_PER_MINUTE
        const val TODAY_LOOKBACK_MILLIS = 15 * MILLIS_PER_MINUTE

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

private const val LOCATION_PREFERENCE_WINDOW_MILLIS = 30 * 60_000L

/** Defaults for [CalendarReader.upcomingEvents]: at most two events, within the next seven days. */
internal const val UPCOMING_EVENT_LIMIT = 2
internal const val UPCOMING_LOOKAHEAD_DAYS = 7L

/**
 * Query window for [CalendarReader.upcomingEvents]: start is tomorrow's local midnight, end
 * (exclusive, as `last + 1`) is that same local date plus [lookaheadDays] at local midnight.
 * Computed from local dates rather than elapsed millis, so a daylight-saving transition inside the
 * window changes its wall-clock span but never its calendar-day length.
 */
internal fun upcomingQueryRange(nowEpochMillis: Long, zone: ZoneId, lookaheadDays: Long): LongRange {
    val start = Instant.ofEpochMilli(nowEpochMillis)
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    val end = Instant.ofEpochMilli(start)
        .atZone(zone)
        .toLocalDate()
        .plusDays(lookaheadDays)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    return start until end
}

/**
 * Junk-location markers office-email invites carry instead of a real address: virtual-meeting
 * URLs and platform names. Matched case-insensitively against the whole trimmed location text, so
 * a room-plus-link string like `"Conf Room 4B / https://teams.microsoft.com/..."` still rejects.
 * Deliberately conservative - a marker miss (a bare conference-room code, say) still attempts
 * routing once and lands on the plain card via the event failure fallback in
 * [com.crpakala.commutewidget.engine.failureSnapshot] after spending the calls (an accepted known
 * edge - see AGENTS.md).
 */
private val JUNK_LOCATION_MARKERS = listOf(
    "http://",
    "https://",
    "teams.microsoft",
    "zoom.us",
    "meet.google",
    "webex",
    "microsoft teams meeting",
)

/**
 * Outlook conference-room resource capacity suffix, e.g. `"PA HQ-1-FOREST (4)"` - the trailing
 * `(4)` is the room's seat count appended by the calendar-room directory, not an address
 * component, and the resource name itself is never geocodable (it fails with ZERO_RESULTS on
 * every attempt, which is what let a broken same-target failure shell persist forever before this
 * check existed - see [com.crpakala.commutewidget.engine.failureSnapshot]'s doc). Matches only
 * when the FINAL parenthesized group is a bare integer, anchored to the end of the trimmed
 * location text, so `"Plot 12 (Phase 2)"` (non-digit content inside the parens) and any address
 * without a trailing parenthesized number are unaffected.
 */
private val ROOM_CAPACITY_SUFFIX_REGEX = Regex("""\(\d+\)$""")

/**
 * True when [location] looks like a real, geocodable place rather than a virtual-meeting marker
 * (see [JUNK_LOCATION_MARKERS]) or an Outlook room-resource capacity suffix (see
 * [ROOM_CAPACITY_SUFFIX_REGEX]). Conservative by design: only literal known markers and the
 * bare-integer trailing-parentheses suffix reject, so a real street address, mall name, or bare
 * city name is never rejected. [location] is compared trimmed and lowercased; callers are expected
 * to pass an already non-blank string - blank/null locations are "no location" before this check
 * ever runs (see [RawInstance.routableLocation]).
 */
internal fun isRoutableEventLocation(location: String): Boolean {
    val normalized = location.trim().lowercase()
    if (JUNK_LOCATION_MARKERS.any { normalized.contains(it) }) return false
    return !ROOM_CAPACITY_SUFFIX_REGEX.containsMatchIn(normalized)
}

/**
 * The single normalization point for "does this row have a usable location": trimmed, non-blank
 * location text, or null when it is blank OR a junk virtual-meeting marker (see
 * [isRoutableEventLocation]). [selectTodayEvent] reads location exclusively through this - never
 * through [RawInstance.location] directly - so a junk location becomes "no location" before both
 * the located-over-unlocated preference and [TodayEvent.location] are built from it, and every
 * downstream consumer (event takeover, calendar-mode routing, the far-event gate, leave-by, the
 * event_near flip) inherits "unlocated" for free.
 */
private fun RawInstance.routableLocation(): String? {
    val trimmed = location?.trim()?.takeUnless { it.isEmpty() } ?: return null
    return trimmed.takeIf { isRoutableEventLocation(it) }
}

/**
 * Query end for [CalendarReader.nextEventToday]: end of the local day, extended to at least
 * `now + minLookaheadMinutes` so a temporally imminent after-midnight event still qualifies.
 */
internal fun calendarQueryEndEpochMillis(
    nowEpochMillis: Long,
    zoneId: ZoneId,
    minLookaheadMinutes: Int,
): Long {
    val endOfDay = Instant.ofEpochMilli(nowEpochMillis)
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
    return maxOf(endOfDay, nowEpochMillis + minLookaheadMinutes * 60_000L)
}

/**
 * Selects the v3 calendar-mode candidate from today's remaining [rows]. Unlike [selectEvent], an
 * unlocated event is eligible - only all-day, cancelled, declined, and already-finished instances
 * are excluded. Every read of a row's location goes through [RawInstance.routableLocation] - a
 * junk virtual-meeting location (see [isRoutableEventLocation]) counts as no location at all
 * here, so it can never win the location preference below and [TodayEvent.location] comes back
 * null for it. Among eligible rows, the earliest-starting *located* (routable) candidate is
 * preferred over the earliest-starting *unlocated* candidate when it starts within
 * [LOCATION_PREFERENCE_WINDOW_MILLIS] of it (a route we can draw is more actionable than a bare
 * reminder); otherwise the plain earliest-begin (then earliest-end) candidate wins, located or not.
 */
internal fun selectTodayEvent(
    rows: List<RawInstance>,
    selectedCalendarIds: Set<Long>,
    nowEpochMillis: Long,
): TodayEvent? {
    val eligible = rows.asSequence()
        .filter { it.calendarId in selectedCalendarIds }
        .filter { !it.allDay }
        .filter { it.status != CalendarContract.Events.STATUS_CANCELED }
        .filter { it.selfAttendeeStatus != CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED }
        .filter { it.endEpochMillis > nowEpochMillis }
        .toList()
    if (eligible.isEmpty()) return null

    val beginThenEnd = compareBy<RawInstance>({ it.beginEpochMillis }, { it.endEpochMillis })
    val earliestUnlocated = eligible.filter { it.routableLocation() == null }.minWithOrNull(beginThenEnd)
    val earliestLocated = eligible.filter { it.routableLocation() != null }.minWithOrNull(beginThenEnd)

    val chosen = when {
        earliestLocated == null -> earliestUnlocated
        earliestUnlocated == null -> earliestLocated
        earliestLocated.beginEpochMillis - earliestUnlocated.beginEpochMillis <= LOCATION_PREFERENCE_WINDOW_MILLIS ->
            earliestLocated
        else -> earliestUnlocated
    } ?: return null

    // FIX-9: only true when the located candidate was chosen *and* it actually started later
    // than the unlocated one - i.e. an actual reordering happened, not just "located happened to
    // be earliest anyway" (see locatedCandidateStartsBeforeUnlocated_locatedWins in the test).
    val preferredOverEarlierEvent = chosen == earliestLocated &&
        earliestUnlocated != null &&
        earliestLocated.beginEpochMillis > earliestUnlocated.beginEpochMillis

    return TodayEvent(
        title = chosen.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Event",
        location = chosen.routableLocation(),
        startEpochMillis = chosen.beginEpochMillis,
        endEpochMillis = chosen.endEpochMillis,
        preferredOverEarlierEvent = preferredOverEarlierEvent,
    )
}

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

internal fun selectUpcomingEvents(
    rows: List<RawInstance>,
    selectedCalendarIds: Set<Long>,
    limit: Int,
    fromEpochMillis: Long,
): List<SnapshotUpcomingEvent> =
    rows.asSequence()
        .filter { it.calendarId in selectedCalendarIds }
        .filter { !it.allDay }
        .filter { it.status != CalendarContract.Events.STATUS_CANCELED }
        .filter { it.selfAttendeeStatus != CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED }
        .filter { it.beginEpochMillis >= fromEpochMillis }
        .sortedWith(compareBy({ it.beginEpochMillis }, { it.endEpochMillis }))
        .take(limit)
        .map { row ->
            SnapshotUpcomingEvent(
                title = row.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Event",
                startEpochMillis = row.beginEpochMillis,
            )
        }
        .toList()

internal fun selectTodaySummary(
    rows: List<RawInstance>,
    selectedCalendarIds: Set<Long>,
    nowEpochMillis: Long,
): TodaySummary {
    val eligible = rows.asSequence()
        .filter { it.calendarId in selectedCalendarIds }
        .filter { !it.allDay }
        .filter { it.status != CalendarContract.Events.STATUS_CANCELED }
        .filter { it.selfAttendeeStatus != CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED }
        .filter { it.endEpochMillis > nowEpochMillis }
        .toList()
    return TodaySummary(
        remainingCount = eligible.size,
        firstStartEpochMillis = eligible.minWithOrNull(
            compareBy<RawInstance>({ it.beginEpochMillis }, { it.endEpochMillis }),
        )?.beginEpochMillis,
    )
}
