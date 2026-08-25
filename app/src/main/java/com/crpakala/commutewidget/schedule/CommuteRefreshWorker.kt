package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.CommuteRefresher
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

class CommuteRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val slot = Slot.from(inputData.getString(INPUT_SLOT)) ?: return Result.success()

        try {
            if (isWeekday(ZonedDateTime.now().dayOfWeek)) {
                CommuteRefresher.refreshNow(applicationContext)
            }
            val settings = SettingsRepository.get(applicationContext).settingsSnapshot()
            // APPEND_OR_REPLACE, not REPLACE: this worker IS the current holder of `slot.workName`,
            // and REPLACE would cancel (i.e. self-cancel) any pending work under that same name.
            CommuteScheduler.scheduleSlot(applicationContext, slot, settings, ExistingWorkPolicy.APPEND_OR_REPLACE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refresh errors are recorded in the snapshot and work retries would duplicate fetches.
        }

        return Result.success()
    }

    enum class Slot(
        val value: String,
        val workName: String,
    ) {
        MORNING("morning", CommuteScheduler.MORNING_WORK_NAME),
        EVENING("evening", CommuteScheduler.EVENING_WORK_NAME),
        ;

        fun minuteOfDay(settings: AppSettings): Int {
            return when (this) {
                MORNING -> settings.morningRefreshMinuteOfDay
                EVENING -> settings.eveningRefreshMinuteOfDay
            }
        }

        companion object {
            fun from(value: String?): Slot? = entries.firstOrNull { it.value == value }
        }
    }

    companion object {
        const val INPUT_SLOT = "slot"

        fun inputData(slot: Slot): Data = Data.Builder()
            .putString(INPUT_SLOT, slot.value)
            .build()

        private fun isWeekday(dayOfWeek: DayOfWeek): Boolean {
            return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
        }
    }
}
