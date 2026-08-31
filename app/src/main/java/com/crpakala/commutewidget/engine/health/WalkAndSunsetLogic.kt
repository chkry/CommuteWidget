package com.crpakala.commutewidget.engine.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

fun suggestWalk(
    stepsToday: Long?,
    stepGoal: Int,
    stepsSinceNoon: Long?,
    events: List<EventSpan>,
    toHomeWindow: IntRange,
    typicalBedtimeMinute: Int?,
    sunsetMinuteOfDay: Int?,
    audibleStoppedAtMinute: Int?,
    nowMinuteOfDay: Int,
    params: HealthParams,
    latchEnabled: Boolean,
    daylightEnabled: Boolean,
    zone: ZoneId = ZoneOffset.UTC,
): WalkSuggestion? {
    // Before the search window even opens, neither trigger (daily-goal deficit, which is true
    // every morning, or the sedentary-afternoon check) may surface a pill - this is what keeps
    // the walk suggestion out of the morning entirely. At exactly window start a suggestion is
    // allowed.
    if (nowMinuteOfDay < params.walkWindowStartMinuteOfDay) return null

    val belowDailyGoal = stepsToday != null && stepsToday < stepGoal
    val sedentaryAfternoon =
        stepsSinceNoon != null &&
            stepsSinceNoon < params.walkSedentaryStepsSinceNoon &&
            nowMinuteOfDay >= params.walkSedentaryCheckMinuteOfDay
    if (!belowDailyGoal && !sedentaryAfternoon) return null

    val deficit = if (belowDailyGoal) {
        (stepGoal.toLong() - requireNotNull(stepsToday)).coerceAtLeast(0)
    } else {
        (params.walkSedentaryStepsSinceNoon.toLong() - (stepsSinceNoon ?: 0L)).coerceAtLeast(0)
    }
    val rawDuration = ceil(deficit.toDouble() / params.walkCadenceStepsPerMinute.coerceAtLeast(1)).toInt()
    val rounding = params.walkDurationRoundingMinutes.coerceAtLeast(1)
    val roundedDuration = ((rawDuration + rounding - 1) / rounding) * rounding
    if (roundedDuration < params.walkMinimumDurationMinutes) return null
    val duration = roundedDuration.coerceAtMost(params.walkMaximumDurationMinutes)

    var searchStart = maxOf(params.walkWindowStartMinuteOfDay, nowMinuteOfDay)
    if (
        latchEnabled &&
        audibleStoppedAtMinute != null &&
        audibleStoppedAtMinute > params.walkLatchEligibleAfterMinuteOfDay
    ) {
        searchStart = maxOf(searchStart, audibleStoppedAtMinute + params.walkArrivalDelayMinutes)
    }
    searchStart = roundUp(searchStart, rounding)

    val latestFinish = typicalBedtimeMinute?.let {
        val normalizedBedtime = if (it <= params.walkWindowStartMinuteOfDay) it + 24 * 60 else it
        normalizedBedtime - params.walkBedtimeBufferMinutes
    } ?: Int.MAX_VALUE
    val calendarIntervals = events
        .asSequence()
        .filter { it.endEpochMillis > it.startEpochMillis }
        .map { localMinuteInterval(it, zone) }
        .toList()

    val validStarts = generateSequence(searchStart) { it + rounding }
        .takeWhile { it + duration <= params.walkWindowEndMinuteOfDay }
        .filter { start ->
            val end = start + duration
            end <= latestFinish &&
                !overlaps(start, end, toHomeWindow) &&
                calendarIntervals.none { overlaps(start, end, it.first, it.second) }
        }
        .toList()
    if (validStarts.isEmpty()) return null

    val daylightStart = if (daylightEnabled && sunsetMinuteOfDay != null) {
        validStarts.lastOrNull { it + duration <= sunsetMinuteOfDay }
    } else {
        null
    }
    return WalkSuggestion(
        startMinuteOfDay = daylightStart ?: validStarts.first(),
        durationMinutes = duration,
    )
}

fun localSunsetMinuteOfDay(
    latitude: Double,
    longitude: Double,
    date: LocalDate,
    zone: ZoneId,
): Int? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

    val dayOfYear = date.dayOfYear.toDouble()
    val longitudeHour = longitude / 15.0
    val approximateTime = dayOfYear + (18.0 - longitudeHour) / 24.0
    val meanAnomaly = 0.9856 * approximateTime - 3.289
    val trueLongitude = normalizeDegrees(
        meanAnomaly +
            1.916 * sin(Math.toRadians(meanAnomaly)) +
            0.020 * sin(Math.toRadians(2.0 * meanAnomaly)) +
            282.634,
    )

    var rightAscension = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(trueLongitude))))
    rightAscension = normalizeDegrees(rightAscension)
    val longitudeQuadrant = floor(trueLongitude / 90.0) * 90.0
    val ascensionQuadrant = floor(rightAscension / 90.0) * 90.0
    rightAscension = (rightAscension + longitudeQuadrant - ascensionQuadrant) / 15.0

    val sineDeclination = 0.39782 * sin(Math.toRadians(trueLongitude))
    val cosineDeclination = cos(kotlin.math.asin(sineDeclination))
    val cosineHourAngle = (
        cos(Math.toRadians(90.833)) -
            sineDeclination * sin(Math.toRadians(latitude))
        ) / (cosineDeclination * cos(Math.toRadians(latitude)))
    if (!cosineHourAngle.isFinite() || cosineHourAngle !in -1.0..1.0) return null

    val hourAngle = Math.toDegrees(acos(cosineHourAngle)) / 15.0
    val localMeanTime =
        hourAngle + rightAscension - 0.06571 * approximateTime - 6.622
    val utcHours = normalizeHours(localMeanTime - longitudeHour)
    val sunsetInstant = date
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .plusSeconds((utcHours * 3_600.0).roundToLong())
    val local = sunsetInstant.atZone(zone)
    return local.hour * 60 + local.minute
}

private fun localMinuteInterval(event: EventSpan, zone: ZoneId): Pair<Int, Int> {
    val start = Instant.ofEpochMilli(event.startEpochMillis).atZone(zone)
    val end = Instant.ofEpochMilli(event.endEpochMillis).atZone(zone)
    val startMinute = start.hour * 60 + start.minute
    val endDayOffset = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()).toInt()
    val endMinute = endDayOffset * 24 * 60 + end.hour * 60 + end.minute
    return startMinute to endMinute
}

private fun overlaps(start: Int, end: Int, range: IntRange): Boolean {
    if (range.isEmpty()) return false
    return overlaps(start, end, range.first, range.last)
}

private fun overlaps(start: Int, end: Int, blockedStart: Int, blockedEnd: Int): Boolean =
    start < blockedEnd && end > blockedStart

private fun roundUp(value: Int, multiple: Int): Int =
    ceil(value.toDouble() / multiple).toInt() * multiple

private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

private fun normalizeHours(value: Double): Double = ((value % 24.0) + 24.0) % 24.0
