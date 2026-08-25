package com.crpakala.commutewidget.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

private const val SLOT_FETCH_WINDOW_MARGIN_MINUTES = 5
private const val SLOT_TICK_INTERVAL_MINUTES = 10

/**
 * Slot-based history collector: fires a routes-only [CommuteRefresher] refresh while inside a
 * configured morning/evening slot on an enabled day, then always self-reschedules the next tick
 * (unless history collection has been disabled, in which case the chain intentionally ends -
 * [CommuteScheduler.ensureScheduled] restarts it once history is re-enabled).
 */
class SlotFetchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = SettingsRepository.get(applicationContext)
        val settings = repo.settingsSnapshot()
        if (!settings.historyEnabled) {
            return Result.success()
        }

        val now = ZonedDateTime.now()
        val todayIso = now.dayOfWeek.value
        val nowMinuteOfDay = now.hour * 60 + now.minute
        val slots = CommuteScheduler.slotRanges(settings)

        try {
            if (isWithinSlotFetchWindow(todayIso, nowMinuteOfDay, settings.historyDays, slots)) {
                CommuteRefresher.refreshNow(applicationContext, RefreshTrigger.SLOT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refresh errors are recorded in the snapshot; the chain must still reschedule below.
        }

        val next = nextSlotTick(now, settings.historyDays, slots)
        if (next != null) {
            // APPEND_OR_REPLACE, not REPLACE: this worker IS the current holder of the slot chain's
            // unique work name, and REPLACE would cancel (self-cancel) this still-running invocation.
            CommuteScheduler.scheduleSlotChainAt(applicationContext, next, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        return Result.success()
    }
}

/**
 * True when [nowMinuteOfDay] falls within [SLOT_FETCH_WINDOW_MARGIN_MINUTES] of either edge of any
 * slot on a day enabled for history collection. The margin absorbs WorkManager's own scheduling
 * jitter around each tick.
 */
internal fun isWithinSlotFetchWindow(
    todayIso: Int,
    nowMinuteOfDay: Int,
    historyDays: Set<Int>,
    slots: List<IntRange>,
    marginMinutes: Int = SLOT_FETCH_WINDOW_MARGIN_MINUTES,
): Boolean {
    if (todayIso !in historyDays) return false
    return slots.any { it.first < it.last && nowMinuteOfDay in (it.first - marginMinutes)..(it.last + marginMinutes) }
}

/**
 * Pure computation of the next slot-collection instant.
 *
 * If [now] falls inside a slot on a day enabled by [historyDays], the next tick is [now] plus
 * [intervalMinutes] - unless that would land past the slot's end, in which case the result jumps
 * to the start of the next slot (same day if one remains, otherwise the first slot on the next
 * enabled day). If [now] falls outside any slot, the result is the next slot start today (if one
 * remains) or on the next enabled day. Invalid ranges (`start >= end`) are ignored. Returns null
 * only when [historyDays] is empty or no valid slot remains after filtering.
 */
internal fun nextSlotTick(
    now: ZonedDateTime,
    historyDays: Set<Int>,
    slots: List<IntRange>,
    intervalMinutes: Int = SLOT_TICK_INTERVAL_MINUTES,
): ZonedDateTime? {
    val validSlots = slots.filter { it.first < it.last }.sortedBy { it.first }
    if (historyDays.isEmpty() || validSlots.isEmpty()) return null

    val todayIso = now.dayOfWeek.value
    val nowMinuteOfDay = now.hour * 60 + now.minute

    if (todayIso in historyDays) {
        val containingSlot = validSlots.firstOrNull { nowMinuteOfDay in it }
        if (containingSlot != null) {
            val tick = nowMinuteOfDay + intervalMinutes
            if (tick <= containingSlot.last) {
                return atMinuteOfDay(now, tick)
            }
        }
        val nextSlotToday = validSlots.firstOrNull { it.first > nowMinuteOfDay }
        if (nextSlotToday != null) {
            return atMinuteOfDay(now, nextSlotToday.first)
        }
    }

    val firstSlotStart = validSlots.first().first
    for (offset in 1..7) {
        val candidateDay = now.plusDays(offset.toLong())
        if (candidateDay.dayOfWeek.value in historyDays) {
            return atMinuteOfDay(candidateDay, firstSlotStart)
        }
    }
    return null
}

private fun atMinuteOfDay(base: ZonedDateTime, minuteOfDay: Int): ZonedDateTime =
    base.withHour(minuteOfDay / 60).withMinute(minuteOfDay % 60).withSecond(0).withNano(0)
