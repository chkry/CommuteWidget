package com.crpakala.commutewidget.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.calendar.DeviceCalendar
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.schedule.CommuteScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Calendar" category: calendar-mode enable, freshness tick, event-takeover window, and the
 * calendar checklist - unchanged from the pre-reorg monolith, plus a new permission status link.
 */
@Composable
fun CalendarScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
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
            val calendarGranted = rememberPermissionState(Manifest.permission.READ_CALENDAR).granted
            PermissionStatusLinkRow(
                statusText = if (calendarGranted) {
                    "Calendar permission on"
                } else {
                    "Calendar permission off - grant it to route to your next event"
                },
                onClick = onNavigateToAccessInfo,
            )
        }
        item {
            CalendarSection(
                enabled = settings.calendarEnabled,
                selectedIds = settings.selectedCalendarIds,
                calendarTickEnabled = settings.calendarTickEnabled,
                eventTakeoverMinutes = settings.eventTakeoverMinutes,
                onEnabledChanged = { enabled ->
                    scope.launch {
                        repository.setCalendarEnabled(enabled)
                        CommuteScheduler.ensureScheduled(applicationContext)
                        refreshWidget(applicationContext)
                    }
                },
                onSelectedIdsChanged = { ids ->
                    scope.launch {
                        repository.setSelectedCalendarIds(ids)
                        CommuteScheduler.ensureScheduled(applicationContext)
                        refreshWidget(applicationContext)
                    }
                },
                onCalendarTickEnabledChanged = { enabled ->
                    scope.launch {
                        repository.setCalendarTickEnabled(enabled)
                        CommuteScheduler.ensureScheduled(applicationContext)
                        refreshWidget(applicationContext)
                    }
                },
                onEventTakeoverMinutesChanged = { minutes ->
                    scope.launch {
                        repository.setEventTakeoverMinutes(minutes)
                        refreshWidget(applicationContext)
                    }
                },
            )
        }
    }
}

@Composable
internal fun CalendarSection(
    enabled: Boolean,
    selectedIds: Set<Long>,
    calendarTickEnabled: Boolean,
    eventTakeoverMinutes: Int,
    onEnabledChanged: (Boolean) -> Unit,
    onSelectedIdsChanged: (Set<Long>) -> Unit,
    onCalendarTickEnabledChanged: (Boolean) -> Unit,
    onEventTakeoverMinutesChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    var editingTakeover by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionGranted = it }
    val calendars = remember(permissionGranted) {
        if (permissionGranted) CalendarReader(context).listCalendars() else emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Calendar destinations", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enable calendar destinations")
            Switch(checked = enabled, onCheckedChange = {
                onEnabledChanged(it)
                if (it && !permissionGranted) permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep event ETA fresh")
                Text(
                    "Refreshes the next event's travel time every 20 minutes outside commute windows. Uses extra background data.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = calendarTickEnabled, onCheckedChange = onCalendarTickEnabledChanged)
        }
        if (enabled) {
            DurationRow("Event takes over within", eventTakeoverMinutes) { editingTakeover = true }
            Text(
                "A located event starting within this window shows its route and map, and replaces the commute view even inside commute windows. Farther out, it shows just its name and time.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (enabled && !permissionGranted) {
            Text("Calendar permission needed", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                Text("Allow calendar")
            }
        }
        if (enabled && permissionGranted) {
            calendars.forEach { calendar ->
                CalendarChoice(calendar, selectedIds.contains(calendar.id)) { checked ->
                    onSelectedIdsChanged(if (checked) selectedIds + calendar.id else selectedIds - calendar.id)
                }
            }
            if (selectedIds.isEmpty()) Text("Select at least one calendar", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Outside your commute windows, the widget shows the next event from these calendars. A located event shows its route and map once it starts within the takeover window above, and just its name and time until then; an event with no location always shows just its name and time. A located event starting soon also takes over during commute windows.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (editingTakeover) {
        DurationDialog(
            initialMinutes = eventTakeoverMinutes,
            title = "Event takes over within",
            minMinutes = 15,
            maxMinutes = 480,
            onDismiss = { editingTakeover = false },
            onSave = { minutes ->
                onEventTakeoverMinutesChanged(minutes)
                editingTakeover = false
            },
        )
    }
}

@Composable
private fun CalendarChoice(calendar: DeviceCalendar, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(calendar.displayName)
            Text(calendar.accountName, style = MaterialTheme.typography.bodySmall)
        }
    }
}
