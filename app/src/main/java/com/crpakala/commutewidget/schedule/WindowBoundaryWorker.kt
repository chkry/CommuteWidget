package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * v3 auto-refresh: fires an [RefreshTrigger.AUTO] refresh at every morning/evening window START
 * and END boundary on an enabled day - a start boundary warms the commute data ahead of the
 * window, an end boundary auto-flips the widget into calendar mode (see
 * `com.crpakala.commutewidget.engine.resolveWidgetMode`). Always self-reschedules to the next
 * boundary after running, unless nothing remains enabled.
 */
class WindowBoundaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            CommuteRefresher.refreshNow(applicationContext, RefreshTrigger.AUTO)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refresh errors are recorded in the snapshot; the chain must still reschedule below.
        }

        val settings = SettingsRepository.get(applicationContext).settingsSnapshot()
        val next = nextWindowBoundary(
            now = ZonedDateTime.now(),
            historyDays = settings.historyDays,
            morningStart = settings.morningSlotStartMinuteOfDay,
            morningEnd = settings.morningSlotEndMinuteOfDay,
            eveningStart = settings.eveningSlotStartMinuteOfDay,
            eveningEnd = settings.eveningSlotEndMinuteOfDay,
        )
        if (next != null) {
            // APPEND_OR_REPLACE, not REPLACE: this worker IS the current holder of
            // "commute_window_boundary", and REPLACE would cancel (self-cancel) this still-running
            // invocation - the same anti-self-cancel pattern CommuteScheduler documents for
            // SlotFetchWorker's own chain.
            CommuteScheduler.scheduleWindowBoundaryAt(applicationContext, next, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        return Result.success()
    }
}

/**
 * Pure computation of the next morning/evening window boundary instant - a window START or END
 * minute-of-day, whichever comes first - among enabled [historyDays]. If [now] falls on an
 * enabled day and a boundary remains later today, that is the result; otherwise the search
 * advances to the earliest boundary on the next enabled day, wrapping the week (e.g. Friday
 * evening end -> Monday morning start). Invalid windows (`start >= end`) contribute no
 * boundaries. Returns null when [historyDays] is empty or neither window is valid.
 */
internal fun nextWindowBoundary(
    now: ZonedDateTime,
    historyDays: Set<Int>,
    morningStart: Int,
    morningEnd: Int,
    eveningStart: Int,
    eveningEnd: Int,
): ZonedDateTime? {
    val boundaries = buildList {
        if (morningStart < morningEnd) {
            add(morningStart)
            add(morningEnd)
        }
        if (eveningStart < eveningEnd) {
            add(eveningStart)
            add(eveningEnd)
        }
    }.distinct().sorted()
    if (historyDays.isEmpty() || boundaries.isEmpty()) return null

    val todayIso = now.dayOfWeek.value
    val nowMinuteOfDay = now.hour * 60 + now.minute

    if (todayIso in historyDays) {
        val nextToday = boundaries.firstOrNull { it > nowMinuteOfDay }
        if (nextToday != null) {
            return atMinuteOfDay(now, nextToday)
        }
    }

    for (offset in 1..7) {
        val candidateDay = now.plusDays(offset.toLong())
        if (candidateDay.dayOfWeek.value in historyDays) {
            return atMinuteOfDay(candidateDay, boundaries.first())
        }
    }
    return null
}

private fun atMinuteOfDay(base: ZonedDateTime, minuteOfDay: Int): ZonedDateTime =
    base.withHour(minuteOfDay / 60).withMinute(minuteOfDay % 60).withSecond(0).withNano(0)
