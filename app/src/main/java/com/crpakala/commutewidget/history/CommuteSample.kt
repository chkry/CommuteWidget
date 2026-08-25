package com.crpakala.commutewidget.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CommuteSample(
    val timestampEpochMillis: Long,
    val localDate: String,
    val minuteOfDay: Int,
    val dayOfWeekIso: Int,
    val direction: String,
    val durationSeconds: Long,
    val staticDurationSeconds: Long,
    val distanceMeters: Long,
    val source: String,
) {
    init {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) {
            "minuteOfDay must be between 0 and ${MINUTES_PER_DAY - 1}"
        }
        require(dayOfWeekIso in MONDAY_ISO..SUNDAY_ISO) {
            "dayOfWeekIso must be between $MONDAY_ISO and $SUNDAY_ISO"
        }
        requireValidDirection(direction)
        require(durationSeconds >= 0) { "durationSeconds must not be negative" }
        require(staticDurationSeconds >= 0) { "staticDurationSeconds must not be negative" }
        require(distanceMeters >= 0) { "distanceMeters must not be negative" }
        requireValidSource(source)
    }

    companion object {
        private const val MINUTES_PER_HOUR = 60
        private const val HOURS_PER_DAY = 24
        private const val MINUTES_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR
        private const val MONDAY_ISO = 1
        private const val SUNDAY_ISO = 7
        fun of(
            timestampEpochMillis: Long,
            zoneId: ZoneId,
            direction: String,
            durationSeconds: Long,
            staticDurationSeconds: Long,
            distanceMeters: Long,
            source: String,
        ): CommuteSample {
            val localTime = Instant.ofEpochMilli(timestampEpochMillis).atZone(zoneId)

            return CommuteSample(
                timestampEpochMillis = timestampEpochMillis,
                localDate = localTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                minuteOfDay = localTime.hour * MINUTES_PER_HOUR + localTime.minute,
                dayOfWeekIso = localTime.dayOfWeek.value,
                direction = direction,
                durationSeconds = durationSeconds,
                staticDurationSeconds = staticDurationSeconds,
                distanceMeters = distanceMeters,
                source = source,
            )
        }
    }
}

internal fun requireValidDirection(direction: String) {
    require(direction in VALID_DIRECTIONS) {
        "direction must be one of $VALID_DIRECTIONS"
    }
}

private val VALID_DIRECTIONS = setOf("TO_WORK", "TO_HOME")
private val VALID_SOURCES = setOf("SLOT", "TAP", "AUTO")

private fun requireValidSource(source: String) {
    require(source in VALID_SOURCES) {
        "source must be one of $VALID_SOURCES"
    }
}

internal fun bucketStartMinuteOfDay(minuteOfDay: Int, bucketMinutes: Int): Int {
    require(minuteOfDay in 0 until 24 * 60) {
        "minuteOfDay must be between 0 and 1439"
    }
    require(bucketMinutes > 0) { "bucketMinutes must be positive" }

    return (minuteOfDay / bucketMinutes) * bucketMinutes
}
