package com.crpakala.commutewidget.engine.health

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkAndSunsetLogicTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 6, 24)
    private val params = HealthParams()

    @Test
    fun goalMetAndAfternoonActive_returnsNull() {
        assertNull(suggest(stepsToday = 8_000, stepsSinceNoon = 2_000))
    }

    @Test
    fun sedentarySignalBeforeFourPm_doesNotTrigger() {
        assertNull(
            suggest(
                stepsToday = null,
                stepsSinceNoon = 500,
                nowMinute = 15 * 60 + 59,
            ),
        )
    }

    @Test
    fun sedentarySignalWithNoDailyTotal_usesAfternoonDeficit() {
        val result = suggest(stepsToday = null, stepsSinceNoon = 500, nowMinute = 16 * 60)

        assertEquals(WalkSuggestion(1_080, 15), result)
    }

    @Test
    fun sedentarySignalStillTriggersAfterDailyGoalWasMet() {
        val result = suggest(stepsToday = 8_100, stepsSinceNoon = 500, nowMinute = 16 * 60)

        assertEquals(WalkSuggestion(1_080, 15), result)
    }

    @Test
    fun durationBelowTenMinutes_skipsSuggestion() {
        assertNull(suggest(stepsToday = 7_600, stepsSinceNoon = 2_000))
    }

    @Test
    fun durationRoundsUpToNearestFiveMinutes() {
        val result = suggest(stepsToday = 7_099, stepsSinceNoon = 2_000)

        assertEquals(15, result?.durationMinutes)
    }

    @Test
    fun durationIsCappedAtFortyFiveMinutes() {
        val result = suggest(stepsToday = 0, stepsSinceNoon = 0)

        assertEquals(45, result?.durationMinutes)
    }

    @Test
    fun toHomeWindow_isExcludedFromSlotSearch() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            toHomeWindow = 1_020..1_200,
        )

        assertEquals(1_200, result?.startMinuteOfDay)
    }

    @Test
    fun calendarMeeting_isExcludedFromSlotSearch() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            events = listOf(event(18, 0, 19, 0)),
        )

        assertEquals(1_140, result?.startMinuteOfDay)
    }

    @Test
    fun walkMayFinishExactlyAtBedtimeBuffer() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            bedtime = 20 * 60,
        )

        assertEquals(1_080, result?.startMinuteOfDay)
    }

    @Test
    fun bedtimeBufferCanEliminateEverySlot() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            bedtime = 19 * 60 + 20,
        )

        assertNull(result)
    }

    @Test
    fun afterMidnightBedtime_isNormalizedIntoFollowingDay() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            bedtime = 30,
        )

        assertEquals(1_080, result?.startMinuteOfDay)
    }

    @Test
    fun daylightPreference_selectsLatestSlotFinishingBySunset() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            sunset = 19 * 60,
            daylight = true,
        )

        assertEquals(18 * 60 + 30, result?.startMinuteOfDay)
    }

    @Test
    fun daylightDisabled_selectsEarliestValidSlot() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            sunset = 19 * 60,
            daylight = false,
        )

        assertEquals(1_080, result?.startMinuteOfDay)
    }

    @Test
    fun noDaylightSlot_fallsBackToEarliestValidSlot() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            sunset = 17 * 60,
            daylight = true,
        )

        assertEquals(1_080, result?.startMinuteOfDay)
    }

    @Test
    fun arrivalLatch_startsTenMinutesAfterAudibleStopsAndRoundsUp() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            audibleStopped = 18 * 60 + 7,
            latch = true,
        )

        assertEquals(18 * 60 + 20, result?.startMinuteOfDay)
    }

    @Test
    fun audibleStopBeforeFivePm_doesNotActivateLatch() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            audibleStopped = 16 * 60 + 59,
            latch = true,
        )

        assertEquals(1_080, result?.startMinuteOfDay)
    }

    @Test
    fun latchAndDaylight_chooseLatestPostArrivalDaylightSlot() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            audibleStopped = 18 * 60 + 30,
            sunset = 19 * 60 + 15,
            latch = true,
            daylight = true,
        )

        assertEquals(18 * 60 + 45, result?.startMinuteOfDay)
    }

    @Test
    fun fullEveningCalendar_returnsNull() {
        val result = suggest(
            stepsToday = 5_300,
            stepsSinceNoon = 1_000,
            events = listOf(event(18, 0, 21, 30)),
        )

        assertNull(result)
    }

    @Test
    fun hyderabadLateJuneSunset_isWithinEightMinutesOfSixFiftyPm() {
        val sunset = localSunsetMinuteOfDay(17.4, 78.5, LocalDate.of(2026, 6, 24), zone)

        assertTrue("sunset=$sunset", sunset in (18 * 60 + 42)..(18 * 60 + 58))
    }

    @Test
    fun londonMidsummerSunset_isWithinTenMinutesOfNineTwentyPm() {
        val sunset = localSunsetMinuteOfDay(
            51.5074,
            -0.1278,
            LocalDate.of(2026, 6, 21),
            ZoneId.of("Europe/London"),
        )

        assertTrue("sunset=$sunset", sunset in (21 * 60 + 10)..(21 * 60 + 30))
    }

    @Test
    fun polarDay_returnsNullSunset() {
        assertNull(
            localSunsetMinuteOfDay(
                69.6492,
                18.9553,
                LocalDate.of(2026, 6, 21),
                ZoneId.of("Europe/Oslo"),
            ),
        )
    }

    @Test
    fun invalidLatitude_returnsNullSunset() {
        assertNull(localSunsetMinuteOfDay(91.0, 0.0, date, zone))
    }

    private fun suggest(
        stepsToday: Long?,
        stepsSinceNoon: Long?,
        events: List<EventSpan> = emptyList(),
        toHomeWindow: IntRange = IntRange.EMPTY,
        bedtime: Int? = null,
        sunset: Int? = null,
        audibleStopped: Int? = null,
        nowMinute: Int = 17 * 60,
        latch: Boolean = false,
        daylight: Boolean = false,
    ): WalkSuggestion? = suggestWalk(
        stepsToday = stepsToday,
        stepGoal = 8_000,
        stepsSinceNoon = stepsSinceNoon,
        events = events,
        toHomeWindow = toHomeWindow,
        typicalBedtimeMinute = bedtime,
        sunsetMinuteOfDay = sunset,
        audibleStoppedAtMinute = audibleStopped,
        nowMinuteOfDay = nowMinute,
        params = params,
        latchEnabled = latch,
        daylightEnabled = daylight,
        zone = zone,
    )

    private fun event(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): EventSpan =
        EventSpan(epoch(startHour, startMinute), epoch(endHour, endMinute))

    private fun epoch(hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
