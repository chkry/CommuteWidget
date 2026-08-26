package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

/**
 * The best-departure sampling result for one local date: the departure minute inside the
 * configured slot with the lowest Google-predicted drive time. Computed at most once per day
 * (see the engine's BestDepartureAdvisor); the widget shows it until the slot end passes.
 */
@Serializable
data class BestDeparture(
    val localDate: String,
    val direction: Direction,
    val bestMinuteOfDay: Int,
    val bestDurationSeconds: Long,
)

fun encodeBestDeparture(value: BestDeparture): String =
    commuteJson.encodeToString(BestDeparture.serializer(), value)

fun decodeBestDeparture(json: String?): BestDeparture? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(BestDeparture.serializer(), json)
    }.getOrNull()
}
