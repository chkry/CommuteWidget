package com.crpakala.commutewidget.data

import kotlinx.serialization.json.Json

internal val commuteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun parseTravelMode(stored: String?, default: TravelMode = TravelMode.DRIVE): TravelMode {
    if (stored.isNullOrBlank()) {
        return default
    }
    return enumValues<TravelMode>().firstOrNull { it.name == stored } ?: default
}

fun parseDirection(stored: String?, default: Direction = Direction.TO_WORK): Direction {
    if (stored.isNullOrBlank()) {
        return default
    }
    return enumValues<Direction>().firstOrNull { it.name == stored } ?: default
}

fun encodePlace(place: Place): String = commuteJson.encodeToString(Place.serializer(), place)

fun decodePlace(json: String?): Place? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(Place.serializer(), json)
    }.getOrNull()
}

fun encodeCommuteSnapshot(snapshot: CommuteSnapshot): String =
    commuteJson.encodeToString(CommuteSnapshot.serializer(), snapshot)

fun decodeCommuteSnapshot(json: String?): CommuteSnapshot? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(CommuteSnapshot.serializer(), json)
    }.getOrNull()
}
