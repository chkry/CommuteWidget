package com.crpakala.commutewidget

import com.crpakala.commutewidget.data.HealthDayState
import com.crpakala.commutewidget.data.HealthNudge
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.data.MapPillCorner
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.engine.health.NudgeCandidate
import com.crpakala.commutewidget.engine.health.NudgeKind
import com.crpakala.commutewidget.engine.health.NudgeSurface
import com.crpakala.commutewidget.engine.health.lineCandidates
import com.crpakala.commutewidget.engine.health.selectVisibleNudges
import java.time.ZoneId

internal const val HEALTH_DEMOTION_ALPHA = 0.6f
internal const val WATER_TAP_VOLUME_ML = 250
internal const val SUPPLEMENT_KIND_MORNING = "MORNING"
internal const val SUPPLEMENT_KIND_PROTEIN = "PROTEIN"
internal const val LARGE_MORNING_SUPPLEMENT_LABEL = "Vitamins + creatine"
internal const val MORNING_LIGHT_FALLBACK_LABEL = "Step outside - morning light"
internal const val HEALTH_WIDE_MIN_WIDTH_DP = 220

internal enum class BriefTruncation {
    FULL,
    DROP_FIRST,
    SLEEP_ONLY,
}

internal data class VisibleHealthChrome(
    val pills: List<NudgeCandidate>,
    val line: NudgeCandidate?,
)

internal fun oppositeCorner(corner: MapPillCorner): MapPillCorner = when (corner) {
    MapPillCorner.TOP_START -> MapPillCorner.BOTTOM_END
    MapPillCorner.TOP_END -> MapPillCorner.BOTTOM_START
    MapPillCorner.BOTTOM_START -> MapPillCorner.TOP_END
    MapPillCorner.BOTTOM_END -> MapPillCorner.TOP_START
}

internal fun nudgeSurfaceFor(mode: SnapshotMode): NudgeSurface = when (mode) {
    SnapshotMode.COMMUTE -> NudgeSurface.MAP_COMMUTE
    SnapshotMode.CALENDAR_EVENT -> NudgeSurface.MAP_EVENT
    SnapshotMode.CALENDAR_EMPTY -> NudgeSurface.CARD
}

internal fun showsHealthChrome(widthDp: Int): Boolean = widthDp >= HEALTH_WIDE_MIN_WIDTH_DP

internal fun toEngineNudgeKind(kind: HealthNudgeKind): NudgeKind = when (kind) {
    HealthNudgeKind.SUPPLEMENT_MORNING -> NudgeKind.SUPPLEMENT_MORNING
    HealthNudgeKind.SUPPLEMENT_PROTEIN -> NudgeKind.SUPPLEMENT_PROTEIN
    HealthNudgeKind.WATER -> NudgeKind.WATER
    HealthNudgeKind.WALK -> NudgeKind.WALK
    HealthNudgeKind.FOCUS_GAP -> NudgeKind.FOCUS_GAP
    HealthNudgeKind.MORNING_LIGHT -> NudgeKind.MORNING_LIGHT
    HealthNudgeKind.CAFFEINE_CUTOFF -> NudgeKind.CAFFEINE_CUTOFF
    HealthNudgeKind.SLEEP_ESTIMATE -> NudgeKind.SLEEP_ESTIMATE
}

internal fun toEngineNudgeCandidate(nudge: HealthNudge): NudgeCandidate = NudgeCandidate(
    kind = toEngineNudgeKind(nudge.kind),
    label = nudge.label,
    startMinuteOfDay = nudge.startMinuteOfDay,
    endMinuteOfDay = nudge.endMinuteOfDay,
    targetMinuteOfDay = nudge.targetMinuteOfDay,
    demoted = nudge.demoted,
)

internal fun healthDayStateForToday(state: HealthDayState?, todayIsoDate: String): HealthDayState {
    return if (state != null && state.date == todayIsoDate) {
        state
    } else {
        HealthDayState(date = todayIsoDate)
    }
}

internal fun filterHealthNudgesAgainstDayState(
    nudges: List<HealthNudge>,
    dayState: HealthDayState?,
    todayIsoDate: String,
    nowMinuteOfDay: Int,
): List<HealthNudge> {
    val state = dayState?.takeIf { it.date == todayIsoDate }
    return nudges.filter { nudge ->
        if (nowMinuteOfDay >= nudge.endMinuteOfDay) return@filter false
        if (state == null) return@filter true
        when (nudge.kind) {
            HealthNudgeKind.SUPPLEMENT_MORNING -> state.morningSupplementsTakenMinute == null
            HealthNudgeKind.SUPPLEMENT_PROTEIN -> state.proteinTakenMinute == null
            HealthNudgeKind.WATER -> state.waterTapMinutes.none { tap ->
                tap >= nudge.startMinuteOfDay && tap < nudge.endMinuteOfDay
            }
            HealthNudgeKind.WALK -> !state.walkDismissed
            HealthNudgeKind.FOCUS_GAP -> nudge.startMinuteOfDay !in state.dismissedFocusGapStartMinutes
            HealthNudgeKind.MORNING_LIGHT -> !state.morningLightDismissed
            HealthNudgeKind.SLEEP_ESTIMATE -> !state.sleepPillDismissed
            HealthNudgeKind.CAFFEINE_CUTOFF -> true
        }
    }
}

internal fun resolveVisibleHealthChrome(
    snapshotNudges: List<HealthNudge>,
    dayState: HealthDayState?,
    todayIsoDate: String,
    nowMinuteOfDay: Int,
    mode: SnapshotMode,
    audiobookPlaying: Boolean,
): VisibleHealthChrome {
    val candidates = filterHealthNudgesAgainstDayState(
        nudges = snapshotNudges,
        dayState = dayState,
        todayIsoDate = todayIsoDate,
        nowMinuteOfDay = nowMinuteOfDay,
    ).map(::toEngineNudgeCandidate)
    val surface = nudgeSurfaceFor(mode)
    val pills = selectVisibleNudges(
        candidates = candidates,
        surface = surface,
        audiobookPlaying = audiobookPlaying,
        shieldActive = false,
        maxVisible = 2,
    )
    val line = if (audiobookPlaying && surface == NudgeSurface.MAP_COMMUTE) {
        null
    } else {
        lineCandidates(candidates, surface).firstOrNull()
    }
    return VisibleHealthChrome(pills = pills, line = line)
}

internal fun formatSleepCompact(minutes: Int): String {
    val clamped = minutes.coerceAtLeast(0)
    val hours = clamped / 60
    val remainder = clamped % 60
    val body = when {
        hours <= 0 -> "${remainder}m"
        remainder == 0 -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
    return "~$body"
}

internal fun sleepBriefSegment(
    sleepEstimateMinutes: Int?,
    shortSleepDay: Boolean,
    sleepBriefEnabled: Boolean,
): String? {
    if (!sleepBriefEnabled || sleepEstimateMinutes == null) return null
    return if (shortSleepDay) "Short sleep" else "Slept ${formatSleepCompact(sleepEstimateMinutes)}"
}

internal fun pickBriefTruncation(isLarge: Boolean, hasSleepPrefix: Boolean): BriefTruncation {
    if (isLarge) return BriefTruncation.FULL
    if (hasSleepPrefix) return BriefTruncation.SLEEP_ONLY
    return BriefTruncation.FULL
}

internal fun buildBriefLine(
    sleepEstimateMinutes: Int?,
    shortSleepDay: Boolean,
    sleepBriefEnabled: Boolean,
    meetingCount: Int?,
    firstStartEpochMillis: Long?,
    truncation: BriefTruncation,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val sleepPart = sleepBriefSegment(sleepEstimateMinutes, shortSleepDay, sleepBriefEnabled)
    val hasMeetings = meetingCount != null && meetingCount > 0
    val meetingsPart = when {
        !hasMeetings -> null
        meetingCount == 1 -> "1 meeting"
        else -> "$meetingCount meetings"
    }
    val firstPart = if (hasMeetings && firstStartEpochMillis != null) {
        "first ${formatEventClockTime(firstStartEpochMillis, zone)}"
    } else {
        null
    }
    val parts = when (truncation) {
        BriefTruncation.FULL -> listOfNotNull(sleepPart, meetingsPart, firstPart)
        BriefTruncation.DROP_FIRST -> listOfNotNull(sleepPart, meetingsPart)
        BriefTruncation.SLEEP_ONLY -> listOfNotNull(sleepPart)
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return listOfNotNull(meetingsPart, firstPart).joinToString(" · ").ifEmpty { null }
}

internal fun mapHealthLabel(kind: NudgeKind, label: String, large: Boolean): String {
    if (large && kind == NudgeKind.SUPPLEMENT_MORNING) {
        return LARGE_MORNING_SUPPLEMENT_LABEL
    }
    return label
}

internal fun healthGlyph(kind: NudgeKind): String = when (kind) {
    NudgeKind.SUPPLEMENT_MORNING,
    NudgeKind.SUPPLEMENT_PROTEIN,
    -> "✓"
    NudgeKind.WATER -> "💧"
    NudgeKind.WALK -> "🚶"
    NudgeKind.FOCUS_GAP -> "◎"
    NudgeKind.SLEEP_ESTIMATE -> "🌙"
    NudgeKind.MORNING_LIGHT -> "☀"
    NudgeKind.CAFFEINE_CUTOFF -> ""
}

internal fun healthLineCaption(candidate: NudgeCandidate): String {
    val trimmed = candidate.label.trim()
    if (trimmed.isNotEmpty()) return trimmed
    return if (candidate.kind == NudgeKind.MORNING_LIGHT) {
        MORNING_LIGHT_FALLBACK_LABEL
    } else {
        trimmed
    }
}

internal fun applySupplementTaken(
    state: HealthDayState?,
    todayIsoDate: String,
    kind: String,
    takenMinuteOfDay: Int,
): HealthDayState {
    val today = healthDayStateForToday(state, todayIsoDate)
    return when (kind) {
        SUPPLEMENT_KIND_MORNING -> today.copy(
            morningSupplementsTakenMinute = today.morningSupplementsTakenMinute ?: takenMinuteOfDay,
        )
        SUPPLEMENT_KIND_PROTEIN -> today.copy(
            proteinTakenMinute = today.proteinTakenMinute ?: takenMinuteOfDay,
        )
        else -> today
    }
}

internal fun applyWaterTap(
    state: HealthDayState?,
    todayIsoDate: String,
    tapMinuteOfDay: Int,
): HealthDayState {
    val today = healthDayStateForToday(state, todayIsoDate)
    if (tapMinuteOfDay in today.waterTapMinutes) return today
    return today.copy(waterTapMinutes = today.waterTapMinutes + tapMinuteOfDay)
}

internal fun applyWalkDismissed(
    state: HealthDayState?,
    todayIsoDate: String,
): HealthDayState {
    return healthDayStateForToday(state, todayIsoDate).copy(walkDismissed = true)
}

internal fun applyFocusGapDismissed(
    state: HealthDayState?,
    todayIsoDate: String,
    gapStartMinute: Int,
): HealthDayState {
    val today = healthDayStateForToday(state, todayIsoDate)
    if (gapStartMinute in today.dismissedFocusGapStartMinutes) return today
    return today.copy(
        dismissedFocusGapStartMinutes = today.dismissedFocusGapStartMinutes + gapStartMinute,
    )
}

internal fun applySleepPillDismissed(
    state: HealthDayState?,
    todayIsoDate: String,
): HealthDayState {
    return healthDayStateForToday(state, todayIsoDate).copy(sleepPillDismissed = true)
}

internal fun applyMorningLightDismissed(
    state: HealthDayState?,
    todayIsoDate: String,
): HealthDayState {
    return healthDayStateForToday(state, todayIsoDate).copy(morningLightDismissed = true)
}
