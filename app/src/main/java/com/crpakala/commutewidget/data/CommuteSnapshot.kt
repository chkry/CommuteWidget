package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

/**
 * Custom pill reminders: one eligible occurrence of a user-defined reminder pill, already
 * resolved and ordered at computation time (see
 * [com.crpakala.commutewidget.engine.health.computeVisibleCustomPillOccurrences]). The widget
 * re-filters against the live taken set and applies the max-3 display cap plus "+N" overflow at
 * render time - see `resolveCustomPillRowContent` in `HealthWidgetUi.kt`. [active] is `true` for
 * the ACTIVE state and `false` for the dimmed CARRY_OVER state.
 */
@Serializable
data class CustomPillOccurrence(
    val pillId: String,
    val label: String,
    val slotMinuteOfDay: Int,
    val active: Boolean,
)

/**
 * One upcoming calendar event for the wind-down card's "Next up" section: the next events from
 * tomorrow's midnight onward, selected with the same rules as the today lookups.
 */
@Serializable
data class UpcomingEvent(
    val title: String,
    val startEpochMillis: Long,
)

@Serializable
data class CommuteSnapshot(
    val direction: Direction,
    val durationSeconds: Long,
    val durationNoTrafficSeconds: Long,
    val distanceMeters: Long,
    val mapImagePath: String?,
    val fetchedAtEpochMillis: Long,
    val lastFetchFailed: Boolean,
    val lastErrorMessage: String?,
    val destinationLabel: String? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val leaveByMinuteOfDay: Int? = null,
    /**
     * Widget display mode for the v3 window model. [SnapshotMode.COMMUTE] is the default for
     * slot-based commutes inside a To Work / To Home window.
     */
    val mode: SnapshotMode = SnapshotMode.COMMUTE,
    /**
     * Start time of the calendar event (epoch millis). Set for [SnapshotMode.CALENDAR_EVENT]
     * (located event with route). For [SnapshotMode.CALENDAR_EMPTY], may be set for an unlocated
     * event when [destinationLat] and [destinationLng] are null.
     */
    val eventStartEpochMillis: Long? = null,
    /** Decode-only legacy field, never populated since 2026-09-09. See the AGENTS.md invariant on upcomingEvents. */
    val nextWindowLabel: String? = null,
    /** Decode-only legacy field, never populated since 2026-09-09. See the AGENTS.md invariant on upcomingEvents. */
    val nextWindowStartMinuteOfDay: Int? = null,
    /**
     * v5 FIX-9: mirrors [com.crpakala.commutewidget.calendar.TodayEvent.preferredOverEarlierEvent]
     * for the event this [SnapshotMode.CALENDAR_EVENT] snapshot routed. Additive/defaulted so a
     * pre-FIX-9 stored snapshot JSON decodes as `false` rather than failing.
     */
    val routedOverEarlier: Boolean = false,
    /** Decode-only legacy field, never populated since 2026-09-09. See the AGENTS.md invariant on upcomingEvents. */
    val tomorrowEventTitle: String? = null,
    /** Decode-only legacy field, never populated since 2026-09-09. See the AGENTS.md invariant on upcomingEvents. */
    val tomorrowEventStartEpochMillis: Long? = null,
    /**
     * Count of events remaining today. Populated only on [SnapshotMode.COMMUTE] snapshots, for
     * the morning-brief line.
     */
    val todayEventCount: Int? = null,
    /**
     * Earliest start time among [todayEventCount]'s events, in epoch millis. Populated only on
     * [SnapshotMode.COMMUTE] snapshots, for the morning-brief line.
     */
    val todayFirstEventStartEpochMillis: Long? = null,
    val healthNudges: List<HealthNudge> = emptyList(),
    val sleepEstimateMinutes: Int? = null,
    val shortSleepDay: Boolean = false,
    /**
     * Custom pill reminders: the FULL ordered eligible occurrence list (at most one per pill, so
     * at most 6 entries), ACTIVE first then CARRY_OVER, both slot-minute ascending. Deliberately
     * uncapped - the renderer filters out freshly tapped occurrences and only then applies the
     * max-3 display cap and derives the "+N" overflow, so a tap can promote a hidden occurrence
     * instead of stranding a stale overflow count.
     */
    val customPillOccurrences: List<CustomPillOccurrence> = emptyList(),
    /**
     * The next calendar events from tomorrow onward (at most two, within seven days) for the
     * wind-down card. Populated only on out-of-window [SnapshotMode.CALENDAR_EMPTY] snapshots
     * with no event remaining today. Additive with a default so older stored JSON still decodes.
     */
    val upcomingEvents: List<UpcomingEvent> = emptyList(),
    /**
     * Airport-mode routing and flight-status data for [SnapshotMode.AIRPORT] snapshots. Additive
     * with a null default so a pre-airport-mode stored snapshot JSON still decodes.
     */
    val airport: AirportSnapshot? = null,
    /**
     * The nearest upcoming flight and its last-fetched status, for the calendar card's flight row
     * before airport mode opens. Populated only on the calendar snapshots
     * ([SnapshotMode.CALENDAR_EMPTY] and [SnapshotMode.CALENDAR_EVENT]). Additive with a null
     * default so a pre-preview stored snapshot JSON still decodes.
     */
    val flightPreview: FlightPreview? = null,
)
