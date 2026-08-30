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
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object CommuteScheduler {
    const val WINDOW_BOUNDARY_WORK_NAME = "commute_window_boundary"

    /**
     * v2 unique work names for the fixed 8am/5pm [CommuteRefreshWorker]-style refreshes, retired
     * by the v3 window model in favor of [WINDOW_BOUNDARY_WORK_NAME]. [ensureScheduled] cancels
     * these explicitly on every call as one-time migration hygiene: WorkManager persists unique
     * work across app updates, so a device that installed v2 before upgrading would otherwise
     * keep firing these indefinitely even though the code (and the settings fields driving their
     * schedule) that enqueued them no longer exists.
     */
    private const val LEGACY_MORNING_WORK_NAME = "commute_refresh_morning"
    private const val LEGACY_EVENING_WORK_NAME = "commute_refresh_evening"

    /**
     * v3/v4 unique work name for the 10-minute in-window history-sampling chain (formerly
     * `SlotFetchWorker`), retired entirely in v5 along with the history subsystem it fed - see
     * [com.crpakala.commutewidget.schedule.CalendarTickScheduler] and
     * [com.crpakala.commutewidget.schedule.CommuteLeaveByScheduler] for what replaced its two
     * actual jobs (calendar staleness, commute leave-by). Cancelled on every [ensureScheduled]
     * call for the same device-migration reason as [LEGACY_MORNING_WORK_NAME]/
     * [LEGACY_EVENING_WORK_NAME] above: a device last running a pre-v5 build could otherwise keep
     * this chain alive in WorkManager's persisted store indefinitely, even though the worker class
     * and the history table it wrote to are both gone.
     */
    private const val LEGACY_SLOT_CHAIN_WORK_NAME = "commute_slot_chain"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsRepository.get(appContext).settingsSnapshot()
        cancelLegacyRefreshWorks(appContext)
        scheduleWindowBoundary(appContext, settings)
        if (shouldObserveCalendar(settings.calendarEnabled, settings.selectedCalendarIds.isNotEmpty())) {
            CalendarChangeScheduler.schedule(appContext)
        } else {
            CalendarChangeScheduler.cancel(appContext)
        }
        if (anyHealthFeatureEnabled(settings)) {
            HealthMorningScheduler.schedule(appContext)
            HealthBoundaryScheduler.schedule(appContext, settings)
        } else {
            HealthMorningScheduler.cancel(appContext)
            HealthBoundaryScheduler.cancel(appContext)
        }
    }

    fun ensureScheduledAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            ensureScheduled(appContext)
        }
    }

    private fun cancelLegacyRefreshWorks(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(LEGACY_MORNING_WORK_NAME)
        workManager.cancelUniqueWork(LEGACY_EVENING_WORK_NAME)
        workManager.cancelUniqueWork(LEGACY_SLOT_CHAIN_WORK_NAME)
    }

    /**
     * (Re)schedules the "commute_window_boundary" unique work to fire [WindowBoundaryWorker] at
     * the next morning/evening window start or end boundary computed by [nextWindowBoundary]. A
     * no-op when nothing is enabled - the boundary chain simply ends until [ensureScheduled] runs
     * again with a config that produces a boundary.
     *
     * [existingWorkPolicy] defaults to [ExistingWorkPolicy.REPLACE] for external callers (app boot,
     * widget enable, settings changes) so a stale chain is always replaced immediately.
     *
     * A worker scheduling its own successor must instead pass [ExistingWorkPolicy.APPEND_OR_REPLACE]:
     * REPLACE cancels any pending (uncompleted) work under the same unique name, and a currently
     * *running* worker counts as uncompleted, so a worker that REPLACEs its own unique name cancels
     * itself mid-run (see WorkManager's ExistingWorkPolicy docs). APPEND_OR_REPLACE instead chains
     * the successor to run after this invocation finishes, regardless of outcome.
     */
    internal fun scheduleWindowBoundary(
        context: Context,
        settings: AppSettings,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val next = nextWindowBoundary(
            now = ZonedDateTime.now(),
            commuteDays = settings.commuteDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        ) ?: return
        scheduleWindowBoundaryAt(context, next, existingWorkPolicy)
    }

    /**
     * Sprint 2: whether the "health_morning"/"health_boundary" chains are worth running at all -
     * true when any independent health nudge feature is on. Deliberately excludes sub-toggles
     * that only ever modify an already-gated feature's behavior rather than introducing a nudge
     * of their own ([AppSettings.gymProteinPriorityEnabled], [AppSettings.sleepDebtSoftenEnabled],
     * [AppSettings.walkPostAudibleLatchEnabled], [AppSettings.walkDaylightPreferenceEnabled],
     * [AppSettings.audiobookSuppressionEnabled]): each of those is meaningless with its owning
     * toggle off, so it never needs to arm the chain on its own.
     */
    internal fun anyHealthFeatureEnabled(settings: AppSettings): Boolean =
        settings.morningSupplementsEnabled ||
            settings.eveningProteinEnabled ||
            settings.waterRemindersEnabled ||
            settings.eveningWalkEnabled ||
            settings.sleepBriefEnabled ||
            settings.restlessNightShieldEnabled ||
            settings.focusGapChipEnabled ||
            settings.postGymWaterPulseEnabled ||
            settings.morningLightLineEnabled ||
            settings.caffeineCutoffLineEnabled

    internal fun scheduleWindowBoundaryAt(
        context: Context,
        target: ZonedDateTime,
        existingWorkPolicy: ExistingWorkPolicy,
    ) {
        val now = ZonedDateTime.now()
        val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<WindowBoundaryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WINDOW_BOUNDARY_WORK_NAME,
            existingWorkPolicy,
            request,
        )
    }
}
