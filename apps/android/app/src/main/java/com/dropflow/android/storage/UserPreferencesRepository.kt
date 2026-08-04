package com.dropflow.android.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

data class UserPreferences(
    val deviceUuid: String,
    val deviceName: String,
    val requireConfirmation: Boolean,
    val autoAcceptTrustedDevices: Boolean,
    val askBeforeOverwrite: Boolean,
    val autoOpenCompleted: Boolean,
    val accentColor: String
)

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val DEVICE_UUID = stringPreferencesKey("device_uuid")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val REQUIRE_CONFIRMATION = booleanPreferencesKey("require_confirmation")
        val AUTO_ACCEPT_TRUSTED = booleanPreferencesKey("auto_accept_trusted")
        val ASK_BEFORE_OVERWRITE = booleanPreferencesKey("ask_before_overwrite")
        val AUTO_OPEN_COMPLETED = booleanPreferencesKey("auto_open_completed")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        val uuid = preferences[PreferencesKeys.DEVICE_UUID] ?: run {
            val newUuid = UUID.randomUUID().toString()
            newUuid
        }

        UserPreferences(
            deviceUuid = uuid,
            deviceName = preferences[PreferencesKeys.DEVICE_NAME] ?: android.os.Build.MODEL,
            requireConfirmation = preferences[PreferencesKeys.REQUIRE_CONFIRMATION] ?: true,
            autoAcceptTrustedDevices = preferences[PreferencesKeys.AUTO_ACCEPT_TRUSTED] ?: true,
            askBeforeOverwrite = preferences[PreferencesKeys.ASK_BEFORE_OVERWRITE] ?: true,
            autoOpenCompleted = preferences[PreferencesKeys.AUTO_OPEN_COMPLETED] ?: false,
            accentColor = preferences[PreferencesKeys.ACCENT_COLOR] ?: "blue"
        )
    }

    suspend fun getOrCreateDeviceUuid(): String {
        var existingUuid: String? = null
        context.dataStore.edit { preferences ->
            existingUuid = preferences[PreferencesKeys.DEVICE_UUID]
            if (existingUuid == null) {
                val newUuid = UUID.randomUUID().toString()
                preferences[PreferencesKeys.DEVICE_UUID] = newUuid
                existingUuid = newUuid
            }
        }
        return existingUuid ?: UUID.randomUUID().toString()
    }

    suspend fun updateDeviceName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEVICE_NAME] = name
        }
    }

    suspend fun updateRequireConfirmation(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.REQUIRE_CONFIRMATION] = value
        }
    }

    suspend fun updateAutoAcceptTrusted(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_ACCEPT_TRUSTED] = value
        }
    }

    suspend fun updateAskBeforeOverwrite(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ASK_BEFORE_OVERWRITE] = value
        }
    }

    suspend fun updateAutoOpenCompleted(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_OPEN_COMPLETED] = value
        }
    }

    suspend fun updateAccentColor(color: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ACCENT_COLOR] = color
        }
    }
}
