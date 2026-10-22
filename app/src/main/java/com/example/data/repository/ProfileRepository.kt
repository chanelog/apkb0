package com.example.data.repository

import com.example.data.local.ProfileDao
import com.example.data.local.ProfileEntity
import com.example.data.preferences.SettingsManager
import com.example.domain.model.Profile
import com.example.domain.model.ProtocolCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class ProfileSortOrder {
    LAST_USED,
    NAME_ASC,
    NAME_DESC,
    DATE_ADDED
}

class ProfileRepository(
    private val profileDao: ProfileDao,
    private val settingsManager: SettingsManager
) {

    val allProfilesFlow: Flow<List<Profile>> = profileDao.getAllProfiles().map { list ->
        list.map { it.toDomain() }
    }

    val profileCountFlow: Flow<Int> = profileDao.getProfileCount()

    val activeProfileFlow: Flow<Profile?> = combine(
        allProfilesFlow,
        settingsManager.settingsFlow
    ) { profiles, settings ->
        val activeId = settings.activeProfileId
        if (activeId != null) {
            profiles.find { it.id == activeId } ?: profiles.firstOrNull()
        } else {
            profiles.firstOrNull()
        }
    }

    fun getFilteredProfiles(
        category: ProtocolCategory,
        searchQuery: String,
        sortOrder: ProfileSortOrder
    ): Flow<List<Profile>> {
        return allProfilesFlow.map { list ->
            var filtered = when (category) {
                ProtocolCategory.ALL -> list
                ProtocolCategory.SSHWS -> list.filter { it.protocol.isSsh }
                ProtocolCategory.XRAY -> list.filter { it.protocol.isXray }
                ProtocolCategory.FAVORITES -> list.filter { it.isFavorite }
            }

            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                filtered = filtered.filter {
                    it.name.lowercase().contains(q) ||
                    it.displayEndpoint.lowercase().contains(q) ||
                    it.protocol.displayName.lowercase().contains(q)
                }
            }

            when (sortOrder) {
                ProfileSortOrder.LAST_USED -> filtered.sortedByDescending { it.lastUsedTimestamp }
                ProfileSortOrder.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
                ProfileSortOrder.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
                ProfileSortOrder.DATE_ADDED -> filtered.sortedByDescending { it.createdAt }
            }
        }
    }

    suspend fun getProfileById(id: String): Profile? {
        return profileDao.getProfileById(id)?.toDomain()
    }

    suspend fun saveProfile(profile: Profile) {
        profileDao.insertProfile(ProfileEntity.fromDomain(profile))
    }

    suspend fun saveProfiles(profiles: List<Profile>) {
        profileDao.insertProfiles(profiles.map { ProfileEntity.fromDomain(it) })
    }

    suspend fun updateProfile(profile: Profile) {
        profileDao.updateProfile(ProfileEntity.fromDomain(profile))
    }

    suspend fun duplicateProfile(profile: Profile): Profile {
        val dup = profile.copy(
            id = UUID.randomUUID().toString(),
            name = "${profile.name} (Copy)",
            createdAt = System.currentTimeMillis(),
            lastUsedTimestamp = 0L
        )
        saveProfile(dup)
        return dup
    }

    suspend fun deleteProfile(id: String) {
        profileDao.deleteProfileById(id)
    }

    suspend fun deleteAllProfiles() {
        profileDao.deleteAllProfiles()
        settingsManager.setActiveProfileId(null)
    }

    suspend fun toggleFavorite(id: String, current: Boolean) {
        profileDao.setFavorite(id, !current)
    }

    suspend fun markLastUsed(id: String) {
        val now = System.currentTimeMillis()
        profileDao.updateLastUsed(id, now)
        settingsManager.setLastConnectedProfileId(id)
    }

    suspend fun setActiveProfile(id: String) {
        settingsManager.setActiveProfileId(id)
    }
}
