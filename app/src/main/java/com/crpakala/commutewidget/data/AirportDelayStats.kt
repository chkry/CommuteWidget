package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * How badly one airport is departing right now, aggregated on device from AirLabs `delays` and
 * cached under the uppercase IATA code. Unlike [AirportLocation] this is a fact about the hour, not
 * about the airport, so it carries its own stamp and is re-fetched once it passes
 * [AIRPORT_DELAY_STATS_MAX_AGE_MILLIS].
 */
@Serializable
data class AirportDelayStats(
    val iata: String,
    val delayedCount: Int,
    val averageDelayMinutes: Int,
    val maxDelayMinutes: Int,
    val fetchedAtMillis: Long,
)

/** Half an hour: the delay board moves, but not fast enough to be worth a query per refresh. */
const val AIRPORT_DELAY_STATS_MAX_AGE_MILLIS = 30 * 60_000L

private val airportDelayStatsMapSerializer =
    MapSerializer(String.serializer(), AirportDelayStats.serializer())

fun encodeAirportDelayStats(value: Map<String, AirportDelayStats>): String =
    commuteJson.encodeToString(airportDelayStatsMapSerializer, value)

fun decodeAirportDelayStats(json: String?): Map<String, AirportDelayStats> {
    if (json.isNullOrBlank()) {
        return emptyMap()
    }
    return runCatching {
        commuteJson.decodeFromString(airportDelayStatsMapSerializer, json)
    }.getOrElse { emptyMap() }
}
