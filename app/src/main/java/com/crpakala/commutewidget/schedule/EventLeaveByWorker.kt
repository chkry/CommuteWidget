package com.crpakala.commutewidget.schedule

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crpakala.commutewidget.MainActivity
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.eventIdentityKey
import com.crpakala.commutewidget.engine.LEAVE_BY_CHANNEL_ID
import com.crpakala.commutewidget.engine.LEAVE_BY_CHANNEL_NAME
import com.crpakala.commutewidget.formatClockTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

private const val EVENT_LEAVE_BY_NOTIFICATION_ID = 1002

/**
 * Guards the check-dedup-key/post/mark critical section shared by [EventLeaveByScheduler]'s
 * immediate-post path and [EventLeaveByWorker.doWork]'s fire-time path. Those two run on
 * independent coroutines (a live refresh vs. a WorkManager-scheduled wake-up) with no other
 * synchronization between them, so without this lock both could read the dedup key as "not yet
 * notified" for the same event at the same instant and post twice.
 */
private val eventLeaveByMutex = Mutex()

/**
 * v4 event leave-by advisor: a zero-API-cost scheduled wake-up for a single located calendar
 * event, distinct from [com.crpakala.commutewidget.engine.CommuteRefresher]'s per-window commute
 * leave-by notification (different channel usage - same channel ID, distinct notification ID -
 * and a completely separate dedup/scheduling story keyed by event identity rather than direction
 * and day). Re-checks [com.crpakala.commutewidget.data.AppSettings.leaveByEnabled] and the dedup
 * key at fire time because a REPLACE-scheduled successor could theoretically race a settings
 * change or a newer [EventLeaveByScheduler.scheduleOrPost] call between enqueue and fire.
 */
class EventLeaveByWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventKey = inputData.getString(KEY_EVENT_KEY)
        val title = inputData.getString(KEY_TITLE)
        val leaveByEpochMillis = inputData.getLong(KEY_LEAVE_BY_EPOCH_MILLIS, -1L)
        val driveMinutes = inputData.getInt(KEY_DRIVE_MINUTES, 0)
        if (eventKey == null || title == null || leaveByEpochMillis < 0) {
            return Result.success()
        }

        val repo = SettingsRepository.get(applicationContext)
        val settings = repo.settingsSnapshot()
        if (!settings.leaveByEnabled) {
            return Result.success()
        }

        postIfNotAlreadyNotified(repo, applicationContext, eventKey, title, leaveByEpochMillis, driveMinutes)
        return Result.success()
    }

    companion object {
        const val KEY_EVENT_KEY = "event_key"
        const val KEY_TITLE = "title"
        const val KEY_LEAVE_BY_EPOCH_MILLIS = "leave_by_epoch_millis"
        const val KEY_DRIVE_MINUTES = "drive_minutes"
    }
}

/**
 * Schedules (or immediately fires) the v4 event leave-by notification, and cancels it when a
 * calendar refresh no longer yields a located event with a computed leave-by.
 */
object EventLeaveByScheduler {
    const val WORK_NAME = "event_leave_by_notify"

    /**
     * [eventStartEpochMillis]/[eventTitle] identify the event ([eventIdentityKey]); already
     * notified for this exact event -> no-op. Otherwise, a past-due [leaveByEpochMillis] posts
     * immediately (mirrors [com.crpakala.commutewidget.engine.CommuteRefresher]'s post-then-mark
     * convention: the dedup key is only written when the notification actually posted, so a
     * denied/revoked POST_NOTIFICATIONS permission does not silently and permanently suppress a
     * later grant), otherwise a one-shot [EventLeaveByWorker] is enqueued for the future instant.
     */
    suspend fun scheduleOrPost(
        context: Context,
        eventTitle: String,
        eventStartEpochMillis: Long,
        leaveByEpochMillis: Long,
        durationSeconds: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val appContext = context.applicationContext
        val repo = SettingsRepository.get(appContext)
        val eventKey = eventIdentityKey(eventStartEpochMillis, eventTitle)
        val driveMinutes = eventLeaveByDriveMinutes(durationSeconds)

        // The due/immediate-post branch always goes through the mutex-guarded helper (see
        // [postIfNotAlreadyNotified]) rather than pre-checking the dedup key here itself, since
        // [EventLeaveByWorker.doWork] can be posting for this exact event concurrently.
        if (isEventLeaveByDue(leaveByEpochMillis, nowEpochMillis)) {
            postIfNotAlreadyNotified(repo, appContext, eventKey, eventTitle, leaveByEpochMillis, driveMinutes)
            return
        }

        if (!shouldScheduleEventLeaveBy(eventKey, repo.eventLeaveByNotifiedKey())) {
            return
        }
        enqueue(appContext, eventKey, eventTitle, leaveByEpochMillis, driveMinutes, nowEpochMillis)
    }

    /** Cancels any pending event leave-by wake-up; safe to call even when none is scheduled. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(
        context: Context,
        eventKey: String,
        eventTitle: String,
        leaveByEpochMillis: Long,
        driveMinutes: Int,
        nowEpochMillis: Long,
    ) {
        val delayMillis = (leaveByEpochMillis - nowEpochMillis).coerceAtLeast(0)
        val data = Data.Builder()
            .putString(EventLeaveByWorker.KEY_EVENT_KEY, eventKey)
            .putString(EventLeaveByWorker.KEY_TITLE, eventTitle)
            .putLong(EventLeaveByWorker.KEY_LEAVE_BY_EPOCH_MILLIS, leaveByEpochMillis)
            .putInt(EventLeaveByWorker.KEY_DRIVE_MINUTES, driveMinutes)
            .build()
        // No network constraint: this wake-up only reads DataStore and posts a notification.
        val request = OneTimeWorkRequestBuilder<EventLeaveByWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Atomically checks the dedup key and, only when [eventKey] has not already been notified, posts
 * the notification and marks it notified. Callers ([EventLeaveByScheduler.scheduleOrPost]'s
 * immediate-post branch and [EventLeaveByWorker.doWork]) must route through this single function
 * rather than inlining the check-then-act sequence themselves - see [eventLeaveByMutex].
 */
private suspend fun postIfNotAlreadyNotified(
    repo: SettingsRepository,
    context: Context,
    eventKey: String,
    eventTitle: String,
    leaveByEpochMillis: Long,
    driveMinutes: Int,
): Unit = eventLeaveByMutex.withLock {
    if (!shouldScheduleEventLeaveBy(eventKey, repo.eventLeaveByNotifiedKey())) {
        return@withLock
    }
    if (postEventLeaveByNotification(context, eventTitle, leaveByEpochMillis, driveMinutes)) {
        repo.markEventLeaveByNotified(eventKey)
    }
}

/** True when [eventKey] has not already been notified (dedup is a single stored key, overwritten per event). */
internal fun shouldScheduleEventLeaveBy(eventKey: String, notifiedKey: String?): Boolean = eventKey != notifiedKey

/** Whole drive minutes, rounded up - matches [com.crpakala.commutewidget.engine.computeLeaveByMinuteOfDay]'s convention. */
internal fun eventLeaveByDriveMinutes(durationSeconds: Long): Int = ceil(durationSeconds / 60.0).toInt()

/** True when [leaveByEpochMillis] has already passed (or is now) relative to [nowEpochMillis]. */
internal fun isEventLeaveByDue(leaveByEpochMillis: Long, nowEpochMillis: Long): Boolean =
    leaveByEpochMillis <= nowEpochMillis

private fun postEventLeaveByNotification(
    context: Context,
    eventTitle: String,
    leaveByEpochMillis: Long,
    driveMinutes: Int,
): Boolean {
    val hasPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return false

    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    manager.createNotificationChannel(
        NotificationChannel(LEAVE_BY_CHANNEL_ID, LEAVE_BY_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
    )

    val leaveByTime = formatClockTime(minuteOfDayFor(leaveByEpochMillis, ZoneId.systemDefault()))
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = Notification.Builder(context, LEAVE_BY_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Time to leave")
        .setContentText("Leave by $leaveByTime for $eventTitle - $driveMinutes min drive")
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    manager.notify(EVENT_LEAVE_BY_NOTIFICATION_ID, notification)
    return true
}

/** Local minute-of-day of an epoch-millis instant, for reuse with [formatClockTime]'s widget-consistent "h:mm a" format. */
internal fun minuteOfDayFor(epochMillis: Long, zoneId: ZoneId): Int {
    val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    return zoned.hour * 60 + zoned.minute
}
