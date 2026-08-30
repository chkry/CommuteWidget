package com.crpakala.commutewidget.ui

import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.CustomPill
import com.crpakala.commutewidget.data.MapPillCorner
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummariesTest {
    private val home = Place("123 Home St", 1.0, 2.0)
    private val work = Place("456 Work Ave", 3.0, 4.0)

    @Test
    fun commuteSetupSummaryReflectsLocationsModeAndDays() {
        assertEquals(
            "Not set - Car - Mo-Fr",
            commuteSetupSummary(AppSettings()),
        )
        assertEquals(
            "Home, Work set - Car - Mo-Fr",
            commuteSetupSummary(AppSettings(home = home, work = work)),
        )
        assertEquals(
            "Home set - Two-wheeler - Every day",
            commuteSetupSummary(
                AppSettings(home = home, travelMode = TravelMode.TWO_WHEELER, commuteDays = (1..7).toSet()),
            ),
        )
        assertEquals(
            "Work set - Car - Sa-Su",
            commuteSetupSummary(AppSettings(work = work, commuteDays = setOf(6, 7))),
        )
    }

    @Test
    fun placesMapsSummaryReflectsKeyAndSavedPlacesCount() {
        assertEquals("Key not set - No saved places", placesMapsSummary(AppSettings()))
        assertEquals(
            "Key set - No saved places",
            placesMapsSummary(AppSettings(apiKey = "abc123")),
        )
        assertEquals(
            "Key set - 1 saved place",
            placesMapsSummary(
                AppSettings(apiKey = "abc123", favourites = listOf(com.crpakala.commutewidget.data.Favourite("Gym", home))),
            ),
        )
        assertEquals(
            "Key set - 2 saved places",
            placesMapsSummary(
                AppSettings(
                    apiKey = "abc123",
                    favourites = listOf(
                        com.crpakala.commutewidget.data.Favourite("Gym", home),
                        com.crpakala.commutewidget.data.Favourite("Cafe", work),
                    ),
                ),
            ),
        )
    }

    @Test
    fun alertsTimingSummaryReflectsBothToggles() {
        assertEquals(
            "Leave-by off - Best departure on",
            alertsTimingSummary(AppSettings()),
        )
        assertEquals(
            "Leave-by on - Best departure on",
            alertsTimingSummary(AppSettings(leaveByEnabled = true)),
        )
        assertEquals(
            "Leave-by off - Best departure off",
            alertsTimingSummary(AppSettings(bestDepartureEnabled = false)),
        )
    }

    @Test
    fun calendarSummaryReflectsEnabledStateAndSelectedCount() {
        assertEquals("Off", calendarSummary(AppSettings()))
        assertEquals(
            "On - 0 calendars",
            calendarSummary(AppSettings(calendarEnabled = true)),
        )
        assertEquals(
            "On - 1 calendar",
            calendarSummary(AppSettings(calendarEnabled = true, selectedCalendarIds = setOf(1L))),
        )
        assertEquals(
            "On - 2 calendars",
            calendarSummary(AppSettings(calendarEnabled = true, selectedCalendarIds = setOf(1L, 2L))),
        )
    }

    @Test
    fun remindersSummaryReflectsPillCountWithSingularAndPluralForms() {
        assertEquals("None", remindersSummary(AppSettings()))
        assertEquals(
            "1 reminder",
            remindersSummary(AppSettings(customPills = listOf(samplePill("a")))),
        )
        assertEquals(
            "3 reminders",
            remindersSummary(AppSettings(customPills = listOf(samplePill("a"), samplePill("b"), samplePill("c")))),
        )
    }

    @Test
    fun healthSummaryCountsOnlyCoreNudgeTogglesNotAudiobookSuppression() {
        assertEquals("5 nudges on", healthSummary(AppSettings()))
        assertEquals(
            "0 nudges on",
            healthSummary(
                AppSettings(
                    morningSupplementsEnabled = false,
                    eveningProteinEnabled = false,
                    waterRemindersEnabled = false,
                    eveningWalkEnabled = false,
                    sleepBriefEnabled = false,
                ),
            ),
        )
        assertEquals(
            "1 nudge on",
            healthSummary(
                AppSettings(
                    morningSupplementsEnabled = true,
                    eveningProteinEnabled = false,
                    waterRemindersEnabled = false,
                    eveningWalkEnabled = false,
                    sleepBriefEnabled = false,
                ),
            ),
        )
    }

    @Test
    fun experimentalNudgesSummaryCountsOutOfNine() {
        assertEquals("0 of 9 on", experimentalNudgesSummary(AppSettings()))
        assertEquals(
            "2 of 9 on",
            experimentalNudgesSummary(
                AppSettings(sleepDebtSoftenEnabled = true, caffeineCutoffLineEnabled = true),
            ),
        )
        assertEquals(
            "9 of 9 on",
            experimentalNudgesSummary(
                AppSettings(
                    sleepDebtSoftenEnabled = true,
                    gymProteinPriorityEnabled = true,
                    restlessNightShieldEnabled = true,
                    walkPostAudibleLatchEnabled = true,
                    walkDaylightPreferenceEnabled = true,
                    focusGapChipEnabled = true,
                    postGymWaterPulseEnabled = true,
                    morningLightLineEnabled = true,
                    caffeineCutoffLineEnabled = true,
                ),
            ),
        )
    }

    @Test
    fun widgetAppearanceSummaryFormatsAllThreeSettings() {
        assertEquals(
            "Solid - Default text - Top left",
            widgetAppearanceSummary(AppSettings()),
        )
        assertEquals(
            "85% - Default text - Top left",
            widgetAppearanceSummary(AppSettings(widgetBackgroundOpacityPercent = 85)),
        )
        assertEquals(
            "85% - Small text - Bottom right",
            widgetAppearanceSummary(
                AppSettings(
                    widgetBackgroundOpacityPercent = 85,
                    widgetTextScalePercent = 85,
                    mapPillCorner = MapPillCorner.BOTTOM_END,
                ),
            ),
        )
    }

    @Test
    fun accessAppInfoSummaryReportsAllGrantedOrMissingCount() {
        val allGranted = AccessPermissionsStatus(
            locationFineGranted = true,
            locationBackgroundGranted = true,
            calendarGranted = true,
            notificationsGranted = true,
            healthConnectGranted = true,
            usageAccessGranted = true,
            notificationAccessGranted = true,
        )
        assertEquals("All granted", accessAppInfoSummary(allGranted))

        val oneMissing = allGranted.copy(notificationAccessGranted = false)
        assertEquals("1 permission missing", accessAppInfoSummary(oneMissing))

        val twoMissing = allGranted.copy(notificationAccessGranted = false, calendarGranted = false)
        assertEquals("2 permissions missing", accessAppInfoSummary(twoMissing))

        val noneGranted = AccessPermissionsStatus(
            locationFineGranted = false,
            locationBackgroundGranted = false,
            calendarGranted = false,
            notificationsGranted = false,
            healthConnectGranted = false,
            usageAccessGranted = false,
            notificationAccessGranted = false,
        )
        assertEquals("7 permissions missing", accessAppInfoSummary(noneGranted))
    }

    @Test
    fun travelModeLabelMapsBothModes() {
        assertEquals("Car", travelModeLabel(TravelMode.DRIVE))
        assertEquals("Two-wheeler", travelModeLabel(TravelMode.TWO_WHEELER))
    }

    @Test
    fun mapPillCornerLabelMapsAllFourCorners() {
        assertEquals("Top left", mapPillCornerLabel(MapPillCorner.TOP_START))
        assertEquals("Top right", mapPillCornerLabel(MapPillCorner.TOP_END))
        assertEquals("Bottom left", mapPillCornerLabel(MapPillCorner.BOTTOM_START))
        assertEquals("Bottom right", mapPillCornerLabel(MapPillCorner.BOTTOM_END))
    }

    @Test
    fun textScaleLabelMapsKnownPresetsAndFallsBackForUnknown() {
        assertEquals("Small", textScaleLabel(85))
        assertEquals("Default", textScaleLabel(100))
        assertEquals("Large", textScaleLabel(115))
        assertEquals("90%", textScaleLabel(90))
    }

    @Test
    fun opacitySummaryLabelTreatsFullAndAboveAsSolid() {
        assertEquals("Solid", opacitySummaryLabel(100))
        assertEquals("85%", opacitySummaryLabel(85))
    }

    private fun samplePill(id: String) = CustomPill(
        id = id,
        name = id,
        slotsMinutesOfDay = listOf(480),
        days = setOf(1),
    )
}
