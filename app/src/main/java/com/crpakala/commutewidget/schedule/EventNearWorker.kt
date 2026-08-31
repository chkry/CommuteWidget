package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Far-located-event near-flip: a located calendar event farther than
 * [com.crpakala.commutewidget.data.AppSettings.eventTakeoverMinutes] away renders as a plain
 * zero-API card instead of being routed (see
 * [com.crpakala.commutewidget.engine.CommuteRefresher.performCalendarRefresh]'s far-located gate).
 * This one-shot wakes exactly when that event crosses into the takeover window and fires a normal
 * [RefreshTrigger.AUTO] refresh, which re-resolves calendar mode and routes the now-near event
 * automatically. Never self-reschedules - only [performCalendarRefresh] (an external caller
 * relative to this worker) ever (re)arms or cancels [EventNearScheduler]'s unique work, exactly
 * once per calendar-refresh outcome (see that function's doc for the arm/cancel matrix). A stale
 * flip firing during a commute window is harmless: the AUTO refresh's own mode resolution and
 * [com.crpakala.commutewidget.engine.eventTakeoverApplies] check decide the outcome fresh, which
 * routes the now-near event - the desired result either way.
 */
class EventNearWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            CommuteRefresher.refreshNow(applicationContext, RefreshTrigger.AUTO)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refresh errors are recorded in the snapshot; this worker never self-reschedules -
            // the next arm comes only from a future performCalendarRefresh call, per its own doc.
        }
        return Result.success()
    }
}

/**
 * Schedules or cancels the far-located-event near-flip one-shot; see [EventNearWorker]'s class
 * doc. [scheduleAt] is only ever called from
 * [com.crpakala.commutewidget.engine.CommuteRefresher.performCalendarRefresh] (an external caller
 * relative to this worker, which never reschedules itself), so
 * [ExistingWorkPolicy.REPLACE] is correct per the repo's WorkManager invariant -
 * [ExistingWorkPolicy.APPEND_OR_REPLACE] is reserved for a worker rescheduling its own unique
 * work, which this one never does.
 */
object EventNearScheduler {
    const val WORK_NAME = "event_near"

    /** [targetEpochMillis] in the past (or now) fires as soon as WorkManager can run it (delay 0). */
    fun scheduleAt(context: Context, targetEpochMillis: Long) {
        val appContext = context.applicationContext
        val delayMillis = (targetEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<EventNearWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}
