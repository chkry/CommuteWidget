package com.crpakala.commutewidget.engine.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for custom pill reminders: occurrence resolution (per-slot dismissal,
 * carry-over transitions, day-of-week filtering, defensive normalization caps), the ACTIVE-then-
 * CARRY_OVER ordering, the toggle-gated audiobook/shield suppression split, and the
 * [HealthBoundaryWorker][com.crpakala.commutewidget.schedule.HealthBoundaryWorker]
 * transition-candidate list. The max-3-plus-overflow display cap is render-layer logic, covered
 * in `HealthWidgetUiTest`.
 */
class CustomPillLogicTest {
    private val monday = 1
    private val tuesday = 2

    private fun pill(
        id: String = "p1",
        label: String = "Vitamin D",
        slots: List<Int>,
        days: Set<Int> = setOf(monday),
    ): CustomPillDefinition = CustomPillDefinition(id = id, label = label, slotsMinutesOfDay = slots, days = days)

    // resolveCustomPillOccurrences - single slot, basic state transitions

    @Test
    fun beforeSlotStart_producesNoOccurrence() {
        val result = resolveCustomPillOccurrences(
            pills = listOf(pill(slots = listOf(600))),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 599,
            activeWindowMinutes = 60,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun atSlotStart_occurrenceIsActive() {
        val result = resolveCustomPillOccurrences(
            pills = listOf(pill(slots = listOf(600))),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 600,
            activeWindowMinutes = 60,
        )

        assertEquals(
            listOf(CustomPillOccurrenceCandidate("p1", "Vitamin D", 600, CustomPillOccurrenceState.ACTIVE)),
            result,
        )
    }

    @Test
    fun justBeforeActiveWindowEnd_stillActive() {
        val result = resolveCustomPillOccurrences(
            pills = listOf(pill(slots = listOf(600))),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 659,
            activeWindowMinutes = 60,
        )

        assertEquals(CustomPillOccurrenceState.ACTIVE, result.single().state)
    }

    @Test
    fun atActiveWindowEnd_becomesCarryOver() {
        val result = resolveCustomPillOccurrences(
            pills = listOf(pill(slots = listOf(600))),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 660,
            activeWindowMinutes = 60,
        )

        assertEquals(CustomPillOccurrenceState.CARRY_OVER, result.single().state)
        assertEquals(600, result.single().slotMinuteOfDay)
    }

    // multi-slot pills

    @Test
    fun multiSlotPill_carryOverEndsAtNextSlotActivation() {
        val definition = pill(slots = listOf(600, 900))

        val duringCarryOver = resolveCustomPillOccurrences(listOf(definition), emptySet(), monday, 899, 60)
        assertEquals(600, duringCarryOver.single().slotMinuteOfDay)
        assertEquals(CustomPillOccurrenceState.CARRY_OVER, duringCarryOver.single().state)

        val atNextSlot = resolveCustomPillOccurrences(listOf(definition), emptySet(), monday, 900, 60)
        assertEquals(900, atNextSlot.single().slotMinuteOfDay)
        assertEquals(CustomPillOccurrenceState.ACTIVE, atNextSlot.single().state)

        // A pill never shows two occurrences simultaneously - only one candidate is ever produced.
        assertEquals(1, atNextSlot.size)
    }

    @Test
    fun multiSlotPill_carryOverPersistsUntilMidnightWhenNoLaterSlotExists() {
        val definition = pill(slots = listOf(600, 900))

        val lateInDay = resolveCustomPillOccurrences(listOf(definition), emptySet(), monday, 1_439, 60)

        assertEquals(900, lateInDay.single().slotMinuteOfDay)
        assertEquals(CustomPillOccurrenceState.CARRY_OVER, lateInDay.single().state)
    }

    // per-slot dismissal

    @Test
    fun dismissedSlot_neverProducesAnOccurrenceEvenDuringItsWindow() {
        val result = resolveCustomPillOccurrences(
            pills = listOf(pill(slots = listOf(600))),
            takenSlots = setOf("p1:600"),
            dayOfWeek = monday,
            nowMinuteOfDay = 610,
            activeWindowMinutes = 60,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun dismissingOneSlot_doesNotAffectLaterSlotOfSamePill() {
        val definition = pill(slots = listOf(600, 900))

        val result = resolveCustomPillOccurrences(
            pills = listOf(definition),
            takenSlots = setOf("p1:600"),
            dayOfWeek = monday,
            nowMinuteOfDay = 900,
            activeWindowMinutes = 60,
        )

        assertEquals(900, result.single().slotMinuteOfDay)
        assertEquals(CustomPillOccurrenceState.ACTIVE, result.single().state)
    }

    @Test
    fun dismissingLaterSlot_earlierSlotCarriesOverPastItsOwnWindowUninterrupted() {
        // Slot 900 is dismissed, so it never "activates" - slot 600's carry-over is not cut short by it.
        val definition = pill(slots = listOf(600, 900))

        val result = resolveCustomPillOccurrences(
            pills = listOf(definition),
            takenSlots = setOf("p1:900"),
            dayOfWeek = monday,
            nowMinuteOfDay = 950,
            activeWindowMinutes = 60,
        )

        assertEquals(600, result.single().slotMinuteOfDay)
        assertEquals(CustomPillOccurrenceState.CARRY_OVER, result.single().state)
    }

    // day-of-week filtering

    @Test
    fun pillNotEnabledToday_producesNoOccurrenceRegardlessOfTime() {
        val definition = pill(slots = listOf(600), days = setOf(monday, 3, 5))

        val result = resolveCustomPillOccurrences(listOf(definition), emptySet(), tuesday, 610, 60)

        assertTrue(result.isEmpty())
    }

    @Test
    fun pillEnabledToday_producesOccurrenceNormally() {
        val definition = pill(slots = listOf(600), days = setOf(monday, 3, 5))

        val result = resolveCustomPillOccurrences(listOf(definition), emptySet(), monday, 610, 60)

        assertEquals(1, result.size)
    }

    // defensive normalization caps

    @Test
    fun slotsBeyondMaxSlotsPerPill_areIgnoredDefensively() {
        // MAX_SLOTS_PER_PILL is 4 - the fifth (sorted) slot, 500, is dropped entirely, so at
        // now=500 the occurrence resolves to slot 400 (still within its own carry-over) rather
        // than to 500.
        val definition = pill(slots = listOf(100, 200, 300, 400, 500))

        val result = resolveCustomPillOccurrences(listOf(definition), emptySet(), monday, 500, 60)

        assertEquals(400, result.single().slotMinuteOfDay)
    }

    @Test
    fun unsortedDuplicateSlots_areDedupedAndSortedBeforeCapping() {
        val definition = pill(slots = listOf(500, 100, 100, 300, 200, 400))

        val result = resolveCustomPillOccurrences(listOf(definition), emptySet(), monday, 400, 60)

        // 500 was dropped by the four-slot cap (sorted: 100, 200, 300, 400) - so 400 is the latest eligible one.
        assertEquals(400, result.single().slotMinuteOfDay)
    }

    @Test
    fun pillsBeyondMaxPills_areIgnoredDefensively() {
        // MAX_PILLS is 6 - a seventh pill is dropped even if its own slot is currently active.
        val pills = (1..7).map { index -> pill(id = "p$index", slots = listOf(600), days = setOf(monday)) }

        val result = resolveCustomPillOccurrences(pills, emptySet(), monday, 600, 60)

        assertEquals(6, result.size)
        assertTrue(result.none { it.pillId == "p7" })
    }

    // customPillActiveWindowEnd - midnight truncation

    @Test
    fun activeWindowEnd_wellBeforeMidnight_isUnaffected() {
        assertEquals(660, customPillActiveWindowEnd(slotMinuteOfDay = 600, activeWindowMinutes = 60))
    }

    @Test
    fun activeWindowEnd_crossingMidnight_truncatesAtMidnightRatherThanOverflowingIntoTomorrow() {
        // 23:30 + 60m would naturally end at 00:30 the next day (1,470) - truncated to 1,440 (midnight).
        assertEquals(1_440, customPillActiveWindowEnd(slotMinuteOfDay = 1_410, activeWindowMinutes = 60))
    }

    @Test
    fun activeWindowEnd_exactlyAtMidnight_isNotPushedFurther() {
        assertEquals(1_440, customPillActiveWindowEnd(slotMinuteOfDay = 1_380, activeWindowMinutes = 60))
    }

    // orderCustomPillOccurrences

    @Test
    fun ordering_activeFirstByAscendingSlotThenCarryOverByAscendingSlot() {
        val activeLate = CustomPillOccurrenceCandidate("a", "A", 900, CustomPillOccurrenceState.ACTIVE)
        val activeEarly = CustomPillOccurrenceCandidate("b", "B", 300, CustomPillOccurrenceState.ACTIVE)
        val carryOverLate = CustomPillOccurrenceCandidate("c", "C", 700, CustomPillOccurrenceState.CARRY_OVER)
        val carryOverEarly = CustomPillOccurrenceCandidate("d", "D", 100, CustomPillOccurrenceState.CARRY_OVER)

        val result = orderCustomPillOccurrences(listOf(activeLate, carryOverLate, activeEarly, carryOverEarly))

        assertEquals(listOf(activeEarly, activeLate, carryOverEarly, carryOverLate), result)
    }

    // customPillAudiobookSuppression - the toggle gate (sprint 5 review finding 4)

    @Test
    fun audiobookSuppression_requiresBothTheToggleAndLivePlayback() {
        assertTrue(customPillAudiobookSuppression(suppressionEnabled = true, audioPlaying = true))
        assertFalse(customPillAudiobookSuppression(suppressionEnabled = true, audioPlaying = false))
        assertFalse(customPillAudiobookSuppression(suppressionEnabled = false, audioPlaying = true))
        assertFalse(customPillAudiobookSuppression(suppressionEnabled = false, audioPlaying = false))
    }

    // computeVisibleCustomPillOccurrences - full pipeline, audiobook suppression, shield non-suppression

    @Test
    fun audiobookSuppressed_suppressesAllCustomPillOccurrencesEvenWhenOtherwiseActive() {
        val definition = pill(slots = listOf(600))

        val result = computeVisibleCustomPillOccurrences(
            pills = listOf(definition),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 600,
            activeWindowMinutes = 60,
            audiobookSuppressed = true,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun audiobookNotSuppressed_showsOccurrenceNormally() {
        val definition = pill(slots = listOf(600))

        val result = computeVisibleCustomPillOccurrences(
            pills = listOf(definition),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 600,
            activeWindowMinutes = 60,
            audiobookSuppressed = false,
        )

        assertEquals(1, result.size)
    }

    @Test
    fun duringTheRestlessNightShieldsDefaultWindow_customPillsAreStillVisible() {
        // The restless-night shield (when active) suppresses water/walk from 00:00 until the
        // first event ends or 10:00 - by owner decision it never suppresses custom pills, and
        // this pure pipeline has no shield parameter to even apply one. 08:00 sits squarely
        // inside that would-be-shielded window; the occurrence still resolves and shows.
        val definition = pill(slots = listOf(480))

        val result = computeVisibleCustomPillOccurrences(
            pills = listOf(definition),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 480,
            activeWindowMinutes = 60,
            audiobookSuppressed = false,
        )

        assertEquals(1, result.size)
        assertEquals(CustomPillOccurrenceState.ACTIVE, result.single().state)
    }

    @Test
    fun fullPipeline_returnsTheFullOrderedListUncapped() {
        // window=110, now=300: a(300)->end 410 ACTIVE; c(200)->end 310 ACTIVE; b(100)->end 210
        // CARRY_OVER; d(50)->end 160 CARRY_OVER. Ordered: ACTIVE asc (c, a), CARRY_OVER asc (d, b).
        // No cap here: all four come back - the max-3 display cap and "+N" overflow are derived
        // at render time after dismissal filtering (sprint 5 review finding 3).
        val pills = listOf(
            pill(id = "a", slots = listOf(300)),
            pill(id = "b", slots = listOf(100)),
            pill(id = "c", slots = listOf(200)),
            pill(id = "d", slots = listOf(50)),
        )

        val result = computeVisibleCustomPillOccurrences(
            pills = pills,
            takenSlots = emptySet(),
            dayOfWeek = monday,
            nowMinuteOfDay = 300,
            activeWindowMinutes = 110,
            audiobookSuppressed = false,
        )

        assertEquals(listOf("c", "a", "d", "b"), result.map { it.pillId })
    }

    // customPillTransitionCandidates

    @Test
    fun transitionCandidates_includeEachEligibleSlotStartAndActiveWindowEnd() {
        val definition = pill(slots = listOf(600, 900))

        val result = customPillTransitionCandidates(
            pills = listOf(definition),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            activeWindowMinutes = 60,
        )

        assertEquals(listOf(600, 660, 900, 960), result)
    }

    @Test
    fun transitionCandidates_excludeDismissedSlots() {
        val definition = pill(slots = listOf(600, 900))

        val result = customPillTransitionCandidates(
            pills = listOf(definition),
            takenSlots = setOf("p1:600"),
            dayOfWeek = monday,
            activeWindowMinutes = 60,
        )

        assertEquals(listOf(900, 960), result)
    }

    @Test
    fun transitionCandidates_excludePillsNotEnabledToday() {
        val definition = pill(slots = listOf(600), days = setOf(3))

        val result = customPillTransitionCandidates(
            pills = listOf(definition),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            activeWindowMinutes = 60,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun transitionCandidates_dropMidnightItselfSoTheDayRolloverFallbackIsNotDuplicated() {
        val definition = pill(slots = listOf(1_410))

        val result = customPillTransitionCandidates(
            pills = listOf(definition),
            takenSlots = emptySet(),
            dayOfWeek = monday,
            activeWindowMinutes = 60,
        )

        // Start (1,410) is included; the truncated end (1,440, midnight) is dropped outright.
        assertEquals(listOf(1_410), result)
    }

    @Test
    fun transitionCandidates_deduplicatesAndSortsAcrossMultiplePills() {
        val pills = listOf(
            pill(id = "a", slots = listOf(600, 900)),
            pill(id = "b", slots = listOf(600, 300)),
        )

        val result = customPillTransitionCandidates(
            pills = pills,
            takenSlots = emptySet(),
            dayOfWeek = monday,
            activeWindowMinutes = 30,
        )

        assertEquals(listOf(300, 330, 600, 630, 900, 930), result)
    }
}
