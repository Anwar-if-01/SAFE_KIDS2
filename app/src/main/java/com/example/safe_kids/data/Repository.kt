package com.example.safe_kids.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.safe_kids.services.blocking.AppMode

// Enhanced data models
data class ChildProfile(
    val id: String = "",
    val name: String = "",
    val age: Int = 0,
    val parentIds: List<String> = emptyList(), // Support for multiple parents
    // ---- Profile display fields (synced from User document) ----
    val profileImageUrl: String? = null,  // Firebase Storage URL or avatar identifier
    val deviceName: String? = null,
    // ---- Restriction / schedule fields ----
    val restrictions: List<AppRestriction> = emptyList(),
    val geofences: List<GeofenceArea> = emptyList(),
    val allowedContacts: List<String> = emptyList(),
    val emergencyContacts: List<EmergencyContact> = emptyList(),
    val schoolSchedule: List<ScheduleItem> = emptyList(),
    val bedtime: TimeRange? = null,
    val studyTime: List<TimeRange> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val activeMode: AppMode = AppMode.NORMAL,
    val downtimeConfig: DowntimeConfig = DowntimeConfig(),
    val appCategories: Map<String, AppCategory> = emptyMap() // packageName -> category
)

enum class AppCategory {
    EDUCATIONAL,
    SOCIAL,
    GAMES,
    ENTERTAINMENT,
    UTILITIES,
    EMERGENCY,
    SYSTEM,
    UNDETERMINED
}

data class AppRestriction(
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean,
    val timeLimit: Long? = null, // daily limit in milliseconds
    val allowedTimes: List<TimeRange> = emptyList(),
    val blockReason: String? = null,
    val customRules: List<String> = emptyList(),
    val remark: String? = null,
    val limitType: String = "INDIVIDUAL", // INDIVIDUAL, TOTAL, DOWNTIME
    val isGeofenceEnabled: Boolean = false,
    val geofenceId: String? = null
)

data class TimeRange(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: List<Int> = (1..7).toList() // 1=Monday, 7=Sunday
)

data class DowntimeSchedule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val timeRange: TimeRange = TimeRange(22, 0, 7, 0),
    val isEnabled: Boolean = true
)

data class DowntimeConfig(
    val isEnabled: Boolean = false,
    val schedules: List<DowntimeSchedule> = emptyList(),
    val alwaysAllowedApps: List<String> = listOf("com.android.dialer", "com.google.android.dialer")
)


data class GeofenceArea(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val type: GeofenceType,
    val isActive: Boolean = true,
    val alertOnEnter: Boolean = true,
    val alertOnExit: Boolean = true
)

enum class GeofenceType {
    SAFE_ZONE,
    RESTRICTED_ZONE,
    SCHOOL,
    HOME,
    CUSTOM
}

data class EmergencyContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val relationship: String,
    val isPrimary: Boolean = false
)

data class ScheduleItem(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val type: ScheduleType,
    val location: String? = null,
    val isRecurring: Boolean = false,
    val recurringDays: List<Int> = emptyList()
)

enum class ScheduleType {
    SCHOOL,
    STUDY_TIME,
    BEDTIME,
    MEAL_TIME,
    ACTIVITY,
    APPOINTMENT
}

data class ActivityLog(
    val id: String,
    val userId: String,
    val timestamp: Long,
    val activityType: ActivityType,
    val details: Map<String, String> = emptyMap(),
    val location: LocationData? = null,
    val duration: Long? = null
)

enum class ActivityType {
    APP_INSTALLED,
    APP_UNINSTALLED,
    APP_USAGE,
    LOCATION_UPDATE,
    SCREEN_TIME,
    BLOCKED_ATTEMPT,
    EMERGENCY_ALERT,
    FOCUS_SESSION,
    STUDY_SESSION,
    CONTENT_ANALYSIS
}

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float,
    val address: String? = null
)

data class AppUsageStats(
    val packageName: String,
    val appName: String,
    val totalTime: Long, // in milliseconds
    val openCount: Int,
    val lastUsed: Long,
    val date: String, // YYYY-MM-DD format
    val categoryTime: Map<String, Long> = emptyMap() // category -> time spent
)

data class InstalledApp(
    val packageName: String = "",
    val appName: String = "",
    val category: String = "Other",
    val installTime: Long = 0L,
    val isSystemApp: Boolean = false,
    val iconBase64: String? = null
)


data class WellbeingInsights(
    val userId: String,
    val date: String,
    val totalScreenTime: Long,
    val mostUsedApps: List<String>,
    val focusScore: Float, // 0.0 to 1.0
    val studyTime: Long,
    val physicalActivityTime: Long,
    val sleepQuality: Float,
    val recommendations: List<String>
)

data class PairingRequest(
    val id: String,
    val code: String,
    val parentId: String,
    val parentName: String,
    val childId: String? = null,
    val status: PairingStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val usedAt: Long? = null
)

enum class PairingStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    USED
}

data class ChildCommand(
    val id: String = java.util.UUID.randomUUID().toString(),
    val childId: String = "",
    val parentId: String = "",
    val type: CommandType = CommandType.INSTANT_BLOCK,
    val payload: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: CommandStatus = CommandStatus.PENDING
)

enum class CommandType {
    INSTANT_BLOCK,
    UNBLOCK,
    TAKE_SCREENSHOT,
    START_AUDIO_MONITOR,
    STOP_AUDIO_MONITOR,
    UPDATE_TIME_LIMIT,
    DISABLE_SCHEDULE,
    ACTIVATE_STUDY_MODE,
    DEACTIVATE_STUDY_MODE,
    ACTIVATE_SLEEP_MODE,
    DEACTIVATE_SLEEP_MODE,
    UPDATE_GEOFENCES,
    UPDATE_APP_CATEGORIES,
    LOCK_DEVICE,
    UNLOCK_DEVICE,
    REQUEST_LOCATION,
    SEND_REMINDER
}

enum class CommandStatus {
    PENDING,
    EXECUTED,
    FAILED
}

data class GlobalScreenTimeConfig(
    val childId: String = "",
    val dailyLimitMinutes: Int = 120,
    val extendedMinutes: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val lastGrantDate: String = ""
)

data class ScreenTimeRequest(
    val id: String = java.util.UUID.randomUUID().toString(),
    val childId: String = "",
    val requestedMinutes: Int = 15, // standard request size
    val status: RequestStatus = RequestStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)

enum class RequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    DENIED
}

data class StudyModeConfig(
    val isEnabled: Boolean = false,
    val startTime: String = "09:00", // HH:mm
    val endTime: String = "10:00",
    val activeDays: List<Int> = listOf(1, 2, 3, 4, 5), // Mon-Fri
    val allowedApps: List<String> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class StudySession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val date: String = "",
    val startTime: Long = 0,
    val endTime: Long = 0,
    val durationMinutes: Int = 0,
    val appsUsed: List<String> = emptyList()
)

data class SleepModeConfig(
    val isEnabled: Boolean = false,
    val startTime: String = "21:00", // HH:mm
    val endTime: String = "07:00",
    val activeDays: List<Int> = (1..7).toList(),
    val suppressNotifications: Boolean = true,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class SleepSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val date: String = "",
    val startTime: Long = 0,
    val endTime: Long = 0,
    val durationMinutes: Int = 0,
    val emergencyUsageMinutes: Int = 0
)

// Repository interfaces
interface UserRepository {
    suspend fun saveUser(user: com.example.safe_kids.auth.User)
    suspend fun getUser(userId: String): com.example.safe_kids.auth.User?
    suspend fun updateUser(user: com.example.safe_kids.auth.User)
    suspend fun deleteUser(userId: String)
    fun getCurrentUser(): Flow<com.example.safe_kids.auth.User?>
    suspend fun getUsersByRole(role: com.example.safe_kids.auth.UserRole): List<com.example.safe_kids.auth.User>
}

interface ChildProfileRepository {
    suspend fun saveChildProfile(profile: ChildProfile)
    suspend fun getChildProfile(childId: String): ChildProfile?
    suspend fun updateChildProfile(profile: ChildProfile)
    suspend fun deleteChildProfile(childId: String)
    suspend fun getChildrenByParent(parentId: String): List<ChildProfile>
    fun getChildProfileFlow(childId: String): Flow<ChildProfile?>
    suspend fun linkChildToParent(childId: String, parentId: String)
    suspend fun unlinkChildFromParent(childId: String, parentId: String)
}

interface AppUsageRepository {
    suspend fun saveAppUsage(usage: AppUsageStats)
    suspend fun getAppUsage(packageName: String, startTime: Long, endTime: Long): Long
    suspend fun getAppUsageStats(userId: String, date: String): List<AppUsageStats>
    suspend fun getDailyScreenTime(userId: String, date: String): Long
    suspend fun getWeeklyScreenTime(userId: String, startDate: String): Map<String, Long>
    fun getAppUsageFlow(packageName: String): Flow<Long>
    suspend fun getMostUsedApps(userId: String, limit: Int): List<AppUsageStats>
}

interface ActivityLogRepository {
    suspend fun logActivity(activity: ActivityLog)
    suspend fun getActivities(userId: String, startTime: Long, endTime: Long): List<ActivityLog>
    suspend fun getActivitiesByType(userId: String, type: ActivityType, limit: Int): List<ActivityLog>
    fun getActivitiesFlow(userId: String): Flow<List<ActivityLog>>
    suspend fun deleteOldActivities(olderThan: Long)
}

interface LocationRepository {
    suspend fun saveLocation(userId: String, location: LocationData)
    suspend fun getLocationHistory(userId: String, startTime: Long, endTime: Long): List<LocationData>
    suspend fun getLastKnownLocation(userId: String): LocationData?
    fun getLocationFlow(userId: String): Flow<LocationData?>
    suspend fun saveGeofence(userId: String, geofence: GeofenceArea)
    suspend fun getGeofences(userId: String): List<GeofenceArea>
    suspend fun deleteGeofence(geofenceId: String)
}

interface WellbeingRepository {
    suspend fun saveWellbeingInsights(insights: WellbeingInsights)
    suspend fun getWellbeingInsights(userId: String, date: String): WellbeingInsights?
    suspend fun getWellbeingTrends(userId: String, days: Int): List<WellbeingInsights>
    fun getWellbeingFlow(userId: String): Flow<WellbeingInsights?>
    suspend fun generateDailyInsights(userId: String, date: String): WellbeingInsights
}

interface PairingRepository {
    suspend fun createPairingRequest(request: PairingRequest)
    suspend fun getPairingRequest(code: String): PairingRequest?
    suspend fun updatePairingRequest(request: PairingRequest)
    suspend fun deletePairingRequest(requestId: String)
    suspend fun getActivePairingRequests(parentId: String): List<PairingRequest>
    suspend fun expireOldRequests()
}

// Firebase implementations (interfaces for now)
interface FirebaseUserRepository : UserRepository
interface FirebaseChildProfileRepository : ChildProfileRepository
interface FirebaseAppUsageRepository : AppUsageRepository
interface FirebaseActivityLogRepository : ActivityLogRepository
interface FirebaseLocationRepository : LocationRepository
interface FirebaseWellbeingRepository : WellbeingRepository
interface FirebasePairingRepository : PairingRepository

// Local database implementations (interfaces for now)
interface LocalUserRepository : UserRepository
interface LocalAppUsageRepository : AppUsageRepository
interface LocalActivityLogRepository : ActivityLogRepository
interface LocalLocationRepository : LocationRepository

// Main repository combining Firebase and local DB
class SafeKidsRepository(
    private val userRepository: UserRepository,
    private val childProfileRepository: ChildProfileRepository,
    private val appUsageRepository: AppUsageRepository,
    private val activityLogRepository: ActivityLogRepository,
    private val locationRepository: LocationRepository,
    private val wellbeingRepository: WellbeingRepository,
    private val pairingRepository: PairingRepository
) {
    // User operations
    suspend fun saveUser(user: com.example.safe_kids.auth.User) = userRepository.saveUser(user)
    suspend fun getUser(userId: String) = userRepository.getUser(userId)
    suspend fun updateUser(user: com.example.safe_kids.auth.User) = userRepository.updateUser(user)
    fun getCurrentUser() = userRepository.getCurrentUser()

    // Child profile operations
    suspend fun saveChildProfile(profile: ChildProfile) = childProfileRepository.saveChildProfile(profile)
    suspend fun getChildProfile(childId: String) = childProfileRepository.getChildProfile(childId)
    suspend fun updateChildProfile(profile: ChildProfile) = childProfileRepository.updateChildProfile(profile)
    suspend fun getChildrenByParent(parentId: String) = childProfileRepository.getChildrenByParent(parentId)
    suspend fun linkChildToParent(childId: String, parentId: String) = 
        childProfileRepository.linkChildToParent(childId, parentId)

    // App usage operations
    suspend fun saveAppUsage(usage: AppUsageStats) = appUsageRepository.saveAppUsage(usage)
    suspend fun getAppUsageStats(userId: String, date: String) = appUsageRepository.getAppUsageStats(userId, date)
    suspend fun getDailyScreenTime(userId: String, date: String) = appUsageRepository.getDailyScreenTime(userId, date)
    suspend fun getMostUsedApps(userId: String, limit: Int) = appUsageRepository.getMostUsedApps(userId, limit)

    // Activity logging
    suspend fun logActivity(activity: ActivityLog) = activityLogRepository.logActivity(activity)
    suspend fun getActivities(userId: String, startTime: Long, endTime: Long) = 
        activityLogRepository.getActivities(userId, startTime, endTime)
    suspend fun getActivitiesByType(userId: String, type: ActivityType, limit: Int) = 
        activityLogRepository.getActivitiesByType(userId, type, limit)

    // Location operations
    suspend fun saveLocation(userId: String, location: LocationData) = 
        locationRepository.saveLocation(userId, location)
    suspend fun getLocationHistory(userId: String, startTime: Long, endTime: Long) = 
        locationRepository.getLocationHistory(userId, startTime, endTime)
    suspend fun getLastKnownLocation(userId: String) = locationRepository.getLastKnownLocation(userId)
    suspend fun saveGeofence(userId: String, geofence: GeofenceArea) = 
        locationRepository.saveGeofence(userId, geofence)
    suspend fun getGeofences(userId: String) = locationRepository.getGeofences(userId)

    // Wellbeing insights
    suspend fun getWellbeingInsights(userId: String, date: String) = 
        wellbeingRepository.getWellbeingInsights(userId, date)
    suspend fun generateDailyInsights(userId: String, date: String) = 
        wellbeingRepository.generateDailyInsights(userId, date)
    suspend fun getWellbeingTrends(userId: String, days: Int) = 
        wellbeingRepository.getWellbeingTrends(userId, days)

    // Pairing operations
    suspend fun createPairingRequest(request: PairingRequest) = pairingRepository.createPairingRequest(request)
    suspend fun getPairingRequest(code: String) = pairingRepository.getPairingRequest(code)
    suspend fun updatePairingRequest(request: PairingRequest) = pairingRepository.updatePairingRequest(request)
    suspend fun getActivePairingRequests(parentId: String) = pairingRepository.getActivePairingRequests(parentId)

    // Utility functions
    suspend fun syncWithFirebase() {
        // TODO: Implement sync between local and Firebase
    }

    suspend fun clearLocalData() {
        // TODO: Clear local database
    }

    suspend fun exportUserData(userId: String): Map<String, Any> {
        // TODO: Export all user data for backup/transfer
        return emptyMap()
    }

    suspend fun importUserData(data: Map<String, Any>) {
        // TODO: Import user data from backup
    }
}
