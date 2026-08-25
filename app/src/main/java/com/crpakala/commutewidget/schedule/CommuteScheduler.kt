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
    const val SLOT_CHAIN_WORK_NAME = "commute_slot_chain"
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsRepository.get(appContext).settingsSnapshot()
        cancelLegacyRefreshWorks(appContext)
        scheduleSlotChain(appContext, settings)
        scheduleWindowBoundary(appContext, settings)
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
    }

    /**
     * [existingWorkPolicy] defaults to [ExistingWorkPolicy.REPLACE] for external callers (app boot,
     * widget enable, settings changes) so a stale chain is always replaced immediately.
     *
     * A worker scheduling its own successor must instead pass [ExistingWorkPolicy.APPEND_OR_REPLACE]:
     * REPLACE cancels any pending (uncompleted) work under the same unique name, and a currently
     * *running* worker counts as uncompleted, so a worker that REPLACEs its own unique name cancels
     * itself mid-run (see WorkManager's ExistingWorkPolicy docs). APPEND_OR_REPLACE instead chains
     * the successor to run after this invocation finishes, regardless of outcome.
     */
    internal fun scheduleSlotChain(
        context: Context,
        settings: AppSettings,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        if (!settings.historyEnabled) {
            return
        }
        val next = nextSlotTick(ZonedDateTime.now(), settings.historyDays, slotRanges(settings)) ?: return
        scheduleSlotChainAt(context, next, existingWorkPolicy)
    }

    internal fun scheduleSlotChainAt(
        context: Context,
        target: ZonedDateTime,
        existingWorkPolicy: ExistingWorkPolicy,
    ) {
        val now = ZonedDateTime.now()
        val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<SlotFetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            SLOT_CHAIN_WORK_NAME,
            existingWorkPolicy,
            request,
        )
    }

    /**
     * (Re)schedules the "commute_window_boundary" unique work to fire [WindowBoundaryWorker] at
     * the next morning/evening window start or end boundary computed by [nextWindowBoundary]. A
     * no-op when nothing is enabled (mirrors [scheduleSlotChain]'s no-op contract) - the boundary
     * chain simply ends until [ensureScheduled] runs again with a config that produces a boundary.
     */
    internal fun scheduleWindowBoundary(
        context: Context,
        settings: AppSettings,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val next = nextWindowBoundary(
            now = ZonedDateTime.now(),
            historyDays = settings.historyDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        ) ?: return
        scheduleWindowBoundaryAt(context, next, existingWorkPolicy)
    }

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

    /** The morning and evening minute-of-day windows history collection samples within. */
    internal fun slotRanges(settings: AppSettings): List<IntRange> = listOf(
        settings.morningSlotStartMinuteOfDay..settings.morningSlotEndMinuteOfDay,
        settings.eveningSlotStartMinuteOfDay..settings.eveningSlotEndMinuteOfDay,
    )
}
