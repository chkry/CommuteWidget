package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

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
    /**
     * Label of the next commute window (for example "To Work") when [mode] is
     * [SnapshotMode.CALENDAR_EMPTY] and no calendar event is available.
     */
    val nextWindowLabel: String? = null,
    /**
     * Start minute-of-day of the next commute window when [mode] is [SnapshotMode.CALENDAR_EMPTY]
     * and no calendar event is available.
     */
    val nextWindowStartMinuteOfDay: Int? = null,
)
