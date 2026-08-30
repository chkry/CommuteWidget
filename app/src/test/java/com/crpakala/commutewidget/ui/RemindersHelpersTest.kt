package com.crpakala.commutewidget.ui

import com.crpakala.commutewidget.data.CustomPill
import com.crpakala.commutewidget.data.HealthDayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersHelpersTest {
    @Test
    fun validateReminderNameRequiresNonBlankName() {
        assertEquals("Name is required", validateReminderName("", existingNames = emptyList()))
        assertEquals("Name is required", validateReminderName("   ", existingNames = emptyList()))
    }

    @Test
    fun validateReminderNameEnforcesMaxLength() {
        val name13Chars = "1234567890123"
        assertEquals(13, name13Chars.length)
        assertEquals(
            "Name must be 12 characters or fewer",
            validateReminderName(name13Chars, existingNames = emptyList()),
        )
        assertNull(validateReminderName("123456789012", existingNames = emptyList()))
    }

    @Test
    fun validateReminderNameRejectsCaseInsensitiveDuplicates() {
        assertEquals(
            "Name already exists",
            validateReminderName("Vitamins", existingNames = listOf("vitamins")),
        )
        assertNull(validateReminderName("Water", existingNames = listOf("Vitamins")))
    }

    @Test
    fun canAddReminderRespectsMaxPillsCap() {
        assertTrue(canAddReminder(CustomPill.MAX_PILLS - 1))
        assertFalse(canAddReminder(CustomPill.MAX_PILLS))
    }

    @Test
    fun canAddSlotRejectsDuplicatesAndRespectsCap() {
        assertTrue(canAddSlot(emptyList(), 480))
        assertFalse(canAddSlot(listOf(480), 480))
        val fullSlots = List(CustomPill.MAX_SLOTS_PER_PILL) { it * 60 }
        assertFalse(canAddSlot(fullSlots, 999))
    }

    @Test
    fun addSlotKeepsListSorted() {
        assertEquals(listOf(60, 480, 600), addSlot(listOf(600, 60), 480))
    }

    @Test
    fun addSlotThrowsWhenNotAllowed() {
        try {
            addSlot(listOf(480), 480)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun removeSlotDropsOnlyMatchingMinute() {
        assertEquals(listOf(60, 600), removeSlot(listOf(60, 480, 600), 480))
        assertEquals(listOf(60, 480, 600), removeSlot(listOf(60, 480, 600), 999))
    }

    @Test
    fun toggleReminderDayAddsAndRemovesDays() {
        assertEquals(setOf(1, 2, 3), toggleReminderDay(setOf(1, 2), 3))
        assertEquals(setOf(1), toggleReminderDay(setOf(1, 2), 2))
    }

    @Test
    fun toggleReminderDayRefusesToDropTheLastDay() {
        assertEquals(setOf(1), toggleReminderDay(setOf(1), 1))
    }

    @Test
    fun isReminderFormValidRequiresNameDaysAndSlots() {
        assertTrue(isReminderFormValid("Vitamins", emptyList(), setOf(1), listOf(480)))
        assertFalse(isReminderFormValid("", emptyList(), setOf(1), listOf(480)))
        assertFalse(isReminderFormValid("Vitamins", emptyList(), emptySet(), listOf(480)))
        assertFalse(isReminderFormValid("Vitamins", emptyList(), setOf(1), emptyList()))
    }

    @Test
    fun pruneCustomPillTakenSlotsRemovesOnlyMatchingPillEntries() {
        val taken = setOf("abc:480", "abc2:480", "abc:600", "xyz:60")
        assertEquals(setOf("abc2:480", "xyz:60"), pruneCustomPillTakenSlots(taken, "abc"))
    }

    @Test
    fun pruneCustomPillTakenSlotsIsNoOpWhenNoMatch() {
        val taken = setOf("xyz:60")
        assertEquals(taken, pruneCustomPillTakenSlots(taken, "abc"))
    }

    // clearTodayCustomPillDismissals - the "Reset today's dismissed reminders" action

    @Test
    fun clearTodayDismissals_clearsOnlyTheTakenSlotsAndPreservesEveryOtherField() {
        val state = HealthDayState(
            date = "2026-08-31",
            waterSlotPlanMinutes = listOf(480, 600),
            waterTapMinutes = listOf(485),
            walkDismissed = true,
            customPillTakenSlots = setOf("p1:480", "p2:600"),
        )

        val result = clearTodayCustomPillDismissals(state, "2026-08-31")

        requireNotNull(result)
        assertEquals(emptySet<String>(), result.customPillTakenSlots)
        assertEquals(state.waterSlotPlanMinutes, result.waterSlotPlanMinutes)
        assertEquals(state.waterTapMinutes, result.waterTapMinutes)
        assertTrue(result.walkDismissed)
        assertEquals(state.date, result.date)
    }

    @Test
    fun clearTodayDismissals_dayStateFromAnotherDateIsReturnedUntouched() {
        val yesterday = HealthDayState(date = "2026-08-30", customPillTakenSlots = setOf("p1:480"))

        assertEquals(yesterday, clearTodayCustomPillDismissals(yesterday, "2026-08-31"))
    }

    @Test
    fun clearTodayDismissals_nullAndAlreadyClearStatesAreIdempotentNoOps() {
        assertNull(clearTodayCustomPillDismissals(null, "2026-08-31"))
        val clear = HealthDayState(date = "2026-08-31")
        assertEquals(clear, clearTodayCustomPillDismissals(clear, "2026-08-31"))
    }
}
