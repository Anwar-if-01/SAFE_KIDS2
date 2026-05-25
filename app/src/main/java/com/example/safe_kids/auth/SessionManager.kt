package com.example.safe_kids.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SessionManager(private val context: Context) {
    
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        EncryptedSharedPreferences.create(
            context,
            "safe_kids_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private val _sessionState = MutableStateFlow(SessionState.UNKNOWN)
    val sessionState: StateFlow<SessionState> = _sessionState
    
    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_LAST_ACTIVITY = "last_activity"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCK_UNTIL = "lock_until"
        
        // Fast-path Cache
        private const val KEY_USER_ROLE = "cached_user_role"
        private const val KEY_USER_EMAIL = "cached_user_email"
        private const val KEY_USER_NAME = "cached_user_name"
        private const val KEY_APP_ICON_HIDDEN = "app_icon_hidden"
        private const val KEY_ADMIN_CODE = "cached_admin_code"
        
        // Onboarding States
        private const val KEY_PROFILE_COMPLETED = "profile_completed"
        private const val KEY_DEVICE_PAIRED = "device_paired"
        private const val KEY_PERMISSIONS_GRANTED = "permissions_granted"
        
        private const val SESSION_TIMEOUT_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val AUTO_LOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }
    
    fun startSession(userId: String, sessionToken: String, user: User? = null) {
        val editor = encryptedPrefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_SESSION_TOKEN, sessionToken)
            .putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LOCK_UNTIL)

        if (user != null) {
            editor.putString(KEY_USER_ROLE, user.role.name)
            editor.putString(KEY_USER_EMAIL, user.email)
            editor.putString(KEY_USER_NAME, user.displayName)
        }
            
        editor.apply()
        
        _sessionState.value = SessionState.ACTIVE
    }
    
    /**
     * Full wipe — call this on Sign Out.
     * Clears auth tokens, cached user, AND all onboarding/mode flags so the
     * next user who logs in starts from a clean slate.
     */
    fun clearAllState() {
        encryptedPrefs.edit()
            // Auth session
            .remove(KEY_USER_ID)
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_LAST_ACTIVITY)
            // Cached profile
            .remove(KEY_USER_ROLE)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            // Onboarding steps
            .remove(KEY_PROFILE_COMPLETED)
            .remove(KEY_DEVICE_PAIRED)
            .remove(KEY_PERMISSIONS_GRANTED)
            .remove(KEY_ADMIN_CODE)
            .apply()

        _sessionState.value = SessionState.ENDED
    }

    /**
     * Mode-only wipe — call this on Switch Mode.
     * Keeps the Firebase auth session alive but resets role and all onboarding
     * flags so MainActivity routes to the "Whose phone is this?" screen.
     */
    fun clearModeState() {
        encryptedPrefs.edit()
            .remove(KEY_USER_ROLE)
            .remove(KEY_PROFILE_COMPLETED)
            .remove(KEY_DEVICE_PAIRED)
            .remove(KEY_PERMISSIONS_GRANTED)
            .apply()
        // sessionState stays ACTIVE — user is still authenticated
    }

    /** Legacy alias — kept for any existing callers. Delegates to clearAllState(). */
    fun endSession() {
        clearAllState()
    }
    
    fun updateActivity() {
        if (isSessionValid()) {
            encryptedPrefs.edit()
                .putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())
                .apply()
        }
    }
    
    fun isSessionValid(): Boolean {
        val userId = encryptedPrefs.getString(KEY_USER_ID, null)
        val lastActivity = encryptedPrefs.getLong(KEY_LAST_ACTIVITY, 0)
        val lockUntil = encryptedPrefs.getLong(KEY_LOCK_UNTIL, 0)
        
        if (userId == null) {
            _sessionState.value = SessionState.ENDED
            return false
        }
        
        val currentTime = System.currentTimeMillis()
        
        // Check if account is locked
        if (lockUntil > currentTime) {
            _sessionState.value = SessionState.LOCKED
            return false
        }
        
        // Check session timeout
        if (currentTime - lastActivity > SESSION_TIMEOUT_MS) {
            _sessionState.value = SessionState.EXPIRED
            return false
        }
        
        // Check auto-lock timeout
        if (currentTime - lastActivity > AUTO_LOCK_TIMEOUT_MS) {
            _sessionState.value = SessionState.AUTO_LOCKED
            return false
        }
        
        _sessionState.value = SessionState.ACTIVE
        return true
    }
    
    fun getCurrentUserId(): String? {
        return if (isSessionValid()) {
            encryptedPrefs.getString(KEY_USER_ID, null)
        } else {
            null
        }
    }
    
    fun getSessionToken(): String? {
        return if (isSessionValid()) {
            encryptedPrefs.getString(KEY_SESSION_TOKEN, null)
        } else {
            null
        }
    }
    
    fun recordFailedAttempt(): Boolean {
        val currentAttempts = encryptedPrefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        
        encryptedPrefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, currentAttempts)
            .apply()
        
        if (currentAttempts >= MAX_FAILED_ATTEMPTS) {
            lockAccount()
            return true // Account locked
        }
        
        return false // Not locked yet
    }
    
    fun resetFailedAttempts() {
        encryptedPrefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LOCK_UNTIL)
            .apply()
    }
    
    private fun lockAccount() {
        val lockUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
        
        encryptedPrefs.edit()
            .putLong(KEY_LOCK_UNTIL, lockUntil)
            .apply()
        
        _sessionState.value = SessionState.LOCKED
    }
    
    fun getRemainingLockTime(): Long {
        val lockUntil = encryptedPrefs.getLong(KEY_LOCK_UNTIL, 0)
        val currentTime = System.currentTimeMillis()
        
        return if (lockUntil > currentTime) {
            lockUntil - currentTime
        } else {
            0
        }
    }
    
    fun getFailedAttempts(): Int {
        return encryptedPrefs.getInt(KEY_FAILED_ATTEMPTS, 0)
    }
    
    fun isBiometricEnabled(): Boolean {
        return encryptedPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }
    
    fun setBiometricEnabled(enabled: Boolean) {
        encryptedPrefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
            .apply()
    }
    
    fun getAutoLockTimeout(): Long {
        return encryptedPrefs.getLong(KEY_AUTO_LOCK_TIMEOUT, AUTO_LOCK_TIMEOUT_MS)
    }
    
    fun setAutoLockTimeout(timeoutMs: Long) {
        encryptedPrefs.edit()
            .putLong(KEY_AUTO_LOCK_TIMEOUT, timeoutMs)
            .apply()
    }

    fun getCachedUser(): User? {
        val userId = encryptedPrefs.getString(KEY_USER_ID, null) ?: return null
        val roleName = encryptedPrefs.getString(KEY_USER_ROLE, null) ?: return null
        
        return try {
            val role = UserRole.valueOf(roleName)
            val email = encryptedPrefs.getString(KEY_USER_EMAIL, "") ?: ""
            val displayName = encryptedPrefs.getString(KEY_USER_NAME, null)
            
            User(
                id = userId,
                role = role,
                email = email,
                displayName = displayName
            )
        } catch (e: Exception) {
            null
        }
    }
    fun updateUserCache(user: User) {
        encryptedPrefs.edit()
            .putString(KEY_USER_ROLE, user.role.name)
            .putString(KEY_USER_EMAIL, user.email)
            .putString(KEY_USER_NAME, user.displayName)
            .putString(KEY_ADMIN_CODE, user.adminCode ?: "")
            .apply()
    }

    fun getAdminCode(): String? {
        val code = encryptedPrefs.getString(KEY_ADMIN_CODE, "")
        return if (code.isNullOrEmpty()) null else code
    }

    fun setAdminCode(code: String?) {
        encryptedPrefs.edit()
            .putString(KEY_ADMIN_CODE, code ?: "")
            .apply()
    }

    fun isAppIconHidden(): Boolean {
        return encryptedPrefs.getBoolean(KEY_APP_ICON_HIDDEN, false)
    }
    
    fun setAppIconHidden(hidden: Boolean) {
        encryptedPrefs.edit()
            .putBoolean(KEY_APP_ICON_HIDDEN, hidden)
            .apply()
    }

    // Onboarding Getters/Setters
    fun isProfileCompleted(): Boolean = encryptedPrefs.getBoolean(KEY_PROFILE_COMPLETED, false)
    fun setProfileCompleted(completed: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_PROFILE_COMPLETED, completed).apply()
    }

    fun isDevicePaired(): Boolean = encryptedPrefs.getBoolean(KEY_DEVICE_PAIRED, false)
    fun setDevicePaired(paired: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_DEVICE_PAIRED, paired).apply()
    }

    fun arePermissionsGranted(): Boolean = encryptedPrefs.getBoolean(KEY_PERMISSIONS_GRANTED, false)
    fun setPermissionsGranted(granted: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_PERMISSIONS_GRANTED, granted).apply()
    }

    // Mode Helpers
    fun isParentMode(): Boolean {
        return getCachedUser()?.role?.isParentLike == true
    }
}

enum class SessionState {
    UNKNOWN,
    ACTIVE,
    EXPIRED,
    AUTO_LOCKED,
    LOCKED,
    ENDED
}
