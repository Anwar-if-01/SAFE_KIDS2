package com.example.safe_kids.auth

object AuthConfig {
    // Password requirements
    const val MIN_PASSWORD_LENGTH = 6
    const val REQUIRE_UPPERCASE = false
    const val REQUIRE_LOWERCASE = false
    const val REQUIRE_DIGITS = true
    const val REQUIRE_SPECIAL_CHARS = false
    
    // Session management
    const val SESSION_TIMEOUT_HOURS = 24
    const val AUTO_LOCK_MINUTES = 5
    const val MAX_FAILED_ATTEMPTS = 5
    const val LOCKOUT_DURATION_MINUTES = 30
    
    // Pairing codes
    const val PAIRING_CODE_LENGTH = 9
    const val PAIRING_CODE_EXPIRY_MINUTES = 15
    const val MAX_ACTIVE_PAIRING_CODES = 3
    
    // Biometric settings
    const val BIOMETRIC_TIMEOUT_SECONDS = 30
    const val ALLOW_DEVICE_CREDENTIAL_FALLBACK = true
    
    // Firebase collections
    const val USERS_COLLECTION = "users"
    const val PAIRING_CODES_COLLECTION = "pairing_codes"
    const val PARENT_CHILD_RELATIONS_COLLECTION = "parent_child_relations"
    const val USER_SESSIONS_COLLECTION = "user_sessions"
    const val CHILD_PROFILES_COLLECTION = "child_profiles"
    const val CHILD_COMMANDS_COLLECTION = "child_commands"
    const val CONTENT_SAFETY_LOGS_COLLECTION = "content_safety_logs"
    const val WEB_HISTORY_COLLECTION = "web_history"
    const val APP_USAGE_LIMITS_COLLECTION = "app_usage_limits"
    const val INVITE_CODES_COLLECTION = "invite_codes"
    const val LOCATIONS_COLLECTION = "locations"
    const val TAMPERING_ALERTS_COLLECTION = "tampering_alerts"
    const val ACTIVITY_REPORTS_COLLECTION = "activity_reports"
    const val ACTIVITY_ALERTS_COLLECTION = "activity_alerts"
    const val SAFE_ZONES_COLLECTION = "safe_zones"
    const val RESTRICTED_AREAS_COLLECTION = "restricted_areas"
    
    // User roles
    val ADMIN_ROLES = setOf(UserRole.PARENT, UserRole.CO_PARENT)
    val RESTRICTED_ROLES = setOf(UserRole.CHILD)
    
    // Security settings
    const val ENABLE_EMAIL_VERIFICATION = true
    const val REQUIRE_PHONE_VERIFICATION = false
    const val ENABLE_TWO_FACTOR_AUTH = false
    
    // Google Sign-In
    const val GOOGLE_SIGN_IN_ENABLED = true
    const val GOOGLE_WEB_CLIENT_ID = "767296457950-fs2vctsarkg42qskpa74j6i565d3skfd.apps.googleusercontent.com"
    
    // Account linking
    const val MAX_CHILDREN_PER_PARENT = 10
    const val MAX_PARENTS_PER_CHILD = 3
    const val ALLOW_MULTIPLE_DEVICE_LOGIN = true
    
    // Data retention
    const val INACTIVE_SESSION_CLEANUP_DAYS = 30
    const val EXPIRED_PAIRING_CODE_CLEANUP_HOURS = 24
    const val USER_ACTIVITY_LOG_RETENTION_DAYS = 90
}

data class AuthValidationRules(
    val minPasswordLength: Int = AuthConfig.MIN_PASSWORD_LENGTH,
    val requireUppercase: Boolean = AuthConfig.REQUIRE_UPPERCASE,
    val requireLowercase: Boolean = AuthConfig.REQUIRE_LOWERCASE,
    val requireDigits: Boolean = AuthConfig.REQUIRE_DIGITS,
    val requireSpecialChars: Boolean = AuthConfig.REQUIRE_SPECIAL_CHARS,
    val maxFailedAttempts: Int = AuthConfig.MAX_FAILED_ATTEMPTS,
    val lockoutDurationMinutes: Int = AuthConfig.LOCKOUT_DURATION_MINUTES
)

object PasswordValidator {
    fun validate(password: String, rules: AuthValidationRules = AuthValidationRules()): PasswordValidationResult {
        val errors = mutableListOf<String>()
        
        if (password.length < rules.minPasswordLength) {
            errors.add("Password must be at least ${rules.minPasswordLength} characters long")
        }
        
        if (rules.requireUppercase && !password.any { it.isUpperCase() }) {
            errors.add("Password must contain at least one uppercase letter")
        }
        
        if (rules.requireLowercase && !password.any { it.isLowerCase() }) {
            errors.add("Password must contain at least one lowercase letter")
        }
        
        if (rules.requireDigits && !password.any { it.isDigit() }) {
            errors.add("Password must contain at least one number")
        }
        
        if (rules.requireSpecialChars && !password.any { !it.isLetterOrDigit() }) {
            errors.add("Password must contain at least one special character")
        }
        
        val strength = calculatePasswordStrength(password)
        
        return PasswordValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            strength = strength
        )
    }
    
    private fun calculatePasswordStrength(password: String): PasswordStrength {
        var score = 0
        
        if (password.length >= 8) score++
        if (password.length >= 12) score++
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++
        if (password.length >= 16) score++
        
        return when (score) {
            0, 1 -> PasswordStrength.VERY_WEAK
            2, 3 -> PasswordStrength.WEAK
            4, 5 -> PasswordStrength.FAIR
            6 -> PasswordStrength.STRONG
            else -> PasswordStrength.VERY_STRONG
        }
    }
}

data class PasswordValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val strength: PasswordStrength
)

enum class PasswordStrength(val displayName: String, val score: Int) {
    VERY_WEAK("Very Weak", 0),
    WEAK("Weak", 1),
    FAIR("Fair", 2),
    STRONG("Strong", 3),
    VERY_STRONG("Very Strong", 4)
}
