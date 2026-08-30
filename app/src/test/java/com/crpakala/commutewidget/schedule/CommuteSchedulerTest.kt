package com.crpakala.commutewidget.schedule

import com.crpakala.commutewidget.data.AppSettings
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 replaces the v2 fixed-time [nextWeekdayOccurrence]-driven morning/evening refreshes (which
 * depended on the now-retired `morningRefreshMinuteOfDay`/`eveningRefreshMinuteOfDay` settings)
 * with [nextWindowBoundary], the pure function driving [WindowBoundaryWorker]'s self-reschedule.
 */
class CommuteSchedulerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val morningStart = 420
    private val morningEnd = 600
    private val eveningStart = 1020
    private val eveningEnd = 1200

    private fun boundary(now: ZonedDateTime, commuteDays: Set<Int> = weekdays) = nextWindowBoundary(
        now = now,
        commuteDays = commuteDays,
        morningStart = morningStart,
        morningEnd = morningEnd,
        eveningStart = eveningStart,
        eveningEnd = eveningEnd,
    )

    @Test
    fun beforeMorningStart_returnsMorningStartToday() {
        val now = at(2026, 8, 24, 6, 0) // Monday, before 420

        assertEquals(at(2026, 8, 24, 7, 0), boundary(now))
    }

    @Test
    fun insideMorningWindow_returnsMorningEndToday() {
        val now = at(2026, 8, 24, 8, 0) // Monday, 480 minutes: inside 420..600

        assertEquals(at(2026, 8, 24, 10, 0), boundary(now))
    }

    @Test
    fun betweenWindows_returnsEveningStartToday() {
        val now = at(2026, 8, 24, 10, 30) // Monday, 630 minutes: between windows

        assertEquals(at(2026, 8, 24, 17, 0), boundary(now))
    }

    @Test
    fun insideEveningWindow_returnsEveningEndToday() {
        val now = at(2026, 8, 24, 18, 0) // Monday, 1080 minutes: inside 1020..1200

        assertEquals(at(2026, 8, 24, 20, 0), boundary(now))
    }

    @Test
    fun afterEveningWindow_movesToNextEnabledDayMorningStart() {
        val now = at(2026, 8, 24, 20, 1) // Monday, just past evening end

        assertEquals(at(2026, 8, 25, 7, 0), boundary(now))
    }

    @Test
    fun fridayAfterEveningWindow_wrapsToMondayMorningStart() {
        val now = at(2026, 8, 21, 20, 1) // Friday, just past evening end

        assertEquals(at(2026, 8, 24, 7, 0), boundary(now))
    }

    @Test
    fun emptyCommuteDays_returnsNull() {
        assertNull(boundary(at(2026, 8, 24, 8, 0), commuteDays = emptySet()))
    }

    @Test
    fun bothWindowsInvalid_returnsNull() {
        val result = nextWindowBoundary(
            now = at(2026, 8, 24, 8, 0),
            commuteDays = weekdays,
            morningStart = 420,
            morningEnd = 420,
            eveningStart = 1200,
            eveningEnd = 1020,
        )

        assertNull(result)
    }

    @Test
    fun overlappingWindows_allFourBoundariesConsidered() {
        // Morning 420..700 and evening 500..1200 overlap; boundaries are 420, 500, 700, 1200.
        val result = nextWindowBoundary(
            now = at(2026, 8, 24, 8, 0), // 480 minutes: past 420, before 500
            commuteDays = weekdays,
            morningStart = 420,
            morningEnd = 700,
            eveningStart = 500,
            eveningEnd = 1200,
        )

        assertEquals(at(2026, 8, 24, 8, 20), result) // 500 minutes
    }

    @Test
    fun dayNotInCommuteDays_isSkippedEntirely() {
        val days = setOf(1, 2, 4, 5) // Wednesday (3) excluded
        val now = at(2026, 8, 26, 8, 0) // Wednesday, would otherwise be inside the morning window

        assertEquals(at(2026, 8, 27, 7, 0), boundary(now, commuteDays = days)) // Thursday morning start
    }

    @Test
    fun exactlyAtABoundaryMinute_movesToNextBoundaryNotSameOne() {
        val now = at(2026, 8, 24, 7, 0) // exactly morning start (420)

        assertEquals(at(2026, 8, 24, 10, 0), boundary(now)) // next boundary is morning end (600)
    }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    /**
     * Sprint 2: [CommuteScheduler.anyHealthFeatureEnabled] gates whether "health_morning" and
     * "health_boundary" are armed at all - every independent nudge-introducing toggle must arm
     * it alone, and sub-toggles that only modify an already-gated feature must not.
     */
    private val allHealthFeaturesDisabled = AppSettings(
        morningSupplementsEnabled = false,
        eveningProteinEnabled = false,
        waterRemindersEnabled = false,
        eveningWalkEnabled = false,
        sleepBriefEnabled = false,
        restlessNightShieldEnabled = false,
        focusGapChipEnabled = false,
        postGymWaterPulseEnabled = false,
        morningLightLineEnabled = false,
        caffeineCutoffLineEnabled = false,
    )

    @Test
    fun allHealthFeaturesDisabled_isNotEnabled() {
        assertFalse(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled))
    }

    @Test
    fun defaultSettings_isEnabled() {
        // AppSettings defaults several health toggles to true.
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(AppSettings()))
    }

    @Test
    fun eachIndependentToggle_aloneIsEnoughToEnable() {
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(morningSupplementsEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(eveningProteinEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(waterRemindersEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(eveningWalkEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(sleepBriefEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(restlessNightShieldEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(focusGapChipEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(postGymWaterPulseEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(morningLightLineEnabled = true)))
        assertTrue(CommuteScheduler.anyHealthFeatureEnabled(allHealthFeaturesDisabled.copy(caffeineCutoffLineEnabled = true)))
    }

    @Test
    fun subTogglesAloneDoNotEnable_theirOwningFeatureMustAlsoBeOn() {
        val result = allHealthFeaturesDisabled.copy(
            gymProteinPriorityEnabled = true,
            sleepDebtSoftenEnabled = true,
            walkPostAudibleLatchEnabled = true,
            walkDaylightPreferenceEnabled = true,
            audiobookSuppressionEnabled = true,
        )

        assertFalse(CommuteScheduler.anyHealthFeatureEnabled(result))
    }
}
