package com.crpakala.commutewidget.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Lifecycle of the tap-to-ride commute inside one window on one local date. */
@Serializable(with = RidePhaseSerializer::class)
enum class RidePhase {
    /** Ride pill is offered, no route has been requested. */
    OFFERED,
    /** Ride tapped, commute route and map are shown. */
    RIDING,
    /** An event takeover pre-empted the ride, the pill is offered again as a resumed ride. */
    INTERRUPTED,
    /** Reached tapped, the ride is consumed for this window today. */
    REACHED,
}

fun parseRidePhase(stored: String?, default: RidePhase = RidePhase.OFFERED): RidePhase {
    if (stored.isNullOrBlank()) {
        return default
    }
    return enumValues<RidePhase>().firstOrNull { it.name == stored } ?: default
}

object RidePhaseSerializer : KSerializer<RidePhase> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RidePhase", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RidePhase) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): RidePhase {
        return parseRidePhase(decoder.decodeString())
    }
}

/** State of the tap-to-ride commute for one window on one local date. */
@Serializable
data class RideState(
    /** ISO local date string, e.g. "2026-09-09", matching BestDeparture.localDate convention. */
    val localDate: String,
    val direction: Direction,
    val phase: RidePhase,
    /** True when the ride resumed after an interruption and routes from the device location. */
    val fromCurrentLocation: Boolean = false,
    /** True when the last Ride tap failed to fetch a route, so the pill renders in the late color. */
    val lastRideFailed: Boolean = false,
    /** True once this ride has stored a routed commute snapshot, so a later failure is a same-target failure. */
    val routed: Boolean = false,
)

fun encodeRideState(value: RideState): String =
    commuteJson.encodeToString(RideState.serializer(), value)

fun decodeRideState(json: String?): RideState? {
    if (json.isNullOrBlank()) {
        return null
    }
    return runCatching {
        commuteJson.decodeFromString(RideState.serializer(), json)
    }.getOrNull()
}

/** True when persisting [updated] over [current] actually changes the stored ride state, so a caller can decide whether a follow-up refresh may bypass the cooldown. */
internal fun rideStateWriteChanged(current: RideState?, updated: RideState?, hadStoredValue: Boolean): Boolean {
    return current != updated || (updated == null && hadStoredValue)
}
