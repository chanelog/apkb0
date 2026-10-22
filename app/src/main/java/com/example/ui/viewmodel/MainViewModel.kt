package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.OGTunnelApplication
import com.example.data.config.ConfigExporter
import com.example.data.config.ConfigParser
import com.example.data.config.ParseResult
import com.example.data.preferences.AppSettings
import com.example.data.preferences.SplitTunnelMode
import com.example.data.preferences.ThemeMode
import com.example.data.repository.ProfileRepository
import com.example.data.repository.ProfileSortOrder
import com.example.domain.model.ConnectionState
import com.example.domain.model.ConnectionStats
import com.example.domain.model.Profile
import com.example.domain.model.ProtocolCategory
import com.example.domain.model.TunnelProtocol
import com.example.utils.LogEntry
import com.example.utils.LogLevel
import com.example.utils.OGLogger
import com.example.vpn.VpnController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val app = OGTunnelApplication.instance
    private val repository = ProfileRepository(app.database.profileDao(), app.settingsManager)
    private val settingsManager = app.settingsManager

    val connectionState: StateFlow<ConnectionState> = VpnController.connectionState
    val connectionStats: StateFlow<ConnectionStats> = VpnController.connectionStats
    val activeProfile: StateFlow<Profile?> = repository.activeProfileFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val settings: StateFlow<AppSettings> = settingsManager.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings()
    )

    // Profiles UI state
    val selectedCategory = MutableStateFlow(ProtocolCategory.ALL)
    val searchQuery = MutableStateFlow("")
    val sortOrder = MutableStateFlow(ProfileSortOrder.LAST_USED)

    val filteredProfiles: StateFlow<List<Profile>> = combine(
        selectedCategory,
        searchQuery,
        sortOrder,
        repository.allProfilesFlow
    ) { category, query, sort, all ->
        var list = when (category) {
            ProtocolCategory.ALL -> all
            ProtocolCategory.SSHWS -> all.filter { it.protocol.isSsh }
            ProtocolCategory.XRAY -> all.filter { it.protocol.isXray }
            ProtocolCategory.FAVORITES -> all.filter { it.isFavorite }
        }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter {
                it.name.lowercase().contains(q) ||
                it.displayEndpoint.lowercase().contains(q) ||
                it.protocol.displayName.lowercase().contains(q)
            }
        }
        when (sort) {
            ProfileSortOrder.LAST_USED -> list.sortedByDescending { it.lastUsedTimestamp }
            ProfileSortOrder.NAME_ASC -> list.sortedBy { it.name.lowercase() }
            ProfileSortOrder.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            ProfileSortOrder.DATE_ADDED -> list.sortedByDescending { it.createdAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profileCount: StateFlow<Int> = repository.profileCountFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    // Logs UI state
    val logs: StateFlow<List<LogEntry>> = OGLogger.logsFlow
    val logFilterLevel = MutableStateFlow<LogLevel?>(null)
    val logSearchQuery = MutableStateFlow("")

    val filteredLogs: StateFlow<List<LogEntry>> = combine(
        logs,
        logFilterLevel,
        logSearchQuery
    ) { allLogs, level, query ->
        var list = allLogs
        if (level != null) {
            list = list.filter { it.level == level }
        }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            list = list.filter { it.message.lowercase().contains(q) || it.tag.lowercase().contains(q) }
        }
        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Tools state
    val dnsLookupResult = MutableStateFlow<List<String>>(emptyList())
    val dnsLookupLoading = MutableStateFlow(false)
    val tcpPingResult = MutableStateFlow<String?>(null)
    val tcpPingLoading = MutableStateFlow(false)
    val sshAuthResult = MutableStateFlow<String?>(null)
    val sshAuthLoading = MutableStateFlow(false)

    fun toggleConnect(context: Context) {
        val state = connectionState.value
        val profile = activeProfile.value

        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
            VpnController.stopVpn(context)
        } else {
            if (profile != null) {
                VpnController.startVpn(context, profile)
            } else {
                OGLogger.w("MainViewModel", "Cannot connect: No profile selected")
            }
        }
    }

    fun setActiveProfile(profile: Profile) {
        viewModelScope.launch {
            repository.setActiveProfile(profile.id)
            repository.markLastUsed(profile.id)
        }
    }

    fun saveProfile(profile: Profile) {
        viewModelScope.launch {
            repository.saveProfile(profile)
            if (activeProfile.value == null) {
                repository.setActiveProfile(profile.id)
            }
        }
    }

    fun duplicateProfile(profile: Profile) {
        viewModelScope.launch {
            repository.duplicateProfile(profile)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
        }
    }

    fun toggleFavorite(profile: Profile) {
        viewModelScope.launch {
            repository.toggleFavorite(profile.id, profile.isFavorite)
        }
    }

    fun importConfig(text: String): ParseResult {
        val result = ConfigParser.parse(text)
        if (result is ParseResult.Success) {
            viewModelScope.launch {
                repository.saveProfiles(result.profiles)
                if (result.profiles.isNotEmpty()) {
                    repository.setActiveProfile(result.profiles.first().id)
                }
            }
        }
        return result
    }

    fun exportProfileToUri(profile: Profile): String {
        return ConfigExporter.exportToUri(profile)
    }

    fun exportAllProfilesJson(redactCredentials: Boolean): String {
        val all = filteredProfiles.value
        return ConfigExporter.exportToJson(all, redactCredentials)
    }

    fun clearAllProfiles() {
        viewModelScope.launch {
            repository.deleteAllProfiles()
        }
    }

    fun clearLogs() {
        OGLogger.clear()
    }

    // Settings actions
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsManager.setThemeMode(mode) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setAutoReconnect(enabled) }
    }

    fun setMaxReconnectAttempts(attempts: Int) {
        viewModelScope.launch { settingsManager.setMaxReconnectAttempts(attempts) }
    }

    fun setConnectionTimeout(seconds: Int) {
        viewModelScope.launch { settingsManager.setConnectionTimeout(seconds) }
    }

    fun setMtu(mtu: Int) {
        viewModelScope.launch { settingsManager.setMtu(mtu) }
    }

    fun setDns(primary: String, secondary: String) {
        viewModelScope.launch { settingsManager.setDns(primary, secondary) }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch { settingsManager.setSplitTunnelMode(mode) }
    }

    fun setSplitTunnelPackages(packages: Set<String>) {
        viewModelScope.launch { settingsManager.setSplitTunnelPackages(packages) }
    }

    // Tool actions
    fun performDnsLookup(domain: String, dnsServer: String) {
        viewModelScope.launch {
            dnsLookupLoading.value = true
            dnsLookupResult.value = emptyList()
            val ips = VpnController.testDnsLookup(domain, dnsServer)
            dnsLookupResult.value = ips
            dnsLookupLoading.value = false
        }
    }

    fun performTcpPing(host: String, port: Int) {
        viewModelScope.launch {
            tcpPingLoading.value = true
            tcpPingResult.value = null
            val rtt = VpnController.measureTcpPing(host, port, 4000)
            tcpPingResult.value = if (rtt != null) "Reachable: ${rtt}ms RTT" else "Unreachable / Connection Timed Out"
            tcpPingLoading.value = false
        }
    }

    fun performSshAuthTest(profile: Profile) {
        viewModelScope.launch {
            sshAuthLoading.value = true
            sshAuthResult.value = null
            val (success, message) = VpnController.testSshAuth(profile)
            sshAuthResult.value = if (success) "SUCCESS: $message" else "FAILED: $message"
            sshAuthLoading.value = false
        }
    }
}
