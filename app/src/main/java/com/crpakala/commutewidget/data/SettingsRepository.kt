package com.crpakala.commutewidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
    val SWITCH_MINUTE_OF_DAY = intPreferencesKey("switch_minute_of_day")
    val MORNING_REFRESH_MINUTE_OF_DAY = intPreferencesKey("morning_refresh_minute_of_day")
    val EVENING_REFRESH_MINUTE_OF_DAY = intPreferencesKey("evening_refresh_minute_of_day")
    val SNAPSHOT_JSON = stringPreferencesKey("snapshot_json")
}

private fun Preferences.toAppSettings(): AppSettings {
    return AppSettings(
        apiKey = this[PreferenceKeys.API_KEY] ?: "",
        home = decodePlace(this[PreferenceKeys.HOME_JSON]),
        work = decodePlace(this[PreferenceKeys.WORK_JSON]),
        travelMode = parseTravelMode(this[PreferenceKeys.TRAVEL_MODE]),
        switchMinuteOfDay = this[PreferenceKeys.SWITCH_MINUTE_OF_DAY] ?: 840,
        morningRefreshMinuteOfDay = this[PreferenceKeys.MORNING_REFRESH_MINUTE_OF_DAY] ?: 480,
        eveningRefreshMinuteOfDay = this[PreferenceKeys.EVENING_REFRESH_MINUTE_OF_DAY] ?: 1020,
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

    suspend fun settingsSnapshot(): AppSettings = settings.first()

    suspend fun snapshot(): CommuteSnapshot? = snapshotFlow.first()

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

    suspend fun setSwitchMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SWITCH_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setMorningRefreshMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MORNING_REFRESH_MINUTE_OF_DAY] = minuteOfDay
        }
    }

    suspend fun setEveningRefreshMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.EVENING_REFRESH_MINUTE_OF_DAY] = minuteOfDay
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
