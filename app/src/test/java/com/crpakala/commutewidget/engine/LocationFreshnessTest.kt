package com.crpakala.commutewidget.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFreshnessTest {
    private val maxAgeMillis = 120_000L

    @Test
    fun freshLocation_withinMaxAge_isFresh() {
        assertTrue(isLocationFresh(locationTimeEpochMillis = 1_000L, nowEpochMillis = 1_000L + maxAgeMillis - 1, maxAgeMillis))
    }

    @Test
    fun exactlyAtMaxAge_isStillFresh() {
        assertTrue(isLocationFresh(locationTimeEpochMillis = 1_000L, nowEpochMillis = 1_000L + maxAgeMillis, maxAgeMillis))
    }

    @Test
    fun justPastMaxAge_isStale() {
        assertFalse(isLocationFresh(locationTimeEpochMillis = 1_000L, nowEpochMillis = 1_000L + maxAgeMillis + 1, maxAgeMillis))
    }

    @Test
    fun zeroAge_isFresh() {
        assertTrue(isLocationFresh(locationTimeEpochMillis = 5_000L, nowEpochMillis = 5_000L, maxAgeMillis))
    }

    @Test
    fun futureLocationTimestamp_isFresh() {
        // Clock skew: a location "from the future" is trivially within the age bound too.
        assertTrue(isLocationFresh(locationTimeEpochMillis = 5_000L, nowEpochMillis = 4_000L, maxAgeMillis))
    }

    @Test
    fun defaultMaxAge_is120Seconds() {
        assertTrue(isLocationFresh(locationTimeEpochMillis = 0L, nowEpochMillis = 120_000L))
        assertFalse(isLocationFresh(locationTimeEpochMillis = 0L, nowEpochMillis = 120_001L))
    }
}
