package com.crpakala.commutewidget.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crpakala.commutewidget.CommuteWidget
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.TravelMode
import com.crpakala.commutewidget.health.CommuteAudioDetector
import com.crpakala.commutewidget.health.HealthConnectFacade
import com.crpakala.commutewidget.health.ScreenEventsReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Shared, look-consistent building blocks used across every category screen produced by the
 * sprint 4 settings reorganization: row/dialog primitives moved unchanged from the pre-reorg
 * monolith, plus the new home-menu row, status-link row, and the permission-status composables
 * that back both individual category summaries and the central "Access & app info" screen.
 */

@Composable
internal fun TimeRow(label: String, minuteOfDay: Int, onClick: () -> Unit) {
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

@Composable
internal fun DurationRow(label: String, minutes: Int, onClick: () -> Unit) {
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
internal fun NumberRow(label: String, value: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(value.toString(), color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(
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
internal fun DurationDialog(
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

@Composable
internal fun NumberDialog(
    initialValue: Int,
    title: String,
    min: Int,
    max: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue.toString()) }
    val parsed = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("$min-$max") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = parsed == null || parsed !in min..max,
            )
        },
        confirmButton = {
            TextButton(
                enabled = parsed in min..max,
                onClick = { onSave(requireNotNull(parsed)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun HealthToggleRow(label: String, description: String, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = onChanged)
    }
}

/** The home menu's one row per category: a text glyph standing in for a leading icon (see report), title, and live summary. */
@Composable
internal fun CategoryRow(icon: String, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A within-screen row that opens a nested sub-screen (only Health > Experimental nudges uses this today). */
@Composable
internal fun NavigationRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("\u203A", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A tappable one-line status ("Notifications off - alerts show on widget only" style) that
 * navigates to "Access & app info" - the approved permission model: feature screens show status,
 * grants live centrally.
 */
@Composable
internal fun PermissionStatusLinkRow(statusText: String, onClick: () -> Unit) {
    Text(
        statusText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

internal fun hasAllHealthPermissions(granted: Set<String>, required: Set<String>): Boolean =
    granted.containsAll(required)

internal fun formatTime(minuteOfDay: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
    }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)
}

internal suspend fun refreshWidget(applicationContext: Context) {
    runCatching { CommuteWidget().updateAll(applicationContext) }
}

internal fun launchNavigation(context: Context, place: Place, travelMode: TravelMode) {
    val mode = when (travelMode) {
        TravelMode.DRIVE -> "d"
        TravelMode.TWO_WHEELER -> "l"
    }
    val coordinate = "${place.lat},${place.lng}"
    val mapsIntent = Intent(
        Intent.ACTION_VIEW,
        "google.navigation:q=$coordinate&mode=$mode".toUri(),
    ).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(mapsIntent)
    } catch (_: ActivityNotFoundException) {
        val geoIntent = Intent(Intent.ACTION_VIEW, "geo:$coordinate".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(geoIntent)
        } catch (_: ActivityNotFoundException) {
            // No compatible maps application is installed.
        }
    }
}

/** A single runtime permission's granted state, refreshed on `ON_RESUME` and by [PermissionState.refresh] after a request. */
internal data class PermissionState(val granted: Boolean, val refresh: () -> Unit)

@Composable
internal fun rememberPermissionState(permission: String): PermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var version by remember { mutableIntStateOf(0) }
    val granted = remember(version) {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) version++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return PermissionState(granted = granted, refresh = { version++ })
}

/** The three health-adjacent permission surfaces that are not simple runtime permissions: Health Connect, usage access, notification access. */
internal data class HealthPermissionStatus(
    val healthAvailable: Boolean,
    val healthConnectGranted: Boolean,
    val usageAccessGranted: Boolean,
    val notificationAccessGranted: Boolean,
    val refresh: () -> Unit,
)

@Composable
internal fun rememberHealthPermissionStatus(): HealthPermissionStatus {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionVersion by remember { mutableIntStateOf(0) }
    val healthAvailable = remember(permissionVersion) { HealthConnectFacade.isAvailable(context) }
    var grantedHealthPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    val usageAccessGranted = remember(permissionVersion) { ScreenEventsReader.hasUsageAccess(context) }
    val notificationAccessGranted = remember(permissionVersion) {
        CommuteAudioDetector.hasNotificationAccess(context)
    }
    LaunchedEffect(healthAvailable, permissionVersion) {
        grantedHealthPermissions = if (healthAvailable) {
            HealthConnectFacade.grantedPermissions(context)
        } else {
            emptySet()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return HealthPermissionStatus(
        healthAvailable = healthAvailable,
        healthConnectGranted = hasAllHealthPermissions(grantedHealthPermissions, HealthConnectFacade.REQUIRED_PERMISSIONS),
        usageAccessGranted = usageAccessGranted,
        notificationAccessGranted = notificationAccessGranted,
        refresh = { permissionVersion++ },
    )
}

/** Aggregates every permission the app cares about, for the "Access & app info" home-row summary and detail screen. */
@Composable
internal fun rememberAccessPermissionsStatus(): AccessPermissionsStatus {
    val locationFine = rememberPermissionState(android.Manifest.permission.ACCESS_FINE_LOCATION).granted
    val locationBackground = rememberPermissionState(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION).granted
    val calendar = rememberPermissionState(android.Manifest.permission.READ_CALENDAR).granted
    val notifications = rememberPermissionState(android.Manifest.permission.POST_NOTIFICATIONS).granted
    val health = rememberHealthPermissionStatus()
    return AccessPermissionsStatus(
        locationFineGranted = locationFine,
        locationBackgroundGranted = locationBackground,
        calendarGranted = calendar,
        notificationsGranted = notifications,
        healthConnectGranted = health.healthConnectGranted,
        usageAccessGranted = health.usageAccessGranted,
        notificationAccessGranted = health.notificationAccessGranted,
    )
}
