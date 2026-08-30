package com.crpakala.commutewidget.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.GeocodeHit
import com.crpakala.commutewidget.api.GeocodingClient
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.schedule.CommuteScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Commute setup" category: home/work locations, travel mode, commute days, and the To Work / To
 * Home window times with their existing validation - everything that used to sit at the top of
 * the pre-reorg monolith `SettingsScreen`.
 */
@Composable
fun CommuteSetupScreen(
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
            LocationCard(
                label = "Home location",
                savedAddress = settings.home?.address,
                apiKey = settings.apiKey,
                onPlaceSelected = { place ->
                    scope.launch {
                        repository.setHome(place)
                        refreshWidget(applicationContext)
                        snackbarHostState.showSnackbar("Home location saved")
                    }
                },
            )
        }
        item {
            LocationCard(
                label = "Work location",
                savedAddress = settings.work?.address,
                apiKey = settings.apiKey,
                onPlaceSelected = { place ->
                    scope.launch {
                        repository.setWork(place)
                        refreshWidget(applicationContext)
                        snackbarHostState.showSnackbar("Work location saved")
                    }
                },
            )
        }
        item {
            TravelModeSection(
                travelMode = settings.travelMode,
                onSelect = { travelMode ->
                    scope.launch {
                        repository.setTravelMode(travelMode)
                        refreshWidget(applicationContext)
                        snackbarHostState.showSnackbar("Travel mode saved")
                    }
                },
            )
        }
        item {
            CommuteWindowsSection(
                settings = settings,
                onSettingsChange = { update ->
                    scope.launch {
                        update(repository)
                        CommuteScheduler.ensureScheduled(applicationContext)
                        refreshWidget(applicationContext)
                    }
                },
            )
        }
    }
}

@Composable
internal fun LocationCard(
    label: String,
    savedAddress: String?,
    apiKey: String,
    onPlaceSelected: (Place) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var savedConfirmation by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(savedAddress ?: "Not set", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (apiKey.isBlank()) {
                Text(
                    "Enter API key first",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                enabled = apiKey.isNotBlank() && query.isNotBlank(),
                onClick = {
                    searchJob?.cancel()
                    searched = true
                    error = null
                    results = emptyList()
                    searchJob = scope.launch {
                        when (val response = GeocodingClient(apiKey).geocode(query.trim())) {
                            is ApiResult.Success -> results = response.value.take(5)
                            is ApiResult.Failure -> error = response.message
                        }
                    }
                },
            ) {
                Text("Search")
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (searched && error == null && results.isEmpty() && searchJob?.isCompleted != false) {
                Text("No matches found", style = MaterialTheme.typography.bodySmall)
            }
            results.forEach { hit ->
                Text(
                    hit.formattedAddress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPlaceSelected(
                                Place(hit.formattedAddress, hit.location.lat, hit.location.lng),
                            )
                            results = emptyList()
                            searched = false
                            savedConfirmation = "Saved: ${hit.formattedAddress}"
                        }
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            savedConfirmation?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { searchJob?.cancel() }
    }
}

@Composable
internal fun TravelModeSection(travelMode: TravelMode, onSelect: (TravelMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Travel mode", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = travelMode == TravelMode.DRIVE,
                onClick = { onSelect(TravelMode.DRIVE) },
                label = { Text(travelModeLabel(TravelMode.DRIVE)) },
            )
            FilterChip(
                selected = travelMode == TravelMode.TWO_WHEELER,
                onClick = { onSelect(TravelMode.TWO_WHEELER) },
                label = { Text(travelModeLabel(TravelMode.TWO_WHEELER)) },
            )
        }
    }
}

private enum class CommuteWindowTime { MORNING_START, MORNING_END, EVENING_START, EVENING_END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommuteWindowsSection(
    settings: AppSettings,
    onSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit,
) {
    var selectedTime by remember { mutableStateOf<CommuteWindowTime?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Commute windows", style = MaterialTheme.typography.titleMedium)
        Text(
            "Inside a window the widget shows that commute. Outside your windows it shows your next calendar event.",
            style = MaterialTheme.typography.bodySmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Commute days")
                Text(
                    "Days the widget shows your To Work and To Home windows",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (1..7).forEach { day ->
                    FilterChip(
                        selected = day in settings.commuteDays,
                        onClick = {
                            val next = if (day in settings.commuteDays) {
                                settings.commuteDays - day
                            } else {
                                settings.commuteDays + day
                            }
                            onSettingsChange { it.setCommuteDays(next) }
                        },
                        label = { Text(dayLabel(day)) },
                    )
                }
            }
        }
        TimeRow("To Work window start", settings.morningSlotStartMinuteOfDay) { selectedTime = CommuteWindowTime.MORNING_START }
        TimeRow("To Work window end", settings.morningSlotEndMinuteOfDay) { selectedTime = CommuteWindowTime.MORNING_END }
        TimeRow("To Home window start", settings.eveningSlotStartMinuteOfDay) { selectedTime = CommuteWindowTime.EVENING_START }
        TimeRow("To Home window end", settings.eveningSlotEndMinuteOfDay) { selectedTime = CommuteWindowTime.EVENING_END }
        validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    selectedTime?.let { selection ->
        val current = when (selection) {
            CommuteWindowTime.MORNING_START -> settings.morningSlotStartMinuteOfDay
            CommuteWindowTime.MORNING_END -> settings.morningSlotEndMinuteOfDay
            CommuteWindowTime.EVENING_START -> settings.eveningSlotStartMinuteOfDay
            CommuteWindowTime.EVENING_END -> settings.eveningSlotEndMinuteOfDay
        }
        key(selection, current) {
            val picker = rememberTimePickerState(current / 60, current % 60, is24Hour = false)
            TimePickerDialog(picker, { selectedTime = null }) {
                val minute = picker.hour * 60 + picker.minute
                val valid = when (selection) {
                    CommuteWindowTime.MORNING_START -> minute < settings.morningSlotEndMinuteOfDay
                    CommuteWindowTime.MORNING_END -> settings.morningSlotStartMinuteOfDay < minute
                    CommuteWindowTime.EVENING_START -> minute < settings.eveningSlotEndMinuteOfDay
                    CommuteWindowTime.EVENING_END -> settings.eveningSlotStartMinuteOfDay < minute
                }
                if (!valid) {
                    validationError = if (selection == CommuteWindowTime.MORNING_START || selection == CommuteWindowTime.MORNING_END) {
                        "To Work window start must be before end"
                    } else {
                        "To Home window start must be before end"
                    }
                } else {
                    validationError = null
                    onSettingsChange {
                        when (selection) {
                            CommuteWindowTime.MORNING_START -> it.setMorningSlotStartMinuteOfDay(minute)
                            CommuteWindowTime.MORNING_END -> it.setMorningSlotEndMinuteOfDay(minute)
                            CommuteWindowTime.EVENING_START -> it.setEveningSlotStartMinuteOfDay(minute)
                            CommuteWindowTime.EVENING_END -> it.setEveningSlotEndMinuteOfDay(minute)
                        }
                    }
                }
                selectedTime = null
            }
        }
    }
}
