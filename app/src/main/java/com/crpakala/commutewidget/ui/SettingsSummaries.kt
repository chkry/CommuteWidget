package com.crpakala.commutewidget.ui

import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.MapPillCorner
import com.crpakala.commutewidget.data.TravelMode

/**
 * Live one-line summaries shown on the home category menu, each a pure function of [AppSettings]
 * (plus permission booleans via [AccessPermissionsStatus] where a category's summary needs them -
 * only "Access & app info" does; every other category's example summary is derivable from settings
 * alone).
 */

internal fun travelModeLabel(mode: TravelMode): String = when (mode) {
    TravelMode.DRIVE -> "Car"
    TravelMode.TWO_WHEELER -> "Two-wheeler"
}

internal fun mapPillCornerLabel(corner: MapPillCorner): String = when (corner) {
    MapPillCorner.TOP_START -> "Top left"
    MapPillCorner.TOP_END -> "Top right"
    MapPillCorner.BOTTOM_START -> "Bottom left"
    MapPillCorner.BOTTOM_END -> "Bottom right"
}

internal fun textScaleLabel(percent: Int): String = when (percent) {
    85 -> "Small"
    100 -> "Default"
    115 -> "Large"
    else -> "$percent%"
}

internal fun opacitySummaryLabel(percent: Int): String = if (percent >= 100) "Solid" else "$percent%"

internal fun commuteSetupSummary(settings: AppSettings): String {
    val locations = when {
        settings.home != null && settings.work != null -> "Home, Work set"
        settings.home != null -> "Home set"
        settings.work != null -> "Work set"
        else -> "Not set"
    }
    return "$locations - ${travelModeLabel(settings.travelMode)} - ${compactWeekdayRange(settings.commuteDays)}"
}

internal fun placesMapsSummary(settings: AppSettings): String {
    val keyPart = if (settings.apiKey.isNotBlank()) "Key set" else "Key not set"
    val count = settings.favourites.size
    val placesPart = when (count) {
        0 -> "No saved places"
        1 -> "1 saved place"
        else -> "$count saved places"
    }
    return "$keyPart - $placesPart"
}

internal fun alertsTimingSummary(settings: AppSettings): String {
    val leaveBy = if (settings.leaveByEnabled) "Leave-by on" else "Leave-by off"
    val bestDeparture = if (settings.bestDepartureEnabled) "Best departure on" else "Best departure off"
    return "$leaveBy - $bestDeparture"
}

internal fun calendarSummary(settings: AppSettings): String {
    if (!settings.calendarEnabled) return "Off"
    val count = settings.selectedCalendarIds.size
    return "On - $count calendar" + if (count == 1) "" else "s"
}

internal fun remindersSummary(settings: AppSettings): String {
    val count = settings.customPills.size
    return when (count) {
        0 -> "None"
        1 -> "1 reminder"
        else -> "$count reminders"
    }
}

/** The six core health features minus the audiobook-suppression toggle, which suppresses rather than nudges. */
private fun coreHealthNudgeToggles(settings: AppSettings): List<Boolean> = listOf(
    settings.morningSupplementsEnabled,
    settings.eveningProteinEnabled,
    settings.waterRemindersEnabled,
    settings.eveningWalkEnabled,
    settings.sleepBriefEnabled,
)

internal fun healthSummary(settings: AppSettings): String {
    val count = coreHealthNudgeToggles(settings).count { it }
    return if (count == 1) "1 nudge on" else "$count nudges on"
}

/** The nine experimental toggles under Health > Experimental nudges. */
private fun experimentalNudgeToggles(settings: AppSettings): List<Boolean> = listOf(
    settings.sleepDebtSoftenEnabled,
    settings.gymProteinPriorityEnabled,
    settings.restlessNightShieldEnabled,
    settings.walkPostAudibleLatchEnabled,
    settings.walkDaylightPreferenceEnabled,
    settings.focusGapChipEnabled,
    settings.postGymWaterPulseEnabled,
    settings.morningLightLineEnabled,
    settings.caffeineCutoffLineEnabled,
)

internal fun experimentalNudgesSummary(settings: AppSettings): String {
    val toggles = experimentalNudgeToggles(settings)
    return "${toggles.count { it }} of ${toggles.size} on"
}

internal fun widgetAppearanceSummary(settings: AppSettings): String =
    "${opacitySummaryLabel(settings.widgetBackgroundOpacityPercent)} - " +
        "${textScaleLabel(settings.widgetTextScalePercent)} text - " +
        mapPillCornerLabel(settings.mapPillCorner)

/**
 * Central permission snapshot for the "Access & app info" category - the only category whose home
 * summary depends on device permission state rather than [AppSettings] alone. Populated by
 * `rememberAccessPermissionsStatus` in `SettingsComponents.kt`.
 */
internal data class AccessPermissionsStatus(
    val locationFineGranted: Boolean,
    val locationBackgroundGranted: Boolean,
    val calendarGranted: Boolean,
    val notificationsGranted: Boolean,
    val healthConnectGranted: Boolean,
    val usageAccessGranted: Boolean,
    val notificationAccessGranted: Boolean,
)

internal fun AccessPermissionsStatus.missingCount(): Int = listOf(
    locationFineGranted,
    locationBackgroundGranted,
    calendarGranted,
    notificationsGranted,
    healthConnectGranted,
    usageAccessGranted,
    notificationAccessGranted,
).count { !it }

internal fun accessAppInfoSummary(status: AccessPermissionsStatus): String {
    val missing = status.missingCount()
    return if (missing == 0) "All granted" else "$missing permission" + (if (missing == 1) "" else "s") + " missing"
}
