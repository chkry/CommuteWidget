package com.crpakala.commutewidget.engine.health

data class ScreenSample(
    val timestampEpochMillis: Long,
    val interactive: Boolean,
)

data class EventSpan(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

data class SleepEstimate(
    val minutes: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val overnightUnlockCount: Int,
)

data class WalkSuggestion(
    val startMinuteOfDay: Int,
    val durationMinutes: Int,
)

enum class NudgeKind {
    SUPPLEMENT_MORNING,
    SUPPLEMENT_PROTEIN,
    WATER,
    WALK,
    FOCUS_GAP,
    MORNING_LIGHT,
    CAFFEINE_CUTOFF,
    SLEEP_ESTIMATE,
}

data class NudgeCandidate(
    val kind: NudgeKind,
    val label: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val targetMinuteOfDay: Int? = null,
    val demoted: Boolean = false,
)

enum class NudgeSurface {
    MAP_COMMUTE,
    MAP_EVENT,
    CARD,
}

data class HealthParams(
    val sleepSearchStartMinuteOfDay: Int = 21 * 60,
    val sleepSearchEndMinuteOfDay: Int = 13 * 60,
    val sleepMinimumInactiveSpanMinutes: Int = 20,
    val sleepBriefWakeToleranceMinutes: Int = 10,
    val sleepMinimumPlausibleMinutes: Int = 3 * 60,
    val sleepMaximumPlausibleMinutes: Int = 12 * 60,
    val sleepHistoryWindowDays: Int = 14,
    val sleepTrustworthyMinimumDays: Int = 7,
    val waterReminderCount: Int = 5,
    val waterFirstAnchorMinuteOfDay: Int = 7 * 60 + 30,
    val waterLastAnchorMinuteOfDay: Int = 19 * 60 + 30,
    val waterEventBufferMinutes: Int = 5,
    val waterMinimumSpacingMinutes: Int = 90,
    val waterCutoffMinuteOfDay: Int = 20 * 60,
    val waterActiveWindowMinutes: Int = 30,
    val waterPulseLookbackMinutes: Int = 90,
    val waterPulseMinimumCalendarGapMinutes: Int = 15,
    val walkSedentaryStepsSinceNoon: Int = 1_500,
    val walkSedentaryCheckMinuteOfDay: Int = 16 * 60,
    val walkCadenceStepsPerMinute: Int = 90,
    val walkDurationRoundingMinutes: Int = 5,
    val walkMinimumDurationMinutes: Int = 10,
    val walkMaximumDurationMinutes: Int = 45,
    val walkWindowStartMinuteOfDay: Int = 18 * 60,
    val walkWindowEndMinuteOfDay: Int = 21 * 60 + 30,
    val walkBedtimeBufferMinutes: Int = 60,
    val walkLatchEligibleAfterMinuteOfDay: Int = 17 * 60,
    val walkArrivalDelayMinutes: Int = 10,
    val supplementMorningCutoffMinuteOfDay: Int = 21 * 60 + 30,
    val supplementEveningPriorityMinuteOfDay: Int = 18 * 60,
    val focusWindowStartMinuteOfDay: Int = 9 * 60,
    val focusWindowEndMinuteOfDay: Int = 18 * 60,
    val focusMinimumGapMinutes: Int = 45,
    val focusStartingSoonMinutes: Int = 10,
    val focusLabelCapMinutes: Int = 120,
    val focusLabelRoundingMinutes: Int = 5,
    val caffeineLeadMinutes: Int = 90,
    val shortSleepThresholdMinutes: Int = 6 * 60,
    val briefSleepDeficitMinutes: Int = 60,
    val briefBusyDayEventCount: Int = 3,
    val focusShieldUnlockThreshold: Int = 6,
    val focusShieldNoEventEndMinuteOfDay: Int = 10 * 60,
)
