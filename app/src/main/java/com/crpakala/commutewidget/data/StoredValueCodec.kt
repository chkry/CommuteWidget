package com.crpakala.commutewidget.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

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

fun encodeFavourites(favourites: List<Favourite>): String =
    commuteJson.encodeToString(ListSerializer(Favourite.serializer()), favourites)

fun decodeFavourites(json: String?): List<Favourite> {
    if (json.isNullOrBlank()) {
        return emptyList()
    }
    return runCatching {
        commuteJson.decodeFromString(ListSerializer(Favourite.serializer()), json)
    }.getOrElse { emptyList() }
}

private val longListSerializer = ListSerializer(serializer<Long>())
private val intListSerializer = ListSerializer(serializer<Int>())
private val stringListSerializer = ListSerializer(serializer<String>())

fun encodeLongSet(values: Set<Long>): String =
    commuteJson.encodeToString(longListSerializer, values.sorted())

fun decodeLongSet(json: String?, default: Set<Long> = emptySet()): Set<Long> {
    if (json.isNullOrBlank()) {
        return default
    }
    return runCatching {
        commuteJson.decodeFromString(longListSerializer, json).toSet()
    }.getOrElse { default }
}

fun encodeIntSet(values: Set<Int>): String =
    commuteJson.encodeToString(intListSerializer, values.sorted())

fun decodeIntSet(json: String?, default: Set<Int> = emptySet()): Set<Int> {
    if (json.isNullOrBlank()) {
        return default
    }
    return runCatching {
        commuteJson.decodeFromString(intListSerializer, json).toSet()
    }.getOrElse { default }
}

fun encodeStringSet(values: Set<String>): String =
    commuteJson.encodeToString(stringListSerializer, values.sorted())

fun decodeStringSet(json: String?, default: Set<String> = emptySet()): Set<String> {
    if (json.isNullOrBlank()) {
        return default
    }
    return runCatching {
        commuteJson.decodeFromString(stringListSerializer, json).toSet()
    }.getOrElse { default }
}

fun encodeHealthDayState(state: HealthDayState): String =
    commuteJson.encodeToString(HealthDayState.serializer(), state)

fun decodeHealthDayState(json: String?): HealthDayState? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(HealthDayState.serializer(), json)
    }.getOrNull()
}

fun encodeHealthHistory(history: HealthHistory): String =
    commuteJson.encodeToString(HealthHistory.serializer(), history)

fun decodeHealthHistory(json: String?): HealthHistory? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(HealthHistory.serializer(), json)
    }.getOrNull()
}

fun eventIdentityKey(eventStartEpochMillis: Long, title: String): String =
    "$eventStartEpochMillis|${title.trim()}"
