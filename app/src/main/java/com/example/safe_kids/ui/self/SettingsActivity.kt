package com.example.safe_kids.ui.self

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.data.SettingsManager
import com.example.safe_kids.data.ThemeMode
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility

        val authManager = AuthManagerImpl(this)
        val settingsManager = SettingsManager(this)

        setContent {
            // Observe theme settings for immediate application
            val themeMode by settingsManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val textSize by settingsManager.textSize.collectAsState(initial = 1.0f)
            val displaySize by settingsManager.displaySize.collectAsState(initial = 1.0f)
            
            val useDarkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            SAFE_KIDSTheme(
                darkTheme = useDarkTheme,
                textSize = textSize,
                displaySize = displaySize
            ) {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SettingsViewModel(authManager, settingsManager) as T
                    }
                }
                
                val viewModel: SettingsViewModel = viewModel(factory = factory)
                
                SettingsScreen(
                    viewModel = viewModel,
                    authManager = authManager,
                    onBack = { finish() }
                )
            }
        }
    }
}
