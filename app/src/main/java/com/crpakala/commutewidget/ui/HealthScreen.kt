package com.crpakala.commutewidget.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.ExistingWorkPolicy
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.health.EventSpan
import com.crpakala.commutewidget.engine.health.HealthParams
import com.crpakala.commutewidget.engine.replanTodayWaterSlots
import com.crpakala.commutewidget.engine.todayEventsChained
import com.crpakala.commutewidget.schedule.CommuteScheduler
import com.crpakala.commutewidget.schedule.HealthBoundaryScheduler
import com.crpakala.commutewidget.schedule.HealthFieldsRefresher
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Health" category: the six core toggles with their conditional sub-rows, exactly as in the
 * pre-reorg monolith, plus compact permission status lines (the three Grant buttons moved to
 * "Access & app info") and a nested link to "Experimental nudges".
 */
@Composable
fun HealthScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    applicationContext: Context,
    padding: PaddingValues,
    onOpenExperimentalNudges: () -> Unit,
    onNavigateToAccessInfo: () -> Unit,
) {
    val onSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit = { update ->
        scope.launch {
            update(repository)
            CommuteScheduler.ensureScheduled(applicationContext)
            refreshWidget(applicationContext)
        }
    }

    // Water window (start/end) and reminders-per-day edits need today's plan replanned
    // immediately, not just the generic reschedule-and-render above: the owner must see the new
    // slots on the widget right away rather than waiting for tomorrow's rollover.
    val onWaterPlanSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit = { update ->
        scope.launch {
            update(repository)
            val freshSettings = repository.settingsSnapshot()
            val zone = ZoneId.systemDefault()
            val nowEpochMillis = System.currentTimeMillis()
            val todayDateStr = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            val events = runCatching { todayEventsChained(applicationContext, freshSettings, nowEpochMillis, zone) }
                .getOrDefault(emptyList())
                .map { EventSpan(it.startEpochMillis, it.endEpochMillis) }
            val params = HealthParams(
                waterFirstAnchorMinuteOfDay = freshSettings.waterWindowStartMinuteOfDay,
                waterLastAnchorMinuteOfDay = freshSettings.waterWindowEndMinuteOfDay,
                waterCutoffMinuteOfDay = freshSettings.waterWindowEndMinuteOfDay + HealthParams().waterActiveWindowMinutes,
            )
            replanTodayWaterSlots(
                repo = repository,
                todayDateStr = todayDateStr,
                events = events,
                waterRemindersPerDay = freshSettings.waterRemindersPerDay,
                nowEpochMillis = nowEpochMillis,
                zone = zone,
                params = params,
            )
            HealthFieldsRefresher.recomputeAndPersist(applicationContext)
            HealthBoundaryScheduler.schedule(applicationContext, freshSettings, ExistingWorkPolicy.REPLACE)
        }
    }

    val healthStatus = rememberHealthPermissionStatus()
    var selectedTime by remember { mutableStateOf<HealthTime?>(null) }
    var selectedNumber by remember { mutableStateOf<HealthNumber?>(null) }
    var editingPackages by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                "Private on-device reminders and Health Connect logging.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            PermissionStatusLinkRow(
                statusText = "Health Connect: " + when {
                    !healthStatus.healthAvailable -> "unavailable"
                    healthStatus.healthConnectGranted -> "granted"
                    else -> "not granted"
                },
                onClick = onNavigateToAccessInfo,
            )
        }
        item {
            PermissionStatusLinkRow(
                statusText = "Usage access: " + if (healthStatus.usageAccessGranted) "granted" else "not granted",
                onClick = onNavigateToAccessInfo,
            )
        }
        item {
            PermissionStatusLinkRow(
                statusText = "Notification access: " + if (healthStatus.notificationAccessGranted) "granted" else "not granted",
                onClick = onNavigateToAccessInfo,
            )
        }
        item {
            HealthToggleRow(
                "Morning supplements",
                "Remind during your morning window",
                settings.morningSupplementsEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setMorningSupplementsEnabled(enabled) } }
        }
        if (settings.morningSupplementsEnabled) {
            item {
                TimeRow("Start", settings.morningSupplementsStartMinuteOfDay) {
                    selectedTime = HealthTime.MORNING_SUPPLEMENTS_START
                }
            }
            item {
                TimeRow("End", settings.morningSupplementsEndMinuteOfDay) {
                    selectedTime = HealthTime.MORNING_SUPPLEMENTS_END
                }
            }
        }
        item {
            HealthToggleRow("Evening protein", "Remind during your evening window", settings.eveningProteinEnabled) { enabled ->
                onSettingsChange { repo -> repo.setEveningProteinEnabled(enabled) }
            }
        }
        if (settings.eveningProteinEnabled) {
            item { TimeRow("Start", settings.proteinStartMinuteOfDay) { selectedTime = HealthTime.PROTEIN_START } }
            item { TimeRow("End", settings.proteinEndMinuteOfDay) { selectedTime = HealthTime.PROTEIN_END } }
        }
        item {
            HealthToggleRow("Water reminders", "Small hydration prompts across the day", settings.waterRemindersEnabled) { enabled ->
                onSettingsChange { repo -> repo.setWaterRemindersEnabled(enabled) }
            }
        }
        if (settings.waterRemindersEnabled) {
            item {
                NumberRow("Reminders per day", settings.waterRemindersPerDay) {
                    selectedNumber = HealthNumber.WATER_REMINDERS
                }
            }
            item {
                TimeRow("Window start", settings.waterWindowStartMinuteOfDay) {
                    selectedTime = HealthTime.WATER_WINDOW_START
                }
            }
            item {
                TimeRow("Window end", settings.waterWindowEndMinuteOfDay) {
                    selectedTime = HealthTime.WATER_WINDOW_END
                }
            }
            item { Text("250 ml per tap - logged to Health Connect", style = MaterialTheme.typography.bodySmall) }
        }
        item {
            HealthToggleRow("Evening walk", "Suggest a walk from your activity and schedule", settings.eveningWalkEnabled) { enabled ->
                onSettingsChange { repo -> repo.setEveningWalkEnabled(enabled) }
            }
        }
        if (settings.eveningWalkEnabled) {
            item { TimeRow("Search start", settings.walkSearchStartMinuteOfDay) { selectedTime = HealthTime.WALK_START } }
            item { TimeRow("Search end", settings.walkSearchEndMinuteOfDay) { selectedTime = HealthTime.WALK_END } }
            item { NumberRow("Step goal", settings.stepGoal) { selectedNumber = HealthNumber.STEP_GOAL } }
            item { Text("Uses your step goal and calendar", style = MaterialTheme.typography.bodySmall) }
        }
        item {
            HealthToggleRow(
                "Sleep estimate in morning brief",
                "Estimate sleep from screen activity",
                settings.sleepBriefEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setSleepBriefEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Suppress during audiobooks",
                "Hide health nudges while selected apps are playing",
                settings.audiobookSuppressionEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setAudiobookSuppressionEnabled(enabled) } }
        }
        if (settings.audiobookSuppressionEnabled) {
            item {
                Column {
                    TextButton(onClick = { editingPackages = true }) { Text("Edit audiobook apps") }
                    Text(
                        settings.commuteAudioPackages.sorted().joinToString().ifBlank { "No audiobook apps selected" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            NavigationRow(
                title = "Experimental nudges",
                summary = experimentalNudgesSummary(settings),
                onClick = onOpenExperimentalNudges,
            )
        }
    }
    HealthTimeDialog(selectedTime, settings, onSettingsChange, onWaterPlanSettingsChange) { selectedTime = null }
    HealthNumberDialog(selectedNumber, settings, onSettingsChange, onWaterPlanSettingsChange) { selectedNumber = null }
    if (editingPackages) {
        AudioPackagesDialog(
            initialPackages = settings.commuteAudioPackages,
            onDismiss = { editingPackages = false },
            onSave = { packages ->
                onSettingsChange { repo -> repo.setCommuteAudioPackages(packages) }
                editingPackages = false
            },
        )
    }
}

private enum class HealthTime {
    MORNING_SUPPLEMENTS_START,
    MORNING_SUPPLEMENTS_END,
    PROTEIN_START,
    PROTEIN_END,
    WALK_START,
    WALK_END,
    WATER_WINDOW_START,
    WATER_WINDOW_END,
}

private enum class HealthNumber { WATER_REMINDERS, STEP_GOAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthTimeDialog(
    selection: HealthTime?,
    settings: AppSettings,
    onSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit,
    onWaterPlanSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    selection ?: return
    val minute = when (selection) {
        HealthTime.MORNING_SUPPLEMENTS_START -> settings.morningSupplementsStartMinuteOfDay
        HealthTime.MORNING_SUPPLEMENTS_END -> settings.morningSupplementsEndMinuteOfDay
        HealthTime.PROTEIN_START -> settings.proteinStartMinuteOfDay
        HealthTime.PROTEIN_END -> settings.proteinEndMinuteOfDay
        HealthTime.WALK_START -> settings.walkSearchStartMinuteOfDay
        HealthTime.WALK_END -> settings.walkSearchEndMinuteOfDay
        HealthTime.WATER_WINDOW_START -> settings.waterWindowStartMinuteOfDay
        HealthTime.WATER_WINDOW_END -> settings.waterWindowEndMinuteOfDay
    }
    key(selection, minute) {
        val picker = rememberTimePickerState(minute / 60, minute % 60, is24Hour = false)
        TimePickerDialog(picker, onDismiss) {
            val selectedMinute = picker.hour * 60 + picker.minute
            when (selection) {
                HealthTime.MORNING_SUPPLEMENTS_START ->
                    onSettingsChange { repository -> repository.setMorningSupplementsStartMinuteOfDay(selectedMinute) }
                HealthTime.MORNING_SUPPLEMENTS_END ->
                    onSettingsChange { repository -> repository.setMorningSupplementsEndMinuteOfDay(selectedMinute) }
                HealthTime.PROTEIN_START ->
                    onSettingsChange { repository -> repository.setProteinStartMinuteOfDay(selectedMinute) }
                HealthTime.PROTEIN_END ->
                    onSettingsChange { repository -> repository.setProteinEndMinuteOfDay(selectedMinute) }
                HealthTime.WALK_START ->
                    onSettingsChange { repository -> repository.setWalkSearchStartMinuteOfDay(selectedMinute) }
                HealthTime.WALK_END ->
                    onSettingsChange { repository -> repository.setWalkSearchEndMinuteOfDay(selectedMinute) }
                HealthTime.WATER_WINDOW_START ->
                    onWaterPlanSettingsChange { repository -> repository.setWaterWindowStartMinuteOfDay(selectedMinute) }
                HealthTime.WATER_WINDOW_END ->
                    onWaterPlanSettingsChange { repository -> repository.setWaterWindowEndMinuteOfDay(selectedMinute) }
            }
            onDismiss()
        }
    }
}

@Composable
private fun HealthNumberDialog(
    selection: HealthNumber?,
    settings: AppSettings,
    onSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit,
    onWaterPlanSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    selection ?: return
    val initial = when (selection) {
        HealthNumber.WATER_REMINDERS -> settings.waterRemindersPerDay
        HealthNumber.STEP_GOAL -> settings.stepGoal
    }
    val title = when (selection) {
        HealthNumber.WATER_REMINDERS -> "Reminders per day"
        HealthNumber.STEP_GOAL -> "Step goal"
    }
    val min = when (selection) {
        HealthNumber.WATER_REMINDERS -> 3
        HealthNumber.STEP_GOAL -> 2_000
    }
    val max = when (selection) {
        HealthNumber.WATER_REMINDERS -> 8
        HealthNumber.STEP_GOAL -> 20_000
    }
    NumberDialog(initial, title, min, max, onDismiss) { value ->
        when (selection) {
            HealthNumber.WATER_REMINDERS ->
                onWaterPlanSettingsChange { repository -> repository.setWaterRemindersPerDay(value) }
            HealthNumber.STEP_GOAL ->
                onSettingsChange { repository -> repository.setStepGoal(value) }
        }
        onDismiss()
    }
}

@Composable
private fun AudioPackagesDialog(
    initialPackages: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var packages by remember(initialPackages) { mutableStateOf(initialPackages) }
    var query by remember { mutableStateOf("") }
    val installedApps by produceState(initialValue = emptyList<InstalledApp>(), context) {
        value = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) {
                    return@mapNotNull null
                }
                InstalledApp(
                    label = resolveInfo.loadLabel(packageManager).toString().ifBlank { packageName },
                    packageName = packageName,
                )
            }.distinctBy { it.packageName }
                .let(::sortInstalledApps)
        }
    }
    val selectedButUninstalled = selectedButUninstalledPackages(packages, installedApps)
    val filteredApps = filterInstalledApps(installedApps, query)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audiobook apps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectedButUninstalled.isNotEmpty()) {
                        item { Text("Selected apps", style = MaterialTheme.typography.bodyMedium) }
                        items(selectedButUninstalled, key = { it }) { packageName ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(packageName, modifier = Modifier.weight(1f))
                                TextButton(onClick = { packages = packages - packageName }) { Text("Remove") }
                            }
                        }
                    }
                    item { Text("Installed apps", style = MaterialTheme.typography.bodyMedium) }
                    items(filteredApps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    packages = if (app.packageName in packages) {
                                        packages - app.packageName
                                    } else {
                                        packages + app.packageName
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(
                                checked = app.packageName in packages,
                                onCheckedChange = { checked ->
                                    packages = if (checked) {
                                        packages + app.packageName
                                    } else {
                                        packages - app.packageName
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(packages) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
