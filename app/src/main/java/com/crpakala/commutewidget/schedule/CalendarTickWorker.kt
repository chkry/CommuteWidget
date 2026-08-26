package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.SnapshotMode
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private const val CALENDAR_TICK_DELAY_MINUTES = 20L

/**
 * v5 opt-in coarse calendar-mode staleness tick (replaces the retired 10-minute history-sampling
 * SLOT cadence for this one purpose): fires a routes-only [RefreshTrigger.TICK] refresh roughly
 * every 20 minutes while a located calendar event is on screen. Fully event-driven with zero idle
 * wakeups - see [com.crpakala.commutewidget.engine.CommuteRefresher]'s post-refresh scheduling,
 * the sole place that (re)schedules or cancels [CalendarTickScheduler.WORK_NAME]; this worker
 * never self-reschedules.
 *
 * Re-checks [com.crpakala.commutewidget.data.AppSettings.calendarTickEnabled] and that the current
 * snapshot mode is still [SnapshotMode.CALENDAR_EVENT]. It deliberately does NOT gate on being
 * outside a commute window anymore: an event-takeover CALENDAR_EVENT displayed inside a window
 * wants freshness too, and the refresh itself re-resolves takeover-vs-commute and then schedules
 * or cancels the next tick correctly for whichever mode won.
 */
class CalendarTickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = SettingsRepository.get(applicationContext)
        val settings = repo.settingsSnapshot()
        val snapshot = repo.snapshot()
        if (snapshot == null || !shouldScheduleCalendarTick(snapshot.mode, settings.calendarTickEnabled)) {
            return Result.success()
        }

        try {
            CommuteRefresher.refreshNow(applicationContext, RefreshTrigger.TICK)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refresh errors are recorded in the snapshot; this worker never self-reschedules
            // regardless of outcome (see class doc) - the engine's post-refresh scheduling decides
            // whether the next tick happens.
        }

        return Result.success()
    }
}

/** Schedules or cancels the v5 calendar staleness tick; see [CalendarTickWorker]'s class doc. */
object CalendarTickScheduler {
    const val WORK_NAME = "calendar_tick"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<CalendarTickWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(CALENDAR_TICK_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}

/**
 * True only when the current snapshot is a located calendar event and the tick is enabled - the
 * shared schedule/cancel decision used both by
 * [com.crpakala.commutewidget.engine.CommuteRefresher] (right after a refresh) and
 * [CalendarTickWorker] (right before a tick fires).
 */
internal fun shouldScheduleCalendarTick(mode: SnapshotMode, calendarTickEnabled: Boolean): Boolean =
    calendarTickEnabled && mode == SnapshotMode.CALENDAR_EVENT
