package com.crpakala.commutewidget.engine

import android.content.Context
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.calendar.TodayEvent
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.HealthDayRecord
import com.crpakala.commutewidget.data.HealthDayState
import com.crpakala.commutewidget.data.HealthHistory
import com.crpakala.commutewidget.data.HealthNudge
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.prunedAndUpserted
import com.crpakala.commutewidget.engine.health.EventSpan
import com.crpakala.commutewidget.engine.health.HealthParams
import com.crpakala.commutewidget.engine.health.NudgeCandidate
import com.crpakala.commutewidget.engine.health.NudgeKind
import com.crpakala.commutewidget.engine.health.ScreenSample
import com.crpakala.commutewidget.engine.health.SleepEstimate
import com.crpakala.commutewidget.engine.health.WalkSuggestion
import com.crpakala.commutewidget.engine.health.briefPrefix
import com.crpakala.commutewidget.engine.health.caffeineLineCandidate
import com.crpakala.commutewidget.engine.health.estimateSleep
import com.crpakala.commutewidget.engine.health.focusGapCandidate
import com.crpakala.commutewidget.engine.health.focusShieldActive
import com.crpakala.commutewidget.engine.health.localSunsetMinuteOfDay
import com.crpakala.commutewidget.engine.health.medianSleepMinutes
import com.crpakala.commutewidget.engine.health.morningLightEligible
import com.crpakala.commutewidget.engine.health.planWaterSlots
import com.crpakala.commutewidget.engine.health.sleepPillCandidate
import com.crpakala.commutewidget.engine.health.suggestWalk
import com.crpakala.commutewidget.engine.health.supplementCandidates
import com.crpakala.commutewidget.engine.health.typicalBedtimeMinuteOfDay
import com.crpakala.commutewidget.engine.health.waterPulseSlot
import com.crpakala.commutewidget.engine.health.waterSlotActiveAt
import com.crpakala.commutewidget.health.CommuteAudioDetector
import com.crpakala.commutewidget.health.HealthConnectFacade
import com.crpakala.commutewidget.health.ScreenEventsReader
import com.crpakala.commutewidget.schedule.HealthWalkNotifyScheduler
import com.crpakala.commutewidget.schedule.shouldScheduleWalkNotification
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

private const val SLEEP_BACKFILL_EARLIEST_MINUTE_OF_DAY = 6 * 60 + 30
private const val NOON_MINUTE_OF_DAY = 12 * 60
private const val MAX_CHAINED_EVENTS = 30

/**
 * Sprint 2: the result of one [computeHealthState] pass - already filtered (shield, toggles,
 * taken/dismissed state) and mapped to the data-layer [HealthNudge] the widget renders from
 * (per the architecture contract, the widget only re-filters for taps newer than this pass and
 * its own live audiobook check).
 */
data class HealthComputation(
    val healthNudges: List<HealthNudge> = emptyList(),
    val sleepEstimateMinutes: Int? = null,
    val shortSleepDay: Boolean = false,
)

/**
 * Computes this refresh's health nudge state: ensures today's [HealthDayState] exists (planning
 * water slots on first touch of a new day), lazily backfills yesterday's sleep estimate once past
 * 06:30, detects gym days, latches the last-observed commute-audio-playing minute, builds every
 * enabled nudge candidate, applies the restless-night focus shield, and schedules the walk
 * notification one-shot when applicable.
 *
 * Every sub-step degrades gracefully (null/empty/false) on a missing permission or platform
 * failure - this function itself never throws; callers still wrap it (see
 * [com.crpakala.commutewidget.engine.CommuteRefresher]) because DataStore/WorkManager calls below
 * are not exhaustively covered by the inner catches.
 */
suspend fun computeHealthState(
    context: Context,
    settings: AppSettings,
    nowEpochMillis: Long,
    zone: ZoneId,
): HealthComputation = try {
    computeHealthStateUnsafe(context, settings, nowEpochMillis, zone)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    HealthComputation()
}

private suspend fun computeHealthStateUnsafe(
    context: Context,
    settings: AppSettings,
    nowEpochMillis: Long,
    zone: ZoneId,
): HealthComputation {
    val repo = SettingsRepository.get(context)
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val todayDateStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val nowMinuteOfDay = now.hour * 60 + now.minute
    val params = HealthParams(
        walkWindowStartMinuteOfDay = settings.walkSearchStartMinuteOfDay,
        walkWindowEndMinuteOfDay = settings.walkSearchEndMinuteOfDay,
    )

    val todayEvents = todayEventsChained(context, settings, nowEpochMillis, zone)
    val calendarEventSpans = todayEvents.map { EventSpan(it.startEpochMillis, it.endEpochMillis) }

    val dayStateAfterEnsure = ensureTodayHealthDayState(
        repo = repo,
        todayDateStr = todayDateStr,
        events = calendarEventSpans,
        waterRemindersPerDay = settings.waterRemindersPerDay,
        nowEpochMillis = nowEpochMillis,
        zone = zone,
        params = params,
    )

    val exerciseSessions = runCatching { HealthConnectFacade.readExerciseSessionsToday(context, zone) }
        .getOrDefault(emptyList())
    val gymDetectedNow = gymDayDetected(
        exerciseSessionEndEpochMillis = exerciseSessions.map { it.second },
        eventTitles = todayEvents.map { it.title },
        zone = zone,
    )
    val audioPlaying = runCatching {
        CommuteAudioDetector.isCommuteAudioPlaying(context, settings.commuteAudioPackages)
    }.getOrDefault(false)
    val exerciseSpans = exerciseSessions.map { (start, end) -> EventSpan(start, end) }

    repo.updateHealthDayState { current ->
        val base = if (current?.date == todayDateStr) current else dayStateAfterEnsure
        val afterGymAudible = base.copy(
            gymDetected = base.gymDetected || gymDetectedNow,
            audibleLastPlayingMinute = nextAudibleLatchState(audioPlaying, base.audibleLastPlayingMinute, nowMinuteOfDay),
        )
        val pulseMinute = if (settings.postGymWaterPulseEnabled) {
            runCatching {
                waterPulseSlot(
                    exerciseSessions = exerciseSpans,
                    events = calendarEventSpans,
                    planMinutes = afterGymAudible.waterSlotPlanMinutes,
                    tapMinutes = afterGymAudible.waterTapMinutes,
                    pulseAlreadyShownMinute = afterGymAudible.waterPulseShownMinute,
                    nowEpochMillis = nowEpochMillis,
                    dayEpochMillis = nowEpochMillis,
                    zone = zone,
                    params = params,
                )
            }.getOrNull()
        } else {
            null
        }
        if (pulseMinute != null) afterGymAudible.copy(waterPulseShownMinute = pulseMinute) else afterGymAudible
    }
    val dayState = runCatching { repo.healthDayState() }.getOrNull() ?: dayStateAfterEnsure

    val audibleStoppedAtMinute = audibleStoppedAtMinuteFor(
        audioPlaying = audioPlaying,
        latchMinute = dayState.audibleLastPlayingMinute,
        eligibleAfterMinuteOfDay = params.walkLatchEligibleAfterMinuteOfDay,
    )

    var sleepEstimateMinutes: Int? = null
    if (nowMinuteOfDay >= SLEEP_BACKFILL_EARLIEST_MINUTE_OF_DAY) {
        sleepEstimateMinutes = runCatching {
            performSleepBackfill(context, repo, nowEpochMillis, zone, params)
        }.getOrNull()?.minutes
    }
    val history = runCatching { repo.healthHistory() }.getOrNull() ?: HealthHistory()
    if (sleepEstimateMinutes == null) {
        sleepEstimateMinutes = history.days.find { it.date == todayDateStr }?.sleepMinutes
    }
    val median14 = medianSleepMinutes(history.days.map { it.date to it.sleepMinutes }, params)
    val bedtimeEstimates = history.days.mapNotNull { record ->
        record.sleepStartEpochMillis?.let { start ->
            SleepEstimate(
                minutes = record.sleepMinutes ?: 0,
                startEpochMillis = start,
                endEpochMillis = start,
                overnightUnlockCount = record.overnightUnlockCount ?: 0,
            )
        }
    }
    val typicalBedtime = typicalBedtimeMinuteOfDay(bedtimeEstimates, zone, params)

    val todayEventCount = calendarEventSpans.size
    val shortSleepDay = briefPrefix(sleepEstimateMinutes, median14, todayEventCount, settings.sleepDebtSoftenEnabled, params) != null

    val overnightUnlockCountToday = history.days.find { it.date == todayDateStr }?.overnightUnlockCount
    val firstEventEndEpochMillis = calendarEventSpans.minByOrNull { it.startEpochMillis }?.endEpochMillis
    val shieldActive = focusShieldActive(
        overnightUnlockCount = overnightUnlockCountToday,
        sleepMinutes = sleepEstimateMinutes,
        median14 = median14,
        firstEventEndEpochMillis = firstEventEndEpochMillis,
        nowEpochMillis = nowEpochMillis,
        dayEpochMillis = nowEpochMillis,
        zone = zone,
        shieldEnabled = settings.restlessNightShieldEnabled,
        params = params,
    )

    val candidates = mutableListOf<NudgeCandidate>()

    candidates += supplementCandidates(
        nowMinuteOfDay = nowMinuteOfDay,
        morningWindow = if (settings.morningSupplementsEnabled) {
            settings.morningSupplementsStartMinuteOfDay..settings.morningSupplementsEndMinuteOfDay
        } else {
            IntRange.EMPTY
        },
        proteinWindow = if (settings.eveningProteinEnabled) {
            settings.proteinStartMinuteOfDay..settings.proteinEndMinuteOfDay
        } else {
            IntRange.EMPTY
        },
        morningTakenMinute = dayState.morningSupplementsTakenMinute,
        proteinTakenMinute = dayState.proteinTakenMinute,
        gymDayDetected = dayState.gymDetected,
        gymPriorityEnabled = settings.gymProteinPriorityEnabled,
        params = params,
    )

    waterCandidate(settings, dayState, nowMinuteOfDay, params)?.let { candidates += it }

    var walkSuggestion: WalkSuggestion? = null
    if (settings.eveningWalkEnabled && !dayState.walkDismissed) {
        walkSuggestion = computeWalkSuggestion(
            context = context,
            settings = settings,
            dayState = dayState,
            calendarEventSpans = calendarEventSpans,
            typicalBedtime = typicalBedtime,
            audibleStoppedAtMinute = audibleStoppedAtMinute,
            now = now,
            nowMinuteOfDay = nowMinuteOfDay,
            zone = zone,
            params = params,
        )
        walkSuggestion?.let {
            candidates += NudgeCandidate(
                kind = NudgeKind.WALK,
                label = "Walk ${it.durationMinutes}m",
                startMinuteOfDay = it.startMinuteOfDay,
                endMinuteOfDay = it.startMinuteOfDay + it.durationMinutes,
                targetMinuteOfDay = it.startMinuteOfDay,
            )
        }
    }

    if (settings.focusGapChipEnabled) {
        focusGapCandidate(
            events = calendarEventSpans,
            nowEpochMillis = nowEpochMillis,
            dayEpochMillis = nowEpochMillis,
            zone = zone,
            dismissedGapStartMinutes = dayState.dismissedFocusGapStartMinutes,
            params = params,
        )?.let { candidates += it }
    }

    if (settings.morningLightLineEnabled && !dayState.morningLightDismissed) {
        val toWorkWindow = settings.morningSlotStartMinuteOfDay..settings.morningSlotEndMinuteOfDay
        if (morningLightEligible(nowMinuteOfDay, toWorkWindow)) {
            candidates += NudgeCandidate(
                kind = NudgeKind.MORNING_LIGHT,
                label = "Morning light",
                startMinuteOfDay = toWorkWindow.first,
                endMinuteOfDay = toWorkWindow.last,
            )
        }
    }

    // Owner request 2026-08-31: the sleep estimate shows every day - the brief carries it on
    // commute mornings, cards carry it as a caption, and event maps get this dismissable pill.
    if (settings.sleepBriefEnabled && !dayState.sleepPillDismissed) {
        sleepPillCandidate(sleepEstimateMinutes)?.let { candidates += it }
    }

    if (settings.caffeineCutoffLineEnabled) {
        caffeineLineCandidate(nowMinuteOfDay, settings.caffeineCutoffMinuteOfDay, params)?.let { candidates += it }
    }

    // Architecture contract (a): the restless-night shield excludes water/walk at COMPUTATION
    // time - the widget re-applies selectVisibleNudges with shieldActive=false, so this is the
    // only place the shield actually takes effect.
    val filtered = if (shieldActive) {
        candidates.filterNot { it.kind == NudgeKind.WATER || it.kind == NudgeKind.WALK }
    } else {
        candidates.toList()
    }

    if (walkSuggestion != null && filtered.any { it.kind == NudgeKind.WALK }) {
        scheduleWalkNotificationIfFuture(context, now, walkSuggestion, nowEpochMillis, zone)
    }

    return HealthComputation(
        healthNudges = filtered.map { it.toHealthNudge() },
        sleepEstimateMinutes = sleepEstimateMinutes,
        shortSleepDay = shortSleepDay,
    )
}

private fun waterCandidate(
    settings: AppSettings,
    dayState: HealthDayState,
    nowMinuteOfDay: Int,
    params: HealthParams,
): NudgeCandidate? {
    if (settings.waterRemindersEnabled) {
        val activeSlot = waterSlotActiveAt(
            planMinutes = dayState.waterSlotPlanMinutes,
            tapMinutes = dayState.waterTapMinutes,
            lastShownOrTappedMinute = dayState.waterTapMinutes.maxOrNull(),
            nowMinuteOfDay = nowMinuteOfDay,
            params = params,
        )
        if (activeSlot != null) {
            return NudgeCandidate(
                kind = NudgeKind.WATER,
                label = "Water",
                startMinuteOfDay = activeSlot,
                endMinuteOfDay = activeSlot + params.waterActiveWindowMinutes,
            )
        }
    }
    if (settings.postGymWaterPulseEnabled) {
        val pulseShownMinute = dayState.waterPulseShownMinute
        if (pulseShownMinute != null) {
            val activeEnd = pulseShownMinute + params.waterActiveWindowMinutes
            val alreadyTapped = dayState.waterTapMinutes.any { it in pulseShownMinute until activeEnd }
            if (!alreadyTapped && nowMinuteOfDay in pulseShownMinute until activeEnd) {
                return NudgeCandidate(
                    kind = NudgeKind.WATER,
                    label = "Water",
                    startMinuteOfDay = pulseShownMinute,
                    endMinuteOfDay = activeEnd,
                )
            }
        }
    }
    return null
}

private suspend fun computeWalkSuggestion(
    context: Context,
    settings: AppSettings,
    dayState: HealthDayState,
    calendarEventSpans: List<EventSpan>,
    typicalBedtime: Int?,
    audibleStoppedAtMinute: Int?,
    now: ZonedDateTime,
    nowMinuteOfDay: Int,
    zone: ZoneId,
    params: HealthParams,
): WalkSuggestion? {
    val stepsToday = runCatching { HealthConnectFacade.readStepsToday(context, zone) }.getOrNull()
    val stepsSinceNoon = if (nowMinuteOfDay >= NOON_MINUTE_OF_DAY) {
        val noonEpochMillis = now.toLocalDate().atStartOfDay(zone).plusMinutes(NOON_MINUTE_OF_DAY.toLong())
            .toInstant().toEpochMilli()
        runCatching { HealthConnectFacade.readStepsBetween(context, noonEpochMillis, now.toInstant().toEpochMilli()) }.getOrNull()
    } else {
        null
    }
    val home = settings.home
    val sunset = if (settings.walkDaylightPreferenceEnabled && home != null) {
        runCatching { localSunsetMinuteOfDay(home.lat, home.lng, now.toLocalDate(), zone) }.getOrNull()
    } else {
        null
    }
    return suggestWalk(
        stepsToday = stepsToday,
        stepGoal = settings.stepGoal,
        stepsSinceNoon = stepsSinceNoon,
        events = calendarEventSpans,
        toHomeWindow = settings.eveningSlotStartMinuteOfDay..settings.eveningSlotEndMinuteOfDay,
        typicalBedtimeMinute = typicalBedtime,
        sunsetMinuteOfDay = sunset,
        audibleStoppedAtMinute = audibleStoppedAtMinute,
        nowMinuteOfDay = nowMinuteOfDay,
        params = params,
        latchEnabled = settings.walkPostAudibleLatchEnabled,
        daylightEnabled = settings.walkDaylightPreferenceEnabled,
        zone = zone,
    )
}

private fun scheduleWalkNotificationIfFuture(
    context: Context,
    now: ZonedDateTime,
    walkSuggestion: WalkSuggestion,
    nowEpochMillis: Long,
    zone: ZoneId,
) {
    val startEpochMillis = now.toLocalDate().atStartOfDay(zone)
        .plusMinutes(walkSuggestion.startMinuteOfDay.toLong())
        .toInstant()
        .toEpochMilli()
    if (shouldScheduleWalkNotification(startEpochMillis, nowEpochMillis)) {
        runCatching {
            HealthWalkNotifyScheduler.schedule(context, startEpochMillis, walkSuggestion.durationMinutes, nowEpochMillis)
        }
    }
}

/**
 * Reconstructs today's remaining eligible event spans (start, end, title) using only
 * [CalendarReader]'s existing public API - repeatedly calling [CalendarReader.nextEventToday] and
 * advancing the cursor past each result - rather than adding a new list-returning query method to
 * that read-only-to-this-territory class. [MAX_CHAINED_EVENTS] bounds the loop defensively; real
 * calendars never approach it in a single day.
 */
internal fun todayEventsChained(
    context: Context,
    settings: AppSettings,
    nowEpochMillis: Long,
    zone: ZoneId,
): List<TodayEvent> {
    if (!settings.calendarEnabled || settings.selectedCalendarIds.isEmpty()) return emptyList()
    val reader = CalendarReader(context)
    if (!reader.hasPermission()) return emptyList()

    val events = mutableListOf<TodayEvent>()
    var cursor = nowEpochMillis
    var guard = 0
    while (guard < MAX_CHAINED_EVENTS) {
        val next = reader.nextEventToday(settings.selectedCalendarIds, cursor, zone) ?: break
        events += next
        if (next.endEpochMillis <= cursor) break
        cursor = next.endEpochMillis
        guard++
    }
    return events
}

/**
 * Ensures a [HealthDayState] exists for [todayDateStr], planning water slots (the day's implicit
 * midnight rollover) on first touch of a new day. A fresh water plan is computed unconditionally
 * (cheap, pure) - whether any nudge actually surfaces from it is a per-feature-toggle decision
 * made later, at candidate-build time.
 */
internal suspend fun ensureTodayHealthDayState(
    repo: SettingsRepository,
    todayDateStr: String,
    events: List<EventSpan>,
    waterRemindersPerDay: Int,
    nowEpochMillis: Long,
    zone: ZoneId,
    params: HealthParams,
): HealthDayState {
    val existing = runCatching { repo.healthDayState() }.getOrNull()
    if (existing?.date == todayDateStr) return existing

    val waterPlan = runCatching {
        planWaterSlots(
            count = waterRemindersPerDay,
            events = events,
            dayEpochMillis = nowEpochMillis,
            zone = zone,
            nowEpochMillis = nowEpochMillis,
            params = params,
        )
    }.getOrDefault(emptyList())
    val fresh = HealthDayState(date = todayDateStr, waterSlotPlanMinutes = waterPlan)
    repo.updateHealthDayState { current -> if (current?.date == todayDateStr) current else fresh }
    return runCatching { repo.healthDayState() }.getOrNull() ?: fresh
}

/**
 * Shared by [computeHealthState]'s lazy backfill (guarded by 06:30) and
 * [com.crpakala.commutewidget.schedule.HealthMorningWorker]'s unconditional daily 06:30 run. A
 * no-op (returns null, writes nothing) once today's history record already has a sleep estimate.
 */
internal suspend fun performSleepBackfill(
    context: Context,
    repo: SettingsRepository,
    nowEpochMillis: Long,
    zone: ZoneId,
    params: HealthParams = HealthParams(),
): SleepEstimate? {
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val todayDateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val yesterday = today.minusDays(1)
    val yesterdayDateStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

    val history = runCatching { repo.healthHistory() }.getOrNull() ?: HealthHistory()
    if (history.days.find { it.date == todayDateStr }?.sleepMinutes != null) return null

    val dayStartEpochMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val yesterdayStartEpochMillis = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
    val windowStart = yesterday.atStartOfDay(zone).plusMinutes(21L * 60).toInstant().toEpochMilli()

    val samples = runCatching {
        ScreenEventsReader.readScreenEvents(context, windowStart, nowEpochMillis)
            .map { ScreenSample(it.timestampEpochMillis, it.interactive) }
    }.getOrDefault(emptyList())
    val estimate = runCatching { estimateSleep(samples, nowEpochMillis, zone, params) }.getOrNull()

    val yesterdaySteps = runCatching {
        HealthConnectFacade.readStepsBetween(context, yesterdayStartEpochMillis, dayStartEpochMillis)
    }.getOrNull()?.toInt()
    val todaySteps = runCatching { HealthConnectFacade.readStepsToday(context, zone) }.getOrNull()?.toInt()

    repo.updateHealthHistory { current ->
        val base = current ?: HealthHistory()
        val withYesterday = base.prunedAndUpserted(
            mergedDayRecord(base.days.find { it.date == yesterdayDateStr }, yesterdayDateStr, steps = yesterdaySteps),
        )
        withYesterday.prunedAndUpserted(
            mergedDayRecord(
                withYesterday.days.find { it.date == todayDateStr },
                todayDateStr,
                steps = todaySteps,
                sleepMinutes = estimate?.minutes,
                overnightUnlockCount = estimate?.overnightUnlockCount,
                sleepStartEpochMillis = estimate?.startEpochMillis,
            ),
        )
    }
    return estimate
}

/** Merges new non-null values over [existing] rather than overwriting the whole record - a concurrent tap or an earlier partial upsert for the same date must not be clobbered. */
internal fun mergedDayRecord(
    existing: HealthDayRecord?,
    date: String,
    steps: Int? = null,
    sleepMinutes: Int? = null,
    overnightUnlockCount: Int? = null,
    sleepStartEpochMillis: Long? = null,
): HealthDayRecord = HealthDayRecord(
    date = date,
    steps = steps ?: existing?.steps,
    sleepMinutes = sleepMinutes ?: existing?.sleepMinutes,
    overnightUnlockCount = overnightUnlockCount ?: existing?.overnightUnlockCount,
    sleepStartEpochMillis = sleepStartEpochMillis ?: existing?.sleepStartEpochMillis,
)

/**
 * Gym-day detection: an exercise session ending after noon, or any of today's calendar event
 * titles mentioning "gym" (case-insensitive) - either is sufficient.
 */
internal fun gymDayDetected(
    exerciseSessionEndEpochMillis: List<Long>,
    eventTitles: List<String>,
    zone: ZoneId,
    noonMinuteOfDay: Int = NOON_MINUTE_OF_DAY,
): Boolean {
    val sessionAfterNoon = exerciseSessionEndEpochMillis.any { minuteOfDayFor(it, zone) >= noonMinuteOfDay }
    val titleMentionsGym = eventTitles.any { it.contains("gym", ignoreCase = true) }
    return sessionAfterNoon || titleMentionsGym
}

/**
 * The commute-audio latch: while [audioPlaying], the latch continuously tracks "now"; once
 * playback stops, it freezes at whatever minute it last saw - approximately "when it stopped".
 */
internal fun nextAudibleLatchState(
    audioPlaying: Boolean,
    previousLastPlayingMinute: Int?,
    nowMinuteOfDay: Int,
): Int? = if (audioPlaying) nowMinuteOfDay else previousLastPlayingMinute

/** [WalkSuggestion]'s `audibleStoppedAtMinute` input: only meaningful once playback has actually stopped and the latch is late enough in the day to matter. */
internal fun audibleStoppedAtMinuteFor(
    audioPlaying: Boolean,
    latchMinute: Int?,
    eligibleAfterMinuteOfDay: Int,
): Int? = if (!audioPlaying && latchMinute != null && latchMinute >= eligibleAfterMinuteOfDay) latchMinute else null

private fun minuteOfDayFor(epochMillis: Long, zone: ZoneId): Int {
    val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return zoned.hour * 60 + zoned.minute
}

internal fun NudgeCandidate.toHealthNudge(): HealthNudge = HealthNudge(
    kind = kind.toHealthNudgeKind(),
    label = label,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    targetMinuteOfDay = targetMinuteOfDay,
    demoted = demoted,
)

internal fun NudgeKind.toHealthNudgeKind(): HealthNudgeKind = when (this) {
    NudgeKind.SUPPLEMENT_MORNING -> HealthNudgeKind.SUPPLEMENT_MORNING
    NudgeKind.SUPPLEMENT_PROTEIN -> HealthNudgeKind.SUPPLEMENT_PROTEIN
    NudgeKind.WATER -> HealthNudgeKind.WATER
    NudgeKind.WALK -> HealthNudgeKind.WALK
    NudgeKind.FOCUS_GAP -> HealthNudgeKind.FOCUS_GAP
    NudgeKind.MORNING_LIGHT -> HealthNudgeKind.MORNING_LIGHT
    NudgeKind.CAFFEINE_CUTOFF -> HealthNudgeKind.CAFFEINE_CUTOFF
    NudgeKind.SLEEP_ESTIMATE -> HealthNudgeKind.SLEEP_ESTIMATE
}
