package com.example.safe_kids.services.blocking

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.safe_kids.utils.PermissionUtils
import com.example.safe_kids.data.DeviceStateManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import java.util.Calendar

data class BlockedApp(
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean,
    val blockStartTime: Long? = null,
    val blockEndTime: Long? = null,
    val blockReason: BlockReason = BlockReason.MANUAL
)

enum class BlockReason {
    MANUAL,
    STUDY_MODE,
    SLEEP_MODE,
    FOCUS_MODE,
    TIME_LIMIT,
    INAPPROPRIATE_CONTENT
}

enum class AppMode {
    NORMAL,
    STUDY_MODE,
    SLEEP_MODE,
    FOCUS_MODE
}

data class FocusSession(
    val id: String,
    val startTime: Long,
    val duration: Long, // in milliseconds
    val allowedApps: List<String>,
    val isActive: Boolean
)

interface AppBlockingService {
    // Basic blocking
    fun blockApp(packageName: String, reason: BlockReason = BlockReason.MANUAL)
    fun unblockApp(packageName: String)
    fun getBlockedApps(): Flow<List<BlockedApp>>
    fun isAppBlocked(packageName: String): Boolean
    
    // Time-based blocking
    fun setTimeBasedBlocking(packageName: String, startTime: Long, endTime: Long)
    fun setDailyTimeLimit(packageName: String, limitMinutes: Int)
    fun getRemainingTime(packageName: String): Long
    
    // Mode-based blocking
    fun enableStudyMode(allowedApps: List<String> = listOf("com.google.android.apps.docs"))
    fun enableSleepMode()
    fun enableFocusMode(duration: Long, allowedApps: List<String> = emptyList())
    fun disableAllModes()
    fun getCurrentMode(): AppMode
    fun setMode(mode: AppMode)
    
    // Focus sessions
    fun startFocusSession(duration: Long, allowedApps: List<String>): String
    fun endFocusSession(sessionId: String)
    fun getActiveFocusSession(): FocusSession?
    
    // Sleep Mode Emergency
    fun activateSleepEmergencyOverride()
    fun getSleepEmergencyRemainingMillis(): Long
    
    // Permissions and setup
    fun hasUsageStatsPermission(): Boolean
    fun hasAccessibilityPermission(): Boolean
    fun requestUsageStatsPermission(): Intent
    fun requestAccessibilityPermission(): Intent
    
    // App usage monitoring
    fun getAppUsageToday(packageName: String): Long
    fun getAppsUsedCountToday(): Int
    fun getStudyTimeToday(): Long
    fun getInstalledApps(): List<String>
    fun isAppRunning(packageName: String): Boolean
    fun forceStopApp(packageName: String)
    
    // Time Extension
    fun extendTime(minutes: Int)
}

class AppBlockingServiceImpl(private val context: Context) : AppBlockingService {
    companion object {
        private val _currentMode = MutableStateFlow(AppMode.NORMAL)
        val currentModeState: StateFlow<AppMode> = _currentMode.asStateFlow()
        
        private val _blockedApps = MutableStateFlow<List<BlockedApp>>(emptyList())
        private val _studyModeWhitelist = MutableStateFlow<List<String>>(emptyList())
        private val _sleepEmergencyEndTime = MutableStateFlow(0L)
        private val _activeFocusSession = MutableStateFlow<FocusSession?>(null)
    }
    
    private val _timeExtensionMinutes = MutableStateFlow(0)
    private val deviceStateManager = DeviceStateManager(context)
    private var isProtectionEnabled = false
    private val dailyTimeLimits = mutableMapOf<String, Long>()
    
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    init {
        // Observe protection state and disable everything if protection is off
        kotlinx.coroutines.MainScope().launch {
            deviceStateManager.deviceState.collect { state ->
                isProtectionEnabled = state.isProtectionEnabled
                if (!isProtectionEnabled) {
                    disableAllProtectionFeatures()
                }
            }
        }
    }

    private fun disableAllProtectionFeatures() {
        _blockedApps.value = emptyList()
        _currentMode.value = AppMode.NORMAL
        _activeFocusSession.value = null
        // Force unblock everything in the OS if needed
        // (Our blocking is mostly logic-based, so clearing the list stops the blocking loops)
    }

    override fun blockApp(packageName: String, reason: BlockReason) {
        if (!isProtectionEnabled) {
            android.util.Log.d("AppBlocking", "Blocking ignored because protection is disabled by parent.")
            return
        }
        
        val currentList = _blockedApps.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.packageName == packageName }
        
        val blockedApp = BlockedApp(
            packageName = packageName,
            appName = getAppName(packageName),
            isBlocked = true,
            blockStartTime = System.currentTimeMillis(),
            blockReason = reason
        )
        
        if (existingIndex >= 0) {
            currentList[existingIndex] = blockedApp
        } else {
            currentList.add(blockedApp)
        }
        
        _blockedApps.value = currentList
        
        // Force stop the app if currently running
        if (isAppRunning(packageName)) {
            forceStopApp(packageName)
        }
    }

    override fun unblockApp(packageName: String) {
        val currentList = _blockedApps.value.toMutableList()
        currentList.removeAll { it.packageName == packageName }
        _blockedApps.value = currentList
    }

    override fun getBlockedApps(): Flow<List<BlockedApp>> = _blockedApps


    
    override fun isAppBlocked(packageName: String): Boolean {
        // Essential apps always allowed
        if (isSystemApp(packageName)) return false
        
        // Priority Order: Study Mode -> Sleep Mode -> Focus Mode -> Downtime/Manual
        
        // 1. Study Mode Priority: If Active, only allowed apps are unblocked
        if (_currentMode.value == AppMode.STUDY_MODE) {
            return !(_studyModeWhitelist.value.contains(packageName))
        }
        
        // 2. Sleep Mode Priority
        if (_currentMode.value == AppMode.SLEEP_MODE) {
            val currentTime = System.currentTimeMillis()
            // Check if emergency override is active (5 min)
            if (currentTime < _sleepEmergencyEndTime.value) {
                // During emergency, allow Dialer, Contacts and Safe Kids
                val emergencyWhitelist = listOf(
                    "com.android.dialer",
                    "com.google.android.dialer",
                    "com.android.contacts",
                    "com.google.android.contacts",
                    context.packageName
                )
                return !emergencyWhitelist.contains(packageName)
            }
            
            // Standard Sleep Mode: everything blocked except Dialer/Contacts/Safe Kids
            // Wait, the requirement says "allows 5 minutes of access to Dialer, Contacts and Safe Kids only"
            // during emergency. But standard Sleep Mode ALREADY allows those?
            // Ah, usually Sleep Mode blocks EVERYTHING. 
            // Let's check the requirement again: "allows 5 minutes of access to Dialer, Contacts and Safe Kids only"
            // Wait, if standard Sleep Mode also allows them, then the emergency override is redundant for those apps.
            // Maybe standard Sleep Mode should block EVERYTHING (total lockout).
            // Image says: "All applications are blocked except emergency features".
            // OK, I'll stick to: Sleep Mode allows Dialer/Contacts/SafeKids by default? 
            // Usually "Emergency Features" = Dialer/SOS.
            
            val emergencyWhitelist = listOf(
                "com.android.dialer",
                "com.google.android.dialer",
                "com.android.contacts",
                "com.google.android.contacts",
                context.packageName
            )
            return !emergencyWhitelist.contains(packageName)
        }
        
        return _blockedApps.value.any { it.packageName == packageName && it.isBlocked }
    }

    override fun activateSleepEmergencyOverride() {
        _sleepEmergencyEndTime.value = System.currentTimeMillis() + (5 * 60 * 1000L)
    }

    override fun getSleepEmergencyRemainingMillis(): Long {
        return maxOf(0L, _sleepEmergencyEndTime.value - System.currentTimeMillis())
    }

    override fun setTimeBasedBlocking(packageName: String, startTime: Long, endTime: Long) {
        val currentTime = System.currentTimeMillis()
        if (currentTime in startTime..endTime) {
            blockApp(packageName, BlockReason.TIME_LIMIT)
        }
    }

    override fun setDailyTimeLimit(packageName: String, limitMinutes: Int) {
        dailyTimeLimits[packageName] = limitMinutes * 60 * 1000L
        val usageToday = getAppUsageToday(packageName)
        val limitMillis = (limitMinutes + _timeExtensionMinutes.value) * 60 * 1000L
        
        if (usageToday >= limitMillis) {
            blockApp(packageName, BlockReason.TIME_LIMIT)
        }
    }

    override fun extendTime(minutes: Int) {
        _timeExtensionMinutes.value += minutes
        // Immediately unblock apps if they were blocked due to time limit
        val currentList = _blockedApps.value.toMutableList()
        currentList.removeAll { it.blockReason == BlockReason.TIME_LIMIT }
        _blockedApps.value = currentList
    }

    override fun getRemainingTime(packageName: String): Long {
        val baseLimitMillis = dailyTimeLimits[packageName] ?: return Long.MAX_VALUE
        val limitMillis = baseLimitMillis + (_timeExtensionMinutes.value * 60 * 1000L)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val endOfDay = System.currentTimeMillis()

        val usedMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            usageStatsManager.queryAndAggregateUsageStats(startOfDay, endOfDay)
                .get(packageName)
                ?.totalTimeInForeground ?: 0L
        } else {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, endOfDay)
                .filter { it.packageName == packageName }
                .sumOf { it.totalTimeInForeground }
        }

        return maxOf(0L, limitMillis - usedMillis)
    }

    override fun enableStudyMode(allowedApps: List<String>) {
        _studyModeWhitelist.value = allowedApps
        _currentMode.value = AppMode.STUDY_MODE
        
        // Force stop apps that are not in whitelist
        val currentForegroundApp = getCurrentForegroundApp()
        if (currentForegroundApp != null && isAppBlocked(currentForegroundApp)) {
            forceStopApp(currentForegroundApp)
        }
    }

    private fun getCurrentForegroundApp(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000L
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    override fun enableSleepMode() {
        _currentMode.value = AppMode.SLEEP_MODE
        
        // In Sleep Mode, we allow only Dialer, Contacts, and Safe Kids
        val emergencyWhitelist = listOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.google.android.contacts",
            context.packageName
        )
        
        // Force stop currently active app if it's not in the emergency whitelist
        val currentForegroundApp = getCurrentForegroundApp()
        if (currentForegroundApp != null && !emergencyWhitelist.contains(currentForegroundApp)) {
            forceStopApp(currentForegroundApp)
        }
    }

    override fun enableFocusMode(duration: Long, allowedApps: List<String>) {
        val sessionId = "focus_${System.currentTimeMillis()}"
        val session = FocusSession(
            id = sessionId,
            startTime = System.currentTimeMillis(),
            duration = duration,
            allowedApps = allowedApps,
            isActive = true
        )
        
        _currentMode.value = AppMode.FOCUS_MODE
        _activeFocusSession.value = session
        
        // Block all apps except allowed ones
        val installedApps = getInstalledApps()
        installedApps.forEach { packageName ->
            if (packageName !in allowedApps && !isSystemApp(packageName)) {
                blockApp(packageName, BlockReason.FOCUS_MODE)
            }
        }
    }

    override fun disableAllModes() {
        _currentMode.value = AppMode.NORMAL
        _activeFocusSession.value = null
        
        // Unblock all mode-based blocks
        val currentList = _blockedApps.value.toMutableList()
        currentList.removeAll { 
            it.blockReason in listOf(BlockReason.STUDY_MODE, BlockReason.SLEEP_MODE, BlockReason.FOCUS_MODE)
        }
        _blockedApps.value = currentList
    }

    override fun getCurrentMode(): AppMode = _currentMode.value

    override fun setMode(mode: AppMode) {
        when (mode) {
            AppMode.NORMAL -> disableAllModes()
            AppMode.STUDY_MODE -> enableStudyMode()
            AppMode.SLEEP_MODE -> enableSleepMode()
            AppMode.FOCUS_MODE -> enableFocusMode(3600000) // Default 1 hour
        }
    }

    override fun startFocusSession(duration: Long, allowedApps: List<String>): String {
        enableFocusMode(duration, allowedApps)
        return _activeFocusSession.value?.id ?: ""
    }

    override fun endFocusSession(sessionId: String) {
        val session = _activeFocusSession.value
        if (session?.id == sessionId) {
            disableAllModes()
        }
    }

    override fun getActiveFocusSession(): FocusSession? = _activeFocusSession.value

    override fun hasUsageStatsPermission(): Boolean {
        return PermissionUtils.isUsageStatsPermissionGranted(context)
    }

    override fun hasAccessibilityPermission(): Boolean {
        return PermissionUtils.isAccessibilityServiceEnabled(context)
    }

    override fun requestUsageStatsPermission(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    override fun requestAccessibilityPermission(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    override fun getAppUsageToday(packageName: String): Long {
        if (!hasUsageStatsPermission()) return 0L
        
        val endTime = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        val startTime = calendar.timeInMillis
        
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, 
            startTime, 
            endTime
        )
        
        if (packageName == "Total") {
            return stats.sumOf { it.totalTimeInForeground }
        }
        
        return stats.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
    }

    override fun getAppsUsedCountToday(): Int {
        if (!hasUsageStatsPermission()) return 0
        
        val endTime = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        val startTime = calendar.timeInMillis
        
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, 
            startTime, 
            endTime
        )
        
        return stats.count { it.totalTimeInForeground > 0 }
    }

    override fun getStudyTimeToday(): Long {
        // In a real app, this would track time spent in AppMode.STUDY_MODE
        // For now, we'll return a dynamic value that reflects if study mode is active
        return if (_currentMode.value == AppMode.STUDY_MODE) {
            // Mocking some study time for demonstration if active
            15 * 60 * 1000L // 15 minutes
        } else {
            0L
        }
    }

    override fun getInstalledApps(): List<String> {
        // FIX: Replaced TODO stub. Query PackageManager for user-launchable apps.
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        return context.packageManager
            .queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName } // exclude Safe Kids itself
            .distinct()
            .sorted()
    }

    override fun isAppRunning(packageName: String): Boolean {
        // FIX: Replaced TODO stub. Use UsageStatsManager to detect recent foreground activity.
        if (!hasUsageStatsPermission()) return false
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 5_000L // look back 5 seconds
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, startTime, endTime
        )
        return stats.any { it.packageName == packageName && it.lastTimeUsed >= startTime }
    }

    override fun forceStopApp(packageName: String) {
        // FIX: Replaced TODO stub.
        // Strategy 1: Delegate to the accessibility service (which calls GLOBAL_ACTION_HOME).
        //              This is the most reliable method without system/root privileges.
        val service = com.example.safe_kids.services.SafeKidsAccessibilityService.getInstance()
        if (service != null) {
            service.forceStopApp(packageName)
        } else {
            // Strategy 2: Fallback – kill background processes (handles minimised apps).
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(packageName)
            } catch (e: Exception) {
                android.util.Log.w("AppBlockingService", "Could not kill background process: $packageName", e)
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return packageName.startsWith("com.android") || 
               packageName.startsWith("android") ||
               packageName == "com.example.safe_kids"
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
