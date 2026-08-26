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
    val BEST_DEPARTURE_ENABLED = booleanPreferencesKey("best_departure_enabled")
    val BEST_DEPARTURE_JSON = stringPreferencesKey("best_departure_json")
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
        bestDepartureEnabled = this[PreferenceKeys.BEST_DEPARTURE_ENABLED] ?: true,
        calendarEnabled = this[PreferenceKeys.CALENDAR_ENABLED] ?: false,
        selectedCalendarIds = decodeLongSet(this[PreferenceKeys.SELECTED_CALENDAR_IDS_JSON]),
        commuteDays = decodeIntSet(this[PreferenceKeys.HISTORY_DAYS_JSON], setOf(1, 2, 3, 4, 5)),
        morningSlotStartMinuteOfDay = this[PreferenceKeys.MORNING_SLOT_START_MINUTE_OF_DAY] ?: 420,
        morningSlotEndMinuteOfDay = this[PreferenceKeys.MORNING_SLOT_END_MINUTE_OF_DAY] ?: 600,
        eveningSlotStartMinuteOfDay = this[PreferenceKeys.EVENING_SLOT_START_MINUTE_OF_DAY] ?: 1020,
        eveningSlotEndMinuteOfDay = this[PreferenceKeys.EVENING_SLOT_END_MINUTE_OF_DAY] ?: 1200,
        calendarTickEnabled = this[PreferenceKeys.CALENDAR_TICK_ENABLED] ?: true,
    )
}

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
