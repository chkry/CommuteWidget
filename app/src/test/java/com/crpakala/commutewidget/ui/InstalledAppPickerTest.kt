package com.crpakala.commutewidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppPickerTest {
    private val apps = listOf(
        InstalledApp(label = "zebra reader", packageName = "com.zebra.reader"),
        InstalledApp(label = "Audible", packageName = "com.audible.application"),
        InstalledApp(label = "Book Player", packageName = "org.bookplayer"),
    )

    @Test
    fun sortInstalledAppsOrdersLabelsCaseInsensitively() {
        assertEquals(
            listOf("Audible", "Book Player", "zebra reader"),
            sortInstalledApps(apps).map { it.label },
        )
    }

    @Test
    fun filterInstalledAppsMatchesLabelSubstring() {
        assertEquals(
            listOf("Book Player"),
            filterInstalledApps(apps, "PLAY").map { it.label },
        )
    }

    @Test
    fun filterInstalledAppsMatchesPackageSubstring() {
        assertEquals(
            listOf("Audible"),
            filterInstalledApps(apps, "AUDIBLE.APPLICATION").map { it.label },
        )
    }

    @Test
    fun filterInstalledAppsReturnsAllForEmptyQuery() {
        assertEquals(apps, filterInstalledApps(apps, "   "))
    }

    @Test
    fun selectedButUninstalledPackagesReturnsOnlyMissingPackages() {
        assertEquals(
            listOf("com.legacy.reader"),
            selectedButUninstalledPackages(
                selectedPackages = setOf("com.audible.application", "com.legacy.reader"),
                installedApps = apps,
            ),
        )
    }
}
