package com.crpakala.commutewidget.engine.health

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepLogicTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 6, 24)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun phoneUntouchedAllNight_capsDurationAtTwelveHours() {
        val result = estimateSleep(
            samples = listOf(sample(-1, 20, 50, false), sample(0, 13, 0, true)),
            dayStartEpochMillis = dayStart,
            zone = zone,
        )

        assertEquals(720, result?.minutes)
        assertEquals(epoch(-1, 21, 0), result?.startEpochMillis)
        assertEquals(epoch(0, 13, 0), result?.endEpochMillis)
    }

    @Test
    fun fragmentedNight_mergesBriefWakeAndReportsUnlock() {
        val result = estimateSleep(
            samples = listOf(
                sample(-1, 22, 0, false),
                sample(0, 1, 0, true),
                sample(0, 1, 5, false),
                sample(0, 4, 0, true),
                sample(0, 5, 0, false),
                sample(0, 8, 0, true),
            ),
            dayStartEpochMillis = dayStart,
            zone = zone,
        )

        assertEquals(360, result?.minutes)
        assertEquals(1, result?.overnightUnlockCount)
    }

    @Test
    fun alarmDismissThreeMinutes_mergesBothSleepGaps() {
        val result = estimateSleep(
            samples = listOf(
                sample(-1, 23, 0, false),
                sample(0, 6, 0, true),
                sample(0, 6, 3, false),
                sample(0, 9, 0, true),
            ),
            dayStartEpochMillis = dayStart,
            zone = zone,
        )

        assertEquals(600, result?.minutes)
        assertEquals(1, result?.overnightUnlockCount)
    }

    @Test
    fun twentyMinuteAlarmBurst_doesNotMerge() {
        val result = estimateSleep(
            samples = listOf(
                sample(-1, 23, 0, false),
                sample(0, 5, 0, true),
                sample(0, 5, 20, false),
                sample(0, 8, 0, true),
            ),
            dayStartEpochMillis = dayStart,
            zone = zone,
        )

        assertEquals(360, result?.minutes)
        assertEquals(0, result?.overnightUnlockCount)
    }

    @Test
    fun exactBriefWakeTolerance_doesNotMerge() {
        val result = estimateSleep(
            samples = listOf(
                sample(-1, 23, 0, false),
                sample(0, 4, 0, true),
                sample(0, 4, 10, false),
                sample(0, 8, 0, true),
            ),
            dayStartEpochMillis = dayStart,
            zone = zone,
        )

        assertEquals(300, result?.minutes)
    }

    @Test
    fun emptySamples_returnsNull() {
        assertNull(estimateSleep(emptyList(), dayStart, zone))
    }

    @Test
    fun sleepBelowThreeHours_returnsNull() {
        val result = estimateSleep(
            listOf(sample(0, 1, 0, false), sample(0, 3, 59, true)),
            dayStart,
            zone,
        )

        assertNull(result)
    }

    @Test
    fun interactiveWindowEdges_truncateTheCandidate() {
        val result = estimateSleep(
            listOf(
                sample(-1, 20, 0, true),
                sample(-1, 22, 0, false),
                sample(0, 10, 0, true),
                sample(0, 14, 0, false),
            ),
            dayStart,
            zone,
        )

        assertEquals(epoch(-1, 22, 0), result?.startEpochMillis)
        assertEquals(epoch(0, 10, 0), result?.endEpochMillis)
    }

    @Test
    fun crossMidnightBlock_usesKolkataLocalDay() {
        val result = estimateSleep(
            listOf(sample(-1, 23, 30, false), sample(0, 6, 15, true)),
            dayStart,
            zone,
        )

        assertEquals(405, result?.minutes)
        assertEquals(epoch(-1, 23, 30), result?.startEpochMillis)
        assertEquals(epoch(0, 6, 15), result?.endEpochMillis)
    }

    @Test
    fun repeatedNonInteractiveSamples_doNotSplitContinuousSleep() {
        val result = estimateSleep(
            listOf(
                sample(-1, 23, 0, false),
                sample(0, 1, 0, false),
                sample(0, 3, 0, false),
                sample(0, 7, 0, true),
            ),
            dayStart,
            zone,
        )

        assertEquals(480, result?.minutes)
    }

    @Test
    fun exactlyMinimumPlausibleSleep_isAccepted() {
        val result = estimateSleep(
            listOf(sample(0, 1, 0, false), sample(0, 4, 0, true)),
            dayStart,
            zone,
        )

        assertEquals(180, result?.minutes)
    }

    @Test
    fun longestOfSeparateBlocksWins() {
        val result = estimateSleep(
            listOf(
                sample(-1, 21, 30, false),
                sample(0, 1, 0, true),
                sample(0, 2, 0, false),
                sample(0, 7, 0, true),
            ),
            dayStart,
            zone,
        )

        assertEquals(300, result?.minutes)
        assertEquals(epoch(0, 2, 0), result?.startEpochMillis)
    }

    @Test
    fun equalLengthBlocks_chooseEarlierBlockDeterministically() {
        val result = estimateSleep(
            listOf(
                sample(-1, 21, 0, false),
                sample(0, 0, 0, true),
                sample(0, 1, 0, false),
                sample(0, 4, 0, true),
            ),
            dayStart,
            zone,
        )

        assertEquals(epoch(-1, 21, 0), result?.startEpochMillis)
    }

    @Test
    fun medianSleepMinutes_oddPopulation() {
        val history = listOf("2026-01-01" to 420, "2026-01-02" to 390, "2026-01-03" to 450)

        assertEquals(420, medianSleepMinutes(history))
    }

    @Test
    fun medianSleepMinutes_evenPopulationUsesIntegerMidpoint() {
        val history = listOf("2026-01-01" to 400, "2026-01-02" to 421)

        assertEquals(410, medianSleepMinutes(history))
    }

    @Test
    fun medianSleepMinutes_usesLastFourteenNonNullByIsoDate() {
        val history = (1..16).map { dayNumber ->
            "2026-01-${dayNumber.toString().padStart(2, '0')}" to
                if (dayNumber == 16) null else dayNumber
        }

        assertEquals(8, medianSleepMinutes(history))
    }

    @Test
    fun medianSleepMinutes_allNullReturnsNull() {
        assertNull(medianSleepMinutes(listOf("2026-01-01" to null)))
    }

    @Test
    fun historyWithSixNonNullDays_isNotTrustworthy() {
        val history = (1..6).map { "2026-01-0$it" to 420 }

        assertFalse(sleepHistoryTrustworthy(history))
    }

    @Test
    fun historyWithSevenNonNullDays_isTrustworthy() {
        val history = (1..7).map { "2026-01-0$it" to 420 }

        assertTrue(sleepHistoryTrustworthy(history))
    }

    @Test
    fun typicalBedtime_normalizesAfterMidnightValues() {
        val estimates = listOf(
            estimateAt(LocalDate.of(2026, 6, 22), 23, 30),
            estimateAt(LocalDate.of(2026, 6, 24), 0, 30),
        )

        assertEquals(0, typicalBedtimeMinuteOfDay(estimates, zone))
    }

    @Test
    fun typicalBedtime_emptyHistoryReturnsNull() {
        assertNull(typicalBedtimeMinuteOfDay(emptyList(), zone))
    }

    private fun sample(dayOffset: Long, hour: Int, minute: Int, interactive: Boolean): ScreenSample =
        ScreenSample(epoch(dayOffset, hour, minute), interactive)

    private fun epoch(dayOffset: Long, hour: Int, minute: Int): Long =
        day.plusDays(dayOffset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun estimateAt(date: LocalDate, hour: Int, minute: Int): SleepEstimate =
        SleepEstimate(
            minutes = 420,
            startEpochMillis = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
            endEpochMillis = date.atTime(hour, minute).plusHours(7).atZone(zone).toInstant().toEpochMilli(),
            overnightUnlockCount = 0,
        )
}
