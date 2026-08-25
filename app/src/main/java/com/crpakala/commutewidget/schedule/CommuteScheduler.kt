package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object CommuteScheduler {
    const val MORNING_WORK_NAME = "commute_refresh_morning"
    const val EVENING_WORK_NAME = "commute_refresh_evening"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsRepository.get(appContext).settingsSnapshot()
        scheduleSlot(appContext, CommuteRefreshWorker.Slot.MORNING, settings)
        scheduleSlot(appContext, CommuteRefreshWorker.Slot.EVENING, settings)
    }

    fun ensureScheduledAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            ensureScheduled(appContext)
        }
    }

    /**
     * [existingWorkPolicy] defaults to [ExistingWorkPolicy.REPLACE] for external callers (app boot,
     * widget enable, settings changes) so a stale chain is always replaced immediately.
     *
     * [CommuteRefreshWorker] itself must pass [ExistingWorkPolicy.APPEND_OR_REPLACE] when scheduling
     * its own successor: REPLACE cancels any pending (uncompleted) work under the same unique name,
     * and a currently-*running* worker counts as uncompleted, so a worker that REPLACEs its own
     * unique name cancels itself mid-run (see WorkManager's ExistingWorkPolicy docs). APPEND_OR_REPLACE
     * instead chains the successor to run after this invocation finishes, regardless of outcome.
     */
    internal fun scheduleSlot(
        context: Context,
        slot: CommuteRefreshWorker.Slot,
        settings: AppSettings,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val now = ZonedDateTime.now()
        val target = nextWeekdayOccurrence(now, slot.minuteOfDay(settings))
        val delayMillis = Duration.between(now, target).toMillis()
        val request = OneTimeWorkRequestBuilder<CommuteRefreshWorker>()
            .setInputData(CommuteRefreshWorker.inputData(slot))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            slot.workName,
            existingWorkPolicy,
            request,
        )
    }

    fun nextWeekdayOccurrence(now: ZonedDateTime, minuteOfDay: Int): ZonedDateTime {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) {
            "minuteOfDay must be between 0 and ${MINUTES_PER_DAY - 1}"
        }

        var target = now
            .withHour(minuteOfDay / MINUTES_PER_HOUR)
            .withMinute(minuteOfDay % MINUTES_PER_HOUR)
            .withSecond(0)
            .withNano(0)
        if (!isWeekday(now.dayOfWeek) || !now.isBefore(target)) {
            target = target.plusDays(1)
        }
        while (!isWeekday(target.dayOfWeek)) {
            target = target.plusDays(1)
        }
        return target
    }

    private fun isWeekday(dayOfWeek: DayOfWeek): Boolean {
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
}
