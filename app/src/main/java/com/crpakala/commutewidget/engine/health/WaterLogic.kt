package com.crpakala.commutewidget.engine.health

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun planWaterSlots(
    count: Int = 5,
    events: List<EventSpan>,
    dayEpochMillis: Long,
    zone: ZoneId,
    nowEpochMillis: Long,
    params: HealthParams = HealthParams(),
): List<Int> {
    if (count <= 0) return emptyList()
    // An inverted or flat window (owner-configurable start/end) has no anchor range to distribute
    // slots across - return an empty plan rather than let waterAnchors divide by a bogus span.
    if (params.waterLastAnchorMinuteOfDay <= params.waterFirstAnchorMinuteOfDay) return emptyList()

    val anchors = waterAnchors(count, params)
    val planned = mutableListOf<Int>()
    for (anchor in anchors) {
        var candidate = maxOf(anchor, planned.lastOrNull()?.plus(params.waterMinimumSpacingMinutes) ?: anchor)
        var checkedEvents = 0
        while (candidate <= params.waterCutoffMinuteOfDay && checkedEvents <= events.size + 1) {
            val slotStart = epochAtMinute(dayEpochMillis, zone, candidate)
            val slotEnd = epochAtMinute(dayEpochMillis, zone, candidate + params.waterActiveWindowMinutes)
            val blockingEnd = events
                .asSequence()
                .filter { it.endEpochMillis > it.startEpochMillis }
                .filter { it.startEpochMillis < slotEnd && it.endEpochMillis > slotStart }
                .maxOfOrNull(EventSpan::endEpochMillis)
            if (blockingEnd == null) break

            candidate = maxOf(
                minuteRelativeToDay(blockingEnd, dayEpochMillis, zone) + params.waterEventBufferMinutes,
                planned.lastOrNull()?.plus(params.waterMinimumSpacingMinutes) ?: Int.MIN_VALUE,
            )
            checkedEvents++
        }
        if (candidate <= params.waterCutoffMinuteOfDay) planned += candidate
    }

    // Planning is deliberately independent of the current clock. Past slots remain in the
    // persisted daily plan and are filtered by their active windows downstream.
    return planned
}

fun waterSlotActiveAt(
    planMinutes: List<Int>,
    tapMinutes: List<Int>,
    lastShownOrTappedMinute: Int?,
    nowMinuteOfDay: Int,
    params: HealthParams = HealthParams(),
): Int? {
    val active = planMinutes.firstOrNull { slot ->
        nowMinuteOfDay >= slot &&
            nowMinuteOfDay < slot + params.waterActiveWindowMinutes &&
            tapMinutes.none { tap -> tap == slot || tap in slot until slot + params.waterActiveWindowMinutes }
    } ?: return null

    if (lastShownOrTappedMinute == null) return active
    val markerBelongsToActiveSlot =
        lastShownOrTappedMinute in active until active + params.waterActiveWindowMinutes
    return if (
        markerBelongsToActiveSlot ||
        nowMinuteOfDay - lastShownOrTappedMinute >= params.waterMinimumSpacingMinutes
    ) {
        active
    } else {
        null
    }
}

fun waterPulseSlot(
    exerciseSessions: List<EventSpan>,
    events: List<EventSpan>,
    planMinutes: List<Int>,
    tapMinutes: List<Int>,
    pulseAlreadyShownMinute: Int?,
    nowEpochMillis: Long,
    dayEpochMillis: Long,
    zone: ZoneId,
    params: HealthParams = HealthParams(),
): Int? {
    if (pulseAlreadyShownMinute != null) return null

    val lookbackStart = nowEpochMillis - params.waterPulseLookbackMinutes * 60_000L
    val recentlyFinishedExercise = exerciseSessions.any {
        it.endEpochMillis > it.startEpochMillis &&
            it.endEpochMillis in lookbackStart..nowEpochMillis
    }
    if (!recentlyFinishedExercise) return null

    val nowMinute = minuteRelativeToDay(nowEpochMillis, dayEpochMillis, zone)
    val planSlotActive = planMinutes.any { slot ->
        nowMinute >= slot &&
            nowMinute < slot + params.waterActiveWindowMinutes &&
            tapMinutes.none { tap -> tap == slot || tap in slot until slot + params.waterActiveWindowMinutes }
    }
    if (planSlotActive) return null

    val gapEnd = nowEpochMillis + params.waterPulseMinimumCalendarGapMinutes * 60_000L
    val calendarIsFree = events.none {
        it.endEpochMillis > it.startEpochMillis &&
            it.startEpochMillis < gapEnd &&
            it.endEpochMillis > nowEpochMillis
    }
    if (!calendarIsFree) return null

    val previousOpportunity = buildList {
        addAll(planMinutes.filter { it <= nowMinute })
        addAll(tapMinutes.filter { it <= nowMinute })
    }.maxOrNull()
    if (
        previousOpportunity != null &&
        nowMinute - previousOpportunity < params.waterMinimumSpacingMinutes
    ) {
        return null
    }

    return nowMinute
}

/**
 * Whether a calendar meeting is ongoing at [nowEpochMillis] (start inclusive, end exclusive).
 * Owner request 2026-08-31: gates the water candidate at computation time - all-day events are
 * already excluded upstream (the calendar reader never yields them as [EventSpan]s here).
 */
fun meetingOngoing(events: List<EventSpan>, nowEpochMillis: Long): Boolean =
    events.any { nowEpochMillis >= it.startEpochMillis && nowEpochMillis < it.endEpochMillis }

/**
 * Today's meeting start/end minutes-of-day that fall inside one of [planMinutes]'s active
 * windows ([slot, slot + waterActiveWindowMinutes)) - the instant a meeting starts or ends
 * mid-slot is exactly when [meetingOngoing]'s gate on the water candidate can flip, so
 * [com.crpakala.commutewidget.schedule.HealthBoundaryWorker] must wake up there instead of
 * waiting for the slot's own start/end and missing a meeting-end reappearance until the next
 * scheduled boundary. An edge outside today (relative to [dayEpochMillis] under [zone], e.g. the
 * far edge of a meeting spanning midnight) is dropped rather than wrapped into a bogus minute.
 */
fun waterMeetingEdgeBoundaryMinutes(
    planMinutes: List<Int>,
    events: List<EventSpan>,
    dayEpochMillis: Long,
    zone: ZoneId,
    params: HealthParams = HealthParams(),
): List<Int> {
    if (planMinutes.isEmpty() || events.isEmpty()) return emptyList()
    return events
        .asSequence()
        .filter { it.endEpochMillis > it.startEpochMillis }
        .flatMap { sequenceOf(it.startEpochMillis, it.endEpochMillis) }
        .map { minuteRelativeToDay(it, dayEpochMillis, zone) }
        .filter { it in 0 until 24 * 60 }
        .filter { edge -> planMinutes.any { slot -> edge in slot until slot + params.waterActiveWindowMinutes } }
        .distinct()
        .sorted()
        .toList()
}

private fun waterAnchors(count: Int, params: HealthParams): List<Int> {
    if (count == 1) {
        return listOf((params.waterFirstAnchorMinuteOfDay + params.waterLastAnchorMinuteOfDay) / 2)
    }
    val span = params.waterLastAnchorMinuteOfDay - params.waterFirstAnchorMinuteOfDay
    return List(count) { index ->
        params.waterFirstAnchorMinuteOfDay +
            (span * index + (count - 1) / 2) / (count - 1)
    }
}

private fun epochAtMinute(dayEpochMillis: Long, zone: ZoneId, minute: Int): Long {
    val date = Instant.ofEpochMilli(dayEpochMillis).atZone(zone).toLocalDate()
    return date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
}

private fun minuteRelativeToDay(epochMillis: Long, dayEpochMillis: Long, zone: ZoneId): Int {
    val day = Instant.ofEpochMilli(dayEpochMillis).atZone(zone).toLocalDate()
    val value = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val dayOffset = ChronoUnit.DAYS.between(day, value.toLocalDate()).toInt()
    return dayOffset * 24 * 60 + value.hour * 60 + value.minute
}
