package com.crpakala.commutewidget.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.SettingsRepository

/**
 * Sprint 4: the settings screen host. Android-Settings-style reorganization of the former
 * ~1500-line monolith into a pure category menu (8 rows, each with a live one-line summary) that
 * opens per-category screens defined in their own files under `ui/`. Navigation is hand-rolled -
 * a single [rememberSaveable] [AppScreen] value, no navigation library - with system back and the
 * top bar's back arrow both returning to the menu (Health > Experimental nudges is the one nested
 * exception: its back returns to Health first).
 */
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

private enum class AppScreen {
    HOME,
    COMMUTE_SETUP,
    PLACES_MAPS,
    ALERTS_TIMING,
    CALENDAR,
    REMINDERS,
    HEALTH,
    EXPERIMENTAL_NUDGES,
    WIDGET_APPEARANCE,
    ACCESS_APP_INFO,
}

private fun titleFor(screen: AppScreen): String = when (screen) {
    AppScreen.HOME -> "Commute Widget"
    AppScreen.COMMUTE_SETUP -> "Commute setup"
    AppScreen.PLACES_MAPS -> "Places & Maps"
    AppScreen.ALERTS_TIMING -> "Alerts & timing"
    AppScreen.CALENDAR -> "Calendar"
    AppScreen.REMINDERS -> "Reminders"
    AppScreen.HEALTH -> "Health"
    AppScreen.EXPERIMENTAL_NUDGES -> "Experimental nudges"
    AppScreen.WIDGET_APPEARANCE -> "Widget appearance"
    AppScreen.ACCESS_APP_INFO -> "Access & app info"
}

private fun parentOf(screen: AppScreen): AppScreen =
    if (screen == AppScreen.EXPERIMENTAL_NUDGES) AppScreen.HEALTH else AppScreen.HOME

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val repository = remember { SettingsRepository.get(applicationContext) }
    val settings by repository.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }

    BackHandler(enabled = screen != AppScreen.HOME) {
        screen = parentOf(screen)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(screen)) },
                navigationIcon = {
                    if (screen != AppScreen.HOME) {
                        IconButton(onClick = { screen = parentOf(screen) }) {
                            Text("\u2190", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        when (screen) {
            AppScreen.HOME -> HomeMenu(settings = settings, padding = padding) { destination ->
                screen = destination
            }
            AppScreen.COMMUTE_SETUP -> CommuteSetupScreen(
                settings, repository, scope, snackbarHostState, applicationContext, padding,
            )
            AppScreen.PLACES_MAPS -> PlacesMapsScreen(
                settings, repository, scope, snackbarHostState, applicationContext, padding,
            )
            AppScreen.ALERTS_TIMING -> AlertsTimingScreen(
                settings, repository, scope, snackbarHostState, applicationContext, padding,
                onNavigateToAccessInfo = { screen = AppScreen.ACCESS_APP_INFO },
            )
            AppScreen.CALENDAR -> CalendarScreen(
                settings, repository, scope, applicationContext, padding,
                onNavigateToAccessInfo = { screen = AppScreen.ACCESS_APP_INFO },
            )
            AppScreen.REMINDERS -> RemindersScreen(
                settings, repository, scope, applicationContext, padding,
            )
            AppScreen.HEALTH -> HealthScreen(
                settings, repository, scope, applicationContext, padding,
                onOpenExperimentalNudges = { screen = AppScreen.EXPERIMENTAL_NUDGES },
                onNavigateToAccessInfo = { screen = AppScreen.ACCESS_APP_INFO },
            )
            AppScreen.EXPERIMENTAL_NUDGES -> ExperimentalNudgesScreen(
                settings, repository, scope, applicationContext, padding,
            )
            AppScreen.WIDGET_APPEARANCE -> WidgetAppearanceScreen(
                settings, repository, scope, applicationContext, padding,
            )
            AppScreen.ACCESS_APP_INFO -> AccessAppInfoScreen(padding)
        }
    }
}

@Composable
private fun HomeMenu(settings: AppSettings, padding: PaddingValues, onNavigate: (AppScreen) -> Unit) {
    val accessStatus = rememberAccessPermissionsStatus()
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = padding.calculateTopPadding(),
            end = 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            CategoryRow("\uD83D\uDE97", "Commute setup", commuteSetupSummary(settings)) {
                onNavigate(AppScreen.COMMUTE_SETUP)
            }
        }
        item {
            CategoryRow("\uD83D\uDCCD", "Places & Maps", placesMapsSummary(settings)) {
                onNavigate(AppScreen.PLACES_MAPS)
            }
        }
        item {
            CategoryRow("\uD83D\uDD14", "Alerts & timing", alertsTimingSummary(settings)) {
                onNavigate(AppScreen.ALERTS_TIMING)
            }
        }
        item {
            CategoryRow("\uD83D\uDCC5", "Calendar", calendarSummary(settings)) {
                onNavigate(AppScreen.CALENDAR)
            }
        }
        item {
            CategoryRow("\u2705", "Reminders", remindersSummary(settings)) {
                onNavigate(AppScreen.REMINDERS)
            }
        }
        item {
            CategoryRow("\u2764", "Health", healthSummary(settings)) {
                onNavigate(AppScreen.HEALTH)
            }
        }
        item {
            CategoryRow("\u2699", "Widget appearance", widgetAppearanceSummary(settings)) {
                onNavigate(AppScreen.WIDGET_APPEARANCE)
            }
        }
        item {
            CategoryRow("\u2139", "Access & app info", accessAppInfoSummary(accessStatus)) {
                onNavigate(AppScreen.ACCESS_APP_INFO)
            }
        }
    }
}
