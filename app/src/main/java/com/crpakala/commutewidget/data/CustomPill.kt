package com.crpakala.commutewidget.data

import kotlinx.serialization.Serializable

@Serializable
data class CustomPill(
    val id: String,
    val name: String,
    val slotsMinutesOfDay: List<Int>,
    val days: Set<Int>,
) {
    companion object {
        const val MAX_PILLS = 6
        const val MAX_SLOTS_PER_PILL = 4

        /** Approved range for [AppSettings.customPillActiveWindowMinutes]; the repository setter clamps to it and the Reminders dialog offers exactly it. */
        const val ACTIVE_WINDOW_MIN_MINUTES = 15
        const val ACTIVE_WINDOW_MAX_MINUTES = 240
    }
}
