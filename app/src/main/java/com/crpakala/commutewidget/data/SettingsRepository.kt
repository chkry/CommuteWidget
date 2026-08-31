package com.crpakala.commutewidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.commuteSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "commute_settings",
)

private object PreferenceKeys {
    val API_KEY = stringPreferencesKey("api_key")
    val HOME_JSON = stringPreferencesKey("home_json")
    val WORK_JSON = stringPreferencesKey("work_json")
    val TRAVEL_MODE = stringPreferencesKey("travel_mode")
    val SNAPSHOT_JSON = stringPreferencesKey("snapshot_json")
    val FAVOURITES_JSON = stringPreferencesKey("favourites_json")
    val LEAVE_BY_ENABLED = booleanPreferencesKey("leave_by_enabled")
    val ARRIVE_WORK_BY_MINUTE_OF_DAY = intPreferencesKey("arrive_work_by_minute_of_day")
    val ARRIVE_HOME_BY_MINUTE_OF_DAY = intPreferencesKey("arrive_home_by_minute_of_day")
    val CALENDAR_ENABLED = booleanPreferencesKey("calendar_enabled")
    val SELECTED_CALENDAR_IDS_JSON = stringPreferencesKey("selected_calendar_ids_json")
    // v5 renames historyDays -> commuteDays (it always gated commute windows, not just sampling);
    // the stored key literal is kept exactly as-is so an existing owner's device data survives.
    val HISTORY_DAYS_JSON = stringPreferencesKey("history_days_json")
    val MORNING_SLOT_START_MINUTE_OF_DAY = intPreferencesKey("morning_slot_start_minute_of_day")
    val MORNING_SLOT_END_MINUTE_OF_DAY = intPreferencesKey("morning_slot_end_minute_of_day")
    val EVENING_SLOT_START_MINUTE_OF_DAY = intPreferencesKey("evening_slot_start_minute_of_day")
    val EVENING_SLOT_END_MINUTE_OF_DAY = intPreferencesKey("evening_slot_end_minute_of_day")
    val LEAVE_BY_NOTIFIED_TO_WORK = stringPreferencesKey("leave_by_notified_to_work")
    val LEAVE_BY_NOTIFIED_TO_HOME = stringPreferencesKey("leave_by_notified_to_home")
    val EVENT_LEAVE_BY_BUFFER_MINUTES = intPreferencesKey("event_leave_by_buffer_minutes")
    val EVENT_REALTIME_THRESHOLD_MINUTES = intPreferencesKey("event_realtime_threshold_minutes")
    val EVENT_TAKEOVER_MINUTES = intPreferencesKey("event_takeover_minutes")
    val EVENT_LEAVE_BY_NOTIFIED_KEY = stringPreferencesKey("event_leave_by_notified_key")
    val CALENDAR_TICK_ENABLED = booleanPreferencesKey("calendar_tick_enabled")
    val REFRESHING_SINCE_EPOCH_MILLIS = longPreferencesKey("refreshing_since_epoch_millis")
    val MAP_RENDER_KEY = stringPreferencesKey("map_render_key")
    val GEOCODE_CACHE_JSON = stringPreferencesKey("geocode_cache_json")
    val MAP_PILL_CORNER = stringPreferencesKey("map_pill_corner")
    val WIDGET_BACKGROUND_OPACITY_PERCENT = intPreferencesKey("widget_background_opacity_percent")
    val WIDGET_TEXT_SCALE_PERCENT = intPreferencesKey("widget_text_scale_percent")
    val BEST_DEPARTURE_ENABLED = booleanPreferencesKey("best_departure_enabled")
    val BEST_DEPARTURE_JSON = stringPreferencesKey("best_departure_json")
    val MORNING_SUPPLEMENTS_ENABLED = booleanPreferencesKey("morning_supplements_enabled")
    val EVENING_PROTEIN_ENABLED = booleanPreferencesKey("evening_protein_enabled")
    val WATER_REMINDERS_ENABLED = booleanPreferencesKey("water_reminders_enabled")
    val EVENING_WALK_ENABLED = booleanPreferencesKey("evening_walk_enabled")
    val SLEEP_BRIEF_ENABLED = booleanPreferencesKey("sleep_brief_enabled")
    val AUDIOBOOK_SUPPRESSION_ENABLED = booleanPreferencesKey("audiobook_suppression_enabled")
    val SLEEP_DEBT_SOFTEN_ENABLED = booleanPreferencesKey("sleep_debt_soften_enabled")
    val GYM_PROTEIN_PRIORITY_ENABLED = booleanPreferencesKey("gym_protein_priority_enabled")
    val RESTLESS_NIGHT_SHIELD_ENABLED = booleanPreferencesKey("restless_night_shield_enabled")
    val WALK_POST_AUDIBLE_LATCH_ENABLED = booleanPreferencesKey("walk_post_audible_latch_enabled")
    val WALK_DAYLIGHT_PREFERENCE_ENABLED = booleanPreferencesKey("walk_daylight_preference_enabled")
    val FOCUS_GAP_CHIP_ENABLED = booleanPreferencesKey("focus_gap_chip_enabled")
    val POST_GYM_WATER_PULSE_ENABLED = booleanPreferencesKey("post_gym_water_pulse_enabled")
    val MORNING_LIGHT_LINE_ENABLED = booleanPreferencesKey("morning_light_line_enabled")
    val CAFFEINE_CUTOFF_LINE_ENABLED = booleanPreferencesKey("caffeine_cutoff_line_enabled")
    val STEP_GOAL = intPreferencesKey("step_goal")
    val WATER_REMINDERS_PER_DAY = intPreferencesKey("water_reminders_per_day")
    val MORNING_SUPPLEMENTS_START_MINUTE_OF_DAY =
        intPreferencesKey("morning_supplements_start_minute_of_day")
    val MORNING_SUPPLEMENTS_END_MINUTE_OF_DAY =
        intPreferencesKey("morning_supplements_end_minute_of_day")
    val PROTEIN_START_MINUTE_OF_DAY = intPreferencesKey("protein_start_minute_of_day")
    val PROTEIN_END_MINUTE_OF_DAY = intPreferencesKey("protein_end_minute_of_day")
    val WALK_SEARCH_START_MINUTE_OF_DAY = intPreferencesKey("walk_search_start_minute_of_day")
    val WALK_SEARCH_END_MINUTE_OF_DAY = intPreferencesKey("walk_search_end_minute_of_day")
    val CAFFEINE_CUTOFF_MINUTE_OF_DAY = intPreferencesKey("caffeine_cutoff_minute_of_day")
    val COMMUTE_AUDIO_PACKAGES_JSON = stringPreferencesKey("commute_audio_packages_json")
    val HEALTH_DAY_STATE_JSON = stringPreferencesKey("health_day_state_json")
    val HEALTH_HISTORY_JSON = stringPreferencesKey("health_history_json")
    val CUSTOM_PILLS_JSON = stringPreferencesKey("custom_pills_json")
    val CUSTOM_PILL_ACTIVE_WINDOW_MINUTES = intPreferencesKey("custom_pill_active_window_minutes")
    val LAST_TO_BED_TAP_EPOCH_MILLIS = longPreferencesKey("last_to_bed_tap_epoch_millis")
    val LAST_WOKE_UP_TAP_EPOCH_MILLIS = longPreferencesKey("last_woke_up_tap_epoch_millis")
}

private fun Preferences.toAppSettings(): AppSettings {
    return AppSettings(
        apiKey = this[PreferenceKeys.API_KEY] ?: "",
        home = decodePlace(this[PreferenceKeys.HOME_JSON]),
        work = decodePlace(this[PreferenceKeys.WORK_JSON]),
        travelMode = parseTravelMode(this[PreferenceKeys.TRAVEL_MODE]),
        favourites = decodeFavourites(this[PreferenceKeys.FAVOURITES_JSON]),
        leaveByEnabled = this[PreferenceKeys.LEAVE_BY_ENABLED] ?: false,
        arriveWorkByMinuteOfDay = this[PreferenceKeys.ARRIVE_WORK_BY_MINUTE_OF_DAY] ?: 570,
        arriveHomeByMinuteOfDay = this[PreferenceKeys.ARRIVE_HOME_BY_MINUTE_OF_DAY] ?: 1170,
        eventLeaveByBufferMinutes = this[PreferenceKeys.EVENT_LEAVE_BY_BUFFER_MINUTES] ?: 10,
        eventRealtimeThresholdMinutes = this[PreferenceKeys.EVENT_REALTIME_THRESHOLD_MINUTES] ?: 60,
        eventTakeoverMinutes = this[PreferenceKeys.EVENT_TAKEOVER_MINUTES] ?: 120,
        mapPillCorner = parseMapPillCorner(this[PreferenceKeys.MAP_PILL_CORNER]),
        widgetBackgroundOpacityPercent = this[PreferenceKeys.WIDGET_BACKGROUND_OPACITY_PERCENT] ?: 100,
        widgetTextScalePercent = this[PreferenceKeys.WIDGET_TEXT_SCALE_PERCENT] ?: 100,
        bestDepartureEnabled = this[PreferenceKeys.BEST_DEPARTURE_ENABLED] ?: true,
        calendarEnabled = this[PreferenceKeys.CALENDAR_ENABLED] ?: false,
        selectedCalendarIds = decodeLongSet(this[PreferenceKeys.SELECTED_CALENDAR_IDS_JSON]),
        commuteDays = decodeIntSet(this[PreferenceKeys.HISTORY_DAYS_JSON], setOf(1, 2, 3, 4, 5)),
        morningSlotStartMinuteOfDay = this[PreferenceKeys.MORNING_SLOT_START_MINUTE_OF_DAY] ?: 420,
        morningSlotEndMinuteOfDay = this[PreferenceKeys.MORNING_SLOT_END_MINUTE_OF_DAY] ?: 600,
        eveningSlotStartMinuteOfDay = this[PreferenceKeys.EVENING_SLOT_START_MINUTE_OF_DAY] ?: 1020,
        eveningSlotEndMinuteOfDay = this[PreferenceKeys.EVENING_SLOT_END_MINUTE_OF_DAY] ?: 1200,
        calendarTickEnabled = this[PreferenceKeys.CALENDAR_TICK_ENABLED] ?: true,
        morningSupplementsEnabled = this[PreferenceKeys.MORNING_SUPPLEMENTS_ENABLED] ?: true,
        eveningProteinEnabled = this[PreferenceKeys.EVENING_PROTEIN_ENABLED] ?: true,
        waterRemindersEnabled = this[PreferenceKeys.WATER_REMINDERS_ENABLED] ?: true,
        eveningWalkEnabled = this[PreferenceKeys.EVENING_WALK_ENABLED] ?: true,
        sleepBriefEnabled = this[PreferenceKeys.SLEEP_BRIEF_ENABLED] ?: true,
        audiobookSuppressionEnabled = this[PreferenceKeys.AUDIOBOOK_SUPPRESSION_ENABLED] ?: true,
        sleepDebtSoftenEnabled = this[PreferenceKeys.SLEEP_DEBT_SOFTEN_ENABLED] ?: false,
        gymProteinPriorityEnabled = this[PreferenceKeys.GYM_PROTEIN_PRIORITY_ENABLED] ?: false,
        restlessNightShieldEnabled = this[PreferenceKeys.RESTLESS_NIGHT_SHIELD_ENABLED] ?: false,
        walkPostAudibleLatchEnabled = this[PreferenceKeys.WALK_POST_AUDIBLE_LATCH_ENABLED] ?: false,
        walkDaylightPreferenceEnabled = this[PreferenceKeys.WALK_DAYLIGHT_PREFERENCE_ENABLED] ?: false,
        focusGapChipEnabled = this[PreferenceKeys.FOCUS_GAP_CHIP_ENABLED] ?: false,
        postGymWaterPulseEnabled = this[PreferenceKeys.POST_GYM_WATER_PULSE_ENABLED] ?: false,
        morningLightLineEnabled = this[PreferenceKeys.MORNING_LIGHT_LINE_ENABLED] ?: false,
        caffeineCutoffLineEnabled = this[PreferenceKeys.CAFFEINE_CUTOFF_LINE_ENABLED] ?: false,
        stepGoal = this[PreferenceKeys.STEP_GOAL] ?: 8000,
        waterRemindersPerDay = this[PreferenceKeys.WATER_REMINDERS_PER_DAY] ?: 5,
        morningSupplementsStartMinuteOfDay =
            this[PreferenceKeys.MORNING_SUPPLEMENTS_START_MINUTE_OF_DAY] ?: 420,
        morningSupplementsEndMinuteOfDay =
            this[PreferenceKeys.MORNING_SUPPLEMENTS_END_MINUTE_OF_DAY] ?: 600,
        proteinStartMinuteOfDay = this[PreferenceKeys.PROTEIN_START_MINUTE_OF_DAY] ?: 1080,
        proteinEndMinuteOfDay = this[PreferenceKeys.PROTEIN_END_MINUTE_OF_DAY] ?: 1260,
        walkSearchStartMinuteOfDay = this[PreferenceKeys.WALK_SEARCH_START_MINUTE_OF_DAY] ?: 1080,
        walkSearchEndMinuteOfDay = this[PreferenceKeys.WALK_SEARCH_END_MINUTE_OF_DAY] ?: 1290,
        caffeineCutoffMinuteOfDay = this[PreferenceKeys.CAFFEINE_CUTOFF_MINUTE_OF_DAY] ?: 840,
        commuteAudioPackages = decodeStringSet(
            this[PreferenceKeys.COMMUTE_AUDIO_PACKAGES_JSON],
            setOf("com.audible.application"),
        ),
        customPills = decodeCustomPills(this[PreferenceKeys.CUSTOM_PILLS_JSON]),
        customPillActiveWindowMinutes =
            this[PreferenceKeys.CUSTOM_PILL_ACTIVE_WINDOW_MINUTES] ?: 60,
    )
}

/**
 * Everything the widget composition renders from, decoded once per DataStore emission. The
 * widget collects this INSIDE its composition so any write (tap actions, refresher, workers,
 * settings screen) recomposes a live Glance session immediately - data captured before
 * provideContent would stay frozen until the session died.
 */
data class WidgetRenderData(
    val settings: AppSettings,
    val snapshot: CommuteSnapshot?,
    val refreshingSince: Long?,
    val bestDeparture: BestDeparture?,
    val healthDayState: HealthDayState?,
)

class SettingsRepository private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        preferences.toAppSettings()
    }

    val snapshotFlow: Flow<CommuteSnapshot?> = dataStore.data.map { preferences ->
        decodeCommuteSnapshot(preferences[PreferenceKeys.SNAPSHOT_JSON])
    }

    val refreshingSinceFlow: Flow<Long?> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.REFRESHING_SINCE_EPOCH_MILLIS]
    }

    val widgetRenderData: Flow<WidgetRenderData> = dataStore.data.map { preferences ->
        WidgetRenderData(
            settings = preferences.toAppSettings(),
            snapshot = decodeCommuteSnapshot(preferences[PreferenceKeys.SNAPSHOT_JSON]),
            refreshingSince = preferences[PreferenceKeys.REFRESHING_SINCE_EPOCH_MILLIS],
            bestDeparture = decodeBestDeparture(preferences[PreferenceKeys.BEST_DEPARTURE_JSON]),
            healthDayState = decodeHealthDayState(preferences[PreferenceKeys.HEALTH_DAY_STATE_JSON]),
        )
    }

    suspend fun widgetRenderDataSnapshot(): WidgetRenderData = widgetRenderData.first()

    suspend fun settingsSnapshot(): AppSettings = settings.first()

    suspend fun snapshot(): CommuteSnapshot? = snapshotFlow.first()

    suspend fun refreshingSince(): Long? = refreshingSinceFlow.first()

    suspend fun setRefreshing(inProgress: Boolean, nowEpochMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            if (inProgress) {
                preferences[PreferenceKeys.REFRESHING_SINCE_EPOCH_MILLIS] = nowEpochMillis
            } else {
                preferences.remove(PreferenceKeys.REFRESHING_SINCE_EPOCH_MILLIS)
            }
        }
    }

    suspend fun setApiKey(apiKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.API_KEY] = apiKey
        }
    }

    suspend fun setHome(home: Place) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.HOME_JSON] = encodePlace(home)
        }
    }

    suspend fun setWork(work: Place) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WORK_JSON] = encodePlace(work)
        }
    }

    suspend fun setTravelMode(travelMode: TravelMode) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.TRAVEL_MODE] = travelMode.name
        }
    }

    suspend fun setFavourites(favourites: List<Favourite>) {
        require(favourites.size <= 4) { "At most 4 favourites allowed" }
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.FAVOURITES_JSON] = encodeFavourites(favourites)
        }
    }

    suspend fun setLeaveByEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.LEAVE_BY_ENABLED] = enabled
        }
    }

    suspend fun setArriveWorkByMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.ARRIVE_WORK_BY_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setArriveHomeByMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.ARRIVE_HOME_BY_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setEventLeaveByBufferMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENT_LEAVE_BY_BUFFER_MINUTES] = minutes
        }
    }

    suspend fun setEventRealtimeThresholdMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENT_REALTIME_THRESHOLD_MINUTES] = minutes
        }
    }

    suspend fun setEventTakeoverMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENT_TAKEOVER_MINUTES] = minutes
        }
    }

    /** Render-content hash of the map image currently on disk; drives the tap-path map cache. */
    suspend fun mapRenderKey(): String? = dataStore.data.first()[PreferenceKeys.MAP_RENDER_KEY]

    suspend fun setMapRenderKey(key: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MAP_RENDER_KEY] = key
        }
    }

    /** Single-entry geocode cache: [Place.address] holds the raw event-location query text. */
    suspend fun geocodeCache(): Place? =
        decodePlace(dataStore.data.first()[PreferenceKeys.GEOCODE_CACHE_JSON])

    suspend fun setGeocodeCache(place: Place) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.GEOCODE_CACHE_JSON] = encodePlace(place)
        }
    }

    suspend fun setMapPillCorner(corner: MapPillCorner) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MAP_PILL_CORNER] = corner.name
        }
    }

    suspend fun setWidgetBackgroundOpacityPercent(percent: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WIDGET_BACKGROUND_OPACITY_PERCENT] = percent
        }
    }

    suspend fun setWidgetTextScalePercent(percent: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WIDGET_TEXT_SCALE_PERCENT] = percent
        }
    }

    suspend fun setBestDepartureEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.BEST_DEPARTURE_ENABLED] = enabled
        }
    }

    suspend fun bestDeparture(): BestDeparture? =
        decodeBestDeparture(dataStore.data.first()[PreferenceKeys.BEST_DEPARTURE_JSON])

    suspend fun setBestDeparture(value: BestDeparture) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.BEST_DEPARTURE_JSON] = encodeBestDeparture(value)
        }
    }

    suspend fun setCalendarEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CALENDAR_ENABLED] = enabled
        }
    }

    suspend fun setSelectedCalendarIds(ids: Set<Long>) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SELECTED_CALENDAR_IDS_JSON] = encodeLongSet(ids)
        }
    }

    suspend fun setCommuteDays(days: Set<Int>) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.HISTORY_DAYS_JSON] = encodeIntSet(days)
        }
    }

    suspend fun setCalendarTickEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CALENDAR_TICK_ENABLED] = enabled
        }
    }

    suspend fun setMorningSupplementsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MORNING_SUPPLEMENTS_ENABLED] = enabled
        }
    }

    suspend fun setEveningProteinEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENING_PROTEIN_ENABLED] = enabled
        }
    }

    suspend fun setWaterRemindersEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WATER_REMINDERS_ENABLED] = enabled
        }
    }

    suspend fun setEveningWalkEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENING_WALK_ENABLED] = enabled
        }
    }

    suspend fun setSleepBriefEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SLEEP_BRIEF_ENABLED] = enabled
        }
    }

    suspend fun setAudiobookSuppressionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.AUDIOBOOK_SUPPRESSION_ENABLED] = enabled
        }
    }

    suspend fun setSleepDebtSoftenEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SLEEP_DEBT_SOFTEN_ENABLED] = enabled
        }
    }

    suspend fun setGymProteinPriorityEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.GYM_PROTEIN_PRIORITY_ENABLED] = enabled
        }
    }

    suspend fun setRestlessNightShieldEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.RESTLESS_NIGHT_SHIELD_ENABLED] = enabled
        }
    }

    suspend fun setWalkPostAudibleLatchEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WALK_POST_AUDIBLE_LATCH_ENABLED] = enabled
        }
    }

    suspend fun setWalkDaylightPreferenceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WALK_DAYLIGHT_PREFERENCE_ENABLED] = enabled
        }
    }

    suspend fun setFocusGapChipEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.FOCUS_GAP_CHIP_ENABLED] = enabled
        }
    }

    suspend fun setPostGymWaterPulseEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.POST_GYM_WATER_PULSE_ENABLED] = enabled
        }
    }

    suspend fun setMorningLightLineEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MORNING_LIGHT_LINE_ENABLED] = enabled
        }
    }

    suspend fun setCaffeineCutoffLineEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CAFFEINE_CUTOFF_LINE_ENABLED] = enabled
        }
    }

    suspend fun setStepGoal(stepGoal: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.STEP_GOAL] = stepGoal
        }
    }

    suspend fun setWaterRemindersPerDay(remindersPerDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WATER_REMINDERS_PER_DAY] = remindersPerDay
        }
    }

    suspend fun setMorningSupplementsStartMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MORNING_SUPPLEMENTS_START_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setMorningSupplementsEndMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MORNING_SUPPLEMENTS_END_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setProteinStartMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.PROTEIN_START_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setProteinEndMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.PROTEIN_END_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setWalkSearchStartMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WALK_SEARCH_START_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setWalkSearchEndMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WALK_SEARCH_END_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setCaffeineCutoffMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CAFFEINE_CUTOFF_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setCommuteAudioPackages(packages: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.COMMUTE_AUDIO_PACKAGES_JSON] = encodeStringSet(packages)
        }
    }

    suspend fun setCustomPills(pills: List<CustomPill>) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CUSTOM_PILLS_JSON] = encodeCustomPills(pills)
        }
    }

    suspend fun setCustomPillActiveWindowMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            // Clamp at the write boundary so a caller bug can never persist a zero-minute or
            // multi-day active window; the Reminders dialog offers exactly this range.
            preferences[PreferenceKeys.CUSTOM_PILL_ACTIVE_WINDOW_MINUTES] =
                minutes.coerceIn(CustomPill.ACTIVE_WINDOW_MIN_MINUTES, CustomPill.ACTIVE_WINDOW_MAX_MINUTES)
        }
    }

    suspend fun setMorningSlotStartMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MORNING_SLOT_START_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setMorningSlotEndMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MORNING_SLOT_END_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setEveningSlotStartMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENING_SLOT_START_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setEveningSlotEndMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENING_SLOT_END_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun leaveByNotifiedOn(direction: Direction): String? {
        val key = when (direction) {
            Direction.TO_WORK -> PreferenceKeys.LEAVE_BY_NOTIFIED_TO_WORK
            Direction.TO_HOME -> PreferenceKeys.LEAVE_BY_NOTIFIED_TO_HOME
        }
        return dataStore.data.first()[key]
    }

    suspend fun markLeaveByNotified(direction: Direction, localDate: String) {
        val key = when (direction) {
            Direction.TO_WORK -> PreferenceKeys.LEAVE_BY_NOTIFIED_TO_WORK
            Direction.TO_HOME -> PreferenceKeys.LEAVE_BY_NOTIFIED_TO_HOME
        }
        dataStore.edit { preferences ->
            preferences[key] = localDate
        }
    }

    suspend fun eventLeaveByNotifiedKey(): String? {
        return dataStore.data.first()[PreferenceKeys.EVENT_LEAVE_BY_NOTIFIED_KEY]
    }

    suspend fun markEventLeaveByNotified(eventKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENT_LEAVE_BY_NOTIFIED_KEY] = eventKey
        }
    }

    suspend fun saveSnapshot(snapshot: CommuteSnapshot) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SNAPSHOT_JSON] = encodeCommuteSnapshot(snapshot)
        }
    }

    suspend fun healthDayState(): HealthDayState? =
        decodeHealthDayState(dataStore.data.first()[PreferenceKeys.HEALTH_DAY_STATE_JSON])

    /**
     * Atomically reads, transforms, and persists the current day's state.
     * Returning null removes the stored state.
     */
    suspend fun updateHealthDayState(
        transform: (HealthDayState?) -> HealthDayState?,
    ) {
        dataStore.edit { preferences ->
            val updated = transform(decodeHealthDayState(preferences[PreferenceKeys.HEALTH_DAY_STATE_JSON]))
            if (updated == null) {
                preferences.remove(PreferenceKeys.HEALTH_DAY_STATE_JSON)
            } else {
                preferences[PreferenceKeys.HEALTH_DAY_STATE_JSON] = encodeHealthDayState(updated)
            }
        }
    }

    suspend fun healthHistory(): HealthHistory? =
        decodeHealthHistory(dataStore.data.first()[PreferenceKeys.HEALTH_HISTORY_JSON])

    /**
     * Atomically reads, transforms, and persists the health history.
     * Returning null removes the stored history.
     */
    suspend fun updateHealthHistory(
        transform: (HealthHistory?) -> HealthHistory?,
    ) {
        dataStore.edit { preferences ->
            val updated = transform(decodeHealthHistory(preferences[PreferenceKeys.HEALTH_HISTORY_JSON]))
            if (updated == null) {
                preferences.remove(PreferenceKeys.HEALTH_HISTORY_JSON)
            } else {
                preferences[PreferenceKeys.HEALTH_HISTORY_JSON] = encodeHealthHistory(updated)
            }
        }
    }

    /** Manual sleep-tap anchors (state, not settings): last "To Bed" / "Woke Up" tap timestamps. */
    suspend fun lastToBedTapEpochMillis(): Long? =
        dataStore.data.first()[PreferenceKeys.LAST_TO_BED_TAP_EPOCH_MILLIS]

    suspend fun setLastToBedTapEpochMillis(epochMillis: Long) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.LAST_TO_BED_TAP_EPOCH_MILLIS] = epochMillis
        }
    }

    suspend fun lastWokeUpTapEpochMillis(): Long? =
        dataStore.data.first()[PreferenceKeys.LAST_WOKE_UP_TAP_EPOCH_MILLIS]

    suspend fun setLastWokeUpTapEpochMillis(epochMillis: Long) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.LAST_WOKE_UP_TAP_EPOCH_MILLIS] = epochMillis
        }
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(appContext.commuteSettingsDataStore).also { instance = it }
            }
        }
    }
}
