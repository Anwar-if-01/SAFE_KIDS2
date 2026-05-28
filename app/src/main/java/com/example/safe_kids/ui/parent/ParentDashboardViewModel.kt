package com.example.safe_kids.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.data.DeviceRepository
import com.example.safe_kids.data.ChildDeviceStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.example.safe_kids.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import com.example.safe_kids.ui.components.DailyUsage
import com.example.safe_kids.ui.components.AppUsageInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlinx.coroutines.tasks.await
import androidx.compose.ui.graphics.Color

data class DashboardSummary(
    val screenTimeMillis: Long = 0L,
    val notificationCount: Int = 0,
    val appsUsedCount: Int = 0,
    val safetyStatus: String = "Safe",
    val permissionIssues: Int = 0
)

class ParentDashboardViewModel : ViewModel() {
    private val deviceRepository = DeviceRepository()
    private val firestore = FirebaseFirestore.getInstance()
    private var statusJob: Job? = null
    private var summaryJob: Job? = null
    private var locationJob: Job? = null

    private val _deviceStatus = MutableStateFlow(ChildDeviceStatus())
    val deviceStatus: StateFlow<ChildDeviceStatus> = _deviceStatus.asStateFlow()

    private val _dashboardSummary = MutableStateFlow(DashboardSummary())
    val dashboardSummary: StateFlow<DashboardSummary> = _dashboardSummary.asStateFlow()

    private val _childLocation = MutableStateFlow<com.example.safe_kids.data.LocationData?>(null)
    val childLocation: StateFlow<com.example.safe_kids.data.LocationData?> = _childLocation.asStateFlow()

    private val _isChildOffline = MutableStateFlow(false)
    val isChildOffline: StateFlow<Boolean> = _isChildOffline.asStateFlow()

    private val _weeklyUsage = MutableStateFlow<List<DailyUsage>>(emptyList())
    val weeklyUsage: StateFlow<List<DailyUsage>> = _weeklyUsage.asStateFlow()

    private val _topApps = MutableStateFlow<List<AppUsageInfo>>(emptyList())
    val topApps: StateFlow<List<AppUsageInfo>> = _topApps.asStateFlow()
    
    private val _screenshotUrl = MutableStateFlow<String?>(null)
    val screenshotUrl: StateFlow<String?> = _screenshotUrl.asStateFlow()
    
    private val _isRequestingScreenshot = MutableStateFlow(false)
    val isRequestingScreenshot: StateFlow<Boolean> = _isRequestingScreenshot.asStateFlow()

    private var screenshotJob: Job? = null
    private var requestJob: Job? = null
    private var studyModeJob: Job? = null
    private var sleepModeJob: Job? = null
    private var sleepEmergencyJob: Job? = null
    
    private val listeners = mutableListOf<ListenerRegistration>()
    private var currentChildId: String? = null

    private val _pendingScreenTimeRequest = MutableStateFlow<Int>(0)
    val pendingScreenTimeRequest: StateFlow<Int> = _pendingScreenTimeRequest.asStateFlow()

    private val _studyModeConfig = MutableStateFlow(com.example.safe_kids.data.StudyModeConfig())
    val studyModeConfig: StateFlow<com.example.safe_kids.data.StudyModeConfig> = _studyModeConfig.asStateFlow()

    private val _sleepModeConfig = MutableStateFlow(com.example.safe_kids.data.SleepModeConfig())
    val sleepModeConfig: StateFlow<com.example.safe_kids.data.SleepModeConfig> = _sleepModeConfig.asStateFlow()

    private val _hasSleepEmergency = MutableStateFlow(false)
    val hasSleepEmergency: StateFlow<Boolean> = _hasSleepEmergency.asStateFlow()

    // NEW: Top 3 apps today for Today's Event card
    private val _todayTopApps = MutableStateFlow<List<AppUsageInfo>>(emptyList())
    val todayTopApps: StateFlow<List<AppUsageInfo>> = _todayTopApps.asStateFlow()

    // NEW: Last active formatted string
    private val _lastActiveFormatted = MutableStateFlow("Never")
    val lastActiveFormatted: StateFlow<String> = _lastActiveFormatted.asStateFlow()

    fun selectChild(childId: String) {
        if (currentChildId == childId) return
        currentChildId = childId

        listeners.forEach { it.remove() }
        listeners.clear()

        // Device Status Listener
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            deviceRepository.getDeviceStatusFlow(childId)
                .catch { e ->
                    android.util.Log.e("ParentDashboardVM", "Error observing device status", e)
                }
                .collect { status ->
                    _deviceStatus.value = status
                    
                    val lastSeen = status.lastActive
                    val isOnline = status.isOnline
                    
                    _lastActiveFormatted.value = when {
                        lastSeen == 0L -> "Offline"
                        isOnline && (System.currentTimeMillis() - lastSeen) <= 2 * 60 * 1000L -> "Online Now"
                        (System.currentTimeMillis() - lastSeen) <= 60 * 60 * 1000L -> {
                            val mins = ((System.currentTimeMillis() - lastSeen) / (60 * 1000)).toInt()
                            if (mins <= 1) "Just now" else "$mins minutes ago"
                        }
                        isSameDay(lastSeen, System.currentTimeMillis()) -> {
                            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                            "Today at ${sdf.format(Date(lastSeen))}"
                        }
                        else -> {
                            val days = ((System.currentTimeMillis() - lastSeen) / (24 * 60 * 60 * 1000)).toInt()
                            if (days == 1) "Yesterday" else "Last seen ${days} days ago"
                        }
                    }
                }
        }

        // Screen Time Request Listener
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            requestJob?.cancel()
            requestJob = viewModelScope.launch {
                val registration = firestore.collection("screen_time_requests")
                    .document(currentUser.uid)
                    .collection("requests")
                    .whereEqualTo("childId", childId)
                    .whereEqualTo("status", "PENDING")
                    .addSnapshotListener { snapshot, e ->
                        if (e != null || snapshot == null) {
                            _pendingScreenTimeRequest.value = 0
                            return@addSnapshotListener
                        }
                        _pendingScreenTimeRequest.value = snapshot.size()
                    }
                listeners.add(registration)
            }
        }

        studyModeJob?.cancel()
        studyModeJob = viewModelScope.launch {
                val registration = firestore.collection("study_mode_configs")
                .document(childId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        _studyModeConfig.value = snapshot.toStudyModeConfigCompat()
                    }
                }
            listeners.add(registration)
        }

        sleepModeJob?.cancel()
        sleepModeJob = viewModelScope.launch {
                val registration = firestore.collection("sleep_mode_configs")
                .document(childId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        _sleepModeConfig.value = snapshot.toSleepModeConfigCompat()
                    }
                }
            listeners.add(registration)
        }

        sleepEmergencyJob?.cancel()
        sleepEmergencyJob = viewModelScope.launch {
            val registration = firestore.collection("sleep_mode_emergency")
                .document(currentUser?.uid ?: "")
                .collection("events")
                .whereEqualTo("childId", childId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && !snapshot.isEmpty) {
                        val last24h = System.currentTimeMillis() - 86400000
                        _hasSleepEmergency.value = snapshot.documents.any { (it.getLong("timestamp") ?: 0) > last24h }
                    } else {
                        _hasSleepEmergency.value = false
                    }
                }
            listeners.add(registration)
        }

        // FIX 1,2,3,4,6,7: Summary listener that reads from children/{childId}/usageStats
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            val deviceRepo = deviceRepository
            // Listen to children/{childId}/usageStats real-time
            val usageRegistration = firestore
                .collection("children")
                .document(childId)
                .collection("usageStats")
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null) return@addSnapshotListener
                    
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    var totalScreenTimeMs = 0L
                    val appsSet = mutableSetOf<String>()
                    val appsList = mutableListOf<AppUsageInfo>()
                    
                    for (doc in snapshot.documents) {
                        val pkg = doc.getString("packageName") ?: doc.id
                        val date = doc.getString("date") ?: ""
                        val dailyMs = doc.getLong("dailyUsageMs") ?: 0L
                        
                        if (date == today) {
                            totalScreenTimeMs += dailyMs
                            appsSet.add(pkg)
                            val appName = doc.getString("appName") ?: pkg
                            appsList.add(AppUsageInfo(
                                appName = appName,
                                packageName = pkg,
                                durationMillis = dailyMs,
                                color = Color.Gray
                            ))
                        }
                    }
                    
                    // Top 3 apps for Today's Event (sorted desc)
                    val top3 = appsList.filter { it.durationMillis > 0 }
                        .sortedByDescending { it.durationMillis }
                        .take(3)
                    _todayTopApps.value = top3
                    
                    // Dashboard summary
                    _dashboardSummary.value = _dashboardSummary.value.copy(
                        screenTimeMillis = totalScreenTimeMs,
                        appsUsedCount = appsSet.size
                    )
                }
            listeners.add(usageRegistration)
        }

        // FIX 3: Notification count listener
        viewModelScope.launch {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val notifRegistration = firestore.collection("child_notifications")
                .whereEqualTo("childId", childId)
                .whereEqualTo("date", today)
                .addSnapshotListener { snapshot, e ->
                    if (snapshot != null) {
                        _dashboardSummary.value = _dashboardSummary.value.copy(
                            notificationCount = snapshot.size()
                        )
                    }
                }
            listeners.add(notifRegistration)
        }



        // FIX 4: Weekly usage from Firestore
        fetchWeeklyUsage(childId)

        // Location Listener
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            deviceRepository.getLocationFlow(childId)
                .catch { e ->
                    android.util.Log.e("ParentDashboardVM", "Error observing location", e)
                }
                .collect { location ->
                    _childLocation.value = location
                    _isChildOffline.value = location == null || (System.currentTimeMillis() - location.timestamp) > 60_000
                }
        }
        
        // Screenshot Listener
        screenshotJob?.cancel()
        screenshotJob = viewModelScope.launch {
            val registration = firestore.collection("screenshots")
                .document(childId)
                .collection("latest")
                .document("result")
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    
                    val url = snapshot.getString("url")
                    val error = snapshot.getString("error")
                    val timestamp = snapshot.getLong("timestamp") ?: 0L
                    
                    if (error != null && _isRequestingScreenshot.value) {
                        _isRequestingScreenshot.value = false
                    } else if (url != null) {
                        if (_isRequestingScreenshot.value || (System.currentTimeMillis() - timestamp < 60_000)) {
                            _screenshotUrl.value = url
                            _isRequestingScreenshot.value = false
                        }
                    }
                }
            listeners.add(registration)
        }

        // Periodic offline check
        viewModelScope.launch {
            while (isActive) {
                delay(30000)
                val currentStatus = _deviceStatus.value
                if (currentStatus.lastActive > 0) {
                    val isOffline = (System.currentTimeMillis() - currentStatus.lastActive) > 60_000
                    if (currentStatus.isOnline == isOffline) {
                         _deviceStatus.value = currentStatus.copy(isOnline = !isOffline)
                    }
                }
                val loc = _childLocation.value
                if (loc != null) {
                    _isChildOffline.value = (System.currentTimeMillis() - loc.timestamp) > 90_000
                }
            }
        }
    }

    private fun fetchWeeklyUsage(childId: String) {
        viewModelScope.launch {
            val dayFormatter = SimpleDateFormat("EEE", Locale.getDefault())
            val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val registration = firestore.collection("children")
                .document(childId)
                .collection("usageStats")
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null) return@addSnapshotListener

                    val daysToFetch = 7
                    val dailyTotals = mutableMapOf<String, Long>()

                    for (doc in snapshot.documents) {
                        val date = doc.getString("date") ?: continue
                        val durationMs = doc.getLong("dailyUsageMs") ?: 0L
                        dailyTotals[date] = (dailyTotals[date] ?: 0L) + durationMs
                    }

                    val results = (0 until daysToFetch).map { i ->
                        val dayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                        val dateStr = dateFormatter.format(dayCal.time)
                        val dayName = dayFormatter.format(dayCal.time)
                        val totalMs = dailyTotals[dateStr] ?: 0L
                        DailyUsage(dayName, totalMs / (1000f * 60 * 60))
                    }.reversed()

                    _weeklyUsage.value = results
                }
            listeners.add(registration)
        }
    }

    private fun getScreenTimeFlow(childId: String): Flow<Pair<Long, Int>> = callbackFlow {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val registration = firestore.collection("children")
            .document(childId)
            .collection("usageStats")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    trySend(Pair(0L, 0))
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    var total = 0L
                    val apps = mutableSetOf<String>()
                    snapshot.documents.forEach { doc ->
                        val date = doc.getString("date") ?: ""
                        if (date == today) {
                            total += doc.getLong("dailyUsageMs") ?: 0L
                            val pkg = doc.getString("packageName") ?: doc.id
                            if (pkg != "_summary") apps.add(pkg)
                        }
                    }
                    trySend(Pair(total, apps.size))
                }
            }
        awaitClose { registration.remove() }
    }

    private fun getNotificationCountFlow(childId: String): Flow<Int> = callbackFlow {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val registration = firestore.collection("child_notifications")
            .whereEqualTo("childId", childId)
            .whereEqualTo("date", today)
            .addSnapshotListener { snapshot, e ->
                if (snapshot != null) {
                    trySend(snapshot.size())
                }
            }
        awaitClose { registration.remove() }
    }

    private fun getPermissionIssueCountFlow(childId: String): Flow<Int> = callbackFlow {
        val listener = firestore.collection("device_status").document(childId)
            .addSnapshotListener { snapshot, _ ->
                val count = if (snapshot != null && snapshot.exists()) {
                    var issues = 0
                    if (snapshot.getBoolean("locationEnabled") == false) issues++
                    if (snapshot.getBoolean("notificationsEnabled") == false) issues++
                    if (snapshot.getBoolean("accessibilityEnabled") == false) issues++
                    issues
                } else 0
                trySend(count)
            }
        awaitClose { listener.remove() }
    }

    private fun isSameDay(ts1: Long, ts2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = ts1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = ts2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
    
    fun requestScreenshot(childId: String, parentId: String) {
        _isRequestingScreenshot.value = true
        _screenshotUrl.value = null
        
        viewModelScope.launch {
            com.example.safe_kids.auth.ParentActionsManager(firestore).sendCommand(
                childId, parentId, com.example.safe_kids.data.CommandType.TAKE_SCREENSHOT
            )
            
            delay(30_000)
            if (_isRequestingScreenshot.value) {
                _isRequestingScreenshot.value = false
            }
        }
    }
    
    fun dismissScreenshot() {
        _screenshotUrl.value = null
    }

    override fun onCleared() {
        super.onCleared()
        listeners.forEach { it.remove() }
        listeners.clear()
    }
}
