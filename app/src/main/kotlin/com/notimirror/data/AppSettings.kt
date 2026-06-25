package com.notimirror.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(private val context: Context) {

    private object Keys {
        val SHOW_BODY = booleanPreferencesKey("show_body")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val FILTERED_APPS = stringPreferencesKey("filtered_apps")
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val showBody: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_BODY] ?: true }
    val keepScreenAwake: Flow<Boolean> = context.dataStore.data.map { it[Keys.KEEP_SCREEN_AWAKE] ?: false }
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RECONNECT] ?: true }
    val filteredApps: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.FILTERED_APPS]?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()
    }
    val lastDeviceAddress: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_DEVICE_ADDRESS] }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setShowBody(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_BODY] = v }
    suspend fun setKeepScreenAwake(v: Boolean) = context.dataStore.edit { it[Keys.KEEP_SCREEN_AWAKE] = v }
    suspend fun setAutoReconnect(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_RECONNECT] = v }
    suspend fun setLastDeviceAddress(addr: String) = context.dataStore.edit { it[Keys.LAST_DEVICE_ADDRESS] = addr }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = v }
    suspend fun toggleFilteredApp(bundleId: String) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.FILTERED_APPS]?.split(",")?.filter(String::isNotBlank)?.toMutableSet() ?: mutableSetOf()
        if (current.contains(bundleId)) current.remove(bundleId) else current.add(bundleId)
        prefs[Keys.FILTERED_APPS] = current.joinToString(",")
    }
}
