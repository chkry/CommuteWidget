package com.crpakala.commutewidget.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import com.crpakala.commutewidget.schedule.CommuteScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Flights" category: the one switch for airport mode as a whole (flight detection in the
 * calendar, the calendar card's flight row, the To Airport pills and map, the flight card and the
 * AirLabs status fetches), the two lead times that used to sit under Calendar, and the AirLabs key
 * that used to sit under Places & Maps.
 */
@Composable
fun FlightsScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    applicationContext: Context,
    padding: PaddingValues,
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
            FlightsSection(
                enabled = settings.flightsEnabled,
                airportPillLeadMinutes = settings.airportPillLeadMinutes,
                airportArriveAheadMinutes = settings.airportArriveAheadMinutes,
                onEnabledChanged = { enabled ->
                    scope.launch {
                        repository.setFlightsEnabled(enabled)
                        // Re-arm or cancel the airport boundary chain, then run a full refresh so
                        // a flight card or flight row the widget is showing is replaced now rather
                        // than at the next boundary. The cooldown is bypassed because the switch
                        // only fires when the stored value actually changed.
                        CommuteScheduler.ensureScheduled(applicationContext)
                        withContext(Dispatchers.IO) {
                            CommuteRefresher.refreshNow(applicationContext, RefreshTrigger.AUTO, bypassCooldown = true)
                        }
                        refreshWidget(applicationContext)
                    }
                },
                onAirportPillLeadMinutesChanged = { minutes ->
                    scope.launch {
                        repository.setAirportPillLeadMinutes(minutes)
                        refreshWidget(applicationContext)
                    }
                },
                onAirportArriveAheadMinutesChanged = { minutes ->
                    scope.launch {
                        repository.setAirportArriveAheadMinutes(minutes)
                        refreshWidget(applicationContext)
                    }
                },
            )
        }
        if (settings.flightsEnabled) {
            item {
                FlightStatusKeySection(
                    savedKey = settings.flightStatusApiKey,
                    onSave = { apiKey ->
                        scope.launch {
                            repository.setFlightStatusApiKey(apiKey)
                            snackbarHostState.showSnackbar("Flight status key saved")
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun FlightsSection(
    enabled: Boolean,
    airportPillLeadMinutes: Int,
    airportArriveAheadMinutes: Int,
    onEnabledChanged: (Boolean) -> Unit,
    onAirportPillLeadMinutesChanged: (Int) -> Unit,
    onAirportArriveAheadMinutesChanged: (Int) -> Unit,
) {
    var editingAirportPill by remember { mutableStateOf(false) }
    var editingArriveAhead by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Flights", style = MaterialTheme.typography.titleMedium)
        HealthToggleRow(
            label = "Show flights on the widget",
            description = "Finds flights in your selected calendars, previews the next one on the card, routes you to the airport before departure and shows the flight card with live status. Off, a flight is just another calendar event.",
            enabled = enabled,
            onChanged = onEnabledChanged,
        )
        if (enabled) {
            DurationRow("Airport pill shows before flight", airportPillLeadMinutes) { editingAirportPill = true }
            Text(
                "How long before departure the To Airport pill and airport map appear.",
                style = MaterialTheme.typography.bodySmall,
            )
            DurationRow("Arrive at airport ahead of flight", airportArriveAheadMinutes) { editingArriveAhead = true }
            Text(
                "Target arrival at the airport before departure; Leave by and Best are computed from it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (editingAirportPill) {
        DurationDialog(
            initialMinutes = airportPillLeadMinutes,
            title = "Airport pill shows before flight",
            minMinutes = 60,
            maxMinutes = 720,
            onDismiss = { editingAirportPill = false },
            onSave = { minutes ->
                onAirportPillLeadMinutesChanged(minutes)
                editingAirportPill = false
            },
        )
    }
    if (editingArriveAhead) {
        DurationDialog(
            initialMinutes = airportArriveAheadMinutes,
            title = "Arrive at airport ahead of flight",
            minMinutes = 30,
            maxMinutes = 360,
            onDismiss = { editingArriveAhead = false },
            onSave = { minutes ->
                onAirportArriveAheadMinutesChanged(minutes)
                editingArriveAhead = false
            },
        )
    }
}

@Composable
internal fun FlightStatusKeySection(savedKey: String, onSave: (String) -> Unit) {
    var apiKey by remember(savedKey) { mutableStateOf(savedKey) }
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Flight status", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("AirLabs API key") },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) {
                    Text(if (visible) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Used for live flight status on the airport card. Free keys have a fixed total query budget.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { onSave(apiKey.trim()) }) {
            Text("Save key")
        }
    }
}
