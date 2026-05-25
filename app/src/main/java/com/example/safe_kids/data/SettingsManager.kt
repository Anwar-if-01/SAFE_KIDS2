package com.example.safe_kids.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { LIGHT, DARK, SYSTEM }

class SettingsManager(private val context: Context) {

    companion object {
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val TEXT_SIZE_KEY = floatPreferencesKey("text_size")
        val DISPLAY_SIZE_KEY = floatPreferencesKey("display_size")
        val LANGUAGE_KEY = stringPreferencesKey("language")
        val TERMS_AGREED_KEY = booleanPreferencesKey("terms_agreed")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        val themeString = preferences[THEME_KEY] ?: ThemeMode.SYSTEM.name
        ThemeMode.valueOf(themeString)
    }

    val textSize: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[TEXT_SIZE_KEY] ?: 1.0f
    }

    val displaySize: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[DISPLAY_SIZE_KEY] ?: 1.0f
    }

    val language: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: "en"
    }

    /** Whether the user has accepted the Terms of Service and Privacy Policy. */
    val termsAgreed: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TERMS_AGREED_KEY] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = mode.name
        }
    }

    suspend fun setTextSize(size: Float) {
        context.dataStore.edit { preferences ->
            preferences[TEXT_SIZE_KEY] = size
        }
    }

    suspend fun setDisplaySize(size: Float) {
        context.dataStore.edit { preferences ->
            preferences[DISPLAY_SIZE_KEY] = size
        }
    }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = lang
        }
    }

    suspend fun setTermsAgreed(agreed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TERMS_AGREED_KEY] = agreed
        }
    }
}
