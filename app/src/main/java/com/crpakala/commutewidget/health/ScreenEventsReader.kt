package com.crpakala.commutewidget.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** One SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE transition, for the sleep estimator. */
data class ScreenEvent(val timestampEpochMillis: Long, val interactive: Boolean)

/** One KEYGUARD_SHOWN / KEYGUARD_HIDDEN transition, for the strict lock/unlock sleep estimator. */
data class KeyguardEvent(val timestampEpochMillis: Long, val locked: Boolean)

/** Both event streams from a single UsageStats query pass over the same time range. */
data class UsageWindowEvents(val screen: List<ScreenEvent>, val keyguard: List<KeyguardEvent>)

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
     * Kept for compatibility; delegates to [readUsageWindowEvents] so callers that only need
     * screen transitions don't have to know about the combined read.
     */
    fun readScreenEvents(context: Context, startEpochMillis: Long, endEpochMillis: Long): List<ScreenEvent> =
        readUsageWindowEvents(context, startEpochMillis, endEpochMillis).screen

    /**
     * Screen and keyguard transitions in `[startEpochMillis, endEpochMillis)`, read from a
     * single UsageStats query pass. Empty lists on no Usage access or any failure; event types
     * other than screen on/off and keyguard shown/hidden are ignored. Same Usage access
     * permission as [readScreenEvents]; no new permissions.
     */
    fun readUsageWindowEvents(
        context: Context,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): UsageWindowEvents =
        runCatching {
            if (!hasUsageAccess(context)) return@runCatching UsageWindowEvents(emptyList(), emptyList())
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@runCatching UsageWindowEvents(emptyList(), emptyList())
            val events = usageStatsManager.queryEvents(startEpochMillis, endEpochMillis)
            val event = UsageEvents.Event()
            val screen = mutableListOf<ScreenEvent>()
            val keyguard = mutableListOf<KeyguardEvent>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                mapScreenEventType(event.eventType)?.let { interactive ->
                    screen.add(ScreenEvent(timestampEpochMillis = event.timeStamp, interactive = interactive))
                }
                mapKeyguardEventType(event.eventType)?.let { locked ->
                    keyguard.add(KeyguardEvent(timestampEpochMillis = event.timeStamp, locked = locked))
                }
            }
            UsageWindowEvents(screen = screen, keyguard = keyguard)
        }.getOrDefault(UsageWindowEvents(emptyList(), emptyList()))

    /**
     * Pure mapping from a raw [UsageEvents.Event.getEventType] value to whether it represents
     * the screen turning interactive (true), non-interactive (false), or an event to ignore (null).
     */
    internal fun mapScreenEventType(eventType: Int): Boolean? = when (eventType) {
        UsageEvents.Event.SCREEN_INTERACTIVE -> true
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> false
        else -> null
    }

    /**
     * Pure mapping from a raw [UsageEvents.Event.getEventType] value to whether it represents
     * the keyguard being shown (true = locked), hidden (false = unlocked), or an event to
     * ignore (null). Mirrors [mapScreenEventType].
     */
    internal fun mapKeyguardEventType(eventType: Int): Boolean? = when (eventType) {
        UsageEvents.Event.KEYGUARD_SHOWN -> true
        UsageEvents.Event.KEYGUARD_HIDDEN -> false
        else -> null
    }
}
