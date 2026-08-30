package com.crpakala.commutewidget.schedule

import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.HealthDayState
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [nextHealthBoundary] decides when [HealthBoundaryWorker] next wakes up to recompute health
 * nudge state - the earliest minute-of-day, across every enabled feature's next possibly-visible-
 * state-changing instant, strictly after now; local midnight + 1 minute when none remains today.
 */
class HealthBoundaryWorkerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val allDisabled = AppSettings(
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

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 8, 31, hour, minute, 0, 0, zone)

    @Test
    fun everyFeatureDisabled_fallsBackToNextLocalMidnightPlusOneMinute() {
        val result = nextHealthBoundary(at(14, 0), allDisabled, dayState = null)

        assertEquals(ZonedDateTime.of(2026, 9, 1, 0, 1, 0, 0, zone), result)
    }

    @Test
    fun waterSlotsEnabled_wakesAtNearestUpcomingSlotStartOrEnd() {
        val settings = allDisabled.copy(waterRemindersEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31", waterSlotPlanMinutes = listOf(480, 600, 720))
        // Candidates (start, start+30): 480, 510, 600, 630, 720, 750. Now is 500 minutes (8:20).
        val result = nextHealthBoundary(at(8, 20), settings, dayState)

        assertEquals(at(8, 30), result) // 510 minutes = the 480 slot's active-window end
    }

    @Test
    fun morningSupplementsNotYetTaken_wakesAtWindowStart() {
        val settings = allDisabled.copy(morningSupplementsEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31") // morningSupplementsTakenMinute == null

        val result = nextHealthBoundary(at(6, 0), settings, dayState)

        assertEquals(at(7, 0), result) // AppSettings default morningSupplementsStartMinuteOfDay = 420
    }

    @Test
    fun morningSupplementsAlreadyTaken_contributesNoBoundary() {
        val settings = allDisabled.copy(morningSupplementsEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31", morningSupplementsTakenMinute = 425)

        val result = nextHealthBoundary(at(6, 0), settings, dayState)

        // Falls through to the day-rollover fallback: no other feature is enabled.
        assertEquals(ZonedDateTime.of(2026, 9, 1, 0, 1, 0, 0, zone), result)
    }

    @Test
    fun eveningWalkEnabledWithFutureTarget_wakesAtWalkStart() {
        val settings = allDisabled.copy(eveningWalkEnabled = true)

        val result = nextHealthBoundary(at(17, 0), settings, dayState = null, walkTargetMinuteOfDay = 19 * 60)

        assertEquals(at(19, 0), result)
    }

    @Test
    fun restlessNightShieldEnabled_wakesAtShieldDefaultEndMinute() {
        val settings = allDisabled.copy(restlessNightShieldEnabled = true)

        val result = nextHealthBoundary(at(6, 0), settings, dayState = null)

        assertEquals(at(10, 0), result) // HealthParams default focusShieldNoEventEndMinuteOfDay = 10*60
    }

    @Test
    fun caffeineCutoffEnabled_wakesAtLeadWindowStartThenCutoff() {
        val settings = allDisabled.copy(caffeineCutoffLineEnabled = true)
        // AppSettings default caffeineCutoffMinuteOfDay = 840 (14:00); lead = 90 minutes -> 750 (12:30).

        val beforeLead = nextHealthBoundary(at(11, 0), settings, dayState = null)
        assertEquals(at(12, 30), beforeLead)

        val afterLeadBeforeCutoff = nextHealthBoundary(at(13, 0), settings, dayState = null)
        assertEquals(at(14, 0), afterLeadBeforeCutoff)
    }

    @Test
    fun allBoundariesPassedToday_fallsBackToMidnightRollover() {
        val settings = allDisabled.copy(morningSupplementsEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31")

        // 21:31 is past the 21:30 supplement cutoff.
        val result = nextHealthBoundary(at(21, 31), settings, dayState)

        assertEquals(ZonedDateTime.of(2026, 9, 1, 0, 1, 0, 0, zone), result)
    }

    @Test
    fun multipleFeaturesEnabled_picksTheEarliestAcrossAllOfThem() {
        val settings = allDisabled.copy(
            morningSupplementsEnabled = true,
            caffeineCutoffLineEnabled = true,
        )
        val dayState = HealthDayState(date = "2026-08-31")

        val result = nextHealthBoundary(at(6, 0), settings, dayState)

        assertEquals(at(7, 0), result) // supplement window start (420) beats caffeine lead start (750)
    }
}
