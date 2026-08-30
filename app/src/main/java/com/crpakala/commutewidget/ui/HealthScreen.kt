package com.crpakala.commutewidget.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.schedule.CommuteScheduler
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    HealthTimeDialog(selectedTime, settings, onSettingsChange) { selectedTime = null }
    HealthNumberDialog(selectedNumber, settings, onSettingsChange) { selectedNumber = null }
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
}

private enum class HealthNumber { WATER_REMINDERS, STEP_GOAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthTimeDialog(
    selection: HealthTime?,
    settings: AppSettings,
    onSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit,
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
    }
    key(selection, minute) {
        val picker = rememberTimePickerState(minute / 60, minute % 60, is24Hour = false)
        TimePickerDialog(picker, onDismiss) {
            val selectedMinute = picker.hour * 60 + picker.minute
            onSettingsChange { repository ->
                when (selection) {
                    HealthTime.MORNING_SUPPLEMENTS_START ->
                        repository.setMorningSupplementsStartMinuteOfDay(selectedMinute)
                    HealthTime.MORNING_SUPPLEMENTS_END ->
                        repository.setMorningSupplementsEndMinuteOfDay(selectedMinute)
                    HealthTime.PROTEIN_START -> repository.setProteinStartMinuteOfDay(selectedMinute)
                    HealthTime.PROTEIN_END -> repository.setProteinEndMinuteOfDay(selectedMinute)
                    HealthTime.WALK_START -> repository.setWalkSearchStartMinuteOfDay(selectedMinute)
                    HealthTime.WALK_END -> repository.setWalkSearchEndMinuteOfDay(selectedMinute)
                }
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
        onSettingsChange { repository ->
            when (selection) {
                HealthNumber.WATER_REMINDERS -> repository.setWaterRemindersPerDay(value)
                HealthNumber.STEP_GOAL -> repository.setStepGoal(value)
            }
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
    var packages by remember(initialPackages) { mutableStateOf(initialPackages) }
    var packageName by remember { mutableStateOf("") }
    val normalized = packageName.trim().lowercase(Locale.ROOT)
    val validPackage = isValidPackageName(normalized) && normalized !in packages
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audiobook apps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                packages.sorted().forEach { existing ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(existing, modifier = Modifier.weight(1f))
                        TextButton(onClick = { packages = packages - existing }) { Text("Remove") }
                    }
                }
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package name") },
                    isError = packageName.isNotBlank() && !validPackage,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = validPackage,
                    onClick = {
                        packages = packages + normalized
                        packageName = ""
                    },
                ) { Text("Add") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(packages) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
