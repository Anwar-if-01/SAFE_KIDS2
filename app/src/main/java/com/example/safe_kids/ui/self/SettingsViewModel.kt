package com.example.safe_kids.ui.self

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.auth.AuthManager
import com.example.safe_kids.auth.UserRole
import com.example.safe_kids.data.SettingsManager
import com.example.safe_kids.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val authManager: AuthManager,
    private val settingsManager: SettingsManager
) : ViewModel() {

    val userRole: StateFlow<UserRole> = authManager.currentUser.map { user ->
        user?.role ?: UserRole.UNDETERMINED
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserRole.UNDETERMINED)

    val themeMode: StateFlow<ThemeMode> = settingsManager.themeMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM
    )

    val textSize: StateFlow<Float> = settingsManager.textSize.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f
    )

    val displaySize: StateFlow<Float> = settingsManager.displaySize.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f
    )

    val language: StateFlow<String> = settingsManager.language.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "English"
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsManager.setThemeMode(mode)
        }
    }

    fun setTextSize(size: Float) {
        viewModelScope.launch {
            settingsManager.setTextSize(size)
        }
    }

    fun setDisplaySize(size: Float) {
        viewModelScope.launch {
            settingsManager.setDisplaySize(size)
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            settingsManager.setLanguage(lang)
        }
    }

    fun switchMode() {
        // Implementation for switching mode if needed
        // Usually redirects to role selection or triggers a re-auth
    }
}
