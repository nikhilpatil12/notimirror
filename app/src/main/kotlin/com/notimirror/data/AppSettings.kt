package com.notimirror.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.net.URLDecoder
import java.net.URLEncoder
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
        val SHOW_ANDROID_NOTIFICATIONS = booleanPreferencesKey("show_android_notifications")
        val VERBOSE_DEBUG_LOGGING = booleanPreferencesKey("verbose_debug_logging")
        val APP_DISPLAY_NAMES = stringPreferencesKey("app_display_names")
    }

    val showBody: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_BODY] ?: true }
    val keepScreenAwake: Flow<Boolean> = context.dataStore.data.map { it[Keys.KEEP_SCREEN_AWAKE] ?: false }
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RECONNECT] ?: true }
    val filteredApps: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.FILTERED_APPS]?.split(",")?.filter(String::isNotBlank)?.toSet() ?: emptySet()
    }
    val lastDeviceAddress: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_DEVICE_ADDRESS] }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val showAndroidNotifications: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_ANDROID_NOTIFICATIONS] ?: true }
    val verboseDebugLogging: Flow<Boolean> = context.dataStore.data.map { it[Keys.VERBOSE_DEBUG_LOGGING] ?: false }
    val appDisplayNames: Flow<Map<String, String>> = context.dataStore.data.map {
        decodeStringMap(it[Keys.APP_DISPLAY_NAMES].orEmpty())
    }

    suspend fun setShowBody(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_BODY] = v }
    suspend fun setKeepScreenAwake(v: Boolean) = context.dataStore.edit { it[Keys.KEEP_SCREEN_AWAKE] = v }
    suspend fun setAutoReconnect(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_RECONNECT] = v }
    suspend fun setLastDeviceAddress(addr: String) = context.dataStore.edit { it[Keys.LAST_DEVICE_ADDRESS] = addr }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = v }
    suspend fun setShowAndroidNotifications(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_ANDROID_NOTIFICATIONS] = v }
    suspend fun setVerboseDebugLogging(v: Boolean) = context.dataStore.edit { it[Keys.VERBOSE_DEBUG_LOGGING] = v }
    suspend fun setAppDisplayName(bundleId: String, displayName: String) = context.dataStore.edit { prefs ->
        val current = decodeStringMap(prefs[Keys.APP_DISPLAY_NAMES].orEmpty()).toMutableMap()
        current[bundleId] = displayName
        prefs[Keys.APP_DISPLAY_NAMES] = encodeStringMap(current)
    }
    suspend fun toggleFilteredApp(bundleId: String) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.FILTERED_APPS]?.split(",")?.filter(String::isNotBlank)?.toMutableSet() ?: mutableSetOf()
        if (current.contains(bundleId)) current.remove(bundleId) else current.add(bundleId)
        prefs[Keys.FILTERED_APPS] = current.joinToString(",")
    }

    private fun encodeStringMap(values: Map<String, String>): String =
        values.entries.joinToString("\n") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

    private fun decodeStringMap(encoded: String): Map<String, String> =
        encoded.lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).urlDecode() to line.substring(separator + 1).urlDecode()
                }
            }
            .toMap()

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
    private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())
}
