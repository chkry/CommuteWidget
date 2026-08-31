package com.crpakala.commutewidget.engine.health

import com.crpakala.commutewidget.health.KeyguardEvent
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

    // The screen-based fallback estimator's own tests pin the pre-redesign window (21:00-13:00)
    // explicitly so they keep exercising the same window-boundary scenarios regardless of the
    // v6 keyguard-model default change (sleepSearchStartMinuteOfDay/sleepSearchEndMinuteOfDay
    // moved to 22:30/10:00); estimateSleep itself is unchanged and stays the fallback path.
    private val legacyWindowParams = HealthParams(
        sleepSearchStartMinuteOfDay = 21 * 60,
        sleepSearchEndMinuteOfDay = 13 * 60,
    )

    @Test
    fun phoneUntouchedAllNight_capsDurationAtTwelveHours() {
        val result = estimateSleep(
            samples = listOf(sample(-1, 20, 50, false), sample(0, 13, 0, true)),
            dayStartEpochMillis = dayStart,
            zone = zone,
            params = legacyWindowParams,
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
            params = legacyWindowParams,
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
            legacyWindowParams,
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
            legacyWindowParams,
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
            legacyWindowParams,
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

    @Test
    fun keyguard_defaultParams_useNewWindow() {
        val params = HealthParams()

        assertEquals(22 * 60 + 30, params.sleepSearchStartMinuteOfDay)
        assertEquals(10 * 60, params.sleepSearchEndMinuteOfDay)
    }

    @Test
    fun keyguard_simpleNight_reportsFullDuration() {
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(-1, 23, 0), unlockEvent(0, 7, 30)),
            nowEpochMillis = epoch(0, 8, 0),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertEquals(510, result?.minutes)
        assertEquals(0, result?.overnightUnlockCount)
        assertEquals(epoch(-1, 23, 0), result?.startEpochMillis)
        assertEquals(epoch(0, 7, 30), result?.endEpochMillis)
    }

    @Test
    fun keyguard_midNightCheck_restartsClockAndCountsUnlock() {
        val result = estimateSleepFromKeyguard(
            events = listOf(
                lockEvent(-1, 23, 10),
                unlockEvent(0, 2, 0),
                lockEvent(0, 2, 5),
                unlockEvent(0, 7, 30),
            ),
            nowEpochMillis = epoch(0, 8, 0),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertEquals(325, result?.minutes)
        assertEquals(1, result?.overnightUnlockCount)
        assertEquals(epoch(0, 2, 5), result?.startEpochMillis)
        assertEquals(epoch(0, 7, 30), result?.endEpochMillis)
    }

    @Test
    fun keyguard_earlyBedtime_startPrecedesWindowStart() {
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(-1, 21, 45), unlockEvent(0, 6, 0)),
            nowEpochMillis = epoch(0, 7, 0),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertEquals(495, result?.minutes)
        assertEquals(epoch(-1, 21, 45), result?.startEpochMillis)
    }

    @Test
    fun keyguard_stillLockedBeforeWindowEnd_defersWithNull() {
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(-1, 23, 0)),
            nowEpochMillis = epoch(0, 6, 30),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertNull(result)
    }

    @Test
    fun keyguard_stillLockedAfterWindowEnd_closesSyntheticallyAtWindowEnd() {
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(-1, 23, 0)),
            nowEpochMillis = epoch(0, 11, 0),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertEquals(epoch(0, 10, 0), result?.endEpochMillis)
        assertEquals(660, result?.minutes)
    }

    @Test
    fun keyguard_fragmentedNightBelowFloorWithNoTaps_returnsNull() {
        val result = estimateSleepFromKeyguard(
            events = listOf(
                lockEvent(-1, 23, 50),
                unlockEvent(0, 0, 10),
                lockEvent(0, 0, 20),
                unlockEvent(0, 0, 40),
            ),
            nowEpochMillis = epoch(0, 8, 0),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertNull(result)
    }

    @Test
    fun keyguard_toBedAnchorIgnoresLocksBeforeTap() {
        val result = estimateSleepFromKeyguard(
            events = listOf(
                lockEvent(-1, 21, 35),
                unlockEvent(-1, 22, 0),
                lockEvent(0, 1, 0),
                unlockEvent(0, 7, 30),
            ),
            nowEpochMillis = epoch(0, 8, 0),
            zone = zone,
            toBedTapEpochMillis = epoch(-1, 21, 30),
            wokeUpTapEpochMillis = null,
        )

        assertEquals(epoch(0, 1, 0), result?.startEpochMillis)
        assertEquals(epoch(0, 7, 30), result?.endEpochMillis)
        assertEquals(390, result?.minutes)
    }

    @Test
    fun keyguard_wokeUpAnchorBoundsDomainAtPrecedingUnlock() {
        val result = estimateSleepFromKeyguard(
            events = listOf(
                lockEvent(-1, 23, 0),
                unlockEvent(0, 5, 45),
                lockEvent(0, 5, 50),
                unlockEvent(0, 7, 30),
            ),
            nowEpochMillis = epoch(0, 7, 35),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = epoch(0, 7, 32),
        )

        assertEquals(epoch(-1, 23, 0), result?.startEpochMillis)
        assertEquals(epoch(0, 5, 45), result?.endEpochMillis)
        assertEquals(405, result?.minutes)
    }

    @Test
    fun keyguard_bothTapsShortNight_bypassesFloorViaAnchor() {
        // Both anchors are within their valid domains (To Bed 01:58 <= 02:00; Woke Up 04:45 >=
        // 04:30), and the anchored interval 02:30-04:40 (130 min) is below the 180-min floor,
        // so the bypass reports it anyway - a genuinely short night still gets a number.
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(0, 2, 30), unlockEvent(0, 4, 40)),
            nowEpochMillis = epoch(0, 4, 50),
            zone = zone,
            toBedTapEpochMillis = epoch(0, 1, 58),
            wokeUpTapEpochMillis = epoch(0, 4, 45),
        )

        assertEquals(130, result?.minutes)
        assertEquals(epoch(0, 2, 30), result?.startEpochMillis)
        assertEquals(epoch(0, 4, 40), result?.endEpochMillis)
    }

    @Test
    fun keyguard_stillLockedWithEarlierQualifyingCandidate_stillDefersWithNull() {
        // Sprint 4 review test gap: the defer contract is whole-function - an unresolved open
        // interval (still locked, now before 10:00) returns null even though an earlier closed
        // candidate (23:00-03:00, 4h) already qualifies. The ongoing sleep phase may supersede it.
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(-1, 23, 0), unlockEvent(0, 3, 0), lockEvent(0, 3, 5)),
            nowEpochMillis = epoch(0, 6, 30),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertNull(result)
    }

    @Test
    fun keyguard_toBedTapWhileAlreadyLocked_synthesizesLockAtTapAndPairsWithMorningUnlock() {
        // Sprint 4 review finding 1: device locked at 21:00, To Bed tapped at 22:00 with no lock
        // event after it, morning unlock 07:00. The synthetic lock at the tap must PAIR with the
        // later unlock (not dangle as an open interval), yielding 22:00-07:00.
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(-1, 21, 0), unlockEvent(0, 7, 0)),
            nowEpochMillis = epoch(0, 7, 5),
            zone = zone,
            toBedTapEpochMillis = epoch(-1, 22, 0),
            wokeUpTapEpochMillis = null,
        )

        assertEquals(540, result?.minutes)
        assertEquals(epoch(-1, 22, 0), result?.startEpochMillis)
        assertEquals(epoch(0, 7, 0), result?.endEpochMillis)
        assertEquals(0, result?.overnightUnlockCount)
    }

    @Test
    fun keyguard_staleTapsFromOtherDaysAreIgnoredAsAnchors() {
        val result = estimateSleepFromKeyguard(
            events = listOf(
                lockEvent(-1, 23, 10),
                unlockEvent(0, 2, 0),
                lockEvent(0, 2, 5),
                unlockEvent(0, 7, 30),
            ),
            nowEpochMillis = epoch(0, 8, 0),
            zone = zone,
            toBedTapEpochMillis = epoch(-3, 21, 30),
            wokeUpTapEpochMillis = epoch(-2, 5, 0),
        )

        assertEquals(325, result?.minutes)
        assertEquals(1, result?.overnightUnlockCount)
        assertEquals(epoch(0, 2, 5), result?.startEpochMillis)
        assertEquals(epoch(0, 7, 30), result?.endEpochMillis)
    }

    @Test
    fun keyguard_thirteenHourLockedStretch_capsAtTwelveHours() {
        val result = estimateSleepFromKeyguard(
            events = listOf(lockEvent(-1, 20, 0), unlockEvent(0, 9, 0)),
            nowEpochMillis = epoch(0, 9, 30),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertEquals(720, result?.minutes)
        assertEquals(epoch(-1, 20, 0), result?.startEpochMillis)
        assertEquals(epoch(0, 9, 0), result?.endEpochMillis)
    }

    @Test
    fun overnightSleep_emptyKeyguardList_fallsBackToScreenEstimator() {
        val screenSamples = listOf(sample(-1, 23, 0, false), sample(0, 7, 0, true))
        val fallback = estimateSleep(screenSamples, dayStart, zone)

        val result = estimateOvernightSleep(
            keyguardEvents = emptyList(),
            screenSamples = screenSamples,
            dayStartEpochMillis = dayStart,
            nowEpochMillis = epoch(0, 8, 0),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertEquals(fallback?.minutes, result?.minutes)
        assertEquals(480, result?.minutes)
    }

    @Test
    fun overnightSleep_nonEmptyFragmentedKeyguardList_doesNotFallBackToScreenData() {
        val screenSamples = listOf(sample(-1, 23, 0, false), sample(0, 7, 0, true))

        val result = estimateOvernightSleep(
            keyguardEvents = listOf(
                lockEvent(-1, 23, 50),
                unlockEvent(0, 0, 10),
                lockEvent(0, 0, 20),
                unlockEvent(0, 0, 40),
            ),
            screenSamples = screenSamples,
            dayStartEpochMillis = dayStart,
            nowEpochMillis = epoch(0, 8, 0),
            zone = zone,
            toBedTapEpochMillis = null,
            wokeUpTapEpochMillis = null,
        )

        assertNull(result)
    }

    // toBedTapDomain / toBedTapInCurrentDomain

    @Test
    fun toBedTapDomain_eveningBandSpansTonightThroughEarlyMorning() {
        val domain = toBedTapDomain(epoch(0, 21, 30), zone)

        assertEquals(epoch(0, 21, 0), domain?.first)
        assertEquals(epoch(1, 2, 0), domain?.last)
    }

    @Test
    fun toBedTapDomain_earlyMorningBandSpansLastEveningThroughToday() {
        val domain = toBedTapDomain(epoch(0, 1, 30), zone)

        assertEquals(epoch(-1, 21, 0), domain?.first)
        assertEquals(epoch(0, 2, 0), domain?.last)
    }

    @Test
    fun toBedTapDomain_bothBandsResolveToTheSameAbsoluteRange() {
        // 22:00 tonight (evening band) and 01:00 the next calendar date (early-morning band)
        // describe the same continuous night; the domain must be byte-for-byte identical.
        val fromEvening = toBedTapDomain(epoch(0, 22, 0), zone)
        val fromEarlyMorning = toBedTapDomain(epoch(1, 1, 0), zone)

        assertEquals(fromEvening, fromEarlyMorning)
    }

    @Test
    fun toBedTapDomain_outsideBothBands_isNull() {
        assertNull(toBedTapDomain(epoch(0, 3, 0), zone))
        assertNull(toBedTapDomain(epoch(0, 20, 59), zone))
    }

    @Test
    fun toBedTapInCurrentDomain_acceptsTapInsideTonightsDomain() {
        assertTrue(toBedTapInCurrentDomain(epoch(0, 22, 0), epoch(0, 23, 0), zone))
    }

    @Test
    fun toBedTapInCurrentDomain_rejectsStaleTapFromAnotherNight() {
        assertFalse(toBedTapInCurrentDomain(epoch(-3, 21, 30), epoch(0, 22, 0), zone))
    }

    @Test
    fun toBedTapInCurrentDomain_nullTap_isFalse() {
        assertFalse(toBedTapInCurrentDomain(null, epoch(0, 22, 0), zone))
    }

    @Test
    fun toBedTapInCurrentDomain_noCurrentDomain_isFalse() {
        assertFalse(toBedTapInCurrentDomain(epoch(0, 21, 30), epoch(0, 12, 0), zone))
    }

    // wokeUpTapDomain / wokeUpTapInCurrentDomain

    @Test
    fun wokeUpTapDomain_isTodayFourThirtyToTen() {
        val domain = wokeUpTapDomain(epoch(0, 6, 0), zone)

        assertEquals(epoch(0, 4, 30), domain.first)
        assertEquals(epoch(0, 10, 0), domain.last)
    }

    @Test
    fun wokeUpTapInCurrentDomain_acceptsTapInsideTodaysWindow() {
        assertTrue(wokeUpTapInCurrentDomain(epoch(0, 5, 0), epoch(0, 6, 0), zone))
    }

    @Test
    fun wokeUpTapInCurrentDomain_rejectsStaleTapFromYesterday() {
        assertFalse(wokeUpTapInCurrentDomain(epoch(-1, 5, 0), epoch(0, 6, 0), zone))
    }

    @Test
    fun wokeUpTapInCurrentDomain_nullTap_isFalse() {
        assertFalse(wokeUpTapInCurrentDomain(null, epoch(0, 6, 0), zone))
    }

    // sleepBackfillFrozen

    @Test
    fun sleepBackfillFrozen_afterTenAmWithValue_isFrozen() {
        assertTrue(
            sleepBackfillFrozen(
                hasSleepMinutesToday = true,
                nowEpochMillis = epoch(0, 10, 0),
                zone = zone,
                wokeUpTapEpochMillis = null,
            ),
        )
    }

    @Test
    fun sleepBackfillFrozen_validWokeUpTapWithValue_isFrozenBeforeTenAm() {
        assertTrue(
            sleepBackfillFrozen(
                hasSleepMinutesToday = true,
                nowEpochMillis = epoch(0, 7, 0),
                zone = zone,
                wokeUpTapEpochMillis = epoch(0, 6, 45),
            ),
        )
    }

    @Test
    fun sleepBackfillFrozen_beforeTenAmWithValueButNoTap_isNotFrozen() {
        assertFalse(
            sleepBackfillFrozen(
                hasSleepMinutesToday = true,
                nowEpochMillis = epoch(0, 7, 0),
                zone = zone,
                wokeUpTapEpochMillis = null,
            ),
        )
    }

    @Test
    fun sleepBackfillFrozen_staleWokeUpTapFromYesterday_doesNotFreezeBeforeTenAm() {
        // Sprint 4 review test gap: a Woke Up tap from a PREVIOUS morning must not freeze today.
        assertFalse(
            sleepBackfillFrozen(
                hasSleepMinutesToday = true,
                nowEpochMillis = epoch(0, 7, 0),
                zone = zone,
                wokeUpTapEpochMillis = epoch(-1, 6, 45),
            ),
        )
    }

    @Test
    fun sleepBackfillFrozen_noValueYet_isNeverFrozenRegardlessOfTime() {
        assertFalse(
            sleepBackfillFrozen(
                hasSleepMinutesToday = false,
                nowEpochMillis = epoch(0, 11, 0),
                zone = zone,
                wokeUpTapEpochMillis = epoch(0, 6, 45),
            ),
        )
    }

    private fun lockEvent(dayOffset: Long, hour: Int, minute: Int): KeyguardEvent =
        KeyguardEvent(epoch(dayOffset, hour, minute), locked = true)

    private fun unlockEvent(dayOffset: Long, hour: Int, minute: Int): KeyguardEvent =
        KeyguardEvent(epoch(dayOffset, hour, minute), locked = false)

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
