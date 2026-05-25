package com.example.safe_kids.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.deviceDataStore by preferencesDataStore(name = "device_state")

data class DeviceState(
    val isBound: Boolean = false,
    val isProfileComplete: Boolean = false,
    val permissionsGranted: Boolean = false,
    val isProtectionEnabled: Boolean = false
)

class DeviceStateManager(private val context: Context) {

    companion object {
        val IS_BOUND = booleanPreferencesKey("is_bound")
        val IS_PROFILE_COMPLETE = booleanPreferencesKey("is_profile_complete")
        val PERMISSIONS_GRANTED = booleanPreferencesKey("permissions_granted")
        val IS_PROTECTION_ENABLED = booleanPreferencesKey("is_protection_enabled")
    }

    val deviceState: Flow<DeviceState> = context.deviceDataStore.data.map { preferences ->
        DeviceState(
            isBound = preferences[IS_BOUND] ?: false,
            isProfileComplete = preferences[IS_PROFILE_COMPLETE] ?: false,
            permissionsGranted = preferences[PERMISSIONS_GRANTED] ?: false,
            isProtectionEnabled = preferences[IS_PROTECTION_ENABLED] ?: false
        )
    }

    suspend fun updateProtection(enabled: Boolean) {
        context.deviceDataStore.edit { preferences ->
            preferences[IS_PROTECTION_ENABLED] = enabled
        }
    }

    suspend fun updateBinding(bound: Boolean) {
        context.deviceDataStore.edit { preferences ->
            preferences[IS_BOUND] = bound
        }
    }

    suspend fun updateProfileCompletion(complete: Boolean) {
        context.deviceDataStore.edit { preferences ->
            preferences[IS_PROFILE_COMPLETE] = complete
        }
    }

    suspend fun updatePermissions(granted: Boolean) {
        context.deviceDataStore.edit { preferences ->
            preferences[PERMISSIONS_GRANTED] = granted
        }
    }
    
    suspend fun clearAll() {
        context.deviceDataStore.edit { it.clear() }
    }
}
