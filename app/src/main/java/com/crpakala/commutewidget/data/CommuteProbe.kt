package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

/**
 * The once-per-window commute probe: a single Routes call with no map, taken on the first refresh
 * inside a commute window, so the leave-by pill and alarm exist before the owner taps Ride.
 */
@Serializable
data class CommuteProbe(
    val localDate: String,
    val direction: Direction,
    val durationSeconds: Long,
    val leaveByMinuteOfDay: Int? = null,
    val probedAtEpochMillis: Long,
)

fun encodeCommuteProbe(value: CommuteProbe): String =
    commuteJson.encodeToString(CommuteProbe.serializer(), value)

fun decodeCommuteProbe(json: String?): CommuteProbe? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(CommuteProbe.serializer(), json)
    }.getOrNull()
}
