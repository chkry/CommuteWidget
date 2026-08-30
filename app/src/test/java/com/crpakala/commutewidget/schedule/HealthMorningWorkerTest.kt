package com.crpakala.commutewidget.schedule

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** [nextSixThirty] drives [HealthMorningWorker]'s daily self-reschedule; always strictly future. */
class HealthMorningWorkerTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 8, 31, hour, minute, 0, 0, zone)

    @Test
    fun beforeSixThirty_returnsSixThirtyToday() {
        assertEquals(at(6, 30), nextSixThirty(at(5, 0)))
    }

    @Test
    fun exactlySixThirty_movesToTomorrowNotSameInstant() {
        assertEquals(ZonedDateTime.of(2026, 9, 1, 6, 30, 0, 0, zone), nextSixThirty(at(6, 30)))
    }

    @Test
    fun afterSixThirty_returnsSixThirtyTomorrow() {
        assertEquals(ZonedDateTime.of(2026, 9, 1, 6, 30, 0, 0, zone), nextSixThirty(at(23, 0)))
    }
}
