package com.crpakala.commutewidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val SHOW_FAVOURITE_CHIPS = booleanPreferencesKey("show_favourite_chips")
    val FAVOURITE_WINDOW_MINUTES = intPreferencesKey("favourite_window_minutes")
    val LEAVE_BY_ENABLED = booleanPreferencesKey("leave_by_enabled")
    val ARRIVE_WORK_BY_MINUTE_OF_DAY = intPreferencesKey("arrive_work_by_minute_of_day")
    val ARRIVE_HOME_BY_MINUTE_OF_DAY = intPreferencesKey("arrive_home_by_minute_of_day")
    val CALENDAR_ENABLED = booleanPreferencesKey("calendar_enabled")
    val SELECTED_CALENDAR_IDS_JSON = stringPreferencesKey("selected_calendar_ids_json")
    val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
    val HISTORY_DAYS_JSON = stringPreferencesKey("history_days_json")
    val MORNING_SLOT_START_MINUTE_OF_DAY = intPreferencesKey("morning_slot_start_minute_of_day")
    val MORNING_SLOT_END_MINUTE_OF_DAY = intPreferencesKey("morning_slot_end_minute_of_day")
    val EVENING_SLOT_START_MINUTE_OF_DAY = intPreferencesKey("evening_slot_start_minute_of_day")
    val EVENING_SLOT_END_MINUTE_OF_DAY = intPreferencesKey("evening_slot_end_minute_of_day")
    val ACTIVE_FAVOURITE_JSON = stringPreferencesKey("active_favourite_json")
    val LEAVE_BY_NOTIFIED_TO_WORK = stringPreferencesKey("leave_by_notified_to_work")
    val LEAVE_BY_NOTIFIED_TO_HOME = stringPreferencesKey("leave_by_notified_to_home")
}

private fun Preferences.toAppSettings(): AppSettings {
    return AppSettings(
        apiKey = this[PreferenceKeys.API_KEY] ?: "",
        home = decodePlace(this[PreferenceKeys.HOME_JSON]),
        work = decodePlace(this[PreferenceKeys.WORK_JSON]),
        travelMode = parseTravelMode(this[PreferenceKeys.TRAVEL_MODE]),
        favourites = decodeFavourites(this[PreferenceKeys.FAVOURITES_JSON]),
        showFavouriteChips = this[PreferenceKeys.SHOW_FAVOURITE_CHIPS] ?: true,
        favouriteWindowMinutes = this[PreferenceKeys.FAVOURITE_WINDOW_MINUTES] ?: 60,
        leaveByEnabled = this[PreferenceKeys.LEAVE_BY_ENABLED] ?: false,
        arriveWorkByMinuteOfDay = this[PreferenceKeys.ARRIVE_WORK_BY_MINUTE_OF_DAY] ?: 570,
        arriveHomeByMinuteOfDay = this[PreferenceKeys.ARRIVE_HOME_BY_MINUTE_OF_DAY] ?: 1170,
        calendarEnabled = this[PreferenceKeys.CALENDAR_ENABLED] ?: false,
        selectedCalendarIds = decodeLongSet(this[PreferenceKeys.SELECTED_CALENDAR_IDS_JSON]),
        historyEnabled = this[PreferenceKeys.HISTORY_ENABLED] ?: true,
        historyDays = decodeIntSet(this[PreferenceKeys.HISTORY_DAYS_JSON], setOf(1, 2, 3, 4, 5)),
        morningSlotStartMinuteOfDay = this[PreferenceKeys.MORNING_SLOT_START_MINUTE_OF_DAY] ?: 420,
        morningSlotEndMinuteOfDay = this[PreferenceKeys.MORNING_SLOT_END_MINUTE_OF_DAY] ?: 600,
        eveningSlotStartMinuteOfDay = this[PreferenceKeys.EVENING_SLOT_START_MINUTE_OF_DAY] ?: 1020,
        eveningSlotEndMinuteOfDay = this[PreferenceKeys.EVENING_SLOT_END_MINUTE_OF_DAY] ?: 1200,
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

    val activeFavouriteFlow: Flow<ActiveFavourite?> = dataStore.data.map { preferences ->
        decodeActiveFavourite(preferences[PreferenceKeys.ACTIVE_FAVOURITE_JSON])
    }

    suspend fun settingsSnapshot(): AppSettings = settings.first()

    suspend fun snapshot(): CommuteSnapshot? = snapshotFlow.first()

    suspend fun activeFavourite(nowEpochMillis: Long = System.currentTimeMillis()): ActiveFavourite? {
        val stored = decodeActiveFavourite(
            dataStore.data.first()[PreferenceKeys.ACTIVE_FAVOURITE_JSON],
        ) ?: return null
        if (isActive(stored, nowEpochMillis)) {
            return stored
        }
        clearActiveFavourite()
        return null
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

    suspend fun setShowFavouriteChips(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SHOW_FAVOURITE_CHIPS] = show
        }
    }

    suspend fun setFavouriteWindowMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.FAVOURITE_WINDOW_MINUTES] = minutes
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

    suspend fun setHistoryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.HISTORY_ENABLED] = enabled
        }
    }

    suspend fun setHistoryDays(days: Set<Int>) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.HISTORY_DAYS_JSON] = encodeIntSet(days)
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

    suspend fun setActiveFavourite(
        favourite: Favourite,
        windowMinutes: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val expiresAtEpochMillis = nowEpochMillis + windowMinutes * 60_000L
        val active = ActiveFavourite(
            favourite = favourite,
            activatedAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.ACTIVE_FAVOURITE_JSON] = encodeActiveFavourite(active)
        }
    }

    suspend fun clearActiveFavourite() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.ACTIVE_FAVOURITE_JSON)
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
