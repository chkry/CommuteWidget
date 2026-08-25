package com.crpakala.commutewidget.data

data class AppSettings(
    val apiKey: String = "",
    val home: Place? = null,
    val work: Place? = null,
    val travelMode: TravelMode = TravelMode.DRIVE,
    val switchMinuteOfDay: Int = 840,
    val morningRefreshMinuteOfDay: Int = 480,
    val eveningRefreshMinuteOfDay: Int = 1020,
)
