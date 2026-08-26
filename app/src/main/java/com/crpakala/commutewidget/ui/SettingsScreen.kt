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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.calendar.CalendarReader
import com.crpakala.commutewidget.calendar.DeviceCalendar
import com.crpakala.commutewidget.history.DateCount
import com.crpakala.commutewidget.history.HistoryStore
import com.crpakala.commutewidget.schedule.CommuteScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun CommuteWidgetApp(
    showStats: Boolean,
    onViewStats: () -> Unit,
    onBackToSettings: () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    // minSdk 34 is always >= S (31), so dynamic color is unconditionally available.
    val colors = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(
        colorScheme = colors,
    ) {
        if (showStats) {
            StatsScreen(onBack = onBackToSettings)
        } else {
            SettingsScreen(onViewStats = onViewStats)
        }
    }
}

@Composable
private fun SettingsScreen(onViewStats: () -> Unit) {
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
                FavouritesSection(
                    favourites = settings.favourites,
                    showChips = settings.showFavouriteChips,
                    windowMinutes = settings.favouriteWindowMinutes,
                    apiKey = settings.apiKey,
                    onSaveFavourites = { favourites ->
                        scope.launch {
                            repository.setFavourites(favourites)
                            refreshWidget(applicationContext)
                        }
                    },
                    onShowChipsChanged = { enabled ->
                        scope.launch {
                            repository.setShowFavouriteChips(enabled)
                            refreshWidget(applicationContext)
                        }
                    },
                    onWindowChanged = { minutes ->
                        scope.launch {
                            repository.setFavouriteWindowMinutes(minutes)
                            refreshWidget(applicationContext)
                        }
                    },
                )
            }
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
                CalendarSection(
                    enabled = settings.calendarEnabled,
                    selectedIds = settings.selectedCalendarIds,
                    onEnabledChanged = { enabled ->
                        scope.launch {
                            repository.setCalendarEnabled(enabled)
                            refreshWidget(applicationContext)
                        }
                    },
                    onSelectedIdsChanged = { ids ->
                        scope.launch {
                            repository.setSelectedCalendarIds(ids)
                            refreshWidget(applicationContext)
                        }
                    },
                )
            }
            item {
                HistorySection(
                    settings = settings,
                    onSettingsChange = { update ->
                        scope.launch {
                            update(repository)
                            CommuteScheduler.ensureScheduled(applicationContext)
                            refreshWidget(applicationContext)
                        }
                    },
                    onViewStats = onViewStats,
                )
            }
            item {
                LocationPermissionSection()
            }
            item {
                Text(
                    "The widget follows your commute windows; outside them it shows your next calendar event. " +
                        "History collects every 10 minutes inside windows on selected days.",
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
private fun FavouritesSection(
    favourites: List<Favourite>,
    showChips: Boolean,
    windowMinutes: Int,
    apiKey: String,
    onSaveFavourites: (List<Favourite>) -> Unit,
    onShowChipsChanged: (Boolean) -> Unit,
    onWindowChanged: (Int) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var editWindow by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Favourites", style = MaterialTheme.typography.titleMedium)
        favourites.forEach { favourite ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(favourite.label)
                    Text(favourite.place.address, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    onSaveFavourites(favourites.filterNot { it.label == favourite.label })
                }) { Text("Delete") }
            }
        }
        if (favourites.size < 4) {
            TextButton(onClick = { adding = !adding }) {
                Text(if (adding) "Cancel adding favourite" else "Add favourite")
            }
        }
        if (adding) {
            AddFavouriteForm(
                apiKey = apiKey,
                existingLabels = favourites.map { it.label }.toSet(),
                onAdd = {
                    onSaveFavourites(favourites + it)
                    adding = false
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Show favourite chips on widget")
            Switch(checked = showChips, onCheckedChange = onShowChipsChanged)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { editWindow = true }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Favourite active for")
            Text("$windowMinutes min", color = MaterialTheme.colorScheme.primary)
        }
    }
    if (editWindow) {
        DurationDialog(
            initialMinutes = windowMinutes,
            title = "Favourite active for",
            minMinutes = 15,
            maxMinutes = 240,
            onDismiss = { editWindow = false },
            onSave = {
                onWindowChanged(it)
                editWindow = false
            },
        )
    }
}

@Composable
private fun AddFavouriteForm(
    apiKey: String,
    existingLabels: Set<String>,
    onAdd: (Favourite) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val normalized = label.trim()
    val labelError = when {
        normalized.isBlank() -> "Label is required"
        normalized.length > 12 -> "Label must be 12 characters or fewer"
        existingLabels.any { it.equals(normalized, ignoreCase = true) } -> "Label already exists"
        else -> null
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Short label") },
                isError = label.isNotBlank() && labelError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (apiKey.isBlank()) Text("Enter API key first", color = MaterialTheme.colorScheme.error)
            Button(
                enabled = apiKey.isNotBlank() && address.isNotBlank() && labelError == null,
                onClick = {
                    error = null
                    results = emptyList()
                    scope.launch {
                        when (val response = GeocodingClient(apiKey).geocode(address.trim())) {
                            is ApiResult.Success -> results = response.value.take(5)
                            is ApiResult.Failure -> error = response.message
                        }
                    }
                },
            ) { Text("Search") }
            labelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            results.forEach { hit ->
                Text(
                    hit.formattedAddress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAdd(Favourite(normalized, Place(hit.formattedAddress, hit.location.lat, hit.location.lng)))
                        }
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DurationDialog(
    initialMinutes: Int,
    title: String,
    minMinutes: Int,
    maxMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initialMinutes.toString()) }
    val minutes = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Minutes ($minMinutes-$maxMinutes)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = minutes == null || minutes !in minMinutes..maxMinutes,
            )
        },
        confirmButton = {
            TextButton(
                enabled = minutes in minMinutes..maxMinutes,
                onClick = { onSave(requireNotNull(minutes)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveBySection(
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

private enum class LeaveByDuration { ARRIVE_EARLY_BUFFER, LIVE_TRAFFIC_THRESHOLD }

@Composable
private fun DurationRow(label: String, minutes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text("$minutes min", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CalendarSection(
    enabled: Boolean,
    selectedIds: Set<Long>,
    onEnabledChanged: (Boolean) -> Unit,
    onSelectedIdsChanged: (Set<Long>) -> Unit,
) {
    val context = LocalContext.current
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
            "Outside your commute windows, the widget shows the next event from these calendars and routes to it when it has a location.",
            style = MaterialTheme.typography.bodySmall,
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

private enum class HistoryTime { MORNING_START, MORNING_END, EVENING_START, EVENING_END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySection(
    settings: AppSettings,
    onSettingsChange: (suspend (SettingsRepository) -> Unit) -> Unit,
    onViewStats: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository.get(context.applicationContext) }
    val history = remember { HistoryStore.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var selectedTime by remember { mutableStateOf<HistoryTime?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf<Long?>(null) }
    var dates by remember { mutableStateOf<List<DateCount>>(emptyList()) }
    var deleteDate by remember { mutableStateOf<DateCount?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun refreshHistoryCounts() {
        scope.launch {
            total = history.totalCount()
            dates = history.datesWithData()
        }
    }
    LaunchedEffect(expanded) { if (expanded) refreshHistoryCounts() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Commute windows", style = MaterialTheme.typography.titleMedium)
        Text(
            "Inside a window the widget shows that commute and collects history every 10 minutes. " +
                "Outside your windows it shows your next calendar event.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Collect commute history")
                Text(
                    "Widget modes still follow the windows when this is off",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings.historyEnabled, onCheckedChange = { enabled ->
                onSettingsChange { it.setHistoryEnabled(enabled) }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, label ->
                val day = index + 1
                FilterChip(
                    selected = day in settings.historyDays,
                    onClick = {
                        val next = if (day in settings.historyDays) settings.historyDays - day else settings.historyDays + day
                        onSettingsChange { it.setHistoryDays(next) }
                    },
                    label = { Text(label) },
                )
            }
        }
        TimeRow("To Work window start", settings.morningSlotStartMinuteOfDay) { selectedTime = HistoryTime.MORNING_START }
        TimeRow("To Work window end", settings.morningSlotEndMinuteOfDay) { selectedTime = HistoryTime.MORNING_END }
        TimeRow("To Home window start", settings.eveningSlotStartMinuteOfDay) { selectedTime = HistoryTime.EVENING_START }
        TimeRow("To Home window end", settings.eveningSlotEndMinuteOfDay) { selectedTime = HistoryTime.EVENING_END }
        validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onViewStats) { Text("View commute stats") }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide data management" else "Data management")
        }
        if (expanded) {
            Text("Total samples: ${total ?: "Loading..."}")
            dates.forEach { date ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${date.localDate}: ${date.sampleCount}")
                    TextButton(onClick = { deleteDate = date }) { Text("Delete") }
                }
            }
            Button(onClick = { clearAll = true }, enabled = (total ?: 0) > 0) { Text("Clear all history") }
        }
    }
    selectedTime?.let { selection ->
        val current = when (selection) {
            HistoryTime.MORNING_START -> settings.morningSlotStartMinuteOfDay
            HistoryTime.MORNING_END -> settings.morningSlotEndMinuteOfDay
            HistoryTime.EVENING_START -> settings.eveningSlotStartMinuteOfDay
            HistoryTime.EVENING_END -> settings.eveningSlotEndMinuteOfDay
        }
        key(selection, current) {
            val picker = rememberTimePickerState(current / 60, current % 60, is24Hour = false)
            TimePickerDialog(picker, { selectedTime = null }) {
                val minute = picker.hour * 60 + picker.minute
                val valid = when (selection) {
                    HistoryTime.MORNING_START -> minute < settings.morningSlotEndMinuteOfDay
                    HistoryTime.MORNING_END -> settings.morningSlotStartMinuteOfDay < minute
                    HistoryTime.EVENING_START -> minute < settings.eveningSlotEndMinuteOfDay
                    HistoryTime.EVENING_END -> settings.eveningSlotStartMinuteOfDay < minute
                }
                if (!valid) {
                    validationError = if (selection == HistoryTime.MORNING_START || selection == HistoryTime.MORNING_END) {
                        "To Work window start must be before end"
                    } else {
                        "To Home window start must be before end"
                    }
                } else {
                    validationError = null
                    onSettingsChange {
                        when (selection) {
                            HistoryTime.MORNING_START -> it.setMorningSlotStartMinuteOfDay(minute)
                            HistoryTime.MORNING_END -> it.setMorningSlotEndMinuteOfDay(minute)
                            HistoryTime.EVENING_START -> it.setEveningSlotStartMinuteOfDay(minute)
                            HistoryTime.EVENING_END -> it.setEveningSlotEndMinuteOfDay(minute)
                        }
                    }
                }
                selectedTime = null
            }
        }
    }
    deleteDate?.let { date ->
        AlertDialog(
            onDismissRequest = { deleteDate = null },
            title = { Text("Delete history") },
            text = { Text("Delete ${date.sampleCount} samples from `${date.localDate}`?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        history.deleteDate(date.localDate)
                        refreshHistoryCounts()
                    }
                    deleteDate = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteDate = null }) { Text("Cancel") } },
        )
    }
    if (clearAll) {
        AlertDialog(
            onDismissRequest = { clearAll = false },
            title = { Text("Clear all history") },
            text = { Text("Delete all collected commute samples?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        history.clearAll()
                        refreshHistoryCounts()
                    }
                    clearAll = false
                }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { clearAll = false }) { Text("Cancel") } },
        )
    }
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
