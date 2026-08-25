package com.crpakala.commutewidget.data

data class AppSettings(
    val apiKey: String = "",
    val home: Place? = null,
    val work: Place? = null,
    val travelMode: TravelMode = TravelMode.DRIVE,
    val favourites: List<Favourite> = emptyList(),
    val showFavouriteChips: Boolean = true,
    val favouriteWindowMinutes: Int = 60,
    val leaveByEnabled: Boolean = false,
    val arriveWorkByMinuteOfDay: Int = 570,
    val arriveHomeByMinuteOfDay: Int = 1170,
    val calendarEnabled: Boolean = false,
    val selectedCalendarIds: Set<Long> = emptySet(),
    val historyEnabled: Boolean = true,
    val historyDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val morningSlotStartMinuteOfDay: Int = 420,
    val morningSlotEndMinuteOfDay: Int = 600,
    val eveningSlotStartMinuteOfDay: Int = 1020,
    val eveningSlotEndMinuteOfDay: Int = 1200,
)
