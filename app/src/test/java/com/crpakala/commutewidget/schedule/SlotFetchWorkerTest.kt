package com.crpakala.commutewidget.schedule

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotFetchWorkerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val defaultDays = setOf(1, 2, 3, 4, 5)
    private val defaultSlots = listOf(420..600, 1020..1200)

    @Test
    fun nextSlotTick_insideMorningSlot_returnsNowPlusInterval() {
        val now = at(2026, 8, 24, 8, 0) // Monday, 480 minutes

        val next = nextSlotTick(now, defaultDays, defaultSlots)

        assertEquals(at(2026, 8, 24, 8, 10), next)
    }

    @Test
    fun nextSlotTick_lastTickOfSlot_returnsExactSlotEnd() {
        val now = at(2026, 8, 24, 9, 50) // 590 minutes, tick lands exactly on slot end (600)

        val next = nextSlotTick(now, defaultDays, defaultSlots)

        assertEquals(at(2026, 8, 24, 10, 0), next)
    }

    @Test
    fun nextSlotTick_tickPastSlotEnd_jumpsToEveningSlotSameDay() {
        val now = at(2026, 8, 24, 10, 0) // 600 minutes: inside slot (inclusive end), tick would be 610

        val next = nextSlotTick(now, defaultDays, defaultSlots)

        assertEquals(at(2026, 8, 24, 17, 0), next)
    }

    @Test
    fun nextSlotTick_betweenSlotsSameDay_returnsNextSlotStart() {
        val now = at(2026, 8, 24, 10, 10) // 610 minutes: between morning and evening slots

        val next = nextSlotTick(now, defaultDays, defaultSlots)

        assertEquals(at(2026, 8, 24, 17, 0), next)
    }

    @Test
    fun nextSlotTick_afterEveningSlot_movesToNextEnabledDay() {
        val now = at(2026, 8, 24, 20, 1) // Monday, past evening slot end

        val next = nextSlotTick(now, defaultDays, defaultSlots)

        assertEquals(at(2026, 8, 25, 7, 0), next) // Tuesday morning slot start
    }

    @Test
    fun nextSlotTick_fridayEveningPastSlot_wrapsToMondayMorning() {
        val now = at(2026, 8, 21, 20, 1) // Friday, past evening slot end

        val next = nextSlotTick(now, defaultDays, defaultSlots)

        assertEquals(at(2026, 8, 24, 7, 0), next) // Monday morning slot start
    }

    @Test
    fun nextSlotTick_dayNotInHistoryDays_skipsThatDayEntirely() {
        val days = setOf(1, 2, 4, 5) // Wednesday (3) excluded
        val now = at(2026, 8, 26, 8, 0) // Wednesday, would otherwise be inside the morning slot

        val next = nextSlotTick(now, days, defaultSlots)

        assertEquals(at(2026, 8, 27, 7, 0), next) // Thursday morning slot start
    }

    @Test
    fun nextSlotTick_emptyHistoryDays_returnsNull() {
        val next = nextSlotTick(at(2026, 8, 24, 8, 0), emptySet(), defaultSlots)

        assertNull(next)
    }

    @Test
    fun nextSlotTick_allInvalidSlotRanges_returnsNull() {
        val invalidSlots = listOf(600..500, 700..700)

        val next = nextSlotTick(at(2026, 8, 24, 8, 0), defaultDays, invalidSlots)

        assertNull(next)
    }

    @Test
    fun nextSlotTick_invalidSlotRangeIsSkippedButValidOneStillUsed() {
        val mixedSlots = listOf(500..500, 420..600) // first entry invalid (start == end)

        val next = nextSlotTick(at(2026, 8, 24, 8, 0), defaultDays, mixedSlots)

        assertEquals(at(2026, 8, 24, 8, 10), next)
    }

    @Test
    fun isWithinSlotFetchWindow_justInsideMarginBeforeSlotStart_isTrue() {
        assertTrue(isWithinSlotFetchWindow(todayIso = 1, nowMinuteOfDay = 415, historyDays = defaultDays, slots = defaultSlots))
    }

    @Test
    fun isWithinSlotFetchWindow_justInsideMarginAfterSlotEnd_isTrue() {
        assertTrue(isWithinSlotFetchWindow(todayIso = 1, nowMinuteOfDay = 605, historyDays = defaultDays, slots = defaultSlots))
    }

    @Test
    fun isWithinSlotFetchWindow_outsideMargin_isFalse() {
        assertFalse(isWithinSlotFetchWindow(todayIso = 1, nowMinuteOfDay = 700, historyDays = defaultDays, slots = defaultSlots))
    }

    @Test
    fun isWithinSlotFetchWindow_dayNotEnabled_isFalseEvenInsideSlot() {
        assertFalse(isWithinSlotFetchWindow(todayIso = 6, nowMinuteOfDay = 480, historyDays = defaultDays, slots = defaultSlots))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
}
