package com.crpakala.commutewidget.schedule

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private const val TRIGGER_DEBOUNCE_SECONDS = 10L
private const val TRIGGER_MAX_DELAY_SECONDS = 60L

/**
 * Calendar change observer: a content-URI-triggered one-shot that fires when anything in the
 * device calendar provider changes (event deleted, added, moved, or edited), so the widget
 * corrects a stale event route within seconds instead of waiting for the next tap, window
 * boundary, or 20-minute tick. Content-trigger works are single-shot observers by WorkManager
 * design, so the worker re-arms itself; [ExistingWorkPolicy.APPEND_OR_REPLACE] for the self
 * re-arm per the anti-self-cancel pattern documented on
 * [CommuteScheduler.scheduleWindowBoundary].
 */
class CalendarChangeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository.get(applicationContext).settingsSnapshot()
        if (!shouldObserveCalendar(settings.calendarEnabled, settings.selectedCalendarIds.isNotEmpty())) {
            // Feature off: do not re-arm; ensureScheduled re-arms when it is enabled again.
            return Result.success()
        }
        // Re-arm before refreshing so a failure in the refresh cannot kill the observer chain.
        CalendarChangeScheduler.schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        try {
            CommuteRefresher.refreshNow(applicationContext, RefreshTrigger.AUTO)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refresh errors are recorded in the snapshot; the observer chain is already re-armed.
        }
        return Result.success()
    }
}

/** Schedules or cancels the calendar change observer; see [CalendarChangeWorker]'s class doc. */
object CalendarChangeScheduler {
    const val WORK_NAME = "calendar_change_observer"

    fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val request = OneTimeWorkRequestBuilder<CalendarChangeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(TRIGGER_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}

/** The observer only earns its wakeups when calendar features can actually affect the widget. */
internal fun shouldObserveCalendar(calendarEnabled: Boolean, hasSelectedCalendars: Boolean): Boolean =
    calendarEnabled && hasSelectedCalendars
