package com.example.safe_kids.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * PermissionManager
 * 
 * Centralized permission checking for Safe Kids app.
 * Ensures ALL required permissions are granted before child can proceed.
 * 
 * Enhanced with:
 * - Permission state tracking (Granted, NotGranted, Unsupported, Skipped, Checking)
 * - OEM-specific handling for Infinix, Tecno, Xiaomi, Huawei, Oppo, Vivo, Samsung
 * - Fallback intent support
 * - Auto-refresh on resume
 * - Comprehensive logging for debugging
 */
object PermissionManager {
    
    private const val TAG = "PermissionManager"
    
    /**
     * Permission states - DO NOT CACHE, always re-check from system APIs
     */
    enum class PermissionState {
        GRANTED,
        NOT_GRANTED,
        UNSUPPORTED,
        SKIPPED,
        CHECKING
    }
    
    /**
     * All required permissions for child device
     */
    enum class RequiredPermission {
        ACCESSIBILITY_SERVICE,
        DRAW_OVER_APPS,
        USAGE_ACCESS,
        DO_NOT_DISTURB,
        NOTIFICATION_LISTENER,
        BATTERY_OPTIMIZATION,
        DEVICE_ADMIN,
        LOCATION,
        BACKGROUND_LOCATION,
        CAMERA,
        CALL_LOG,
        SMS
    }
    
    /**
     * Manufacturer detection for OEM-specific handling
     * Logged on every state check for debugging
     */
    private val manufacturer: String = Build.MANUFACTURER.lowercase()
    
    private fun isInfinix() = manufacturer.contains("infinix")
    private fun isTecno() = manufacturer.contains("tecno")
    private fun isXiaomi() = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("mi")
    private fun isHuawei() = manufacturer.contains("huawei") || manufacturer.contains("honor")
    private fun isOppo() = manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")
    private fun isVivo() = manufacturer.contains("vivo")
    private fun isSamsung() = manufacturer.contains("samsung")
    private fun isGoogle() = manufacturer.contains("google") || manufacturer.contains("pixel")
    
    fun isProblematicOEM(): Boolean {
        return isInfinix() || isTecno() || isXiaomi() || isHuawei() || isOppo() || isVivo()
    }
    
    /**
     * Get OEM name for logging
     */
    fun getOemName(): String {
        return when {
            isInfinix() -> "Infinix/XOS"
            isTecno() -> "Tecno/HiOS"
            isXiaomi() -> "Xiaomi/MIUI"
            isHuawei() -> "Huawei/EMUI"
            isOppo() -> "Oppo/ColorOS"
            isVivo() -> "Vivo/FuntouchOS"
            isSamsung() -> "Samsung/OneUI"
            isGoogle() -> "Google/Pixel"
            else -> "${Build.MANUFACTURER} (generic)"
        }
    }
    
    /**
     * Track skipped permissions (persisted in SharedPreferences)
     */
    private const val PREFS_NAME = "permission_manager_prefs"
    private const val KEY_SKIPPED_PERMISSIONS = "skipped_permissions"
    
    fun markPermissionAsSkipped(context: Context, permission: RequiredPermission) {
        Log.d(TAG, "=== Marking permission as skipped: ${getPermissionName(permission)} ===")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val skipped = prefs.getStringSet(KEY_SKIPPED_PERMISSIONS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        skipped.add(permission.name)
        prefs.edit().putStringSet(KEY_SKIPPED_PERMISSIONS, skipped).apply()
        Log.d(TAG, "Skipped permissions now: $skipped")
    }
    
    fun isPermissionSkipped(context: Context, permission: RequiredPermission): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val skipped = prefs.getStringSet(KEY_SKIPPED_PERMISSIONS, emptySet()) ?: emptySet()
        val isSkipped = skipped.contains(permission.name)
        if (isSkipped) {
            Log.d(TAG, "Permission ${getPermissionName(permission)} was previously skipped")
        }
        return isSkipped
    }
    
    fun clearSkippedPermissions(context: Context) {
        Log.d(TAG, "=== Clearing all skipped permissions ===")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SKIPPED_PERMISSIONS).apply()
    }
    
    /**
     * Track unsupported permissions (persisted in SharedPreferences)
     * This persists across process restarts
     */
    private const val KEY_UNSUPPORTED_PERMISSIONS = "unsupported_permissions"
    
    fun markPermissionAsUnsupported(context: Context, permission: RequiredPermission) {
        Log.d(TAG, "=== Persisting unsupported state: ${getPermissionName(permission)} ===")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val unsupported = prefs.getStringSet(KEY_UNSUPPORTED_PERMISSIONS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        unsupported.add(permission.name)
        prefs.edit().putStringSet(KEY_UNSUPPORTED_PERMISSIONS, unsupported).apply()
        Log.d(TAG, "Unsupported permissions now: $unsupported")
    }
    
    private fun isPermissionMarkedUnsupported(context: Context, permission: RequiredPermission): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val unsupported = prefs.getStringSet(KEY_UNSUPPORTED_PERMISSIONS, emptySet()) ?: emptySet()
        return unsupported.contains(permission.name)
    }
    
    /**
     * Get list of all required permissions
     */
    fun getAllRequiredPermissions(): List<RequiredPermission> {
        return RequiredPermission.values().toList()
    }
    
    /**
     * Get permission state (enhanced with Unsupported and Skipped states)
     * CRITICAL: Always checks directly from system APIs - NO CACHING
     */
    fun getPermissionState(context: Context, permission: RequiredPermission): PermissionState {
        Log.d(TAG, "[OEM:${getOemName()}] Checking state for ${getPermissionName(permission)}...")
        
        // Step 1: Check if permission was manually skipped
        if (isPermissionSkipped(context, permission)) {
            Log.d(TAG, "  → SKIPPED (user manually skipped)")
            return PermissionState.SKIPPED
        }
        
        // Step 2: Check if permission is unsupported on this device/OEM
        if (isPermissionUnsupported(context, permission)) {
            Log.d(TAG, "  → UNSUPPORTED (device/OEM limitation)")
            return PermissionState.UNSUPPORTED
        }
        
        // Step 3: Check if permission was previously marked unsupported (persisted)
        if (isPermissionMarkedUnsupported(context, permission)) {
            Log.d(TAG, "  → UNSUPPORTED (persisted from previous session)")
            return PermissionState.UNSUPPORTED
        }
        
        // Step 4: Check if permission is granted (directly from system APIs)
        // CRITICAL: Re-check every time, DO NOT cache
        val isGranted = isPermissionGranted(context, permission)
        Log.d(TAG, "  → ${if (isGranted) "GRANTED" else "NOT_GRANTED"}")
        
        return if (isGranted) {
            PermissionState.GRANTED
        } else {
            PermissionState.NOT_GRANTED
        }
    }
    
    /**
     * Check if permission is unsupported on this device/OEM
     * Handles known OEM limitations:
     * - Infinix/Tecno: Missing notification listener settings
     * - Xiaomi/MIUI: Aggressive battery management, notification listener, accessibility quirks
     * - Huawei/EMUI: Some API-level restrictions on notification access
     * - Oppo/Vivo: Background restrictions, notification listener absence
     * - Android < M: DND not available
     * - Android < Q: Background location not available
     */
    private fun isPermissionUnsupported(context: Context, permission: RequiredPermission): Boolean {
        val unsupported = when (permission) {
            RequiredPermission.NOTIFICATION_LISTENER -> {
                // NOTIFICATION_LISTENER is commonly missing on:
                // - Infinix (XOS)
                // - Tecno (HiOS)
                // - Xiaomi (some MIUI versions redirect to a different page)
                // - Oppo (ColorOS)
                // - Vivo (FuntouchOS)
                val isLikelyMissing = isInfinix() || isTecno() || 
                                       (isXiaomi() && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ||
                                       isOppo() || isVivo()
                
                if (isLikelyMissing) {
                    Log.d(TAG, "    [OEM:${getOemName()}] Notification Listener is likely missing - marking as UNSUPPORTED")
                    // Also check if the Settings action resolves (will be handled at runtime too)
                }
                
                // Check if notification listener was already detected as unsupported
                // via the runtime flag in PermissionUtils
                val runtimeFlag = PermissionUtils.isNotificationListenerNotSupported
                Log.d(TAG, "    Runtime unsupported flag: $runtimeFlag")
                Log.d(TAG, "    OEM check: isLikelyMissing=$isLikelyMissing")
                
                isLikelyMissing || runtimeFlag
            }
            RequiredPermission.DO_NOT_DISTURB -> {
                // DND not available on older Android versions
                val notSupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                if (notSupported) {
                    Log.d(TAG, "    DND not available on Android ${Build.VERSION.SDK_INT} (requires 23+)")
                }
                notSupported
            }
            RequiredPermission.BACKGROUND_LOCATION -> {
                // Background location only on Android 10+
                val notSupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                if (notSupported) {
                    Log.d(TAG, "    Background location not available on Android ${Build.VERSION.SDK_INT} (requires 29+)")
                }
                notSupported
            }
            RequiredPermission.ACCESSIBILITY_SERVICE -> {
                // On some very restricted devices (Android Go, some Huawei), accessibility may be limited
                // But we handle this via runtime detection, not static check
                false
            }
            else -> false
        }
        
        return unsupported
    }
    
    /**
     * Check if ALL required permissions are granted (or skipped/unsupported)
     * Returns true only if every single permission is in acceptable state
     * CRITICAL: Re-checks from system APIs every time - NO CACHING
     */
    fun areAllPermissionsGranted(context: Context): Boolean {
        Log.d(TAG, "=== Checking if all permissions granted ===")
        val allPermissions = getAllRequiredPermissions()
        
        val results = allPermissions.map { permission ->
            val state = getPermissionState(context, permission)
            val acceptable = state == PermissionState.GRANTED || 
                           state == PermissionState.SKIPPED || 
                           state == PermissionState.UNSUPPORTED
            Log.d(TAG, "  ${getPermissionName(permission)}: $state (acceptable: $acceptable)")
            acceptable
        }
        
        val allAcceptable = results.all { it }
        Log.d(TAG, "=== Result: allPermissionsGranted=$allAcceptable ===")
        return allAcceptable
    }
    
    /**
     * Check if essential permissions are granted (cannot be skipped)
     * CRITICAL: Re-checks from system APIs every time - NO CACHING
     */
    fun areEssentialPermissionsGranted(context: Context): Boolean {
        Log.d(TAG, "=== Checking if essential permissions granted ===")
        val essentialPermissions = listOf(
            RequiredPermission.ACCESSIBILITY_SERVICE,
            RequiredPermission.USAGE_ACCESS,
            RequiredPermission.LOCATION
        )
        
        val results = essentialPermissions.map { permission ->
            val state = getPermissionState(context, permission)
            val acceptable = state == PermissionState.GRANTED || state == PermissionState.UNSUPPORTED
            Log.d(TAG, "  [ESSENTIAL] ${getPermissionName(permission)}: $state (acceptable: $acceptable)")
            acceptable
        }
        
        val allEssentialGranted = results.all { it }
        Log.d(TAG, "=== Result: essentialPermissionsGranted=$allEssentialGranted ===")
        return allEssentialGranted
    }
    
    /**
     * Get the count of completed permissions (granted + skipped + unsupported)
     */
    fun getCompletedPermissionCount(context: Context): Int {
        val allPermissions = getAllRequiredPermissions()
        var count = 0
        for (permission in allPermissions) {
            val state = getPermissionState(context, permission)
            if (state == PermissionState.GRANTED || 
                state == PermissionState.SKIPPED || 
                state == PermissionState.UNSUPPORTED) {
                count++
            }
        }
        return count
    }
    
    /**
     * Check individual permission status directly from system APIs
     * NO CACHING - always returns fresh state
     */
    fun isPermissionGranted(context: Context, permission: RequiredPermission): Boolean {
        return when (permission) {
            RequiredPermission.ACCESSIBILITY_SERVICE -> isAccessibilityServiceEnabled(context)
            RequiredPermission.DRAW_OVER_APPS -> canDrawOverlays(context)
            RequiredPermission.USAGE_ACCESS -> isUsageStatsPermissionGranted(context)
            RequiredPermission.DO_NOT_DISTURB -> hasNotificationPolicyAccess(context)
            RequiredPermission.NOTIFICATION_LISTENER -> isNotificationListenerEnabled(context)
            RequiredPermission.BATTERY_OPTIMIZATION -> isIgnoringBatteryOptimizations(context)
            RequiredPermission.DEVICE_ADMIN -> isDeviceAdminActive(context)
            RequiredPermission.LOCATION -> hasLocationPermissions(context)
            RequiredPermission.BACKGROUND_LOCATION -> hasBackgroundLocationPermission(context)
            RequiredPermission.CAMERA -> hasCameraPermission(context)
            RequiredPermission.CALL_LOG -> hasCallLogPermission(context)
            RequiredPermission.SMS -> hasSmsPermissions(context)
        }
    }
    
    /**
     * Get list of missing permissions (not granted and not skipped/unsupported)
     */
    fun getMissingPermissions(context: Context): List<RequiredPermission> {
        return getAllRequiredPermissions().filter { 
            val state = getPermissionState(context, it)
            state == PermissionState.NOT_GRANTED
        }
    }
    
    /**
     * Get list of unsupported-or-skipped permissions
     */
    fun getNonRequiredPermissions(context: Context): List<RequiredPermission> {
        return getAllRequiredPermissions().filter {
            val state = getPermissionState(context, it)
            state == PermissionState.SKIPPED || state == PermissionState.UNSUPPORTED
        }
    }
    
    /**
     * Get human-readable name for permission
     */
    fun getPermissionName(permission: RequiredPermission): String {
        return when (permission) {
            RequiredPermission.ACCESSIBILITY_SERVICE -> "Accessibility Service"
            RequiredPermission.DRAW_OVER_APPS -> "Draw Over Other Apps"
            RequiredPermission.USAGE_ACCESS -> "Usage Access"
            RequiredPermission.DO_NOT_DISTURB -> "Do Not Disturb Access"
            RequiredPermission.NOTIFICATION_LISTENER -> "Notification Listener"
            RequiredPermission.BATTERY_OPTIMIZATION -> "Ignore Battery Optimization"
            RequiredPermission.DEVICE_ADMIN -> "Device Admin"
            RequiredPermission.LOCATION -> "Location Permission"
            RequiredPermission.BACKGROUND_LOCATION -> "Background Location"
            RequiredPermission.CAMERA -> "Camera Permission"
            RequiredPermission.CALL_LOG -> "Call Log Permission"
            RequiredPermission.SMS -> "SMS Permission"
        }
    }
    
    /**
     * Get human-readable description for permission
     */
    fun getPermissionDescription(permission: RequiredPermission): String {
        return when (permission) {
            RequiredPermission.ACCESSIBILITY_SERVICE -> "Required for monitoring app usage and enforcing restrictions"
            RequiredPermission.DRAW_OVER_APPS -> "Required for showing overlay warnings and alerts"
            RequiredPermission.USAGE_ACCESS -> "Required for tracking app usage time and blocking apps"
            RequiredPermission.DO_NOT_DISTURB -> "Required for enforcing silent hours and focus mode"
            RequiredPermission.NOTIFICATION_LISTENER -> "Required for monitoring notifications for safety alerts"
            RequiredPermission.BATTERY_OPTIMIZATION -> "Required to keep SafeKids running in the background"
            RequiredPermission.DEVICE_ADMIN -> "Required to prevent uninstallation of SafeKids"
            RequiredPermission.LOCATION -> "Required for location-based safety features and geofencing"
            RequiredPermission.BACKGROUND_LOCATION -> "Required for continuous location monitoring in background"
            RequiredPermission.CAMERA -> "Required for safety camera features and monitoring"
            RequiredPermission.CALL_LOG -> "Required for monitoring incoming and outgoing calls"
            RequiredPermission.SMS -> "Required for SMS safety monitoring and alerts"
        }
    }
    
    // Delegate to PermissionUtils for actual checks
    private fun isAccessibilityServiceEnabled(context: Context) = 
        PermissionUtils.isAccessibilityServiceEnabled(context)
    
    private fun canDrawOverlays(context: Context) = 
        Settings.canDrawOverlays(context)
    
    private fun isUsageStatsPermissionGranted(context: Context) = 
        PermissionUtils.isUsageStatsPermissionGranted(context)
    
    private fun hasNotificationPolicyAccess(context: Context) = 
        PermissionUtils.hasNotificationPolicyAccess(context)
    
    private fun isNotificationListenerEnabled(context: Context) = 
        PermissionUtils.isNotificationListenerEnabled(context)
    
    private fun isIgnoringBatteryOptimizations(context: Context) = 
        PermissionUtils.isIgnoringBatteryOptimizations(context)
    
    private fun isDeviceAdminActive(context: Context) = 
        PermissionUtils.isDeviceAdminActive(context)
    
    private fun hasLocationPermissions(context: Context) = 
        PermissionUtils.hasLocationPermissions(context)
    
    private fun hasBackgroundLocationPermission(context: Context) = 
        PermissionUtils.hasBackgroundLocationPermission(context)
    
    private fun hasCameraPermission(context: Context) = 
        PermissionUtils.hasCameraPermission(context)
    
    private fun hasCallLogPermission(context: Context) = 
        PermissionUtils.hasCallLogPermission(context)
    
    private fun hasSmsPermissions(context: Context) = 
        PermissionUtils.hasSmsPermissions(context)
}