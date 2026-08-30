package com.crpakala.commutewidget

import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.HealthDayState
import com.crpakala.commutewidget.data.HealthNudge
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.data.MapPillCorner
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.engine.health.NudgeCandidate
import com.crpakala.commutewidget.engine.health.NudgeKind
import com.crpakala.commutewidget.engine.health.NudgeSurface
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthWidgetUiTest {
    private val today = "2026-08-31"
    private val zone = ZoneId.of("UTC")

    @Test
    fun oppositeCorner_isDiagonal() {
        assertEquals(MapPillCorner.BOTTOM_END, oppositeCorner(MapPillCorner.TOP_START))
        assertEquals(MapPillCorner.BOTTOM_START, oppositeCorner(MapPillCorner.TOP_END))
        assertEquals(MapPillCorner.TOP_END, oppositeCorner(MapPillCorner.BOTTOM_START))
        assertEquals(MapPillCorner.TOP_START, oppositeCorner(MapPillCorner.BOTTOM_END))
    }

    @Test
    fun oppositeCorner_isInvolutive() {
        MapPillCorner.entries.forEach { corner ->
            assertEquals(corner, oppositeCorner(oppositeCorner(corner)))
        }
    }

    @Test
    fun formatSleepCompact_hoursAndMinutes() {
        assertEquals("~6h 40m", formatSleepCompact(6 * 60 + 40))
        assertEquals("~1h 5m", formatSleepCompact(65))
    }

    @Test
    fun formatSleepCompact_exactHours() {
        assertEquals("~6h", formatSleepCompact(6 * 60))
        assertEquals("~1h", formatSleepCompact(60))
    }

    @Test
    fun formatSleepCompact_minutesOnly() {
        assertEquals("~45m", formatSleepCompact(45))
        assertEquals("~0m", formatSleepCompact(0))
    }

    @Test
    fun formatSleepCompact_clampsNegative() {
        assertEquals("~0m", formatSleepCompact(-12))
    }

    @Test
    fun buildBriefLine_fullCanonicalWithSleepAndMeetings() {
        val first = epoch(zone, 10, 0)
        assertEquals(
            "Slept ~6h 40m · 3 meetings · first 10:00 am",
            buildBriefLine(
                sleepEstimateMinutes = 6 * 60 + 40,
                shortSleepDay = false,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = first,
                truncation = BriefTruncation.FULL,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_shortSleepReplacesDurationSegment() {
        val first = epoch(zone, 10, 0)
        assertEquals(
            "Short sleep · 3 meetings · first 10:00 am",
            buildBriefLine(
                sleepEstimateMinutes = 5 * 60,
                shortSleepDay = true,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = first,
                truncation = BriefTruncation.FULL,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_nullSleepKeepsExistingMeetingsForm() {
        val first = epoch(zone, 10, 0)
        assertEquals(
            "3 meetings · first 10:00 am",
            buildBriefLine(
                sleepEstimateMinutes = null,
                shortSleepDay = false,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = first,
                truncation = BriefTruncation.FULL,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_sleepBriefDisabledOmitsSleepEvenWhenMinutesPresent() {
        val first = epoch(zone, 9, 5)
        assertEquals(
            "1 meeting · first 9:05 am",
            buildBriefLine(
                sleepEstimateMinutes = 400,
                shortSleepDay = true,
                sleepBriefEnabled = false,
                meetingCount = 1,
                firstStartEpochMillis = first,
                truncation = BriefTruncation.FULL,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_shortSleepWithoutMinutesDoesNotPrefix() {
        assertEquals(
            "3 meetings",
            buildBriefLine(
                sleepEstimateMinutes = null,
                shortSleepDay = true,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = null,
                truncation = BriefTruncation.FULL,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_dropFirstRemovesClockButKeepsMeetings() {
        val first = epoch(zone, 10, 0)
        assertEquals(
            "Slept ~6h 40m · 3 meetings",
            buildBriefLine(
                sleepEstimateMinutes = 6 * 60 + 40,
                shortSleepDay = false,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = first,
                truncation = BriefTruncation.DROP_FIRST,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_sleepOnlyIsTheLastLadderStep() {
        val first = epoch(zone, 10, 0)
        assertEquals(
            "Slept ~6h 40m",
            buildBriefLine(
                sleepEstimateMinutes = 6 * 60 + 40,
                shortSleepDay = false,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = first,
                truncation = BriefTruncation.SLEEP_ONLY,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_sleepOnlyWithShortSleep() {
        assertEquals(
            "Short sleep",
            buildBriefLine(
                sleepEstimateMinutes = 300,
                shortSleepDay = true,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = null,
                truncation = BriefTruncation.SLEEP_ONLY,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_sleepOnlyWithoutSleepFallsBackToMeetings() {
        val first = epoch(zone, 10, 0)
        assertEquals(
            "3 meetings · first 10:00 am",
            buildBriefLine(
                sleepEstimateMinutes = null,
                shortSleepDay = false,
                sleepBriefEnabled = true,
                meetingCount = 3,
                firstStartEpochMillis = first,
                truncation = BriefTruncation.SLEEP_ONLY,
                zone = zone,
            ),
        )
    }

    @Test
    fun buildBriefLine_sleepWithoutMeetings() {
        assertEquals(
            "Slept ~7h",
            buildBriefLine(
                sleepEstimateMinutes = 7 * 60,
                shortSleepDay = false,
                sleepBriefEnabled = true,
                meetingCount = 0,
                firstStartEpochMillis = null,
                truncation = BriefTruncation.FULL,
                zone = zone,
            ),
        )
        assertNull(
            buildBriefLine(
                sleepEstimateMinutes = null,
                shortSleepDay = false,
                sleepBriefEnabled = true,
                meetingCount = 0,
                firstStartEpochMillis = null,
                truncation = BriefTruncation.FULL,
                zone = zone,
            ),
        )
    }

    @Test
    fun pickBriefTruncation_largeAlwaysFull() {
        assertEquals(BriefTruncation.FULL, pickBriefTruncation(isLarge = true, hasSleepPrefix = true))
        assertEquals(BriefTruncation.FULL, pickBriefTruncation(isLarge = true, hasSleepPrefix = false))
    }

    @Test
    fun pickBriefTruncation_wideUsesSleepOnlyWhenSleepPrefixPresent() {
        assertEquals(BriefTruncation.SLEEP_ONLY, pickBriefTruncation(isLarge = false, hasSleepPrefix = true))
        assertEquals(BriefTruncation.FULL, pickBriefTruncation(isLarge = false, hasSleepPrefix = false))
    }

    @Test
    fun filter_keepsCandidatesWhenDayStateIsNull() {
        val nudges = listOf(nudge(HealthNudgeKind.WATER, start = 450, end = 480))
        assertEquals(nudges, filterHealthNudgesAgainstDayState(nudges, null, today, nowMinuteOfDay = 460))
    }

    @Test
    fun filter_dropsExpiredByEndMinute() {
        val nudges = listOf(nudge(HealthNudgeKind.WATER, start = 450, end = 480))
        assertTrue(
            filterHealthNudgesAgainstDayState(nudges, emptyState(), today, nowMinuteOfDay = 480).isEmpty(),
        )
        assertEquals(
            1,
            filterHealthNudgesAgainstDayState(nudges, emptyState(), today, nowMinuteOfDay = 479).size,
        )
    }

    @Test
    fun filter_dropsTakenMorningAndProteinSupplements() {
        val nudges = listOf(
            nudge(HealthNudgeKind.SUPPLEMENT_MORNING),
            nudge(HealthNudgeKind.SUPPLEMENT_PROTEIN),
        )
        val taken = emptyState().copy(
            morningSupplementsTakenMinute = 430,
            proteinTakenMinute = 1100,
        )
        assertTrue(filterHealthNudgesAgainstDayState(nudges, taken, today, nowMinuteOfDay = 500).isEmpty())
    }

    @Test
    fun filter_keepsUntakenSupplements() {
        val nudges = listOf(nudge(HealthNudgeKind.SUPPLEMENT_MORNING))
        assertEquals(
            1,
            filterHealthNudgesAgainstDayState(nudges, emptyState(), today, nowMinuteOfDay = 500).size,
        )
    }

    @Test
    fun filter_dropsWaterWhenTapFallsInsideSlot() {
        val nudges = listOf(nudge(HealthNudgeKind.WATER, start = 450, end = 480))
        val tapped = emptyState().copy(waterTapMinutes = listOf(460))
        assertTrue(filterHealthNudgesAgainstDayState(nudges, tapped, today, nowMinuteOfDay = 465).isEmpty())
    }

    @Test
    fun filter_keepsWaterWhenTapIsOutsideSlot() {
        val nudges = listOf(nudge(HealthNudgeKind.WATER, start = 450, end = 480))
        val tapped = emptyState().copy(waterTapMinutes = listOf(400, 480))
        assertEquals(
            1,
            filterHealthNudgesAgainstDayState(nudges, tapped, today, nowMinuteOfDay = 460).size,
        )
    }

    @Test
    fun filter_dropsWalkWhenDismissed() {
        val nudges = listOf(nudge(HealthNudgeKind.WALK, start = 1080, end = 1290))
        val dismissed = emptyState().copy(walkDismissed = true)
        assertTrue(filterHealthNudgesAgainstDayState(nudges, dismissed, today, nowMinuteOfDay = 1100).isEmpty())
    }

    @Test
    fun filter_dropsFocusGapWhenStartMinuteDismissed() {
        val nudges = listOf(nudge(HealthNudgeKind.FOCUS_GAP, start = 600, end = 720))
        val dismissed = emptyState().copy(dismissedFocusGapStartMinutes = listOf(600))
        assertTrue(filterHealthNudgesAgainstDayState(nudges, dismissed, today, nowMinuteOfDay = 610).isEmpty())
        assertEquals(
            1,
            filterHealthNudgesAgainstDayState(
                nudges,
                emptyState().copy(dismissedFocusGapStartMinutes = listOf(540)),
                today,
                nowMinuteOfDay = 610,
            ).size,
        )
    }

    @Test
    fun filter_ignoresStaleDayStateFromAnotherDate() {
        val nudges = listOf(
            nudge(HealthNudgeKind.SUPPLEMENT_MORNING),
            nudge(HealthNudgeKind.WALK, start = 1080, end = 1290),
        )
        val yesterday = HealthDayState(
            date = "2026-08-30",
            morningSupplementsTakenMinute = 430,
            walkDismissed = true,
        )
        assertEquals(
            2,
            filterHealthNudgesAgainstDayState(nudges, yesterday, today, nowMinuteOfDay = 500).size,
        )
    }

    @Test
    fun nudgeSurfaceFor_mapsEachSnapshotMode() {
        assertEquals(NudgeSurface.MAP_COMMUTE, nudgeSurfaceFor(SnapshotMode.COMMUTE))
        assertEquals(NudgeSurface.MAP_EVENT, nudgeSurfaceFor(SnapshotMode.CALENDAR_EVENT))
        assertEquals(NudgeSurface.CARD, nudgeSurfaceFor(SnapshotMode.CALENDAR_EMPTY))
    }

    @Test
    fun mapHealthLabel_expandsMorningSupplementOnLargeOnly() {
        assertEquals(
            "Vitamins + creatine",
            mapHealthLabel(NudgeKind.SUPPLEMENT_MORNING, "Vitamins + Cr", large = true),
        )
        assertEquals(
            "Vitamins + Cr",
            mapHealthLabel(NudgeKind.SUPPLEMENT_MORNING, "Vitamins + Cr", large = false),
        )
        assertEquals(
            "Vitamins + creatine",
            mapHealthLabel(NudgeKind.SUPPLEMENT_MORNING, "anything", large = true),
        )
        assertEquals("Protein", mapHealthLabel(NudgeKind.SUPPLEMENT_PROTEIN, "Protein", large = true))
        assertEquals("Drink water", mapHealthLabel(NudgeKind.WATER, "Drink water", large = true))
    }

    @Test
    fun healthGlyph_matchesUxCategories() {
        assertEquals("✓", healthGlyph(NudgeKind.SUPPLEMENT_MORNING))
        assertEquals("✓", healthGlyph(NudgeKind.SUPPLEMENT_PROTEIN))
        assertEquals("💧", healthGlyph(NudgeKind.WATER))
        assertEquals("🚶", healthGlyph(NudgeKind.WALK))
        assertEquals("◎", healthGlyph(NudgeKind.FOCUS_GAP))
        assertEquals("", healthGlyph(NudgeKind.MORNING_LIGHT))
        assertEquals("", healthGlyph(NudgeKind.CAFFEINE_CUTOFF))
    }

    @Test
    fun healthLineCaption_fallsBackForEmptyMorningLight() {
        assertEquals(
            "Step outside - morning light",
            healthLineCaption(
                NudgeCandidate(NudgeKind.MORNING_LIGHT, "", 420, 600),
            ),
        )
        assertEquals(
            "Coffee by 2:00 pm",
            healthLineCaption(
                NudgeCandidate(NudgeKind.CAFFEINE_CUTOFF, "Coffee by 2:00 pm", 750, 840),
            ),
        )
    }

    @Test
    fun toEngineNudgeCandidate_preservesFieldsAndKind() {
        val data = HealthNudge(
            kind = HealthNudgeKind.WALK,
            label = "Walk 7:30",
            startMinuteOfDay = 18 * 60 + 30,
            endMinuteOfDay = 19 * 60 + 15,
            targetMinuteOfDay = 18 * 60 + 30,
            demoted = true,
        )
        val engine = toEngineNudgeCandidate(data)
        assertEquals(NudgeKind.WALK, engine.kind)
        assertEquals("Walk 7:30", engine.label)
        assertEquals(18 * 60 + 30, engine.startMinuteOfDay)
        assertEquals(19 * 60 + 15, engine.endMinuteOfDay)
        assertEquals(18 * 60 + 30, engine.targetMinuteOfDay)
        assertTrue(engine.demoted)
    }

    @Test
    fun resolveVisibleHealthChrome_hidesWaterOnCommuteMap() {
        val chrome = resolveVisibleHealthChrome(
            snapshotNudges = listOf(
                nudge(HealthNudgeKind.SUPPLEMENT_MORNING, "Vitamins + Cr"),
                nudge(HealthNudgeKind.WATER, "Drink water", start = 450, end = 480),
                nudge(HealthNudgeKind.WALK, "Walk 7:30", start = 1110, end = 1290),
            ),
            dayState = emptyState(),
            todayIsoDate = today,
            nowMinuteOfDay = 460,
            mode = SnapshotMode.COMMUTE,
            audiobookPlaying = false,
        )
        assertEquals(
            listOf(NudgeKind.SUPPLEMENT_MORNING, NudgeKind.WALK),
            chrome.pills.map { it.kind },
        )
    }

    @Test
    fun resolveVisibleHealthChrome_audiobookSuppressesPillsAndCommuteLine() {
        val chrome = resolveVisibleHealthChrome(
            snapshotNudges = listOf(
                nudge(HealthNudgeKind.SUPPLEMENT_MORNING, "Vitamins + Cr"),
                nudge(HealthNudgeKind.MORNING_LIGHT, "", start = 420, end = 600),
            ),
            dayState = emptyState(),
            todayIsoDate = today,
            nowMinuteOfDay = 500,
            mode = SnapshotMode.COMMUTE,
            audiobookPlaying = true,
        )
        assertTrue(chrome.pills.isEmpty())
        assertNull(chrome.line)
    }

    @Test
    fun resolveVisibleHealthChrome_cardKeepsFocusAndLine() {
        val chrome = resolveVisibleHealthChrome(
            snapshotNudges = listOf(
                nudge(HealthNudgeKind.FOCUS_GAP, "Focus 45m", start = 600, end = 720),
                nudge(HealthNudgeKind.CAFFEINE_CUTOFF, "Coffee by 2:00 pm", start = 750, end = 840),
            ),
            dayState = emptyState(),
            todayIsoDate = today,
            nowMinuteOfDay = 610,
            mode = SnapshotMode.CALENDAR_EMPTY,
            audiobookPlaying = false,
        )
        assertEquals(listOf(NudgeKind.FOCUS_GAP), chrome.pills.map { it.kind })
        assertEquals(NudgeKind.CAFFEINE_CUTOFF, chrome.line?.kind)
    }

    @Test
    fun resolveVisibleHealthChrome_failureCommuteModeStillMapsToCommuteSurface() {
        val chrome = resolveVisibleHealthChrome(
            snapshotNudges = listOf(nudge(HealthNudgeKind.SUPPLEMENT_PROTEIN, "Protein")),
            dayState = emptyState(),
            todayIsoDate = today,
            nowMinuteOfDay = 1100,
            mode = SnapshotMode.COMMUTE,
            audiobookPlaying = false,
        )
        assertEquals(listOf(NudgeKind.SUPPLEMENT_PROTEIN), chrome.pills.map { it.kind })
    }

    @Test
    fun applySupplementTaken_isIdempotentAndDateScoped() {
        val first = applySupplementTaken(null, today, SUPPLEMENT_KIND_MORNING, 430)
        val second = applySupplementTaken(first, today, SUPPLEMENT_KIND_MORNING, 500)
        assertEquals(430, first.morningSupplementsTakenMinute)
        assertEquals(430, second.morningSupplementsTakenMinute)
        val yesterday = HealthDayState(date = "2026-08-30", morningSupplementsTakenMinute = 400)
        val rolled = applySupplementTaken(yesterday, today, SUPPLEMENT_KIND_PROTEIN, 1100)
        assertEquals(today, rolled.date)
        assertNull(rolled.morningSupplementsTakenMinute)
        assertEquals(1100, rolled.proteinTakenMinute)
    }

    @Test
    fun applyWaterTap_deduplicatesMinutes() {
        val first = applyWaterTap(null, today, 460)
        val duplicate = applyWaterTap(first, today, 460)
        val secondSlot = applyWaterTap(duplicate, today, 630)
        assertEquals(listOf(460), first.waterTapMinutes)
        assertEquals(listOf(460), duplicate.waterTapMinutes)
        assertEquals(listOf(460, 630), secondSlot.waterTapMinutes)
    }

    @Test
    fun applyWalkDismissed_isIdempotent() {
        val first = applyWalkDismissed(null, today)
        val second = applyWalkDismissed(first, today)
        assertTrue(first.walkDismissed)
        assertTrue(second.walkDismissed)
    }

    @Test
    fun applyFocusGapDismissed_deduplicatesStartMinutes() {
        val first = applyFocusGapDismissed(null, today, 600)
        val duplicate = applyFocusGapDismissed(first, today, 600)
        val extra = applyFocusGapDismissed(duplicate, today, 720)
        assertEquals(listOf(600), first.dismissedFocusGapStartMinutes)
        assertEquals(listOf(600), duplicate.dismissedFocusGapStartMinutes)
        assertEquals(listOf(600, 720), extra.dismissedFocusGapStartMinutes)
    }

    @Test
    fun showsHealthChrome_hidesSmallAndKeepsWide() {
        assertFalse(showsHealthChrome(110))
        assertTrue(showsHealthChrome(220))
    }

    @Test
    fun shouldShowTodayBrief_allowsSleepOnlyWhenEnabled() {
        val snapshot = emptySnapshot().copy(
            mode = SnapshotMode.COMMUTE,
            direction = Direction.TO_WORK,
            todayEventCount = 0,
            sleepEstimateMinutes = 400,
        )
        assertTrue(shouldShowTodayBrief(snapshot, captionAllowedForSize = true, sleepBriefEnabled = true))
        assertFalse(shouldShowTodayBrief(snapshot, captionAllowedForSize = true, sleepBriefEnabled = false))
    }

    @Test
    fun applySupplementTaken_unknownKindLeavesStateUnchanged() {
        val original = emptyState()
        val result = applySupplementTaken(original, today, "UNKNOWN", 400)
        assertEquals(original, result)
    }

    private fun emptyState(): HealthDayState = HealthDayState(date = today)

    private fun nudge(
        kind: HealthNudgeKind,
        label: String = kind.name,
        start: Int = 0,
        end: Int = 24 * 60,
        demoted: Boolean = false,
    ): HealthNudge = HealthNudge(
        kind = kind,
        label = label,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        demoted = demoted,
    )

    private fun emptySnapshot(): CommuteSnapshot = CommuteSnapshot(
        direction = Direction.TO_WORK,
        durationSeconds = 0L,
        durationNoTrafficSeconds = 0L,
        distanceMeters = 0L,
        mapImagePath = null,
        fetchedAtEpochMillis = 0L,
        lastFetchFailed = false,
        lastErrorMessage = null,
    )

    private fun epoch(zone: ZoneId, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2026, 8, 26, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    }
}
