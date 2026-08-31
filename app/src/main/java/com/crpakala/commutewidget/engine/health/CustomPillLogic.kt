package com.crpakala.commutewidget.engine.health

/**
 * Custom pill reminders: user-defined generic reminder pills, each with its own set of daily
 * time slots and enabled weekdays (1..7, Monday=1, ISO day-of-week - matches
 * [com.crpakala.commutewidget.data.AppSettings.commuteDays] and every `dayOfWeekIso` call site in
 * this codebase). This file is the pure engine layer only: resolving which occurrence (if any)
 * each pill shows right now, ordering the visible set, and listing the minutes-of-day
 * [com.crpakala.commutewidget.schedule.HealthBoundaryWorker] should next wake up for. The max-3
 * display cap lives in the render layer (`HealthWidgetUi.kt`), after dismissal filtering.
 *
 * Every function here is scoped to "today" - a pill's occurrences on other days never enter a
 * single call, since the day rolls over and
 * [com.crpakala.commutewidget.data.HealthDayState.customPillTakenSlots] resets at midnight (the
 * existing day-state rollover; nothing new is needed for that here).
 */

private const val MINUTES_PER_DAY = 24 * 60

/**
 * Mirrors [com.crpakala.commutewidget.data.CustomPill.MAX_PILLS] /
 * [com.crpakala.commutewidget.data.CustomPill.MAX_SLOTS_PER_PILL]. Duplicated as local constants
 * (rather than imported) to keep this pure logic layer decoupled from the data/persistence
 * package, matching every other file under `engine.health`. Defensive only - the data-layer codec
 * already enforces these limits on write; this is a second line of defense against a stored value
 * that predates the limit or was hand-edited, per the owner's "codecs are lenient" note.
 */
private const val MAX_PILLS = 6
private const val MAX_SLOTS_PER_PILL = 4

enum class CustomPillOccurrenceState {
    ACTIVE,
    CARRY_OVER,
}

/** Pure engine-side mirror of [com.crpakala.commutewidget.data.CustomPill] - decouples this logic layer from the persistence model, matching [NudgeCandidate] vs [com.crpakala.commutewidget.data.HealthNudge]'s existing split. */
data class CustomPillDefinition(
    val id: String,
    val label: String,
    val slotsMinutesOfDay: List<Int>,
    val days: Set<Int>,
)

data class CustomPillOccurrenceCandidate(
    val pillId: String,
    val label: String,
    val slotMinuteOfDay: Int,
    val state: CustomPillOccurrenceState,
)

/**
 * A slot's active-window end minute-of-day, truncated at midnight (owner-accepted edge for a
 * window that would otherwise cross into tomorrow). Every caller in this file only ever considers
 * "today" (0..1439 for [now][resolveCustomPillOccurrences]'s `nowMinuteOfDay]), so a raw end past
 * [MINUTES_PER_DAY] is never meaningfully different from exactly [MINUTES_PER_DAY] to any of them
 * - this truncation exists so the value itself stays a sane minute-of-day for any future caller
 * that might render or log it directly.
 */
internal fun customPillActiveWindowEnd(slotMinuteOfDay: Int, activeWindowMinutes: Int): Int =
    (slotMinuteOfDay + activeWindowMinutes).coerceAtMost(MINUTES_PER_DAY)

private fun pillsEnabledToday(pills: List<CustomPillDefinition>, dayOfWeek: Int): List<CustomPillDefinition> =
    pills.take(MAX_PILLS).filter { dayOfWeek in it.days }

/**
 * Normalizes one pill's slots defensively - dedupes, sorts ascending, caps at
 * [MAX_SLOTS_PER_PILL] - then drops any slot dismissed today (encoded `"pillId:slotMinute"` in
 * [takenSlots]). Dismissal is per-occurrence: excluding a dismissed slot here means it can never
 * become a candidate again today, but it does not touch any other slot of the same pill.
 */
private fun eligibleSlotsFor(pill: CustomPillDefinition, takenSlots: Set<String>): List<Int> =
    pill.slotsMinutesOfDay.distinct().sorted().take(MAX_SLOTS_PER_PILL)
        .filter { slot -> "${pill.id}:$slot" !in takenSlots }

/**
 * Resolves each enabled-today pill's single current occurrence, if any, for [nowMinuteOfDay].
 *
 * Per pill, the occurrence is the LATEST eligible (non-dismissed) slot whose minute has already
 * arrived - "a pill never shows two occurrences simultaneously; the newest eligible occurrence
 * wins" - so once a later slot's start time passes, it always supersedes an earlier slot's
 * carry-over, and a dismissed slot is excluded from consideration entirely (it neither shows nor
 * ends a prior carry-over; only the next NON-dismissed slot can do that).
 *
 * The chosen occurrence is ACTIVE while [nowMinuteOfDay] is within [activeWindowMinutes] of its
 * slot, else CARRY_OVER (still visible, dimmed by the renderer) - and because no later slot has
 * started yet by construction (it would otherwise have been chosen instead), CARRY_OVER
 * naturally persists for the rest of today unless a later eligible slot activates first, matching
 * "visible until midnight OR until that same pill's next slot today becomes active".
 */
fun resolveCustomPillOccurrences(
    pills: List<CustomPillDefinition>,
    takenSlots: Set<String>,
    dayOfWeek: Int,
    nowMinuteOfDay: Int,
    activeWindowMinutes: Int,
): List<CustomPillOccurrenceCandidate> = pillsEnabledToday(pills, dayOfWeek).mapNotNull { pill ->
    val chosen = eligibleSlotsFor(pill, takenSlots).filter { it <= nowMinuteOfDay }.maxOrNull()
        ?: return@mapNotNull null
    val activeEnd = customPillActiveWindowEnd(chosen, activeWindowMinutes)
    val state = if (nowMinuteOfDay < activeEnd) CustomPillOccurrenceState.ACTIVE else CustomPillOccurrenceState.CARRY_OVER
    CustomPillOccurrenceCandidate(pillId = pill.id, label = pill.label, slotMinuteOfDay = chosen, state = state)
}

/** Approved display ordering: ACTIVE first (by slot minute ascending), then CARRY_OVER (by slot minute ascending). */
fun orderCustomPillOccurrences(occurrences: List<CustomPillOccurrenceCandidate>): List<CustomPillOccurrenceCandidate> =
    occurrences.sortedWith(
        compareBy(
            { it.state != CustomPillOccurrenceState.ACTIVE },
            { it.slotMinuteOfDay },
        ),
    )

/**
 * Whether audiobook playback suppresses custom pills right now: only when the owner's "Suppress
 * during audiobooks" toggle is on AND playback is live - exactly the gate the widget applies to
 * built-in health chrome at render time. A raw "is playing" boolean must never be used alone
 * here, or turning the suppression toggle off would still hide custom pills during playback.
 */
fun customPillAudiobookSuppression(suppressionEnabled: Boolean, audioPlaying: Boolean): Boolean =
    suppressionEnabled && audioPlaying

/**
 * The full resolve/order pipeline, plus the one suppression rule that applies to custom pills:
 * audiobook playback (gated by the suppression toggle - pass
 * [customPillAudiobookSuppression]'s result as [audiobookSuppressed]) suppresses ALL health
 * nudges, custom pills included, applied at computation time exactly like the restless-night
 * shield is applied to water/walk at computation time (see [computeHealthState]'s architecture
 * contract in `HealthNudgeComputer.kt`). Unlike water/walk, custom pills take no shield parameter
 * at all - the owner's decision is that the shield only ever suppresses water and walk - and
 * unlike water, there is no surface parameter either: custom pill eligibility has never varied by
 * which surface renders it (the per-surface display cap lives in `HealthWidgetUi.kt`'s
 * `customPillCapFor`, at render time, not here).
 *
 * Returns the FULL ordered eligible list (at most one occurrence per pill, so at most
 * [MAX_PILLS] entries) - deliberately uncapped. The max-3 display cap and the "+N" overflow
 * indicator are derived at render time AFTER the widget's live dismissal filtering (see
 * `resolveCustomPillRowContent` in `HealthWidgetUi.kt`): capping here would let a tap strand a
 * stale or lone "+N" whose hidden occurrences the renderer no longer knows about.
 */
fun computeVisibleCustomPillOccurrences(
    pills: List<CustomPillDefinition>,
    takenSlots: Set<String>,
    dayOfWeek: Int,
    nowMinuteOfDay: Int,
    activeWindowMinutes: Int,
    audiobookSuppressed: Boolean,
): List<CustomPillOccurrenceCandidate> {
    if (audiobookSuppressed) return emptyList()
    return orderCustomPillOccurrences(
        resolveCustomPillOccurrences(pills, takenSlots, dayOfWeek, nowMinuteOfDay, activeWindowMinutes),
    )
}

/**
 * Next-transition candidate minutes-of-day for today's enabled pills, for
 * [com.crpakala.commutewidget.schedule.HealthBoundaryWorker] to wake up at: each eligible
 * (non-dismissed) slot's start and its active-window end. A dismissed slot contributes neither -
 * nothing about its occurrence can ever change again today, so there is nothing to wake up for.
 * A candidate landing exactly on midnight (or past it, pre-truncation) is dropped outright:
 * [com.crpakala.commutewidget.schedule.HealthBoundaryWorker]'s day-rollover fallback already
 * covers that transition, and this must not duplicate it.
 */
fun customPillTransitionCandidates(
    pills: List<CustomPillDefinition>,
    takenSlots: Set<String>,
    dayOfWeek: Int,
    activeWindowMinutes: Int,
): List<Int> = pillsEnabledToday(pills, dayOfWeek)
    .flatMap { pill ->
        eligibleSlotsFor(pill, takenSlots).flatMap { slot ->
            listOf(slot, customPillActiveWindowEnd(slot, activeWindowMinutes))
        }
    }
    .filter { it in 0 until MINUTES_PER_DAY }
    .distinct()
    .sorted()
