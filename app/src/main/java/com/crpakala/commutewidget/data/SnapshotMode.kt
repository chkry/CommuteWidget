package com.crpakala.commutewidget.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = SnapshotModeSerializer::class)
enum class SnapshotMode {
    COMMUTE,
    CALENDAR_EVENT,
    CALENDAR_EMPTY,
}

fun parseSnapshotMode(stored: String?, default: SnapshotMode = SnapshotMode.COMMUTE): SnapshotMode {
    if (stored.isNullOrBlank()) {
        return default
    }
    return enumValues<SnapshotMode>().firstOrNull { it.name == stored } ?: default
}

object SnapshotModeSerializer : KSerializer<SnapshotMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SnapshotMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SnapshotMode) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): SnapshotMode {
        return parseSnapshotMode(decoder.decodeString())
    }
}
