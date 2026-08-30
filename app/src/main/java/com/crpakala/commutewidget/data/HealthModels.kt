package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

@Serializable
enum class HealthNudgeKind {
    SUPPLEMENT_MORNING,
    SUPPLEMENT_PROTEIN,
    WATER,
    WALK,
    FOCUS_GAP,
    MORNING_LIGHT,
    CAFFEINE_CUTOFF,
}

@Serializable
data class HealthNudge(
    val kind: HealthNudgeKind,
    val label: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val targetMinuteOfDay: Int? = null,
    val demoted: Boolean = false,
)

@Serializable
data class HealthDayState(
    /** ISO-8601 local date, for example "2026-08-31". */
    val date: String,
    val morningSupplementsTakenMinute: Int? = null,
    val proteinTakenMinute: Int? = null,
    val waterTapMinutes: List<Int> = emptyList(),
    val waterSlotPlanMinutes: List<Int> = emptyList(),
    val walkDismissed: Boolean = false,
    val dismissedFocusGapStartMinutes: List<Int> = emptyList(),
    val gymDetected: Boolean = false,
    val waterPulseShownMinute: Int? = null,
    /**
     * Sprint 2: minute-of-day the last commute-audio observation found [AppSettings
     * .commuteAudioPackages] playing - a latch, not a live flag. Updated to "now" on every
     * observation while playing; frozen at its last value once playback stops, so
     * [com.crpakala.commutewidget.engine.health.suggestWalk]'s `audibleStoppedAtMinute` input can
     * see approximately when the audiobook/podcast ended for the post-audible walk latch.
     */
    val audibleLastPlayingMinute: Int? = null,
    /** Sprint 2: true once [com.crpakala.commutewidget.schedule.HealthWalkNotifyWorker] has posted today's walk notification - the dedup guard. */
    val walkNotified: Boolean = false,
)

@Serializable
data class HealthDayRecord(
    /** ISO-8601 local date, for example "2026-08-31". */
    val date: String,
    val steps: Int? = null,
    val sleepMinutes: Int? = null,
    val overnightUnlockCount: Int? = null,
    /**
     * Sprint 2: epoch millis the estimated sleep block started (bedtime), the minimal extra
     * datum [com.crpakala.commutewidget.engine.health.typicalBedtimeMinuteOfDay] needs beyond
     * [sleepMinutes] to derive a typical bedtime across history - that function reads only this
     * field from each [SleepEstimate][com.crpakala.commutewidget.engine.health.SleepEstimate] it
     * is given.
     */
    val sleepStartEpochMillis: Long? = null,
)

@Serializable
data class HealthHistory(
    val days: List<HealthDayRecord> = emptyList(),
)

fun HealthHistory.prunedAndUpserted(
    record: HealthDayRecord,
    keepDays: Int = 14,
): HealthHistory {
    require(keepDays >= 0) { "keepDays must not be negative" }

    return HealthHistory(
        days = (days.filterNot { it.date == record.date } + record)
            .sortedBy { it.date }
            .takeLast(keepDays),
    )
}
