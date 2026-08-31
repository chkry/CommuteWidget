package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CommuteSnapshot
import com.crpakala.commutewidget.data.CustomPillOccurrence
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.HealthDayRecord
import com.crpakala.commutewidget.data.HealthDayState
import com.crpakala.commutewidget.data.HealthNudge
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.engine.health.HealthParams
import com.crpakala.commutewidget.engine.health.NudgeKind
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [computeHealthState]'s Android-touching glue: gym-day detection,
 * the commute-audio latch, the day-record merge used by both the lazy sleep backfill and
 * [com.crpakala.commutewidget.schedule.HealthMorningWorker]'s daily run, and the
 * [withHealthComputation] snapshot rewrite shared by every non-route health persist path.
 */
class HealthNudgeComputerTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun epochAt(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 8, 31, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // withHealthComputation - the single health-fields rewrite (sprint 5 review blocker 1: the
    // boundary worker's hand-rolled copy silently dropped customPillOccurrences)

    @Test
    fun withHealthComputation_appliesEveryHealthFieldAndPreservesRouteFields() {
        val previous = CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 1200L,
            durationNoTrafficSeconds = 1000L,
            distanceMeters = 8000L,
            mapImagePath = "/cache/commute-map.png",
            fetchedAtEpochMillis = 1_700_000_000_000L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            leaveByMinuteOfDay = 540,
            healthNudges = listOf(
                HealthNudge(kind = HealthNudgeKind.WATER, label = "Water", startMinuteOfDay = 0, endMinuteOfDay = 1),
            ),
            sleepEstimateMinutes = 100,
            shortSleepDay = false,
            customPillOccurrences = emptyList(),
        )
        val computation = HealthComputation(
            healthNudges = emptyList(),
            sleepEstimateMinutes = 390,
            shortSleepDay = true,
            customPillOccurrences = listOf(
                CustomPillOccurrence(pillId = "p1", label = "Vitamin D", slotMinuteOfDay = 480, active = true),
            ),
        )

        val result = previous.withHealthComputation(computation)

        assertEquals(computation.healthNudges, result.healthNudges)
        assertEquals(computation.sleepEstimateMinutes, result.sleepEstimateMinutes)
        assertEquals(computation.shortSleepDay, result.shortSleepDay)
        assertEquals(computation.customPillOccurrences, result.customPillOccurrences)
        // Route/map/leave-by fields must be untouched by a health-only rewrite.
        assertEquals(previous.mapImagePath, result.mapImagePath)
        assertEquals(previous.durationSeconds, result.durationSeconds)
        assertEquals(previous.leaveByMinuteOfDay, result.leaveByMinuteOfDay)
        assertEquals(previous.fetchedAtEpochMillis, result.fetchedAtEpochMillis)
    }

    // gymDayDetected

    @Test
    fun sessionEndingAfterNoon_detectsGymDay() {
        assertTrue(gymDayDetected(listOf(epochAt(13, 0)), emptyList(), zone))
    }

    @Test
    fun sessionEndingExactlyAtNoon_detectsGymDay() {
        assertTrue(gymDayDetected(listOf(epochAt(12, 0)), emptyList(), zone))
    }

    @Test
    fun sessionEndingBeforeNoonAndNoGymTitle_doesNotDetectGymDay() {
        assertFalse(gymDayDetected(listOf(epochAt(9, 0)), emptyList(), zone))
    }

    @Test
    fun eventTitleMentionsGymCaseInsensitive_detectsGymDayRegardlessOfSessions() {
        assertTrue(gymDayDetected(emptyList(), listOf("Gym session"), zone))
        assertTrue(gymDayDetected(emptyList(), listOf("morning GYM"), zone))
    }

    @Test
    fun noMorningSessionsAndUnrelatedTitles_doesNotDetectGymDay() {
        assertFalse(gymDayDetected(listOf(epochAt(9, 0)), listOf("Standup", "1:1 with manager"), zone))
    }

    // nextAudibleLatchState

    @Test
    fun audioPlaying_latchesToNowRegardlessOfPreviousValue() {
        assertEquals(500, nextAudibleLatchState(audioPlaying = true, previousLastPlayingMinute = 100, nowMinuteOfDay = 500))
        assertEquals(500, nextAudibleLatchState(audioPlaying = true, previousLastPlayingMinute = null, nowMinuteOfDay = 500))
    }

    @Test
    fun audioNotPlaying_freezesAtPreviousValue() {
        assertEquals(100, nextAudibleLatchState(audioPlaying = false, previousLastPlayingMinute = 100, nowMinuteOfDay = 500))
    }

    @Test
    fun audioNotPlayingAndNoPreviousValue_staysNull() {
        assertNull(nextAudibleLatchState(audioPlaying = false, previousLastPlayingMinute = null, nowMinuteOfDay = 500))
    }

    // audibleStoppedAtMinuteFor

    @Test
    fun stoppedAndLatchAtOrAfterEligibleMinute_returnsLatch() {
        assertEquals(1050, audibleStoppedAtMinuteFor(audioPlaying = false, latchMinute = 1050, eligibleAfterMinuteOfDay = 1020))
        assertEquals(1020, audibleStoppedAtMinuteFor(audioPlaying = false, latchMinute = 1020, eligibleAfterMinuteOfDay = 1020))
    }

    @Test
    fun stillPlaying_returnsNullEvenIfLatchWouldOtherwiseBeEligible() {
        assertNull(audibleStoppedAtMinuteFor(audioPlaying = true, latchMinute = 1050, eligibleAfterMinuteOfDay = 1020))
    }

    @Test
    fun latchBeforeEligibleMinute_returnsNull() {
        assertNull(audibleStoppedAtMinuteFor(audioPlaying = false, latchMinute = 900, eligibleAfterMinuteOfDay = 1020))
    }

    @Test
    fun noLatchRecorded_returnsNull() {
        assertNull(audibleStoppedAtMinuteFor(audioPlaying = false, latchMinute = null, eligibleAfterMinuteOfDay = 1020))
    }

    // mergedDayRecord

    @Test
    fun noExisting_usesOnlySuppliedValues() {
        val result = mergedDayRecord(
            existing = null,
            date = "2026-08-31",
            steps = 5000,
            sleepMinutes = 420,
            overnightUnlockCount = 2,
            sleepStartEpochMillis = 123L,
        )

        assertEquals(
            HealthDayRecord(
                date = "2026-08-31",
                steps = 5000,
                sleepMinutes = 420,
                overnightUnlockCount = 2,
                sleepStartEpochMillis = 123L,
            ),
            result,
        )
    }

    @Test
    fun unsuppliedFields_preserveExistingValuesRatherThanClobbering() {
        val existing = HealthDayRecord(
            date = "2026-08-31",
            steps = 3000,
            sleepMinutes = 400,
            overnightUnlockCount = 1,
            sleepStartEpochMillis = 50L,
        )

        val result = mergedDayRecord(existing = existing, date = "2026-08-31", steps = 5000)

        assertEquals(5000, result.steps)
        assertEquals(400, result.sleepMinutes)
        assertEquals(1, result.overnightUnlockCount)
        assertEquals(50L, result.sleepStartEpochMillis)
    }

    @Test
    fun suppliedNonNullValues_overrideExisting() {
        val existing = HealthDayRecord(
            date = "2026-08-31",
            steps = 3000,
            sleepMinutes = 400,
            overnightUnlockCount = 1,
            sleepStartEpochMillis = 50L,
        )

        val result = mergedDayRecord(existing = existing, date = "2026-08-31", sleepMinutes = 450, sleepStartEpochMillis = 99L)

        assertEquals(3000, result.steps)
        assertEquals(450, result.sleepMinutes)
        assertEquals(1, result.overnightUnlockCount)
        assertEquals(99L, result.sleepStartEpochMillis)
    }

    // waterCandidate - Owner request 2026-08-31: an ongoing calendar meeting suppresses the water
    // pill (plan-driven or post-gym pulse) at computation time, mirroring the restless-night
    // shield's computation-time-only precedent.

    @Test
    fun ongoingMeeting_suppressesPlanDrivenWaterCandidate() {
        val settings = AppSettings(waterRemindersEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31", waterSlotPlanMinutes = listOf(480))

        val result = waterCandidate(settings, dayState, nowMinuteOfDay = 485, meetingOngoing = true, params = HealthParams())

        assertNull(result)
    }

    @Test
    fun finishedMeeting_doesNotSuppressPlanDrivenWaterCandidate() {
        val settings = AppSettings(waterRemindersEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31", waterSlotPlanMinutes = listOf(480))

        val result = waterCandidate(settings, dayState, nowMinuteOfDay = 485, meetingOngoing = false, params = HealthParams())

        assertEquals(NudgeKind.WATER, result?.kind)
        assertEquals(480, result?.startMinuteOfDay)
    }

    @Test
    fun ongoingMeeting_suppressesPostGymWaterPulseCandidate() {
        val settings = AppSettings(waterRemindersEnabled = false, postGymWaterPulseEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31", waterPulseShownMinute = 480)

        val result = waterCandidate(settings, dayState, nowMinuteOfDay = 485, meetingOngoing = true, params = HealthParams())

        assertNull(result)
    }

    @Test
    fun finishedMeeting_doesNotSuppressPostGymWaterPulseCandidate() {
        val settings = AppSettings(waterRemindersEnabled = false, postGymWaterPulseEnabled = true)
        val dayState = HealthDayState(date = "2026-08-31", waterPulseShownMinute = 480)

        val result = waterCandidate(settings, dayState, nowMinuteOfDay = 485, meetingOngoing = false, params = HealthParams())

        assertEquals(NudgeKind.WATER, result?.kind)
        assertEquals(480, result?.startMinuteOfDay)
    }

    // toHealthNudgeKind - Sprint 2 added SLEEP_TO_BED/SLEEP_WOKE_UP to the engine-to-data mapper.

    @Test
    fun toHealthNudgeKind_mapsSleepToBedAndWokeUp() {
        assertEquals(HealthNudgeKind.SLEEP_TO_BED, NudgeKind.SLEEP_TO_BED.toHealthNudgeKind())
        assertEquals(HealthNudgeKind.SLEEP_WOKE_UP, NudgeKind.SLEEP_WOKE_UP.toHealthNudgeKind())
    }
}
