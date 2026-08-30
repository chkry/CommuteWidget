package com.crpakala.commutewidget.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crpakala.commutewidget.health.HealthConnectFacade

/**
 * "Access & app info" - the central permissions screen (approved permission model): every grant
 * launcher and settings-intent lives here; feature screens elsewhere only show a status link back
 * to this screen. Also carries the refresh-behavior footer text moved from the old monolith.
 */
@Composable
fun AccessAppInfoScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val calendarState = rememberPermissionState(Manifest.permission.READ_CALENDAR)
    val notificationsState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { calendarState.refresh() }
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsState.refresh() }
    val healthStatus = rememberHealthPermissionStatus()
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { healthStatus.refresh() }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LocationPermissionSection() }
        item {
            PermissionGrantRow(
                label = "Calendar",
                status = if (calendarState.granted) "Granted" else "Not granted",
                enabled = true,
                onGrant = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) },
            )
        }
        item {
            PermissionGrantRow(
                label = "Notifications",
                status = if (notificationsState.granted) "Granted" else "Not granted",
                enabled = true,
                onGrant = { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
        item {
            PermissionGrantRow(
                label = "Health Connect",
                status = when {
                    !healthStatus.healthAvailable -> "Health Connect unavailable"
                    healthStatus.healthConnectGranted -> "Granted"
                    else -> "Not granted"
                },
                enabled = healthStatus.healthAvailable,
                onGrant = { healthPermissionLauncher.launch(HealthConnectFacade.REQUIRED_PERMISSIONS) },
            )
        }
        item {
            PermissionGrantRow(
                label = "Usage access",
                status = if (healthStatus.usageAccessGranted) "Granted" else "Not granted",
                enabled = true,
                onGrant = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            )
        }
        item {
            PermissionGrantRow(
                label = "Notification access",
                status = if (healthStatus.notificationAccessGranted) "Granted" else "Not granted",
                enabled = true,
                onGrant = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
        }
        item {
            Text(
                "The widget refreshes on tap, at window boundaries, and every 20 minutes while showing a routed event.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionGrantRow(label: String, status: String, enabled: Boolean, onGrant: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = enabled && status != "Granted", onClick = onGrant) { Text("Grant") }
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
