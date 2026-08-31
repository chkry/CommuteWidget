package com.crpakala.commutewidget.ui

import java.util.Locale

internal data class InstalledApp(
    val label: String,
    val packageName: String,
)

internal fun sortInstalledApps(apps: List<InstalledApp>): List<InstalledApp> =
    apps.sortedWith(
        compareBy<InstalledApp> { it.label.lowercase(Locale.ROOT) }
            .thenBy { it.packageName.lowercase(Locale.ROOT) },
    )

internal fun filterInstalledApps(apps: List<InstalledApp>, query: String): List<InstalledApp> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return if (normalizedQuery.isEmpty()) {
        apps
    } else {
        apps.filter { app ->
            app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        }
    }
}

internal fun selectedButUninstalledPackages(
    selectedPackages: Set<String>,
    installedApps: List<InstalledApp>,
): List<String> {
    val installedPackages = installedApps.mapTo(mutableSetOf()) { it.packageName }
    return selectedPackages
        .filterNot { it in installedPackages }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
}
