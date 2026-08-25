package com.crpakala.commutewidget.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.GeocodeHit
import com.crpakala.commutewidget.api.GeocodingClient
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.schedule.CommuteScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun CommuteWidgetApp() {
    val context = LocalContext.current
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    // minSdk 34 is always >= S (31), so dynamic color is unconditionally available.
    val colors = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(
        colorScheme = colors,
    ) {
        SettingsScreen()
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val repository = remember { SettingsRepository.get(applicationContext) }
    val settings by repository.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
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
                Text("Commute Widget", style = MaterialTheme.typography.headlineMedium)
            }
            item {
                ApiKeySection(
                    savedKey = settings.apiKey,
                    onSave = { apiKey ->
                        scope.launch {
                            repository.setApiKey(apiKey)
                            refreshWidget(applicationContext)
                            snackbarHostState.showSnackbar("API key saved")
                        }
                    },
                )
            }
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
                TimesSection(
                    settings = settings,
                    onSave = { label, minuteOfDay ->
                        scope.launch {
                            when (label) {
                                TimeSetting.SWITCH -> repository.setSwitchMinuteOfDay(minuteOfDay)
                                TimeSetting.MORNING -> repository.setMorningRefreshMinuteOfDay(minuteOfDay)
                                TimeSetting.EVENING -> repository.setEveningRefreshMinuteOfDay(minuteOfDay)
                            }
                            // Morning/evening changes move the WorkManager fire time itself, not just
                            // the value read at the next natural firing, so the pending chain must be
                            // rebuilt now or the old time keeps firing until one more cycle passes.
                            CommuteScheduler.ensureScheduled(applicationContext)
                            refreshWidget(applicationContext)
                            snackbarHostState.showSnackbar("Time saved")
                        }
                    },
                )
            }
            item {
                LocationPermissionSection()
            }
            item {
                Text(
                    "Data refreshes on widget tap and weekday auto-refresh times.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ApiKeySection(savedKey: String, onSave: (String) -> Unit) {
    var apiKey by remember(savedKey) { mutableStateOf(savedKey) }
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("API key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Google Maps API key") },
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
            "Google Maps Platform key with Routes, Static Maps and Geocoding enabled",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { onSave(apiKey.trim()) }) {
            Text("Save key")
        }
    }
}

@Composable
private fun LocationCard(
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
private fun TravelModeSection(travelMode: TravelMode, onSelect: (TravelMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Travel mode", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = travelMode == TravelMode.DRIVE,
                onClick = { onSelect(TravelMode.DRIVE) },
                label = { Text("Car") },
            )
            FilterChip(
                selected = travelMode == TravelMode.TWO_WHEELER,
                onClick = { onSelect(TravelMode.TWO_WHEELER) },
                label = { Text("Two-wheeler") },
            )
        }
    }
}

private enum class TimeSetting { SWITCH, MORNING, EVENING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimesSection(settings: AppSettings, onSave: (TimeSetting, Int) -> Unit) {
    var selected by remember { mutableStateOf<TimeSetting?>(null) }
    val selectedMinute = when (selected) {
        TimeSetting.SWITCH -> settings.switchMinuteOfDay
        TimeSetting.MORNING -> settings.morningRefreshMinuteOfDay
        TimeSetting.EVENING -> settings.eveningRefreshMinuteOfDay
        null -> 0
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Times", style = MaterialTheme.typography.titleMedium)
        TimeRow("Switch to homeward after", settings.switchMinuteOfDay) { selected = TimeSetting.SWITCH }
        TimeRow("Morning auto-refresh", settings.morningRefreshMinuteOfDay) { selected = TimeSetting.MORNING }
        TimeRow("Evening auto-refresh", settings.eveningRefreshMinuteOfDay) { selected = TimeSetting.EVENING }
    }
    selected?.let { setting ->
        key(setting, selectedMinute) {
            val pickerState = rememberTimePickerState(
                initialHour = selectedMinute / 60,
                initialMinute = selectedMinute % 60,
                is24Hour = false,
            )
            TimePickerDialog(
                state = pickerState,
                onDismiss = { selected = null },
                onConfirm = {
                    onSave(setting, pickerState.hour * 60 + pickerState.minute)
                    selected = null
                },
            )
        }
    }
}

@Composable
private fun TimeRow(label: String, minuteOfDay: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(formatTime(minuteOfDay), color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    state: TimePickerState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun LocationPermissionSection() {
    val context = LocalContext.current
    var permissionVersion by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val fineGranted = remember(permissionVersion) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val backgroundGranted = remember(permissionVersion) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionVersion++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Location permission", style = MaterialTheme.typography.titleMedium)
        Text("Fine location: ${if (fineGranted) "Granted" else "Not granted"}")
        Text("Background location: ${if (backgroundGranted) "Granted" else "Not granted"}")
        if (!fineGranted) {
            Button(
                onClick = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
            ) {
                Text("Allow location")
            }
        } else if (!backgroundGranted) {
            Text(
                "Choose Permissions, Location, then Allow all the time",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
            ) {
                Text("Allow all the time in settings")
            }
        }
    }
}

private suspend fun refreshWidget(applicationContext: android.content.Context) {
    runCatching { CommuteWidget().updateAll(applicationContext) }
}

private fun formatTime(minuteOfDay: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
    }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)
}
