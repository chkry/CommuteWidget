package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * One airframe from AirLabs `fleets`, cached under its uppercase registration. An airframe's build
 * facts do not change, so the map is kept forever and a registration is queried exactly once for
 * the life of the install. A record whose detail fields all came back null is cached too: on the
 * free plan most of them do, and re-asking would spend a query on the same nulls every refresh.
 */
@Serializable
data class AircraftRecord(
    val reg: String,
    val model: String? = null,
    val manufacturer: String? = null,
    val typeIcao: String? = null,
    val typeIata: String? = null,
    val engineType: String? = null,
    val engineCount: Int? = null,
    val builtYear: Int? = null,
    val ageYears: Int? = null,
    val serialNumber: String? = null,
    val fetchedAtMillis: Long,
)

private val aircraftFleetMapSerializer =
    MapSerializer(String.serializer(), AircraftRecord.serializer())

fun encodeAircraftFleet(value: Map<String, AircraftRecord>): String =
    commuteJson.encodeToString(aircraftFleetMapSerializer, value)

fun decodeAircraftFleet(json: String?): Map<String, AircraftRecord> {
    if (json.isNullOrBlank()) {
        return emptyMap()
    }
    return runCatching {
        commuteJson.decodeFromString(aircraftFleetMapSerializer, json)
    }.getOrElse { emptyMap() }
}
