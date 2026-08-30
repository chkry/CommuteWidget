package com.crpakala.commutewidget.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** One SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE transition, for the sleep estimator. */
data class ScreenEvent(val timestampEpochMillis: Long, val interactive: Boolean)

object ScreenEventsReader {

    /** True only when the user has granted this app Usage access (Settings > Usage access). */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return@runCatching false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Screen interactive/non-interactive events in `[startEpochMillis, endEpochMillis)`.
     * Empty on no Usage access or any failure; event types other than screen on/off are ignored.
     */
    fun readScreenEvents(context: Context, startEpochMillis: Long, endEpochMillis: Long): List<ScreenEvent> =
        runCatching {
            if (!hasUsageAccess(context)) return@runCatching emptyList()
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@runCatching emptyList()
            val events = usageStatsManager.queryEvents(startEpochMillis, endEpochMillis)
            val event = UsageEvents.Event()
            val result = mutableListOf<ScreenEvent>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val interactive = mapScreenEventType(event.eventType) ?: continue
                result.add(ScreenEvent(timestampEpochMillis = event.timeStamp, interactive = interactive))
            }
            result
        }.getOrDefault(emptyList())

    /**
     * Pure mapping from a raw [UsageEvents.Event.getEventType] value to whether it represents
     * the screen turning interactive (true), non-interactive (false), or an event to ignore (null).
     */
    internal fun mapScreenEventType(eventType: Int): Boolean? = when (eventType) {
        UsageEvents.Event.SCREEN_INTERACTIVE -> true
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> false
        else -> null
    }
}
