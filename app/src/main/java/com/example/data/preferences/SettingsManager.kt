package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ogtunnel_settings")

enum class ThemeMode {
    SYSTEM, DARK, LIGHT
}

enum class SplitTunnelMode {
    ALL_APPS,
    INCLUDE_ONLY,
    EXCLUDE_ONLY
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 3,
    val connectionTimeoutSeconds: Int = 15,
    val mtu: Int = 1500,
    val primaryDns: String = "1.1.1.1",
    val secondaryDns: String = "8.8.8.8",
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val splitTunnelPackages: Set<String> = emptySet(),
    val activeProfileId: String? = null,
    val lastConnectedProfileId: String? = null
)

class SettingsManager(private val context: Context) {

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val MAX_RECONNECT_ATTEMPTS = intPreferencesKey("max_reconnect_attempts")
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        val MTU = intPreferencesKey("mtu")
        val PRIMARY_DNS = stringPreferencesKey("primary_dns")
        val SECONDARY_DNS = stringPreferencesKey("secondary_dns")
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_TUNNEL_PACKAGES = stringSetPreferencesKey("split_tunnel_packages")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val LAST_CONNECTED_PROFILE_ID = stringPreferencesKey("last_connected_profile_id")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { pref ->
        AppSettings(
            themeMode = try {
                ThemeMode.valueOf(pref[PreferencesKeys.THEME_MODE] ?: ThemeMode.DARK.name)
            } catch (e: Exception) {
                ThemeMode.DARK
            },
            autoReconnect = pref[PreferencesKeys.AUTO_RECONNECT] ?: true,
            maxReconnectAttempts = pref[PreferencesKeys.MAX_RECONNECT_ATTEMPTS] ?: 3,
            connectionTimeoutSeconds = pref[PreferencesKeys.CONNECTION_TIMEOUT] ?: 15,
            mtu = pref[PreferencesKeys.MTU] ?: 1500,
            primaryDns = pref[PreferencesKeys.PRIMARY_DNS] ?: "1.1.1.1",
            secondaryDns = pref[PreferencesKeys.SECONDARY_DNS] ?: "8.8.8.8",
            splitTunnelMode = try {
                SplitTunnelMode.valueOf(pref[PreferencesKeys.SPLIT_TUNNEL_MODE] ?: SplitTunnelMode.ALL_APPS.name)
            } catch (e: Exception) {
                SplitTunnelMode.ALL_APPS
            },
            splitTunnelPackages = pref[PreferencesKeys.SPLIT_TUNNEL_PACKAGES] ?: emptySet(),
            activeProfileId = pref[PreferencesKeys.ACTIVE_PROFILE_ID],
            lastConnectedProfileId = pref[PreferencesKeys.LAST_CONNECTED_PROFILE_ID]
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setMaxReconnectAttempts(attempts: Int) {
        context.dataStore.edit { it[PreferencesKeys.MAX_RECONNECT_ATTEMPTS] = attempts.coerceIn(1, 10) }
    }

    suspend fun setConnectionTimeout(seconds: Int) {
        context.dataStore.edit { it[PreferencesKeys.CONNECTION_TIMEOUT] = seconds.coerceIn(5, 60) }
    }

    suspend fun setMtu(mtu: Int) {
        context.dataStore.edit { it[PreferencesKeys.MTU] = mtu.coerceIn(1280, 9000) }
    }

    suspend fun setDns(primary: String, secondary: String) {
        context.dataStore.edit {
            it[PreferencesKeys.PRIMARY_DNS] = primary.trim()
            it[PreferencesKeys.SECONDARY_DNS] = secondary.trim()
        }
    }

    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) {
        context.dataStore.edit { it[PreferencesKeys.SPLIT_TUNNEL_MODE] = mode.name }
    }

    suspend fun setSplitTunnelPackages(packages: Set<String>) {
        context.dataStore.edit { it[PreferencesKeys.SPLIT_TUNNEL_PACKAGES] = packages }
    }

    suspend fun setActiveProfileId(id: String?) {
        context.dataStore.edit {
            if (id != null) {
                it[PreferencesKeys.ACTIVE_PROFILE_ID] = id
            } else {
                it.remove(PreferencesKeys.ACTIVE_PROFILE_ID)
            }
        }
    }

    suspend fun setLastConnectedProfileId(id: String?) {
        context.dataStore.edit {
            if (id != null) {
                it[PreferencesKeys.LAST_CONNECTED_PROFILE_ID] = id
            } else {
                it.remove(PreferencesKeys.LAST_CONNECTED_PROFILE_ID)
            }
        }
    }
}
