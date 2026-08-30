package com.crpakala.commutewidget.data

data class AppSettings(
    val apiKey: String = "",
    val home: Place? = null,
    val work: Place? = null,
    val travelMode: TravelMode = TravelMode.DRIVE,
    val favourites: List<Favourite> = emptyList(),
    val leaveByEnabled: Boolean = false,
    val arriveWorkByMinuteOfDay: Int = 570,
    val arriveHomeByMinuteOfDay: Int = 1170,
    val eventLeaveByBufferMinutes: Int = 10,
    val eventRealtimeThresholdMinutes: Int = 60,
    /** A located event starting within this many minutes takes over the widget even inside commute windows. */
    val eventTakeoverMinutes: Int = 120,
    /** Which map corner hosts the overlay pills (leave-by, best departure) on the 4x2 widget. */
    val mapPillCorner: MapPillCorner = MapPillCorner.TOP_START,
    /** Widget background opacity percent (100 = solid); One UI-style translucency around 70-85. */
    val widgetBackgroundOpacityPercent: Int = 100,
    /** Widget text scale percent: 85 = small, 100 = default, 115 = large. */
    val widgetTextScalePercent: Int = 100,
    /**
     * Best-departure advisor: samples predicted traffic across the CURRENT commute window (morning
     * = home to work, evening = work to home, auto-switching) and shows the cheapest time.
     */
    val bestDepartureEnabled: Boolean = true,
    val calendarEnabled: Boolean = false,
    val selectedCalendarIds: Set<Long> = emptySet(),
    /**
     * Renamed from `historyDays` in v5: this set was always what actually gates which days have
     * commute windows at all (see [com.crpakala.commutewidget.engine.resolveWidgetMode]) - history
     * sampling merely piggybacked on the same field. The stored DataStore key remains
     * `history_days_json` unchanged, so an existing owner's device data survives the rename.
     */
    val commuteDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val morningSlotStartMinuteOfDay: Int = 420,
    val morningSlotEndMinuteOfDay: Int = 600,
    val eveningSlotStartMinuteOfDay: Int = 1020,
    val eveningSlotEndMinuteOfDay: Int = 1200,
    /** v5: opt-in coarse staleness tick for calendar mode - see [com.crpakala.commutewidget.schedule.CalendarTickWorker]. */
    val calendarTickEnabled: Boolean = true,
    val morningSupplementsEnabled: Boolean = true,
    val eveningProteinEnabled: Boolean = true,
    val waterRemindersEnabled: Boolean = true,
    val eveningWalkEnabled: Boolean = true,
    val sleepBriefEnabled: Boolean = true,
    val audiobookSuppressionEnabled: Boolean = true,
    val sleepDebtSoftenEnabled: Boolean = false,
    val gymProteinPriorityEnabled: Boolean = false,
    val restlessNightShieldEnabled: Boolean = false,
    val walkPostAudibleLatchEnabled: Boolean = false,
    val walkDaylightPreferenceEnabled: Boolean = false,
    val focusGapChipEnabled: Boolean = false,
    val postGymWaterPulseEnabled: Boolean = false,
    val morningLightLineEnabled: Boolean = false,
    val caffeineCutoffLineEnabled: Boolean = false,
    val stepGoal: Int = 8000,
    val waterRemindersPerDay: Int = 5,
    val morningSupplementsStartMinuteOfDay: Int = 420,
    val morningSupplementsEndMinuteOfDay: Int = 600,
    val proteinStartMinuteOfDay: Int = 1080,
    val proteinEndMinuteOfDay: Int = 1260,
    val walkSearchStartMinuteOfDay: Int = 1080,
    val walkSearchEndMinuteOfDay: Int = 1290,
    val caffeineCutoffMinuteOfDay: Int = 840,
    val commuteAudioPackages: Set<String> = setOf("com.audible.application"),
)
