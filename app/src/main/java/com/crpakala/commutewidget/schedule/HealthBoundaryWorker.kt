package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.HealthDayState
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.computeHealthState
import com.crpakala.commutewidget.engine.health.HealthParams
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sprint 2: recomputes health nudge state on its own cadence, independent of the commute
 * refresh pipeline - NO Routes/Static Maps call, so this never touches the Google Maps API
 * budget. Rewrites only the three health fields of whatever [com.crpakala.commutewidget.data
 * .CommuteSnapshot] is already stored (route/map/leave-by fields are untouched), then
 * self-reschedules to the next boundary at which some enabled feature's visible state could
 * change. Unique work name "health_boundary"; APPEND_OR_REPLACE from inside (this worker IS the
 * current holder), REPLACE only from [HealthBoundaryScheduler.schedule] (an external caller) -
 * see [com.crpakala.commutewidget.schedule.WindowBoundaryWorker] for the identical anti-self-
 * cancel reasoning.
 */
class HealthBoundaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // NonCancellable: both the snapshot rewrite and the self-reschedule must complete even if
        // this worker's coroutine is cancelled mid-run (matches CommuteRefresher's finally-block
        // convention) - a dropped reschedule would silently end the whole boundary chain.
        withContext(NonCancellable) {
            try {
                recomputeAndPersistHealthFields(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            try {
                rescheduleNextBoundary(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result.success()
    }

    private suspend fun recomputeAndPersistHealthFields(context: Context) {
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        val zone = ZoneId.systemDefault()
        val nowEpochMillis = System.currentTimeMillis()
        val computation = computeHealthState(context, settings, nowEpochMillis, zone)
        val previous = repo.snapshot()
        if (previous != null) {
            repo.saveSnapshot(
                previous.copy(
                    healthNudges = computation.healthNudges,
                    sleepEstimateMinutes = computation.sleepEstimateMinutes,
                    shortSleepDay = computation.shortSleepDay,
                ),
            )
        }
        CommuteWidget().updateAll(context)
    }

    private suspend fun rescheduleNextBoundary(context: Context) {
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        val dayState = repo.healthDayState()
        val walkTargetMinuteOfDay = repo.snapshot()
            ?.healthNudges
            ?.firstOrNull { it.kind == HealthNudgeKind.WALK }
            ?.targetMinuteOfDay
        val next = nextHealthBoundary(ZonedDateTime.now(), settings, dayState, walkTargetMinuteOfDay)
        HealthBoundaryScheduler.scheduleAt(context, next, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
}

/** (Re)schedules the "health_boundary" one-shot chain; see [HealthBoundaryWorker]'s class doc for the APPEND_OR_REPLACE/REPLACE split. */
object HealthBoundaryScheduler {
    const val WORK_NAME = "health_boundary"

    /** External-caller entry point ([CommuteScheduler.ensureScheduled]): always REPLACE. */
    suspend fun schedule(context: Context, settings: AppSettings, existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val appContext = context.applicationContext
        val repo = SettingsRepository.get(appContext)
        val dayState = repo.healthDayState()
        val walkTargetMinuteOfDay = repo.snapshot()
            ?.healthNudges
            ?.firstOrNull { it.kind == HealthNudgeKind.WALK }
            ?.targetMinuteOfDay
        val next = nextHealthBoundary(ZonedDateTime.now(), settings, dayState, walkTargetMinuteOfDay)
        scheduleAt(appContext, next, existingWorkPolicy)
    }

    fun scheduleAt(context: Context, target: ZonedDateTime, existingWorkPolicy: ExistingWorkPolicy) {
        val now = ZonedDateTime.now()
        val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<HealthBoundaryWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME, existingWorkPolicy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}

/**
 * Pure "when might a health nudge's visible state next change" computation, purely from settings
 * and day state (no live sensor/calendar reads - this only decides when to wake up and recompute,
 * not what the state will be). Candidate boundary minutes-of-day, each included only when its
 * owning feature is enabled (and, for the taken/dismissed-gated ones, not yet taken):
 * - every water slot's start and (start + active-window) end,
 * - the morning-supplement window start and the 21:30 vitamins cutoff,
 * - the protein window start and the 18:00 protein-outranks-morning transition,
 * - [walkTargetMinuteOfDay] (the last computed walk suggestion's start, if any),
 * - the shield's default no-early-event end minute, when the restless-night shield is enabled,
 * - the caffeine lead-time window start and cutoff.
 * The earliest candidate strictly after [now] wins; if none remains today, the result is local
 * midnight + 1 minute (the day-rollover fallback), so this function never returns null.
 */
internal fun nextHealthBoundary(
    now: ZonedDateTime,
    settings: AppSettings,
    dayState: HealthDayState?,
    walkTargetMinuteOfDay: Int? = null,
    params: HealthParams = HealthParams(),
): ZonedDateTime {
    val nowMinuteOfDay = now.hour * 60 + now.minute
    val candidates = healthBoundaryMinutesOfDay(settings, dayState, walkTargetMinuteOfDay, params)
    val nextToday = candidates.firstOrNull { it > nowMinuteOfDay }
    return if (nextToday != null) {
        atMinuteOfDay(now, nextToday)
    } else {
        atMinuteOfDay(now.plusDays(1), 1)
    }
}

internal fun healthBoundaryMinutesOfDay(
    settings: AppSettings,
    dayState: HealthDayState?,
    walkTargetMinuteOfDay: Int?,
    params: HealthParams,
): List<Int> = buildList {
    if (settings.waterRemindersEnabled) {
        dayState?.waterSlotPlanMinutes?.forEach { slot ->
            add(slot)
            add(slot + params.waterActiveWindowMinutes)
        }
    }
    if (settings.morningSupplementsEnabled && dayState?.morningSupplementsTakenMinute == null) {
        add(settings.morningSupplementsStartMinuteOfDay)
        add(params.supplementMorningCutoffMinuteOfDay)
    }
    if (settings.eveningProteinEnabled && dayState?.proteinTakenMinute == null) {
        add(settings.proteinStartMinuteOfDay)
        add(params.supplementEveningPriorityMinuteOfDay)
    }
    if (settings.eveningWalkEnabled && walkTargetMinuteOfDay != null) {
        add(walkTargetMinuteOfDay)
    }
    if (settings.restlessNightShieldEnabled) {
        add(params.focusShieldNoEventEndMinuteOfDay)
    }
    if (settings.caffeineCutoffLineEnabled) {
        add((settings.caffeineCutoffMinuteOfDay - params.caffeineLeadMinutes).coerceAtLeast(0))
        add(settings.caffeineCutoffMinuteOfDay)
    }
}.distinct().sorted()

private fun atMinuteOfDay(base: ZonedDateTime, minuteOfDay: Int): ZonedDateTime =
    base.withHour(minuteOfDay / 60).withMinute(minuteOfDay % 60).withSecond(0).withNano(0)
