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
import com.crpakala.commutewidget.data.HealthDayState
import com.crpakala.commutewidget.data.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val HEALTH_WALK_NOTIFICATION_ID = 1003

/** Sprint 2: one new approved notification channel for health nudges, shared by any future health notification (today, only the walk suggestion uses it). */
internal const val HEALTH_CHANNEL_ID = "health"
internal const val HEALTH_CHANNEL_NAME = "Health nudges"

/**
 * Guards the check-dedup-key/post/mark critical section for the walk notification, mirroring
 * [postCommuteLeaveByIfNotAlreadyNotified]/`postIfNotAlreadyNotified` in the leave-by workers -
 * this worker's fire-time path is the only writer of [HealthDayState.walkNotified], but the lock
 * still protects against a future second caller (e.g. a manual re-trigger) racing it.
 */
private val healthWalkNotifyMutex = Mutex()

/**
 * Sprint 2: one-shot precise wake-up for the evening walk suggestion, scheduled by
 * [com.crpakala.commutewidget.engine.computeHealthState] (an external caller, hence
 * [ExistingWorkPolicy.REPLACE] - see [HealthWalkNotifyScheduler]). Posts at most one notification
 * per day; exits silently if the walk was dismissed or already notified by the time this fires.
 */
class HealthWalkNotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val startMinuteOfDay = inputData.getInt(KEY_START_MINUTE_OF_DAY, -1)
        val durationMinutes = inputData.getInt(KEY_DURATION_MINUTES, 0)
        if (startMinuteOfDay < 0) {
            return Result.success()
        }

        val repo = SettingsRepository.get(applicationContext)
        val settings = repo.settingsSnapshot()
        if (!settings.eveningWalkEnabled) {
            return Result.success()
        }

        val today = ZonedDateTime.now().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        postWalkNotificationIfNotAlreadyNotified(repo, applicationContext, today, startMinuteOfDay, durationMinutes)
        return Result.success()
    }

    companion object {
        const val KEY_START_MINUTE_OF_DAY = "start_minute_of_day"
        const val KEY_DURATION_MINUTES = "duration_minutes"
    }
}

/** Schedules (or cancels) the Sprint 2 walk notification one-shot. */
object HealthWalkNotifyScheduler {
    const val WORK_NAME = "health_walk_notify"

    fun schedule(
        context: Context,
        startEpochMillis: Long,
        durationMinutes: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val appContext = context.applicationContext
        val startMinuteOfDay = minuteOfDayFor(startEpochMillis, ZoneId.systemDefault())
        val delayMillis = (startEpochMillis - nowEpochMillis).coerceAtLeast(0)
        val data = Data.Builder()
            .putInt(HealthWalkNotifyWorker.KEY_START_MINUTE_OF_DAY, startMinuteOfDay)
            .putInt(HealthWalkNotifyWorker.KEY_DURATION_MINUTES, durationMinutes)
            .build()
        // No network constraint: this wake-up only reads DataStore and posts a notification.
        val request = OneTimeWorkRequestBuilder<HealthWalkNotifyWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels any pending walk-notify wake-up; safe to call even when none is scheduled. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}

/** True only when [startEpochMillis] is still ahead of [nowEpochMillis] - never alarm for a suggestion whose start has already passed. */
internal fun shouldScheduleWalkNotification(startEpochMillis: Long, nowEpochMillis: Long): Boolean =
    startEpochMillis > nowEpochMillis

/** True only when today's day state is on hand for [today] and the walk hasn't already been notified or dismissed. */
internal fun shouldPostWalkNotification(dayState: HealthDayState?, today: String): Boolean =
    dayState != null && dayState.date == today && !dayState.walkNotified && !dayState.walkDismissed

/**
 * Atomically checks [shouldPostWalkNotification] and, only when true, posts the notification and
 * marks [HealthDayState.walkNotified] - see [healthWalkNotifyMutex].
 */
internal suspend fun postWalkNotificationIfNotAlreadyNotified(
    repo: SettingsRepository,
    context: Context,
    today: String,
    startMinuteOfDay: Int,
    durationMinutes: Int,
): Unit = healthWalkNotifyMutex.withLock {
    if (!shouldPostWalkNotification(repo.healthDayState(), today)) {
        return@withLock
    }
    if (postWalkNotification(context, startMinuteOfDay, durationMinutes)) {
        repo.updateHealthDayState { current -> if (current?.date == today) current.copy(walkNotified = true) else current }
    }
}

private fun postWalkNotification(context: Context, startMinuteOfDay: Int, durationMinutes: Int): Boolean {
    val hasPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return false

    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    manager.createNotificationChannel(
        NotificationChannel(HEALTH_CHANNEL_ID, HEALTH_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
    )

    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = Notification.Builder(context, HEALTH_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Time for a walk")
        .setContentText("Walk ${durationMinutes}m - suggested now")
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    manager.notify(HEALTH_WALK_NOTIFICATION_ID, notification)
    return true
}
