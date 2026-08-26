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
    /** Best-departure advisor: sample predicted traffic across the departure slot, show the cheapest time. */
    val bestDepartureEnabled: Boolean = true,
    val departureSlotStartMinuteOfDay: Int = 840,
    val departureSlotEndMinuteOfDay: Int = 1080,
    val departureSlotDirection: Direction = Direction.TO_WORK,
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
)
