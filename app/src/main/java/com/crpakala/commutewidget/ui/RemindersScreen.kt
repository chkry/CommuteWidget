package com.crpakala.commutewidget.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CustomPill
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.schedule.CommuteScheduler
import com.crpakala.commutewidget.schedule.HealthFieldsRefresher
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Reminders" category: the new custom pill reminder manager (sprint 4). The data layer, engine,
 * and widget rendering already exist (sprints 1-3); this screen is purely list/add/edit/remove
 * management plus the global active-window duration.
 */
@Composable
fun RemindersScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    applicationContext: Context,
    padding: PaddingValues,
) {
    var editingPill by remember { mutableStateOf<CustomPill?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var editingActiveWindow by remember { mutableStateOf(false) }

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
            Text(
                "Reminders show as pills on the widget at their times. Tap a pill to mark it done for that slot.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (settings.customPills.isEmpty()) {
            item { Text("No reminders yet", style = MaterialTheme.typography.bodyMedium) }
        }
        items(settings.customPills, key = { it.id }) { pill ->
            ReminderRow(
                pill = pill,
                onEdit = { editingPill = pill },
                onRemove = {
                    scope.launch {
                        repository.setCustomPills(settings.customPills.filterNot { it.id == pill.id })
                        repository.updateHealthDayState { state ->
                            state?.copy(
                                customPillTakenSlots = pruneCustomPillTakenSlots(state.customPillTakenSlots, pill.id),
                            )
                        }
                        CommuteScheduler.ensureScheduled(applicationContext)
                        HealthFieldsRefresher.recomputeAndPersist(applicationContext)
                    }
                },
            )
        }
        item {
            if (canAddReminder(settings.customPills.size)) {
                TextButton(onClick = { addingNew = true }) { Text("Add reminder") }
            } else {
                Text(
                    "Maximum ${CustomPill.MAX_PILLS} reminders reached",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SectionHeader("Active window")
            DurationRow("Active window", settings.customPillActiveWindowMinutes) { editingActiveWindow = true }
            Text(
                "How long a reminder pill stays active on the widget after its scheduled time",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (addingNew) {
        ReminderFormDialog(
            existing = null,
            existingNames = settings.customPills.map { it.name },
            onDismiss = { addingNew = false },
            onSave = { pill ->
                scope.launch {
                    repository.setCustomPills(settings.customPills + pill)
                    CommuteScheduler.ensureScheduled(applicationContext)
                    // Recompute health fields now (local only, no route/API call) so a pill whose
                    // slot already passed today shows immediately instead of at the next boundary.
                    HealthFieldsRefresher.recomputeAndPersist(applicationContext)
                }
                addingNew = false
            },
        )
    }
    editingPill?.let { pill ->
        ReminderFormDialog(
            existing = pill,
            existingNames = settings.customPills.filterNot { it.id == pill.id }.map { it.name },
            onDismiss = { editingPill = null },
            onSave = { updated ->
                scope.launch {
                    repository.setCustomPills(settings.customPills.map { if (it.id == updated.id) updated else it })
                    CommuteScheduler.ensureScheduled(applicationContext)
                    HealthFieldsRefresher.recomputeAndPersist(applicationContext)
                }
                editingPill = null
            },
        )
    }
    if (editingActiveWindow) {
        DurationDialog(
            initialMinutes = settings.customPillActiveWindowMinutes,
            title = "Active window",
            minMinutes = CustomPill.ACTIVE_WINDOW_MIN_MINUTES,
            maxMinutes = CustomPill.ACTIVE_WINDOW_MAX_MINUTES,
            onDismiss = { editingActiveWindow = false },
            onSave = { minutes ->
                scope.launch {
                    repository.setCustomPillActiveWindowMinutes(minutes)
                    CommuteScheduler.ensureScheduled(applicationContext)
                    HealthFieldsRefresher.recomputeAndPersist(applicationContext)
                }
                editingActiveWindow = false
            },
        )
    }
}

@Composable
private fun ReminderRow(pill: CustomPill, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pill.name, style = MaterialTheme.typography.titleMedium)
            Text(compactDaysList(pill.days), style = MaterialTheme.typography.bodySmall)
            Text(
                pill.slotsMinutesOfDay.sorted().joinToString(", ") { formatTime(it) },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderFormDialog(
    existing: CustomPill?,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustomPill) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var days by remember(existing) { mutableStateOf(existing?.days ?: ALL_WEEK_DAYS) }
    var slots by remember(existing) { mutableStateOf(existing?.slotsMinutesOfDay?.sorted() ?: emptyList()) }
    var addingSlot by remember { mutableStateOf(false) }
    var slotError by remember { mutableStateOf<String?>(null) }

    val nameError = validateReminderName(name, existingNames)
    val formValid = isReminderFormValid(name, existingNames, days, slots)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add reminder" else "Edit reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    isError = name.isNotBlank() && nameError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (name.isNotBlank()) {
                    nameError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text("Days", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = day in days,
                            onClick = { days = toggleReminderDay(days, day) },
                            label = { Text(dayLabel(day)) },
                        )
                    }
                }

                Text("Times", style = MaterialTheme.typography.bodyMedium)
                slots.forEach { slot ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(slot))
                        TextButton(onClick = { slots = removeSlot(slots, slot) }) { Text("Remove") }
                    }
                }
                if (slots.size < CustomPill.MAX_SLOTS_PER_PILL) {
                    TextButton(onClick = { addingSlot = true }) { Text("Add time") }
                } else {
                    Text(
                        "Maximum ${CustomPill.MAX_SLOTS_PER_PILL} times reached",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                slotError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = formValid,
                onClick = {
                    val id = existing?.id ?: UUID.randomUUID().toString()
                    onSave(CustomPill(id = id, name = name.trim(), slotsMinutesOfDay = slots, days = days))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (addingSlot) {
        val picker = rememberTimePickerState(9, 0, is24Hour = false)
        TimePickerDialog(picker, { addingSlot = false }) {
            val minute = picker.hour * 60 + picker.minute
            if (canAddSlot(slots, minute)) {
                slots = addSlot(slots, minute)
                slotError = null
            } else {
                slotError = "Already have a reminder at that time, or the limit is reached"
            }
            addingSlot = false
        }
    }
}
