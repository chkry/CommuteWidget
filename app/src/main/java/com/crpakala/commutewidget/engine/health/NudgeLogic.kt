package com.crpakala.commutewidget.engine.health

import java.time.Instant
import java.time.ZoneId

fun supplementCandidates(
    nowMinuteOfDay: Int,
    morningWindow: IntRange,
    proteinWindow: IntRange,
    morningTakenMinute: Int?,
    proteinTakenMinute: Int?,
    gymDayDetected: Boolean,
    gymPriorityEnabled: Boolean,
    params: HealthParams = HealthParams(),
): List<NudgeCandidate> {
    val morning = if (
        morningTakenMinute == null &&
        !morningWindow.isEmpty() &&
        nowMinuteOfDay >= morningWindow.first &&
        nowMinuteOfDay < params.supplementMorningCutoffMinuteOfDay
    ) {
        NudgeCandidate(
            kind = NudgeKind.SUPPLEMENT_MORNING,
            label = "Vitamins + Cr",
            startMinuteOfDay = morningWindow.first,
            endMinuteOfDay = params.supplementMorningCutoffMinuteOfDay,
            demoted = nowMinuteOfDay >= morningWindow.last,
        )
    } else {
        null
    }
    val protein = if (
        proteinTakenMinute == null &&
        !proteinWindow.isEmpty() &&
        nowMinuteOfDay >= proteinWindow.first
    ) {
        NudgeCandidate(
            kind = NudgeKind.SUPPLEMENT_PROTEIN,
            label = "Protein",
            startMinuteOfDay = proteinWindow.first,
            endMinuteOfDay = 24 * 60,
            demoted = nowMinuteOfDay >= proteinWindow.last,
        )
    } else {
        null
    }

    val proteinFirst =
        protein != null &&
            (
                nowMinuteOfDay >= params.supplementEveningPriorityMinuteOfDay ||
                    (gymDayDetected && gymPriorityEnabled)
                )
    return if (proteinFirst) {
        listOfNotNull(protein, morning)
    } else {
        listOfNotNull(morning, protein)
    }
}

fun focusGapCandidate(
    events: List<EventSpan>,
    nowEpochMillis: Long,
    dayEpochMillis: Long,
    zone: ZoneId,
    dismissedGapStartMinutes: List<Int>,
    params: HealthParams = HealthParams(),
): NudgeCandidate? {
    val date = Instant.ofEpochMilli(dayEpochMillis).atZone(zone).toLocalDate()
    val dayStart = date.atStartOfDay(zone)
    val workStart = dayStart.plusMinutes(params.focusWindowStartMinuteOfDay.toLong()).toInstant().toEpochMilli()
    val workEnd = dayStart.plusMinutes(params.focusWindowEndMinuteOfDay.toLong()).toInstant().toEpochMilli()
    if (nowEpochMillis < workStart || nowEpochMillis >= workEnd) return null

    val mergedBusy = mutableListOf<EventSpan>()
    events
        .asSequence()
        .filter { it.endEpochMillis > it.startEpochMillis }
        .map {
            EventSpan(
                startEpochMillis = maxOf(it.startEpochMillis, workStart),
                endEpochMillis = minOf(it.endEpochMillis, workEnd),
            )
        }
        .filter { it.endEpochMillis > it.startEpochMillis }
        .sortedBy(EventSpan::startEpochMillis)
        .forEach { event ->
            val last = mergedBusy.lastOrNull()
            if (last != null && event.startEpochMillis <= last.endEpochMillis) {
                mergedBusy[mergedBusy.lastIndex] = last.copy(
                    endEpochMillis = maxOf(last.endEpochMillis, event.endEpochMillis),
                )
            } else {
                mergedBusy += event
            }
        }

    val gaps = buildList {
        var cursor = workStart
        for (event in mergedBusy) {
            if (event.startEpochMillis > cursor) add(EventSpan(cursor, event.startEpochMillis))
            cursor = maxOf(cursor, event.endEpochMillis)
        }
        if (cursor < workEnd) add(EventSpan(cursor, workEnd))
    }
    val soonDeadline = nowEpochMillis + params.focusStartingSoonMinutes * 60_000L
    for (gap in gaps) {
        val effectiveStart = maxOf(gap.startEpochMillis, nowEpochMillis)
        if (effectiveStart > soonDeadline) break
        val remainingMinutes = ((gap.endEpochMillis - effectiveStart) / 60_000L).toInt()
        if (remainingMinutes < params.focusMinimumGapMinutes) continue

        val gapStartLocal = Instant.ofEpochMilli(gap.startEpochMillis).atZone(zone)
        val gapStartMinute = gapStartLocal.hour * 60 + gapStartLocal.minute
        if (gapStartMinute in dismissedGapStartMinutes) return null

        val endLocal = Instant.ofEpochMilli(gap.endEpochMillis).atZone(zone)
        val rounded = (
            remainingMinutes.coerceAtMost(params.focusLabelCapMinutes) /
                params.focusLabelRoundingMinutes.coerceAtLeast(1)
            ) * params.focusLabelRoundingMinutes.coerceAtLeast(1)
        return NudgeCandidate(
            kind = NudgeKind.FOCUS_GAP,
            label = "Focus ${rounded}m",
            startMinuteOfDay = gapStartMinute,
            endMinuteOfDay = endLocal.hour * 60 + endLocal.minute,
        )
    }
    return null
}

fun morningLightEligible(nowMinuteOfDay: Int, toWorkWindow: IntRange): Boolean =
    !toWorkWindow.isEmpty() &&
        nowMinuteOfDay >= toWorkWindow.first &&
        nowMinuteOfDay < toWorkWindow.last

fun caffeineLineCandidate(
    nowMinuteOfDay: Int,
    cutoffMinuteOfDay: Int,
    params: HealthParams = HealthParams(),
): NudgeCandidate? {
    val start = (cutoffMinuteOfDay - params.caffeineLeadMinutes).coerceAtLeast(0)
    if (nowMinuteOfDay < start || nowMinuteOfDay >= cutoffMinuteOfDay) return null
    return NudgeCandidate(
        kind = NudgeKind.CAFFEINE_CUTOFF,
        label = "Coffee by ${formatCompactClock(cutoffMinuteOfDay)}",
        startMinuteOfDay = start,
        endMinuteOfDay = cutoffMinuteOfDay,
        targetMinuteOfDay = cutoffMinuteOfDay,
    )
}

fun shortSleepDay(
    sleepMinutes: Int?,
    params: HealthParams = HealthParams(),
): Boolean = sleepMinutes != null && sleepMinutes < params.shortSleepThresholdMinutes

fun briefPrefix(
    sleepMinutes: Int?,
    median14: Int?,
    todayEventCount: Int?,
    softenEnabled: Boolean,
    params: HealthParams = HealthParams(),
): String? {
    if (!softenEnabled || sleepMinutes == null || median14 == null || todayEventCount == null) return null
    return if (
        sleepMinutes < median14 - params.briefSleepDeficitMinutes &&
        todayEventCount >= params.briefBusyDayEventCount
    ) {
        "Short sleep"
    } else {
        null
    }
}

fun focusShieldActive(
    overnightUnlockCount: Int?,
    sleepMinutes: Int?,
    median14: Int?,
    firstEventEndEpochMillis: Long?,
    nowEpochMillis: Long,
    dayEpochMillis: Long,
    zone: ZoneId,
    shieldEnabled: Boolean,
    params: HealthParams = HealthParams(),
): Boolean {
    if (
        !shieldEnabled ||
        overnightUnlockCount == null ||
        sleepMinutes == null ||
        median14 == null ||
        overnightUnlockCount <= params.focusShieldUnlockThreshold ||
        sleepMinutes >= median14
    ) {
        return false
    }
    val date = Instant.ofEpochMilli(dayEpochMillis).atZone(zone).toLocalDate()
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val shieldEnd = firstEventEndEpochMillis ?: date
        .atStartOfDay(zone)
        .plusMinutes(params.focusShieldNoEventEndMinuteOfDay.toLong())
        .toInstant()
        .toEpochMilli()
    return nowEpochMillis >= dayStart && nowEpochMillis < shieldEnd
}

fun selectVisibleNudges(
    candidates: List<NudgeCandidate>,
    surface: NudgeSurface,
    audiobookPlaying: Boolean,
    shieldActive: Boolean,
    maxVisible: Int = 2,
): List<NudgeCandidate> {
    if (audiobookPlaying || maxVisible <= 0) return emptyList()

    return candidates
        .withIndex()
        .filter { (_, candidate) ->
            candidate.kind != NudgeKind.CAFFEINE_CUTOFF &&
                // Owner request 2026-08-31: sleep and morning-light render as dismissable pills,
                // but only on an event map; they stay line-only on commute maps and cards.
                !(candidate.kind in INFO_PILL_KINDS && surface != NudgeSurface.MAP_EVENT) &&
                !(shieldActive && candidate.kind in SHIELD_SUPPRESSED_KINDS) &&
                // Owner request 2026-08-31: the prior water-never-on-commute-map ban is lifted -
                // water now competes purely by selectionPriority and the surface's maxVisible cap.
                !(surface != NudgeSurface.CARD && candidate.kind == NudgeKind.FOCUS_GAP)
        }
        .sortedWith(compareBy<IndexedValue<NudgeCandidate>> { selectionPriority(it.value) }.thenBy { it.index })
        .take(maxVisible)
        .map { it.value }
}

fun lineCandidates(
    candidates: List<NudgeCandidate>,
    surface: NudgeSurface,
): List<NudgeCandidate> {
    // Owner request 2026-08-31: the morning-light line shows on commute maps AND cards (all
    // days); on an event map it renders as a dismissable pill instead, never a line.
    val morning = candidates.firstOrNull {
        it.kind == NudgeKind.MORNING_LIGHT && surface != NudgeSurface.MAP_EVENT
    }
    val caffeine = candidates.firstOrNull { it.kind == NudgeKind.CAFFEINE_CUTOFF }
    return listOfNotNull(morning ?: caffeine).take(1)
}

/**
 * Owner request 2026-08-31: last night's estimate as a pill candidate for event maps. Label
 * drops the estimate tilde to stay inside the 13-character pill budget ("Slept 6h 40m").
 */
fun sleepPillCandidate(sleepMinutes: Int?): NudgeCandidate? {
    if (sleepMinutes == null) return null
    val clamped = sleepMinutes.coerceAtLeast(0)
    val hours = clamped / 60
    val remainder = clamped % 60
    val body = when {
        hours <= 0 -> "${remainder}m"
        remainder == 0 -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
    return NudgeCandidate(
        kind = NudgeKind.SLEEP_ESTIMATE,
        label = "Slept $body",
        startMinuteOfDay = 0,
        endMinuteOfDay = 24 * 60,
    )
}

/**
 * Sprint 2: the "To bed" / "Woke up" manual tap pills ([toBedCandidate], [wokeUpCandidate]) rank
 * below active supplements and above water/walk (owner-approved ranking) - see
 * [selectionPriority].
 */
fun toBedCandidate(
    nowEpochMillis: Long,
    zone: ZoneId,
    toBedTapEpochMillis: Long?,
): NudgeCandidate? {
    if (toBedTapInCurrentDomain(toBedTapEpochMillis, nowEpochMillis, zone)) return null
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val nowMinuteOfDay = now.hour * 60 + now.minute
    return when {
        nowMinuteOfDay >= TO_BED_EVENING_START_MINUTE_OF_DAY -> NudgeCandidate(
            kind = NudgeKind.SLEEP_TO_BED,
            label = "To bed",
            startMinuteOfDay = TO_BED_EVENING_START_MINUTE_OF_DAY,
            endMinuteOfDay = 24 * 60,
        )
        nowMinuteOfDay < TO_BED_EARLY_MORNING_END_MINUTE_OF_DAY -> NudgeCandidate(
            kind = NudgeKind.SLEEP_TO_BED,
            label = "To bed",
            startMinuteOfDay = 0,
            endMinuteOfDay = TO_BED_EARLY_MORNING_END_MINUTE_OF_DAY,
        )
        else -> null
    }
}

/** Sprint 2: the "Woke up" manual tap pill, visible 04:30-10:00; see [toBedCandidate]'s doc for the ranking note. */
fun wokeUpCandidate(
    nowEpochMillis: Long,
    zone: ZoneId,
    wokeUpTapEpochMillis: Long?,
): NudgeCandidate? {
    if (wokeUpTapInCurrentDomain(wokeUpTapEpochMillis, nowEpochMillis, zone)) return null
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val nowMinuteOfDay = now.hour * 60 + now.minute
    if (nowMinuteOfDay < WOKE_UP_WINDOW_START_MINUTE_OF_DAY || nowMinuteOfDay >= WOKE_UP_WINDOW_END_MINUTE_OF_DAY) return null
    return NudgeCandidate(
        kind = NudgeKind.SLEEP_WOKE_UP,
        label = "Woke up",
        startMinuteOfDay = WOKE_UP_WINDOW_START_MINUTE_OF_DAY,
        endMinuteOfDay = WOKE_UP_WINDOW_END_MINUTE_OF_DAY,
    )
}

private fun selectionPriority(candidate: NudgeCandidate): Int = when {
    candidate.kind in SUPPLEMENT_KINDS && !candidate.demoted -> 0
    candidate.kind in SLEEP_TAP_KINDS -> 1
    candidate.kind == NudgeKind.SLEEP_ESTIMATE -> 2
    candidate.kind == NudgeKind.MORNING_LIGHT -> 3
    candidate.kind == NudgeKind.WATER -> 4
    candidate.kind in SUPPLEMENT_KINDS -> 5
    candidate.kind == NudgeKind.WALK -> 6
    candidate.kind == NudgeKind.FOCUS_GAP -> 7
    else -> Int.MAX_VALUE
}

private fun formatCompactClock(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, 23 * 60 + 59)
    val hour24 = clamped / 60
    val minute = clamped % 60
    val hour12 = (hour24 % 12).takeUnless { it == 0 } ?: 12
    val period = if (hour24 < 12) "am" else "pm"
    return "$hour12:${minute.toString().padStart(2, '0')} $period"
}

private val SUPPLEMENT_KINDS = setOf(
    NudgeKind.SUPPLEMENT_MORNING,
    NudgeKind.SUPPLEMENT_PROTEIN,
)

private val SLEEP_TAP_KINDS = setOf(
    NudgeKind.SLEEP_TO_BED,
    NudgeKind.SLEEP_WOKE_UP,
)

private val SHIELD_SUPPRESSED_KINDS = setOf(
    NudgeKind.WATER,
    NudgeKind.WALK,
)

private val INFO_PILL_KINDS = setOf(
    NudgeKind.SLEEP_ESTIMATE,
    NudgeKind.MORNING_LIGHT,
)
