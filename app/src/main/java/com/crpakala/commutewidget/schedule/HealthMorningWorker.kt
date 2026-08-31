package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.ensureTodayHealthDayState
import com.crpakala.commutewidget.engine.health.EventSpan
import com.crpakala.commutewidget.engine.health.HealthParams
import com.crpakala.commutewidget.engine.performSleepBackfill
import com.crpakala.commutewidget.engine.todayEventsChained
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private const val MORNING_TARGET_HOUR = 6
private const val MORNING_TARGET_MINUTE = 30

/**
 * Sprint 2: unconditional daily 06:30 run of the same "live until frozen" sleep-estimation-and-
 * history-upsert path [com.crpakala.commutewidget.engine.computeHealthState] runs on every
 * refresh ([performSleepBackfill] defers with a null return while the owner is still asleep, and
 * is frozen into a no-op once today's estimate is already settled - see
 * [com.crpakala.commutewidget.engine.health.sleepBackfillFrozen] - so running both this worker
 * and the per-refresh path on the same day is always safe), plus the day's water-slot plan (also
 * idempotent - [ensureTodayHealthDayState] no-ops once today's [com.crpakala.commutewidget
 * .data.HealthDayState] already exists). Unique work name "health_morning"; APPEND_OR_REPLACE from
 * inside (this worker IS the current holder), REPLACE only from [HealthMorningScheduler.schedule]
 * (an external caller) - see [HealthBoundaryWorker]'s identical reasoning.
 */
class HealthMorningWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withContext(NonCancellable) {
            try {
                runMorningHealthMaintenance(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            try {
                HealthMorningScheduler.scheduleAt(applicationContext, nextSixThirty(ZonedDateTime.now()), ExistingWorkPolicy.APPEND_OR_REPLACE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result.success()
    }

    private suspend fun runMorningHealthMaintenance(context: Context) {
        val repo = SettingsRepository.get(context)
        val settings = repo.settingsSnapshot()
        val zone = ZoneId.systemDefault()
        val nowEpochMillis = System.currentTimeMillis()

        performSleepBackfill(context, repo, nowEpochMillis, zone)

        val todayDateStr = ZonedDateTime.now(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val events = todayEventsChained(context, settings, nowEpochMillis, zone)
            .map { EventSpan(it.startEpochMillis, it.endEpochMillis) }
        ensureTodayHealthDayState(
            repo = repo,
            todayDateStr = todayDateStr,
            events = events,
            waterRemindersPerDay = settings.waterRemindersPerDay,
            nowEpochMillis = nowEpochMillis,
            zone = zone,
            params = HealthParams(
                waterFirstAnchorMinuteOfDay = settings.waterWindowStartMinuteOfDay,
                waterLastAnchorMinuteOfDay = settings.waterWindowEndMinuteOfDay,
                waterCutoffMinuteOfDay = settings.waterWindowEndMinuteOfDay + HealthParams().waterActiveWindowMinutes,
            ),
        )

        CommuteWidget().updateAll(context)
    }
}

/** (Re)schedules the "health_morning" daily 06:30 one-shot chain; see [HealthMorningWorker]'s class doc for the APPEND_OR_REPLACE/REPLACE split. */
object HealthMorningScheduler {
    const val WORK_NAME = "health_morning"

    /** External-caller entry point ([CommuteScheduler.ensureScheduled]): always REPLACE. */
    fun schedule(context: Context, existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        scheduleAt(context, nextSixThirty(ZonedDateTime.now()), existingWorkPolicy)
    }

    fun scheduleAt(context: Context, target: ZonedDateTime, existingWorkPolicy: ExistingWorkPolicy) {
        val now = ZonedDateTime.now()
        val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<HealthMorningWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME, existingWorkPolicy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}

/** The next 06:30 local instant strictly after [now] - today's if it hasn't passed yet, else tomorrow's. */
internal fun nextSixThirty(now: ZonedDateTime): ZonedDateTime {
    val todayTarget = now.withHour(MORNING_TARGET_HOUR).withMinute(MORNING_TARGET_MINUTE).withSecond(0).withNano(0)
    return if (todayTarget.isAfter(now)) todayTarget else todayTarget.plusDays(1)
}
