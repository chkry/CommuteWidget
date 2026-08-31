package com.crpakala.commutewidget.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crpakala.commutewidget.clearSleepEstimateForDay
import com.crpakala.commutewidget.clearSleepPillDismissed
import com.crpakala.commutewidget.clearTodayHealthNudgeDismissals
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.schedule.CommuteScheduler
import com.crpakala.commutewidget.schedule.HealthFieldsRefresher
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Alerts & timing" category: the leave-by advisor (arrive-by targets, buffers, live-traffic
 * threshold) and best departure. The map-pill corner picker moved out to "Widget appearance".
 */
@Composable
fun AlertsTimingScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    applicationContext: Context,
    padding: PaddingValues,
    onNavigateToAccessInfo: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LeaveBySection(
                enabled = settings.leaveByEnabled,
                arriveWork = settings.arriveWorkByMinuteOfDay,
                arriveHome = settings.arriveHomeByMinuteOfDay,
                eventLeaveByBufferMinutes = settings.eventLeaveByBufferMinutes,
                eventRealtimeThresholdMinutes = settings.eventRealtimeThresholdMinutes,
                onEnabledChanged = { enabled ->
                    scope.launch {
                        repository.setLeaveByEnabled(enabled)
                        refreshWidget(applicationContext)
                    }
                },
                onTimeChanged = { work, minute ->
                    scope.launch {
                        if (work) repository.setArriveWorkByMinuteOfDay(minute)
                        else repository.setArriveHomeByMinuteOfDay(minute)
                        refreshWidget(applicationContext)
                    }
                },
                onEventLeaveByBufferChanged = { minutes ->
                    scope.launch {
                        repository.setEventLeaveByBufferMinutes(minutes)
                        refreshWidget(applicationContext)
                        snackbarHostState.showSnackbar("Arrive early buffer saved")
                    }
                },
                onEventRealtimeThresholdChanged = { minutes ->
                    scope.launch {
                        repository.setEventRealtimeThresholdMinutes(minutes)
                        refreshWidget(applicationContext)
                        snackbarHostState.showSnackbar("Live traffic threshold saved")
                    }
                },
            )
        }
        item {
            val notificationsGranted = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS).granted
            PermissionStatusLinkRow(
                statusText = if (notificationsGranted) {
                    "Notifications on"
                } else {
                    "Notifications off - alerts show on widget only"
                },
                onClick = onNavigateToAccessInfo,
            )
        }
        item {
            BestDepartureSection(
                enabled = settings.bestDepartureEnabled,
                onEnabledChanged = { enabled ->
                    scope.launch {
                        repository.setBestDepartureEnabled(enabled)
                        refreshWidget(applicationContext)
                    }
                },
            )
        }
        item {
            SectionHeader("Today")
            TextButton(onClick = {
                scope.launch {
                    val todayIsoDate = LocalDate.now().toString()
                    repository.updateHealthDayState { state ->
                        clearTodayHealthNudgeDismissals(state, todayIsoDate)
                    }
                    // Cleared dismissals reintroduce boundary wake candidates (supplement window
                    // edges), so reschedule before the immediate local recompute + re-render.
                    CommuteScheduler.ensureScheduled(applicationContext)
                    HealthFieldsRefresher.recomputeAndPersist(applicationContext)
                    snackbarHostState.showSnackbar("Today's dismissed nudges are back")
                }
            }) { Text("Reset dismissed nudges") }
            Text(
                "Bring back the health and experimental nudges you dismissed today - supplements, walk, sleep, morning light, and focus chips. " +
                    "Water taps stay logged, the walk notification still fires at most once a day, " +
                    "and custom reminders have their own reset under Reminders. Today only.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                scope.launch {
                    val todayIsoDate = LocalDate.now().toString()
                    repository.updateHealthHistory { history ->
                        clearSleepEstimateForDay(history, todayIsoDate)
                    }
                    repository.updateHealthDayState { state ->
                        clearSleepPillDismissed(state, todayIsoDate)
                    }
                    HealthFieldsRefresher.recomputeAndPersist(applicationContext)
                    snackbarHostState.showSnackbar("Last night's sleep recalculated")
                }
            }) { Text("Recalculate last night's sleep") }
            Text(
                "Clears the stored estimate for last night and rescores it from lock/unlock history " +
                    "with the current model, bringing back the sleep pill if you dismissed it. " +
                    "Use it when the shown value predates an app update or looks wrong.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun BestDepartureSection(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Best departure", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Suggest the best time to leave")
                Text(
                    "Follows your commute windows automatically: mornings show the best time to leave for work, evenings the best time to head home, from Google's predicted traffic",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChanged)
        }
    }
}

private enum class LeaveByDuration { ARRIVE_EARLY_BUFFER, LIVE_TRAFFIC_THRESHOLD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeaveBySection(
    enabled: Boolean,
    arriveWork: Int,
    arriveHome: Int,
    eventLeaveByBufferMinutes: Int,
    eventRealtimeThresholdMinutes: Int,
    onEnabledChanged: (Boolean) -> Unit,
    onTimeChanged: (Boolean, Int) -> Unit,
    onEventLeaveByBufferChanged: (Int) -> Unit,
    onEventRealtimeThresholdChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    var notificationsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsGranted = it }
    var editingWork by remember { mutableStateOf<Boolean?>(null) }
    var editingDuration by remember { mutableStateOf<LeaveByDuration?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Leave-by advisor", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enable leave-by advisor")
            Switch(checked = enabled, onCheckedChange = {
                onEnabledChanged(it)
                if (it && !notificationsGranted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        if (enabled && !notificationsGranted) {
            Text("Notifications off - leave-by will only show on the widget", style = MaterialTheme.typography.bodySmall)
        }
        if (enabled) {
            TimeRow("Arrive at work by", arriveWork) { editingWork = true }
            TimeRow("Arrive home by", arriveHome) { editingWork = false }
            DurationRow("Arrive early by", eventLeaveByBufferMinutes) {
                editingDuration = LeaveByDuration.ARRIVE_EARLY_BUFFER
            }
            Text(
                "Buffer before a calendar event's start time",
                style = MaterialTheme.typography.bodySmall,
            )
            DurationRow("Use live traffic within", eventRealtimeThresholdMinutes) {
                editingDuration = LeaveByDuration.LIVE_TRAFFIC_THRESHOLD
            }
            Text(
                "Events further out use predicted traffic",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Applies inside your To Work and To Home windows, and to calendar events with a location - one notification per event.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    editingWork?.let { work ->
        val minute = if (work) arriveWork else arriveHome
        key(work, minute) {
            val picker = rememberTimePickerState(minute / 60, minute % 60, is24Hour = false)
            TimePickerDialog(picker, { editingWork = null }) {
                onTimeChanged(work, picker.hour * 60 + picker.minute)
                editingWork = null
            }
        }
    }
    editingDuration?.let { duration ->
        val initialMinutes = when (duration) {
            LeaveByDuration.ARRIVE_EARLY_BUFFER -> eventLeaveByBufferMinutes
            LeaveByDuration.LIVE_TRAFFIC_THRESHOLD -> eventRealtimeThresholdMinutes
        }
        val title = when (duration) {
            LeaveByDuration.ARRIVE_EARLY_BUFFER -> "Arrive early by"
            LeaveByDuration.LIVE_TRAFFIC_THRESHOLD -> "Use live traffic within"
        }
        val minMinutes = when (duration) {
            LeaveByDuration.ARRIVE_EARLY_BUFFER -> 0
            LeaveByDuration.LIVE_TRAFFIC_THRESHOLD -> 15
        }
        val maxMinutes = when (duration) {
            LeaveByDuration.ARRIVE_EARLY_BUFFER -> 60
            LeaveByDuration.LIVE_TRAFFIC_THRESHOLD -> 180
        }
        DurationDialog(
            initialMinutes = initialMinutes,
            title = title,
            minMinutes = minMinutes,
            maxMinutes = maxMinutes,
            onDismiss = { editingDuration = null },
            onSave = { minutes ->
                when (duration) {
                    LeaveByDuration.ARRIVE_EARLY_BUFFER -> onEventLeaveByBufferChanged(minutes)
                    LeaveByDuration.LIVE_TRAFFIC_THRESHOLD -> onEventRealtimeThresholdChanged(minutes)
                }
                editingDuration = null
            },
        )
    }
}
