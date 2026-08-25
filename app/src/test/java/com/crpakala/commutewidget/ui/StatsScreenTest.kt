package com.crpakala.commutewidget.ui

import com.crpakala.commutewidget.history.TimeBucketAverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsScreenTest {
    @Test
    fun `formats minute of day as twelve hour clock`() {
        assertEquals("8:20", formatMinuteOfDay(500))
    }

    @Test
    fun `picks lowest duration bucket and returns null for empty data`() {
        val fastest = TimeBucketAverage(500, 2_100, 3)
        assertEquals(
            fastest,
            pickLowestBucket(
                listOf(
                    TimeBucketAverage(480, 2_400, 4),
                    fastest,
                ),
            ),
        )
        assertNull(pickLowestBucket(emptyList()))
    }
}
