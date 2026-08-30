package com.crpakala.commutewidget.engine.health

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeLogicTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 6, 24)
    private val dayStart = epoch(0, 0)
    private val params = HealthParams()
    private val morningWindow = 420..600
    private val proteinWindow = 1_080..1_260

    @Test
    fun supplementsBeforeMorningWindow_areAbsent() {
        assertTrue(supplements(419).isEmpty())
    }

    @Test
    fun morningSupplementStartsAtWindowBoundary() {
        val result = supplements(420)

        assertEquals("Vitamins + Cr", result.single().label)
        assertFalse(result.single().demoted)
    }

    @Test
    fun takenMorningSupplement_disappears() {
        assertTrue(supplements(500, morningTaken = 450).isEmpty())
    }

    @Test
    fun morningSupplementCarriesOverAsDemoted() {
        val result = supplements(900)

        assertEquals(NudgeKind.SUPPLEMENT_MORNING, result.single().kind)
        assertTrue(result.single().demoted)
    }

    @Test
    fun morningSupplementDisappearsAtNineThirtyPm() {
        val result = supplements(1_290)

        assertTrue(result.none { it.kind == NudgeKind.SUPPLEMENT_MORNING })
    }

    @Test
    fun proteinStartsAtSixPm() {
        val protein = supplements(1_080).first()

        assertEquals(NudgeKind.SUPPLEMENT_PROTEIN, protein.kind)
        assertEquals("Protein", protein.label)
        assertFalse(protein.demoted)
    }

    @Test
    fun takenProtein_disappears() {
        val result = supplements(1_100, morningTaken = 500, proteinTaken = 1_090)

        assertTrue(result.isEmpty())
    }

    @Test
    fun proteinCarriesOverToMidnightAsDemoted() {
        val protein = supplements(1_300).single()

        assertEquals(NudgeKind.SUPPLEMENT_PROTEIN, protein.kind)
        assertTrue(protein.demoted)
        assertEquals(1_440, protein.endMinuteOfDay)
    }

    @Test
    fun afterSixPmProteinPrecedesMorningCarryover() {
        val result = supplements(1_100)

        assertEquals(
            listOf(NudgeKind.SUPPLEMENT_PROTEIN, NudgeKind.SUPPLEMENT_MORNING),
            result.map { it.kind },
        )
    }

    @Test
    fun gymPriorityCanPromoteEarlyProteinWindow() {
        val result = supplementCandidates(
            nowMinuteOfDay = 1_050,
            morningWindow = morningWindow,
            proteinWindow = 1_020..1_260,
            morningTakenMinute = null,
            proteinTakenMinute = null,
            gymDayDetected = true,
            gymPriorityEnabled = true,
        )

        assertEquals(NudgeKind.SUPPLEMENT_PROTEIN, result.first().kind)
    }

    @Test
    fun allDayFocusGap_isCappedAtOneHundredTwentyMinutes() {
        val result = focus(nowHour = 9, nowMinute = 0)

        assertEquals("Focus 120m", result?.label)
        assertEquals(540, result?.startMinuteOfDay)
        assertEquals(1_080, result?.endMinuteOfDay)
    }

    @Test
    fun focusGapExactlyFortyFiveMinutes_isEligible() {
        val result = focus(
            nowHour = 10,
            nowMinute = 0,
            events = listOf(event(10, 45, 18, 0)),
        )

        assertEquals("Focus 45m", result?.label)
    }

    @Test
    fun focusGapBelowFortyFiveMinutes_isRejected() {
        val result = focus(
            nowHour = 10,
            nowMinute = 0,
            events = listOf(event(10, 44, 18, 0)),
        )

        assertNull(result)
    }

    @Test
    fun meetingEndingInTenMinutes_allowsUpcomingFocusGap() {
        val result = focus(
            nowHour = 10,
            nowMinute = 0,
            events = listOf(event(9, 0, 10, 10)),
        )

        assertEquals(610, result?.startMinuteOfDay)
    }

    @Test
    fun meetingEndingInElevenMinutes_isTooFarAway() {
        val result = focus(
            nowHour = 10,
            nowMinute = 0,
            events = listOf(event(9, 0, 10, 11)),
        )

        assertNull(result)
    }

    @Test
    fun dismissedFocusGap_isSuppressedByStableGapStart() {
        val result = focus(nowHour = 10, nowMinute = 0, dismissed = listOf(540))

        assertNull(result)
    }

    @Test
    fun overlappingMeetings_areMergedBeforeGapSearch() {
        val result = focus(
            nowHour = 10,
            nowMinute = 0,
            events = listOf(event(9, 0, 10, 5), event(10, 0, 10, 10)),
        )

        assertEquals(610, result?.startMinuteOfDay)
    }

    @Test
    fun focusOutsideWorkHours_isAbsent() {
        assertNull(focus(nowHour = 18, nowMinute = 0))
    }

    @Test
    fun morningLight_isEligibleAtWindowStart() {
        assertTrue(morningLightEligible(420, morningWindow))
    }

    @Test
    fun morningLight_isIneligibleBeforeWindow() {
        assertFalse(morningLightEligible(419, morningWindow))
    }

    @Test
    fun morningLight_isIneligibleAtEndExclusiveBoundary() {
        assertFalse(morningLightEligible(600, morningWindow))
    }

    @Test
    fun caffeineLine_startsNinetyMinutesBeforeCutoff() {
        val result = caffeineLineCandidate(750, 840)

        assertEquals("Coffee by 2:00 pm", result?.label)
        assertEquals(750, result?.startMinuteOfDay)
        assertEquals(840, result?.targetMinuteOfDay)
    }

    @Test
    fun caffeineLine_beforeLeadWindow_isAbsent() {
        assertNull(caffeineLineCandidate(749, 840))
    }

    @Test
    fun caffeineLine_atCutoff_isAbsent() {
        assertNull(caffeineLineCandidate(840, 840))
    }

    @Test
    fun caffeineLine_formatsNoonInCompactStyle() {
        assertEquals("Coffee by 12:00 pm", caffeineLineCandidate(700, 720)?.label)
    }

    @Test
    fun shortSleepNull_isFalse() {
        assertFalse(shortSleepDay(null))
    }

    @Test
    fun fiveHoursFiftyNine_isShortSleep() {
        assertTrue(shortSleepDay(359))
    }

    @Test
    fun sixHoursExactly_isNotShortSleep() {
        assertFalse(shortSleepDay(360))
    }

    @Test
    fun briefPrefix_requiresMoreThanSixtyMinuteDeficit() {
        assertNull(briefPrefix(360, 420, 3, true))
        assertEquals("Short sleep", briefPrefix(359, 420, 3, true))
    }

    @Test
    fun briefPrefix_requiresBusyDayAndEnabledFlag() {
        assertNull(briefPrefix(300, 420, 2, true))
        assertNull(briefPrefix(300, 420, 3, false))
    }

    @Test
    fun focusShieldRequiresMoreThanSixUnlocks() {
        assertFalse(shield(unlocks = 6, sleep = 300, median = 420, nowHour = 9))
        assertTrue(shield(unlocks = 7, sleep = 300, median = 420, nowHour = 9))
    }

    @Test
    fun focusShieldRequiresSleepBelowMedian() {
        assertFalse(shield(unlocks = 7, sleep = 420, median = 420, nowHour = 9))
    }

    @Test
    fun focusShieldEndsAtFirstEventEnd() {
        assertTrue(shield(7, 300, 420, 9, firstEventEnd = epoch(9, 30)))
        assertFalse(shield(7, 300, 420, 9, 30, firstEventEnd = epoch(9, 30)))
    }

    @Test
    fun focusShieldWithoutEventsEndsAtTenAm() {
        assertTrue(shield(7, 300, 420, 9, 59))
        assertFalse(shield(7, 300, 420, 10, 0))
    }

    @Test
    fun focusShieldDisabled_isAlwaysFalse() {
        assertFalse(shield(7, 300, 420, 9, enabled = false))
    }

    @Test
    fun shortSleepMorning_canEnableBothSoftenedPrefixAndFocusShield() {
        val prefix = briefPrefix(
            sleepMinutes = 300,
            median14 = 420,
            todayEventCount = 4,
            softenEnabled = true,
        )
        val shield = shield(unlocks = 8, sleep = 300, median = 420, nowHour = 9)

        assertEquals("Short sleep", prefix)
        assertTrue(shield)
    }

    @Test
    fun fullEveningCollision_selectsPerSurfacePolicy() {
        val candidates = listOf(
            candidate(NudgeKind.SUPPLEMENT_PROTEIN),
            candidate(NudgeKind.WATER),
            candidate(NudgeKind.WALK),
            candidate(NudgeKind.FOCUS_GAP),
        )

        assertEquals(
            listOf(NudgeKind.SUPPLEMENT_PROTEIN, NudgeKind.WALK),
            selectVisibleNudges(candidates, NudgeSurface.MAP_COMMUTE, false, false).map { it.kind },
        )
        assertEquals(
            listOf(NudgeKind.SUPPLEMENT_PROTEIN, NudgeKind.WATER),
            selectVisibleNudges(candidates, NudgeSurface.MAP_EVENT, false, false).map { it.kind },
        )
        assertEquals(
            listOf(NudgeKind.SUPPLEMENT_PROTEIN, NudgeKind.WATER),
            selectVisibleNudges(candidates, NudgeSurface.CARD, false, false).map { it.kind },
        )
    }

    @Test
    fun audiobookPlayback_suppressesEveryVisibleNudge() {
        val result = selectVisibleNudges(
            listOf(candidate(NudgeKind.SUPPLEMENT_PROTEIN)),
            NudgeSurface.CARD,
            audiobookPlaying = true,
            shieldActive = false,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun shieldRemovesWaterAndWalkButKeepsSupplements() {
        val result = selectVisibleNudges(
            listOf(
                candidate(NudgeKind.WATER),
                candidate(NudgeKind.WALK),
                candidate(NudgeKind.SUPPLEMENT_MORNING),
            ),
            NudgeSurface.CARD,
            audiobookPlaying = false,
            shieldActive = true,
        )

        assertEquals(listOf(NudgeKind.SUPPLEMENT_MORNING), result.map { it.kind })
    }

    @Test
    fun activeWaterOutranksDemotedSupplement() {
        val result = selectVisibleNudges(
            listOf(
                candidate(NudgeKind.SUPPLEMENT_MORNING, demoted = true),
                candidate(NudgeKind.WATER),
            ),
            NudgeSurface.CARD,
            audiobookPlaying = false,
            shieldActive = false,
        )

        assertEquals(NudgeKind.WATER, result.first().kind)
    }

    @Test
    fun supplementOrderingIsStableWithinPriorityClass() {
        val result = selectVisibleNudges(
            listOf(
                candidate(NudgeKind.SUPPLEMENT_PROTEIN),
                candidate(NudgeKind.SUPPLEMENT_MORNING),
            ),
            NudgeSurface.CARD,
            audiobookPlaying = false,
            shieldActive = false,
        )

        assertEquals(
            listOf(NudgeKind.SUPPLEMENT_PROTEIN, NudgeKind.SUPPLEMENT_MORNING),
            result.map { it.kind },
        )
    }

    @Test
    fun maximumVisibleLimit_isHonored() {
        val result = selectVisibleNudges(
            listOf(candidate(NudgeKind.SUPPLEMENT_PROTEIN), candidate(NudgeKind.WATER)),
            NudgeSurface.CARD,
            audiobookPlaying = false,
            shieldActive = false,
            maxVisible = 1,
        )

        assertEquals(1, result.size)
    }

    @Test
    fun textLineKinds_areExcludedFromPillSelection() {
        val result = selectVisibleNudges(
            listOf(candidate(NudgeKind.MORNING_LIGHT), candidate(NudgeKind.CAFFEINE_CUTOFF)),
            NudgeSurface.MAP_COMMUTE,
            audiobookPlaying = false,
            shieldActive = false,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun morningLightLineWinsOverCaffeineOnCommute() {
        val result = lineCandidates(
            listOf(candidate(NudgeKind.CAFFEINE_CUTOFF), candidate(NudgeKind.MORNING_LIGHT)),
            NudgeSurface.MAP_COMMUTE,
        )

        assertEquals(NudgeKind.MORNING_LIGHT, result.single().kind)
    }

    @Test
    fun morningLightLine_isExcludedFromEventMap() {
        val result = lineCandidates(
            listOf(candidate(NudgeKind.MORNING_LIGHT), candidate(NudgeKind.CAFFEINE_CUTOFF)),
            NudgeSurface.MAP_EVENT,
        )

        assertEquals(NudgeKind.CAFFEINE_CUTOFF, result.single().kind)
    }

    private fun supplements(
        now: Int,
        morningTaken: Int? = null,
        proteinTaken: Int? = null,
    ): List<NudgeCandidate> = supplementCandidates(
        nowMinuteOfDay = now,
        morningWindow = morningWindow,
        proteinWindow = proteinWindow,
        morningTakenMinute = morningTaken,
        proteinTakenMinute = proteinTaken,
        gymDayDetected = false,
        gymPriorityEnabled = false,
    )

    private fun focus(
        nowHour: Int,
        nowMinute: Int,
        events: List<EventSpan> = emptyList(),
        dismissed: List<Int> = emptyList(),
    ): NudgeCandidate? = focusGapCandidate(
        events = events,
        nowEpochMillis = epoch(nowHour, nowMinute),
        dayEpochMillis = dayStart,
        zone = zone,
        dismissedGapStartMinutes = dismissed,
    )

    private fun shield(
        unlocks: Int,
        sleep: Int,
        median: Int,
        nowHour: Int,
        nowMinute: Int = 0,
        firstEventEnd: Long? = null,
        enabled: Boolean = true,
    ): Boolean = focusShieldActive(
        overnightUnlockCount = unlocks,
        sleepMinutes = sleep,
        median14 = median,
        firstEventEndEpochMillis = firstEventEnd,
        nowEpochMillis = epoch(nowHour, nowMinute),
        dayEpochMillis = dayStart,
        zone = zone,
        shieldEnabled = enabled,
    )

    private fun candidate(kind: NudgeKind, demoted: Boolean = false): NudgeCandidate =
        NudgeCandidate(kind, kind.name, 0, 1_440, demoted = demoted)

    private fun event(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): EventSpan =
        EventSpan(epoch(startHour, startMinute), epoch(endHour, endMinute))

    private fun epoch(hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
