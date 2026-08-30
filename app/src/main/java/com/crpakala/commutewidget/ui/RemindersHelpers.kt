package com.crpakala.commutewidget.ui

import com.crpakala.commutewidget.data.CustomPill

/** Mirrors the saved-places label validation pattern (see `AddFavouriteForm` in `SettingsComponents.kt`). */
internal const val REMINDER_NAME_MAX_LENGTH = 12

/**
 * Validates a reminder's name: required, at most [REMINDER_NAME_MAX_LENGTH] characters, and not a
 * case-insensitive duplicate of [existingNames] (the caller excludes the pill being edited, if any).
 */
internal fun validateReminderName(name: String, existingNames: Collection<String>): String? {
    val normalized = name.trim()
    return when {
        normalized.isBlank() -> "Name is required"
        normalized.length > REMINDER_NAME_MAX_LENGTH -> "Name must be $REMINDER_NAME_MAX_LENGTH characters or fewer"
        existingNames.any { it.equals(normalized, ignoreCase = true) } -> "Name already exists"
        else -> null
    }
}

internal fun canAddReminder(currentPillCount: Int): Boolean = currentPillCount < CustomPill.MAX_PILLS

internal fun canAddSlot(currentSlots: List<Int>, newSlotMinuteOfDay: Int): Boolean =
    currentSlots.size < CustomPill.MAX_SLOTS_PER_PILL && newSlotMinuteOfDay !in currentSlots

/** Adds a slot and keeps the list sorted, per the "slots display sorted" spec. Caller must check [canAddSlot] first. */
internal fun addSlot(currentSlots: List<Int>, newSlotMinuteOfDay: Int): List<Int> {
    require(canAddSlot(currentSlots, newSlotMinuteOfDay)) {
        "Cannot add slot $newSlotMinuteOfDay: at cap or duplicate"
    }
    return (currentSlots + newSlotMinuteOfDay).sorted()
}

internal fun removeSlot(currentSlots: List<Int>, slotMinuteOfDay: Int): List<Int> =
    currentSlots.filterNot { it == slotMinuteOfDay }

/**
 * Toggles a day's membership, refusing to drop the last remaining day - a pill always keeps at
 * least one enabled weekday (the "at least one required" rule), enforced as a silent no-op rather
 * than a blocking error since there is never a valid moment to show one.
 */
internal fun toggleReminderDay(currentDays: Set<Int>, day: Int): Set<Int> = when {
    day in currentDays && currentDays.size > 1 -> currentDays - day
    day in currentDays -> currentDays
    else -> currentDays + day
}

internal fun isReminderFormValid(
    name: String,
    existingNames: Collection<String>,
    days: Set<Int>,
    slots: List<Int>,
): Boolean = validateReminderName(name, existingNames) == null && days.isNotEmpty() && slots.isNotEmpty()

/**
 * Removes a deleted pill's taken-slot markers (encoded `"<pillId>:<slotMinute>"`) from today's
 * [com.crpakala.commutewidget.data.HealthDayState.customPillTakenSlots], via the existing
 * `SettingsRepository.updateHealthDayState` facility - no data-layer changes needed.
 */
internal fun pruneCustomPillTakenSlots(takenSlots: Set<String>, removedPillId: String): Set<String> =
    takenSlots.filterNot { it.startsWith("$removedPillId:") }.toSet()
