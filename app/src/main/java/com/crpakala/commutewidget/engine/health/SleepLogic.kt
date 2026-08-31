package com.crpakala.commutewidget.engine.health

import com.crpakala.commutewidget.health.KeyguardEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Sprint 2 tap-pill windows (see [toBedTapDomain], [wokeUpTapDomain], [sleepBackfillFrozen]). */
internal const val TO_BED_EVENING_START_MINUTE_OF_DAY = 21 * 60
internal const val TO_BED_EARLY_MORNING_END_MINUTE_OF_DAY = 2 * 60
internal const val WOKE_UP_WINDOW_START_MINUTE_OF_DAY = 4 * 60 + 30
internal const val WOKE_UP_WINDOW_END_MINUTE_OF_DAY = 10 * 60

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

private data class LockInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
)

private data class ResolvedInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

/** Pairs each lock event with its next chronological unlock; the final lock is open (null end). */
private fun buildLockIntervals(events: List<KeyguardEvent>): List<LockInterval> {
    val sorted = events.sortedBy(KeyguardEvent::timestampEpochMillis)
    return sorted.indices.mapNotNull { index ->
        val event = sorted[index]
        if (!event.locked) return@mapNotNull null
        val nextUnlock = sorted.subList(index + 1, sorted.size).firstOrNull { !it.locked }
        LockInterval(startEpochMillis = event.timestampEpochMillis, endEpochMillis = nextUnlock?.timestampEpochMillis)
    }
}

/**
 * Strict lock/unlock sleep estimator. Pairs each lock with its next unlock (no brief-wake
 * merging - a 2am phone check restarts the clock on purpose), applies manual tap anchors to
 * NARROW the search domain (they never widen it or replace strict pairing), then picks the
 * LATEST candidate interval clearing [HealthParams.sleepMinimumPlausibleMinutes]. If at least one
 * tap anchor is valid and no candidate clears the floor, the longest candidate wins instead
 * (explicit taps outrank the plausibility floor; un-anchored nights never bypass it).
 *
 * Returns null when no candidate end exists yet - device still locked, [nowEpochMillis] is
 * before the search window ends, and no valid Woke Up tap resolved the open interval. Callers
 * treat null as "no value yet, recompute later", not as "no sleep last night".
 */
fun estimateSleepFromKeyguard(
    events: List<KeyguardEvent>,
    nowEpochMillis: Long,
    zone: ZoneId,
    toBedTapEpochMillis: Long?,
    wokeUpTapEpochMillis: Long?,
    params: HealthParams = HealthParams(),
): SleepEstimate? {
    val scoredDay = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val windowStart = scoredDay.minusDays(1)
        .atStartOfDay(zone)
        .plusMinutes(params.sleepSearchStartMinuteOfDay.toLong())
        .toInstant()
        .toEpochMilli()
    val windowEnd = scoredDay.atStartOfDay(zone)
        .plusMinutes(params.sleepSearchEndMinuteOfDay.toLong())
        .toInstant()
        .toEpochMilli()
    if (windowEnd <= windowStart) return null

    val toBedRangeStart = scoredDay.minusDays(1).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
    val toBedRangeEnd = scoredDay.atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
    val toBedValid = toBedTapEpochMillis != null && toBedTapEpochMillis in toBedRangeStart..toBedRangeEnd

    val wokeUpRangeStart = scoredDay.atTime(4, 30).atZone(zone).toInstant().toEpochMilli()
    val wokeUpRangeEnd = scoredDay.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    val wokeUpValid = wokeUpTapEpochMillis != null && wokeUpTapEpochMillis in wokeUpRangeStart..wokeUpRangeEnd

    val sorted = events.sortedBy(KeyguardEvent::timestampEpochMillis)

    // To Bed narrows which locks may start a candidate; unlocks are left alone since they only
    // ever serve as pairing targets for locks that survive the filter.
    var working = if (toBedValid) {
        sorted.filter { !it.locked || it.timestampEpochMillis >= toBedTapEpochMillis }
    } else {
        sorted
    }

    // Fallback anchor (sprint 4 review finding 1): the device was already locked when a valid
    // To Bed tap was made and no lock event exists at/after the tap. Synthesize the lock AT the
    // tap time BEFORE interval pairing, so it pairs with whatever unlock follows (a later morning
    // unlock, a Woke Up synthetic close, or the 10:00 window close) instead of being appended as
    // a post-hoc open interval that could never pair with unlocks already in the list.
    if (toBedValid && working.none(KeyguardEvent::locked)) {
        val lastEventAtOrBeforeTap = sorted.lastOrNull { it.timestampEpochMillis <= toBedTapEpochMillis }
        if (lastEventAtOrBeforeTap?.locked == true) {
            working = (working + KeyguardEvent(timestampEpochMillis = toBedTapEpochMillis, locked = true))
                .sortedBy(KeyguardEvent::timestampEpochMillis)
        }
    }

    // Woke Up bounds the domain at the unlock immediately preceding (or equal to) the tap;
    // anything after that reference point is ignored entirely, not just later unlocks.
    var boundingUnlockFound = false
    if (wokeUpValid) {
        val boundingUnlock = working
            .filter { !it.locked && it.timestampEpochMillis <= wokeUpTapEpochMillis }
            .maxByOrNull(KeyguardEvent::timestampEpochMillis)
        if (boundingUnlock != null) {
            boundingUnlockFound = true
            val cutoff = boundingUnlock.timestampEpochMillis
            working = working.filter { it.timestampEpochMillis <= cutoff }
        }
    }

    var intervals = buildLockIntervals(working)

    // No unlock preceded a valid Woke Up tap: the tap itself synthetically closes the open interval.
    if (wokeUpValid && !boundingUnlockFound) {
        intervals = intervals.map { interval ->
            if (interval.endEpochMillis == null) {
                interval.copy(endEpochMillis = wokeUpTapEpochMillis)
            } else {
                interval
            }
        }
    }

    val resolved = mutableListOf<ResolvedInterval>()
    for (interval in intervals) {
        val end = interval.endEpochMillis
        when {
            end != null -> resolved += ResolvedInterval(interval.startEpochMillis, end)
            nowEpochMillis >= windowEnd -> resolved += ResolvedInterval(interval.startEpochMillis, windowEnd)
            else -> return null
        }
    }

    val candidates = resolved.filter { it.endEpochMillis > windowStart && it.endEpochMillis <= windowEnd }
    if (candidates.isEmpty()) return null

    val floorMillis = params.sleepMinimumPlausibleMinutes.coerceAtLeast(0) * 60_000L
    val floorFiltered = candidates.filter { it.endEpochMillis - it.startEpochMillis >= floorMillis }
    val anchorValid = toBedValid || wokeUpValid

    val chosen = when {
        floorFiltered.isNotEmpty() -> floorFiltered.maxWithOrNull(
            compareBy<ResolvedInterval> { it.endEpochMillis }.thenBy { it.startEpochMillis },
        )
        anchorValid -> candidates.maxWithOrNull(
            compareBy<ResolvedInterval> { it.endEpochMillis - it.startEpochMillis }.thenBy { it.startEpochMillis },
        )
        else -> null
    } ?: return null

    val rawMinutes = ((chosen.endEpochMillis - chosen.startEpochMillis) / 60_000L).toInt()
    val unlockCount = working.count {
        !it.locked && it.timestampEpochMillis > windowStart && it.timestampEpochMillis < chosen.endEpochMillis
    }

    return SleepEstimate(
        minutes = rawMinutes.coerceAtMost(params.sleepMaximumPlausibleMinutes),
        startEpochMillis = chosen.startEpochMillis,
        endEpochMillis = chosen.endEpochMillis,
        overnightUnlockCount = unlockCount,
    )
}

/**
 * Preferred sleep estimator for a given morning: uses the strict keyguard model whenever any
 * keyguard events are present, otherwise falls back to the legacy screen-based [estimateSleep]
 * (an overnight reboot or OEM quirk can wipe keyguard events entirely). A fragmented keyguard
 * night that yields no candidate is an honest "no estimate" and never falls back to screen
 * data - keyguard events being present at all means the device recorded a real signal.
 */
fun estimateOvernightSleep(
    keyguardEvents: List<KeyguardEvent>,
    screenSamples: List<ScreenSample>,
    dayStartEpochMillis: Long,
    nowEpochMillis: Long,
    zone: ZoneId,
    toBedTapEpochMillis: Long?,
    wokeUpTapEpochMillis: Long?,
    params: HealthParams = HealthParams(),
): SleepEstimate? = if (keyguardEvents.isEmpty()) {
    estimateSleep(screenSamples, dayStartEpochMillis, zone, params)
} else {
    estimateSleepFromKeyguard(
        events = keyguardEvents,
        nowEpochMillis = nowEpochMillis,
        zone = zone,
        toBedTapEpochMillis = toBedTapEpochMillis,
        wokeUpTapEpochMillis = wokeUpTapEpochMillis,
        params = params,
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

/**
 * Sprint 2: the "To Bed" pill's visible window crosses midnight (21:00-02:00), so its tap-
 * suppression domain is the SAME absolute range whichever side of midnight [nowEpochMillis]
 * falls on - evening (now's minute-of-day >= [TO_BED_EVENING_START_MINUTE_OF_DAY]) resolves to
 * [today 21:00, tomorrow 02:00]; early-morning (now's minute-of-day < [TO_BED_EARLY_MORNING_END_MINUTE_OF_DAY])
 * resolves to [yesterday 21:00, today 02:00] - a tap made at 22:00 stays valid when re-checked at
 * 01:00 the next calendar date, since both evaluations produce the identical millis range.
 * Returns null outside both bands: there is no "current night" domain to suppress against.
 */
fun toBedTapDomain(nowEpochMillis: Long, zone: ZoneId): LongRange? {
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val today = now.toLocalDate()
    val nowMinuteOfDay = now.hour * 60 + now.minute
    return when {
        nowMinuteOfDay >= TO_BED_EVENING_START_MINUTE_OF_DAY ->
            today.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()..
                today.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
        nowMinuteOfDay < TO_BED_EARLY_MORNING_END_MINUTE_OF_DAY ->
            today.minusDays(1).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()..
                today.atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
        else -> null
    }
}

/** True only when [tapEpochMillis] is non-null and falls inside [toBedTapDomain] for [nowEpochMillis] - a stale tap from a different night, or any tap when there is no current domain, is rejected. */
fun toBedTapInCurrentDomain(tapEpochMillis: Long?, nowEpochMillis: Long, zone: ZoneId): Boolean {
    if (tapEpochMillis == null) return false
    val domain = toBedTapDomain(nowEpochMillis, zone) ?: return false
    return tapEpochMillis in domain
}

/** Sprint 2: the "Woke Up" pill's fixed same-day window (04:30-10:00), always relative to [nowEpochMillis]'s local date. */
fun wokeUpTapDomain(nowEpochMillis: Long, zone: ZoneId): LongRange {
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val start = today.atTime(4, 30).atZone(zone).toInstant().toEpochMilli()
    val end = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    return start..end
}

/** True only when [tapEpochMillis] is non-null and falls inside today's [wokeUpTapDomain] - a tap from a previous day is rejected. */
fun wokeUpTapInCurrentDomain(tapEpochMillis: Long?, nowEpochMillis: Long, zone: ZoneId): Boolean {
    if (tapEpochMillis == null) return false
    return tapEpochMillis in wokeUpTapDomain(nowEpochMillis, zone)
}

/**
 * Sprint 2 freeze rule for [performSleepBackfill]'s "live until frozen" lifecycle. Without an
 * existing estimate for today, the backfill is NEVER frozen - it keeps trying on every call (the
 * estimator's own null return only means "no end exists yet, recompute later"). Once today's
 * history record already has a sleep estimate, further recomputation stops as soon as EITHER now
 * is past the 10:00 Woke Up window end, OR a valid Woke Up tap already resolved today's night.
 */
fun sleepBackfillFrozen(
    hasSleepMinutesToday: Boolean,
    nowEpochMillis: Long,
    zone: ZoneId,
    wokeUpTapEpochMillis: Long?,
): Boolean {
    if (!hasSleepMinutesToday) return false
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val nowMinuteOfDay = now.hour * 60 + now.minute
    if (nowMinuteOfDay >= WOKE_UP_WINDOW_END_MINUTE_OF_DAY) return true
    return wokeUpTapInCurrentDomain(wokeUpTapEpochMillis, nowEpochMillis, zone)
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
