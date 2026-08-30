package com.crpakala.commutewidget.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.schedule.CommuteScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Health > "Experimental nudges" - a nested sub-screen (back returns to Health, not the home
 * menu) holding the nine default-OFF experimental toggles and the conditional caffeine cutoff
 * time row, moved out of the core Health screen to keep it scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalNudgesScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    applicationContext: Context,
    padding: PaddingValues,
) {
    val onSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit = { update ->
        scope.launch {
            update(repository)
            CommuteScheduler.ensureScheduled(applicationContext)
            refreshWidget(applicationContext)
        }
    }
    var editingCaffeineCutoff by remember { mutableStateOf(false) }

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
                "Off by default - each line only appears once its toggle is on.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            HealthToggleRow(
                "Soften after short sleep",
                "Rewrite the brief after a short night",
                settings.sleepDebtSoftenEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setSleepDebtSoftenEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Prioritize protein on gym days",
                "Protein first on gym days",
                settings.gymProteinPriorityEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setGymProteinPriorityEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Rough-night shield",
                "Mute morning nudges after a rough night",
                settings.restlessNightShieldEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setRestlessNightShieldEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Post-drive walk",
                "Suggest the walk after the drive home",
                settings.walkPostAudibleLatchEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setWalkPostAudibleLatchEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Prefer daylight walks",
                "Prefer pre-sunset walk slots",
                settings.walkDaylightPreferenceEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setWalkDaylightPreferenceEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Focus gap chip",
                "Focus chip in free calendar gaps",
                settings.focusGapChipEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setFocusGapChipEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Post-gym water",
                "Extra water slot after workouts",
                settings.postGymWaterPulseEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setPostGymWaterPulseEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Morning light",
                "Morning light reminder line",
                settings.morningLightLineEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setMorningLightLineEnabled(enabled) } }
        }
        item {
            HealthToggleRow(
                "Caffeine cutoff",
                "Coffee cutoff line",
                settings.caffeineCutoffLineEnabled,
            ) { enabled -> onSettingsChange { repo -> repo.setCaffeineCutoffLineEnabled(enabled) } }
        }
        if (settings.caffeineCutoffLineEnabled) {
            item {
                TimeRow("Cutoff time", settings.caffeineCutoffMinuteOfDay) { editingCaffeineCutoff = true }
            }
        }
    }

    if (editingCaffeineCutoff) {
        val minute = settings.caffeineCutoffMinuteOfDay
        val picker = rememberTimePickerState(minute / 60, minute % 60, is24Hour = false)
        TimePickerDialog(picker, { editingCaffeineCutoff = false }) {
            val selectedMinute = picker.hour * 60 + picker.minute
            onSettingsChange { repo -> repo.setCaffeineCutoffMinuteOfDay(selectedMinute) }
            editingCaffeineCutoff = false
        }
    }
}
