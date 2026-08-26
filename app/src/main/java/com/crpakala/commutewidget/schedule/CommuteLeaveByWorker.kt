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
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.LEAVE_BY_CHANNEL_ID
import com.crpakala.commutewidget.engine.LEAVE_BY_CHANNEL_NAME
import com.crpakala.commutewidget.formatClockTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val COMMUTE_LEAVE_BY_NOTIFICATION_ID = 1001

/**
 * Guards the check-dedup-key/post/mark critical section shared by
 * [com.crpakala.commutewidget.engine.CommuteRefresher]'s in-refresh immediate-fire path and
 * [CommuteLeaveByWorker.doWork]'s fire-time path. Those two run on independent coroutines (a live
 * refresh vs. a WorkManager-scheduled wake-up) with no other synchronization between them, so
 * without this lock both could read the dedup key as "not yet notified" for the same
 * direction/date at the same instant and post twice. Mirrors [EventLeaveByWorker]'s identical
 * mutex, kept separate because the two advisors dedup on different keys (direction+date here,
 * event identity there).
 */
private val commuteLeaveByMutex = Mutex()

/**
 * v5 FIX-16: gives the daily-used commute leave-by its own precise one-shot alarm, mirroring
 * [EventLeaveByWorker] exactly, instead of only ever being checked from inside whatever refresh
 * happens to run next. Re-checks [com.crpakala.commutewidget.data.AppSettings.leaveByEnabled] and
 * the per-direction-per-date dedup key at fire time for the same reason [EventLeaveByWorker]
 * does: a REPLACE-scheduled successor could race a settings change or a newer schedule call
 * between enqueue and fire. Never reschedules itself -
 * [com.crpakala.commutewidget.engine.CommuteRefresher]'s post-refresh scheduling owns the chain.
 */
class CommuteLeaveByWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val directionName = inputData.getString(KEY_DIRECTION)
        val leaveByMinuteOfDay = inputData.getInt(KEY_LEAVE_BY_MINUTE_OF_DAY, -1)
        val arriveByMinuteOfDay = inputData.getInt(KEY_ARRIVE_BY_MINUTE_OF_DAY, -1)
        val travelMinutes = inputData.getInt(KEY_TRAVEL_MINUTES, 0)
        val destinationLabel = inputData.getString(KEY_DESTINATION_LABEL)
        val direction = directionName?.let { name -> Direction.entries.firstOrNull { it.name == name } }
        if (direction == null || leaveByMinuteOfDay < 0 || arriveByMinuteOfDay < 0 || destinationLabel == null) {
            return Result.success()
        }

        val repo = SettingsRepository.get(applicationContext)
        val settings = repo.settingsSnapshot()
        if (!settings.leaveByEnabled) {
            return Result.success()
        }

        val today = ZonedDateTime.now().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        postCommuteLeaveByIfNotAlreadyNotified(
            repo = repo,
            context = applicationContext,
            direction = direction,
            today = today,
            leaveByMinuteOfDay = leaveByMinuteOfDay,
            arriveByMinuteOfDay = arriveByMinuteOfDay,
            travelMinutes = travelMinutes,
            destinationLabel = destinationLabel,
        )
        return Result.success()
    }

    companion object {
        const val KEY_DIRECTION = "direction"
        const val KEY_LEAVE_BY_MINUTE_OF_DAY = "leave_by_minute_of_day"
        const val KEY_ARRIVE_BY_MINUTE_OF_DAY = "arrive_by_minute_of_day"
        const val KEY_TRAVEL_MINUTES = "travel_minutes"
        const val KEY_DESTINATION_LABEL = "destination_label"
    }
}

/**
 * Schedules the v5 commute leave-by one-shot alarm. The in-refresh immediate-fire path
 * ([com.crpakala.commutewidget.engine.CommuteRefresher.maybeNotifyLeaveBy]) stays in place for
 * the case where leave-by has already arrived by the time a refresh runs - this scheduler only
 * ever needs to arrange a *future* wake-up, and is cancelled outright whenever a refresh resolves
 * to calendar mode.
 */
object CommuteLeaveByScheduler {
    const val WORK_NAME = "commute_leave_by_notify"

    fun schedule(
        context: Context,
        direction: Direction,
        leaveByMinuteOfDay: Int,
        arriveByMinuteOfDay: Int,
        travelMinutes: Int,
        destinationLabel: String,
        leaveByEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val appContext = context.applicationContext
        val delayMillis = (leaveByEpochMillis - nowEpochMillis).coerceAtLeast(0)
        val data = Data.Builder()
            .putString(CommuteLeaveByWorker.KEY_DIRECTION, direction.name)
            .putInt(CommuteLeaveByWorker.KEY_LEAVE_BY_MINUTE_OF_DAY, leaveByMinuteOfDay)
            .putInt(CommuteLeaveByWorker.KEY_ARRIVE_BY_MINUTE_OF_DAY, arriveByMinuteOfDay)
            .putInt(CommuteLeaveByWorker.KEY_TRAVEL_MINUTES, travelMinutes)
            .putString(CommuteLeaveByWorker.KEY_DESTINATION_LABEL, destinationLabel)
            .build()
        // No network constraint: this wake-up only reads DataStore and posts a notification.
        val request = OneTimeWorkRequestBuilder<CommuteLeaveByWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels any pending commute leave-by wake-up; safe to call even when none is scheduled. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}

/**
 * True only when [leaveByEpochMillis] is still ahead of [nowEpochMillis] and this
 * direction/date has not already fired - the alarm-scheduling half of FIX-16's decision matrix
 * (the immediate-fire half is [com.crpakala.commutewidget.engine.shouldFireLeaveByNotification]).
 */
internal fun shouldScheduleCommuteLeaveByAlarm(
    alreadyNotifiedToday: Boolean,
    leaveByEpochMillis: Long,
    nowEpochMillis: Long,
): Boolean = !alreadyNotifiedToday && leaveByEpochMillis > nowEpochMillis

/**
 * Atomically checks the per-direction-per-date dedup key and, only when not already notified,
 * posts the notification and marks it notified - see [commuteLeaveByMutex].
 */
internal suspend fun postCommuteLeaveByIfNotAlreadyNotified(
    repo: SettingsRepository,
    context: Context,
    direction: Direction,
    today: String,
    leaveByMinuteOfDay: Int,
    arriveByMinuteOfDay: Int,
    travelMinutes: Int,
    destinationLabel: String,
): Unit = commuteLeaveByMutex.withLock {
    if (repo.leaveByNotifiedOn(direction) == today) {
        return@withLock
    }
    if (postCommuteLeaveByNotification(context, leaveByMinuteOfDay, arriveByMinuteOfDay, travelMinutes, destinationLabel)) {
        repo.markLeaveByNotified(direction, today)
    }
}

private fun postCommuteLeaveByNotification(
    context: Context,
    leaveByMinuteOfDay: Int,
    arriveByMinuteOfDay: Int,
    travelMinutes: Int,
    destinationLabel: String,
): Boolean {
    val hasPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return false

    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    manager.createNotificationChannel(
        NotificationChannel(LEAVE_BY_CHANNEL_ID, LEAVE_BY_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
    )

    val leaveByTime = formatClockTime(leaveByMinuteOfDay)
    val arriveByTime = formatClockTime(arriveByMinuteOfDay)
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = Notification.Builder(context, LEAVE_BY_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Time to leave")
        .setContentText(
            "Leave by $leaveByTime to reach $destinationLabel by $arriveByTime - $travelMinutes min drive",
        )
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    manager.notify(COMMUTE_LEAVE_BY_NOTIFICATION_ID, notification)
    return true
}
