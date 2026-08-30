package com.crpakala.commutewidget.engine.health

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterLogicTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 6, 24)
    private val dayStart = epoch(0, 0)

    @Test
    fun defaultPlan_usesFiveFixedAnchors() {
        val result = plan(events = emptyList())

        assertEquals(listOf(450, 630, 810, 990, 1_170), result)
    }

    @Test
    fun threeSlots_areEvenlyDistributedAcrossAnchorRange() {
        assertEquals(listOf(450, 810, 1_170), plan(count = 3, events = emptyList()))
    }

    @Test
    fun oneSlot_usesRangeMidpoint() {
        assertEquals(listOf(810), plan(count = 1, events = emptyList()))
    }

    @Test
    fun zeroSlots_returnsEmptyPlan() {
        assertTrue(plan(count = 0, events = emptyList()).isEmpty())
    }

    @Test
    fun slotInsideMeeting_movesToFiveMinutesAfterMeeting() {
        val result = plan(events = listOf(event(10, 15, 11, 0)))

        assertEquals(665, result[1])
    }

    @Test
    fun meetingOverlappingActiveWindow_movesSlotEvenWhenStartIsFree() {
        val result = plan(events = listOf(event(10, 50, 11, 10)))

        assertEquals(675, result[1])
    }

    @Test
    fun collisionShift_enforcesNinetyMinutesBeforeFollowingSlot() {
        val result = plan(events = listOf(event(7, 0, 9, 45)))

        assertEquals(listOf(590, 680, 810, 990, 1_170), result)
    }

    @Test
    fun packedCalendar_dropsAllSlotsThatOverflowCutoff() {
        val result = plan(events = listOf(event(7, 0, 19, 58)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun slotExactlyAtEightPm_isAllowed() {
        val result = plan(events = listOf(event(19, 0, 19, 55)))

        assertEquals(1_200, result.last())
    }

    @Test
    fun slotAfterEightPm_isDropped() {
        val result = plan(events = listOf(event(19, 0, 19, 56)))

        assertEquals(listOf(450, 630, 810, 990), result)
    }

    @Test
    fun pastSlotsRemainInDailyPlan() {
        val result = plan(events = emptyList(), nowHour = 18)

        assertEquals(5, result.size)
        assertEquals(450, result.first())
    }

    @Test
    fun activeSlot_isReturnedAtWindowStart() {
        assertEquals(450, waterSlotActiveAt(listOf(450), emptyList(), null, 450))
    }

    @Test
    fun activeSlot_isReturnedInsideWindow() {
        assertEquals(450, waterSlotActiveAt(listOf(450), emptyList(), null, 479))
    }

    @Test
    fun slotExpiresAtThirtyMinutes() {
        assertNull(waterSlotActiveAt(listOf(450), emptyList(), null, 480))
    }

    @Test
    fun tapRecordedAtSlotStart_suppressesSlot() {
        assertNull(waterSlotActiveAt(listOf(450), listOf(450), null, 455))
    }

    @Test
    fun tapRecordedInsideSlotWindow_suppressesSlot() {
        assertNull(waterSlotActiveAt(listOf(450), listOf(460), null, 470))
    }

    @Test
    fun recentPreviousOpportunity_blocksActiveSlot() {
        assertNull(waterSlotActiveAt(listOf(630), emptyList(), 600, 630))
    }

    @Test
    fun opportunityExactlyNinetyMinutesEarlier_allowsSlot() {
        assertEquals(630, waterSlotActiveAt(listOf(630), emptyList(), 540, 630))
    }

    @Test
    fun markerForCurrentSlot_keepsUntappedSlotVisible() {
        assertEquals(630, waterSlotActiveAt(listOf(630), emptyList(), 635, 640))
    }

    @Test
    fun recentExerciseWithFreeGap_createsPulseNow() {
        val result = pulse(
            exercise = listOf(event(13, 0, 13, 30)),
            events = emptyList(),
            plan = listOf(450),
        )

        assertEquals(840, result)
    }

    @Test
    fun exerciseExactlyNinetyMinutesAgo_isEligible() {
        val result = pulse(
            exercise = listOf(event(12, 0, 12, 30)),
            events = emptyList(),
            plan = listOf(450),
        )

        assertEquals(840, result)
    }

    @Test
    fun exerciseOlderThanNinetyMinutes_isIneligible() {
        val result = pulse(
            exercise = listOf(event(12, 0, 12, 29)),
            events = emptyList(),
            plan = listOf(450),
        )

        assertNull(result)
    }

    @Test
    fun activePlanSlot_blocksPulse() {
        val result = pulse(
            exercise = listOf(event(13, 0, 13, 30)),
            events = emptyList(),
            plan = listOf(840),
        )

        assertNull(result)
    }

    @Test
    fun meetingWithinNextFifteenMinutes_blocksPulse() {
        val result = pulse(
            exercise = listOf(event(13, 0, 13, 30)),
            events = listOf(event(14, 10, 14, 30)),
            plan = listOf(450),
        )

        assertNull(result)
    }

    @Test
    fun meetingStartingAtGapBoundary_doesNotBlockPulse() {
        val result = pulse(
            exercise = listOf(event(13, 0, 13, 30)),
            events = listOf(event(14, 15, 14, 30)),
            plan = listOf(450),
        )

        assertEquals(840, result)
    }

    @Test
    fun recentPlannedOpportunity_blocksPulse() {
        val result = pulse(
            exercise = listOf(event(13, 0, 13, 30)),
            events = emptyList(),
            plan = listOf(780),
        )

        assertNull(result)
    }

    @Test
    fun recentTap_blocksPulse() {
        val result = pulse(
            exercise = listOf(event(13, 0, 13, 30)),
            events = emptyList(),
            plan = listOf(450),
            taps = listOf(800),
        )

        assertNull(result)
    }

    @Test
    fun pulseAlreadyShownToday_blocksAnotherPulse() {
        val result = pulse(
            exercise = listOf(event(13, 0, 13, 30)),
            events = emptyList(),
            plan = listOf(450),
            alreadyShown = 700,
        )

        assertNull(result)
    }

    private fun plan(
        count: Int = 5,
        events: List<EventSpan>,
        nowHour: Int = 6,
    ): List<Int> = planWaterSlots(
        count = count,
        events = events,
        dayEpochMillis = dayStart,
        zone = zone,
        nowEpochMillis = epoch(nowHour, 0),
    )

    private fun pulse(
        exercise: List<EventSpan>,
        events: List<EventSpan>,
        plan: List<Int>,
        taps: List<Int> = emptyList(),
        alreadyShown: Int? = null,
    ): Int? = waterPulseSlot(
        exerciseSessions = exercise,
        events = events,
        planMinutes = plan,
        tapMinutes = taps,
        pulseAlreadyShownMinute = alreadyShown,
        nowEpochMillis = epoch(14, 0),
        dayEpochMillis = dayStart,
        zone = zone,
    )

    private fun event(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): EventSpan =
        EventSpan(epoch(startHour, startMinute), epoch(endHour, endMinute))

    private fun epoch(hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
