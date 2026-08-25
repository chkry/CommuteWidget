package com.crpakala.commutewidget.history

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommuteSampleTest {
    @Test
    fun of_utcInstantNearMidnight_derivesKolkataDateAndTuesday() {
        val sample =
            CommuteSample.of(
                timestampEpochMillis = Instant.parse("2026-08-24T18:30:00Z").toEpochMilli(),
                zoneId = ZoneId.of("Asia/Kolkata"),
                direction = "TO_WORK",
                durationSeconds = 1_800,
                staticDurationSeconds = 1_500,
                distanceMeters = 12_000,
                source = "SLOT",
            )

        assertEquals("2026-08-25", sample.localDate)
        assertEquals(0, sample.minuteOfDay)
        assertEquals(2, sample.dayOfWeekIso)
    }

    @Test
    fun of_sameInstantInLosAngeles_derivesMondayBeforeMidnight() {
        val sample =
            CommuteSample.of(
                timestampEpochMillis = Instant.parse("2026-08-24T18:30:00Z").toEpochMilli(),
                zoneId = ZoneId.of("America/Los_Angeles"),
                direction = "TO_HOME",
                durationSeconds = 1_800,
                staticDurationSeconds = 1_500,
                distanceMeters = 12_000,
                source = "TAP",
            )

        assertEquals("2026-08-24", sample.localDate)
        assertEquals(690, sample.minuteOfDay)
        assertEquals(1, sample.dayOfWeekIso)
    }

    @Test
    fun of_crossesUtcDateBoundaryInHonolulu() {
        val sample =
            CommuteSample.of(
                timestampEpochMillis = Instant.parse("2026-01-01T00:05:00Z").toEpochMilli(),
                zoneId = ZoneId.of("Pacific/Honolulu"),
                direction = "TO_WORK",
                durationSeconds = 1,
                staticDurationSeconds = 1,
                distanceMeters = 1,
                source = "AUTO",
            )

        assertEquals("2025-12-31", sample.localDate)
        assertEquals(845, sample.minuteOfDay)
        assertEquals(3, sample.dayOfWeekIso)
    }

    @Test
    fun constructor_rejectsInvalidFields() {
        assertThrows(IllegalArgumentException::class.java) {
            validSample(minuteOfDay = 1_440)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSample(dayOfWeekIso = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSample(direction = "OTHER")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSample(durationSeconds = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSample(source = "MANUAL")
        }
    }

    @Test
    fun bucketStartMinuteOfDay_matchesSqlIntegerDivisionFormula() {
        assertEquals(0, bucketStartMinuteOfDay(9, 10))
        assertEquals(10, bucketStartMinuteOfDay(10, 10))
        assertEquals(1_430, bucketStartMinuteOfDay(1_439, 10))
        assertEquals(945, bucketStartMinuteOfDay(959, 15))
    }

    @Test
    fun bucketStartMinuteOfDay_rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            bucketStartMinuteOfDay(-1, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bucketStartMinuteOfDay(1_440, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bucketStartMinuteOfDay(1, 0)
        }
    }

    private fun validSample(
        minuteOfDay: Int = 480,
        dayOfWeekIso: Int = 1,
        direction: String = "TO_WORK",
        durationSeconds: Long = 1,
        source: String = "SLOT",
    ): CommuteSample =
        CommuteSample(
            timestampEpochMillis = 0,
            localDate = "1970-01-01",
            minuteOfDay = minuteOfDay,
            dayOfWeekIso = dayOfWeekIso,
            direction = direction,
            durationSeconds = durationSeconds,
            staticDurationSeconds = 1,
            distanceMeters = 1,
            source = source,
        )
}
