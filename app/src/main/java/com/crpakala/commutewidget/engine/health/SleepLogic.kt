package com.crpakala.commutewidget.engine.health

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private data class InactiveSegment(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

private data class MergedSleepBlock(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val unlockCount: Int,
)

fun estimateSleep(
    samples: List<ScreenSample>,
    dayStartEpochMillis: Long,
    zone: ZoneId,
    params: HealthParams = HealthParams(),
): SleepEstimate? {
    if (samples.isEmpty()) return null

    val day = Instant.ofEpochMilli(dayStartEpochMillis).atZone(zone).toLocalDate()
    val windowStart = day.minusDays(1)
        .atStartOfDay(zone)
        .plusMinutes(params.sleepSearchStartMinuteOfDay.toLong())
        .toInstant()
        .toEpochMilli()
    val windowEnd = day.atStartOfDay(zone)
        .plusMinutes(params.sleepSearchEndMinuteOfDay.toLong())
        .toInstant()
        .toEpochMilli()
    if (windowEnd <= windowStart) return null

    val sorted = samples.sortedBy(ScreenSample::timestampEpochMillis)
    val segments = mutableListOf<InactiveSegment>()
    var knownState: Boolean? = null
    var cursor = windowStart

    for (sample in sorted) {
        if (sample.timestampEpochMillis < windowStart) {
            knownState = sample.interactive
            cursor = windowStart
            continue
        }
        if (sample.timestampEpochMillis > windowEnd) break

        when {
            knownState == null -> {
                knownState = sample.interactive
                cursor = sample.timestampEpochMillis
            }
            knownState == false && sample.interactive -> {
                if (sample.timestampEpochMillis > cursor) {
                    segments += InactiveSegment(cursor, sample.timestampEpochMillis)
                }
                knownState = true
                cursor = sample.timestampEpochMillis
            }
            knownState == true && !sample.interactive -> {
                knownState = false
                cursor = sample.timestampEpochMillis
            }
        }
    }
    if (knownState == false && cursor < windowEnd) {
        segments += InactiveSegment(cursor, windowEnd)
    }

    val minimumSpanMillis = params.sleepMinimumInactiveSpanMinutes.coerceAtLeast(0) * 60_000L
    val candidateSegments = segments.filter { it.endEpochMillis - it.startEpochMillis >= minimumSpanMillis }
    if (candidateSegments.isEmpty()) return null

    val toleranceMillis = params.sleepBriefWakeToleranceMinutes.coerceAtLeast(0) * 60_000L
    val merged = mutableListOf<MergedSleepBlock>()
    var current = MergedSleepBlock(
        startEpochMillis = candidateSegments.first().startEpochMillis,
        endEpochMillis = candidateSegments.first().endEpochMillis,
        unlockCount = 0,
    )
    for (next in candidateSegments.drop(1)) {
        val burstMillis = next.startEpochMillis - current.endEpochMillis
        if (burstMillis in 1 until toleranceMillis) {
            current = current.copy(
                endEpochMillis = next.endEpochMillis,
                unlockCount = current.unlockCount + 1,
            )
        } else {
            merged += current
            current = MergedSleepBlock(next.startEpochMillis, next.endEpochMillis, 0)
        }
    }
    merged += current

    val longest = merged.maxWithOrNull(
        compareBy<MergedSleepBlock> { it.endEpochMillis - it.startEpochMillis }
            .thenBy { -it.startEpochMillis },
    ) ?: return null
    val rawMinutes = ((longest.endEpochMillis - longest.startEpochMillis) / 60_000L).toInt()
    if (rawMinutes < params.sleepMinimumPlausibleMinutes) return null

    return SleepEstimate(
        minutes = rawMinutes.coerceAtMost(params.sleepMaximumPlausibleMinutes),
        startEpochMillis = longest.startEpochMillis,
        endEpochMillis = longest.endEpochMillis,
        overnightUnlockCount = longest.unlockCount,
    )
}

fun medianSleepMinutes(
    history: List<Pair<String, Int?>>,
    params: HealthParams = HealthParams(),
): Int? {
    val values = history
        .sortedBy { it.first }
        .mapNotNull { it.second }
        .takeLast(params.sleepHistoryWindowDays.coerceAtLeast(0))
    return integerMedian(values)
}

fun sleepHistoryTrustworthy(
    history: List<Pair<String, Int?>>,
    params: HealthParams = HealthParams(),
): Boolean {
    val nonNullInWindow = history
        .sortedBy { it.first }
        .mapNotNull { it.second }
        .takeLast(params.sleepHistoryWindowDays.coerceAtLeast(0))
        .size
    return nonNullInWindow >= params.sleepTrustworthyMinimumDays
}

fun typicalBedtimeMinuteOfDay(
    estimates: List<SleepEstimate>,
    zone: ZoneId = ZoneOffset.UTC,
    params: HealthParams = HealthParams(),
): Int? {
    val normalized = estimates.map {
        val local = Instant.ofEpochMilli(it.startEpochMillis).atZone(zone)
        val minute = local.hour * 60 + local.minute
        if (minute < params.sleepSearchEndMinuteOfDay) minute + 24 * 60 else minute
    }
    val median = integerMedian(normalized) ?: return null
    return Math.floorMod(median, 24 * 60)
}

private fun integerMedian(values: List<Int>): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2
    }
}
