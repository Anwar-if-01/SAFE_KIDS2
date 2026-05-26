package com.example.safe_kids.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.example.safe_kids.auth.AuthConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.database.FirebaseDatabase
import android.util.Log
import android.content.Context
import java.util.UUID
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar
import com.example.safe_kids.services.CallLogReader
import com.example.safe_kids.services.SmsReader

class ForegroundProtectionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "safe_kids_protection"
        private const val CHANNEL_NAME = "Safe Kids Protection"

        @Volatile
        var isGlobalLockoutActive: Boolean = false
            private set

        fun forceReleaseLockout() {
            isGlobalLockoutActive = false
            android.util.Log.d("ScreenTimeDebug", "forceReleaseLockout called — lockout released immediately")
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var commandListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var instantBlockListener: com.google.firebase.firestore.ListenerRegistration? = null
    private val TAG = "ProtectionService"

    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        try {
            val notification = createNotification()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception launching foreground service: ${e.message}")
            stopSelf()
            return
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Cannot start foreground service from background: ${e.message}")
                stopSelf()
                return
            }
            Log.e(TAG, "Unexpected error in startForeground: ${e.message}", e)
            stopSelf()
            return
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        // Persistent listeners initialization
        authStateListener = FirebaseAuth.AuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null) {
                Log.d(TAG, "User authenticated in service: ${user.uid}. Starting listeners.")
                startBackgroundTasks()
            } else {
            Log.w(TAG, "No user session in service. Listeners paused.")
                tasksStarted = false
                instantBlockListener?.remove()
                instantBlockListener = null
        }
        }
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener!!)

        scheduleAutoRestart()
    }

    private var tasksStarted = false
    private fun startBackgroundTasks() {
        if (tasksStarted) return
        tasksStarted = true
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                syncServerTime()
                startCommandListener()
                startInstantBlockListener()
                startPermissionMonitor()
                startPermissionPolling()
                startStatusSync()
                startPresenceTracker()
                startUsageMonitoring()
                startPeriodicSessionValidation()
                
                val aiService = com.example.safe_kids.services.ai.AIContentAnalysisServiceImpl(this@ForegroundProtectionService)
                aiService.startContentMonitoring()
                // Periodic usage sync to Firestore every 5 minutes while service runs
                startPeriodicUsageSync()
                startCallLogSync() // Start periodic call log sync
                startSmsLogSync() // Start periodic SMS log sync
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init background components", e)
                tasksStarted = false // Allow retry on failure
            }
        }
        syncHandler.postDelayed(instantBlockSyncRunnable, 30000)
    }

    private var periodicSmsLogSyncJob: Job? = null
    private fun startSmsLogSync() {
        periodicSmsLogSyncJob?.cancel()
        periodicSmsLogSyncJob = serviceScope.launch {
            val smsReader = SmsReader(this@ForegroundProtectionService)
            val authManager = com.example.safe_kids.auth.AuthManagerImpl(applicationContext)
            authManager.initialize()
            val childId = authManager.getCurrentUser()?.id ?: return@launch

            while (isActive) {
                try {
                    smsReader.syncSmsLogs(childId)
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic SMS log sync failed", e)
                }
                delay(30 * 60 * 1000L) // 30 minutes
            }
        }
    }



    private var periodicUsageSyncJob: Job? = null
    private fun startPeriodicUsageSync() {
        periodicUsageSyncJob?.cancel()
        periodicUsageSyncJob = serviceScope.launch {
            val usageRepository = com.example.safe_kids.data.UsageRepository(this@ForegroundProtectionService)
            val authManager = com.example.safe_kids.auth.AuthManagerImpl(applicationContext)
            authManager.initialize()
            val childId = authManager.getCurrentUser()?.id ?: return@launch

            while (isActive) {
                try {
                    val result = usageRepository.syncUsageToFirestore(childId)
                    if (result.isSuccess) {
                        Log.d(TAG, "Periodic usage sync succeeded")
                    } else {
                        Log.w(TAG, "Periodic usage sync returned failure")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic usage sync failed", e)
                }
                delay(5 * 60 * 1000L) // 5 minutes
            }
        }
    }

    private var periodicCallLogSyncJob: Job? = null
    private fun startCallLogSync() {
        periodicCallLogSyncJob?.cancel()
        periodicCallLogSyncJob = serviceScope.launch {
            val callLogReader = CallLogReader(this@ForegroundProtectionService)
            val authManager = com.example.safe_kids.auth.AuthManagerImpl(applicationContext)
            authManager.initialize()
            val childId = authManager.getCurrentUser()?.id ?: return@launch

            while (isActive) {
                try {
                    callLogReader.syncCallLogs(childId)
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic call log sync failed", e)
                }
                delay(30 * 60 * 1000L) // 30 minutes
            }
        }
    }

    private fun startPeriodicSessionValidation() {
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15 * 1000) // 15 sec for testing, change to 2 min for production
                try {
                    kotlinx.coroutines.withTimeout(10000) {
                        val tokenResult = FirebaseAuth.getInstance()
                            .currentUser
                            ?.getIdToken(true)
                            ?.await()

                        if (tokenResult?.token == null) {
                            com.example.safe_kids.utils.AuthUtils.forceSignOut(applicationContext)
                        }
                    }
                } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                    com.example.safe_kids.utils.AuthUtils.forceSignOut(applicationContext)
                } catch (e: Exception) {
                    // offline - ignore
                }
            }
        }
    }

    private fun startPermissionPolling() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            var lastUsageStatus = com.example.safe_kids.utils.PermissionUtils.isUsageStatsPermissionGranted(this@ForegroundProtectionService)
            var lastAccessibilityStatus = com.example.safe_kids.utils.PermissionUtils.isAccessibilityServiceEnabled(this@ForegroundProtectionService)
            
            Log.d(TAG, "Starting permission polling. Initial Usage: $lastUsageStatus, Accessibility: $lastAccessibilityStatus")
            
            // Check Usage Access permission on startup
            if (!lastUsageStatus) {
                Log.w(TAG, "Usage Access permission NOT granted. Sending notification to enable it.")
                sendUsageAccessNotification()
            }
            
            while (isActive) {
                delay(2000) // Poll every 2 seconds
                
                val currentUsageStatus = com.example.safe_kids.utils.PermissionUtils.isUsageStatsPermissionGranted(this@ForegroundProtectionService)
                val currentAccessibilityStatus = com.example.safe_kids.utils.PermissionUtils.isAccessibilityServiceEnabled(this@ForegroundProtectionService)
                
                // If usage permission was just granted
                if (currentUsageStatus && !lastUsageStatus) {
                    Log.d(TAG, "Usage Stats permission GRANTED. Bringing app to foreground.")
                    bringAppToForeground()
                }
                
                // If accessibility permission was just granted (fallback for service-side launch)
                if (currentAccessibilityStatus && !lastAccessibilityStatus) {
                    Log.d(TAG, "Accessibility permission GRANTED. Bringing app to foreground.")
                    bringAppToForeground()
                }
                
                lastUsageStatus = currentUsageStatus
                lastAccessibilityStatus = currentAccessibilityStatus
            }
        }
    }
    
    private fun sendUsageAccessNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "usage_access_channel",
                "Usage Access Required",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for required permissions"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(intent),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, "usage_access_channel")
            .setContentTitle("Usage Access Required")
            .setContentText("Please enable Usage Access in Settings for app monitoring to work properly")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(2001, notification)
    }

    private fun bringAppToForeground() {
        // Return to the app's main entry point (MainActivity)
        // MainActivity will handle the logic of where to redirect (Onboarding or Dashboard)
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        if (intent != null) {
            startActivity(intent)
            Log.d(TAG, "Permission granted, auto-returning to main entry point")
        }
    }

    private var presenceUpdateJob: Job? = null
    private var realtimePresenceListener: com.google.firebase.database.ValueEventListener? = null

    private fun startPresenceTracker() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val childId = currentUser.uid
        
        // Get the correct Firebase Realtime Database instance with regional URL
        val database = com.example.safe_kids.utils.FirebaseUtils.getRealtimeDatabase()
        
        // Write to Firestore: child_profiles/{childId} with isOnline/lastSeen
        // Write to RTDB: child_presence/{childId} and status/{childId}
        // Also write to Firestore: children/{childId} with isOnline/lastSeen
        val childPresenceRef = database.getReference("child_presence/$childId")
        val statusRef = database.getReference("status/$childId")
        val connectedRef = database.getReference(".info/connected")

        realtimePresenceListener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    val now = System.currentTimeMillis()
                    Log.d("PRESENCE_DEBUG", "Device connected to RTDB. Writing online=true to child_presence and status paths.")

                    // child_presence/{childId}/isOnline = true
                    childPresenceRef.child("isOnline").setValue(true)
                    childPresenceRef.child("lastSeen").setValue(now)
                    // onDisconnect: automatically mark offline when connection drops
                    childPresenceRef.child("isOnline").onDisconnect().setValue(false)
                    childPresenceRef.child("lastSeen").onDisconnect().setValue(System.currentTimeMillis())

                    // status/{childId} = "online" (legacy String path)
                    statusRef.setValue("online")
                    statusRef.onDisconnect().setValue("offline")
                } else {
                    Log.d("PRESENCE_DEBUG", "Device disconnected from RTDB")
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                Log.e(TAG, "Presence listener cancelled", error.toException())
            }
        }
        connectedRef.addValueEventListener(realtimePresenceListener!!)

        // Periodic heartbeat — refreshes lastSeen every 5 minutes so the 90s fallback stays alive
        serviceScope.launch {
            try {
                writePresenceToFirestore(childId, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write initial presence", e)
            }

            while (isActive) {
                delay(5 * 60 * 1000L) // 5 minutes
                try {
                    val now = System.currentTimeMillis()
                    updatePresenceTimestamp(childId)
                    // Refresh RTDB lastSeen so DeviceRepository 90s fallback stays fresh
                    childPresenceRef.child("lastSeen").setValue(now)
                    Log.d("PRESENCE_DEBUG", "Heartbeat: refreshed lastSeen=$now")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update presence timestamp", e)
                }
            }
        }
    }

    private suspend fun writePresenceToFirestore(childId: String, isOnline: Boolean) {
        val statusMonitor = com.example.safe_kids.utils.DeviceStatusMonitor(this@ForegroundProtectionService)
        val status = statusMonitor.getCurrentStatus()
        val now = System.currentTimeMillis()
        
        val presenceData = hashMapOf(
            "isOnline" to isOnline,
            "lastSeen" to now,
            "deviceInfo" to mapOf(
                "batteryLevel" to status.batteryPercentage,
                "wifiConnected" to (status.connectionType == "WiFi")
            )
        )
        val childPresenceData = mapOf(
            "isOnline" to isOnline,
            "lastSeen" to now
        )
        
        val db = FirebaseFirestore.getInstance()
        
        // Write to users/{childId}/presence/current
        db.collection(AuthConfig.USERS_COLLECTION)
            .document(childId)
            .collection("presence")
            .document("current")
            .set(presenceData)
            .await()
        
        // Write to child_profiles/{childId} with isOnline/lastSeen
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION)
            .document(childId)
            .set(childPresenceData, com.google.firebase.firestore.SetOptions.merge())
            .await()
        
        // Write to children/{childId} with isOnline/lastSeen
        db.collection("children")
            .document(childId)
            .set(childPresenceData, com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    private suspend fun updatePresenceTimestamp(childId: String) {
        val now = System.currentTimeMillis()
        val db = FirebaseFirestore.getInstance()
        
        // Update users path
        db.collection(AuthConfig.USERS_COLLECTION)
            .document(childId)
            .collection("presence")
            .document("current")
            .update("lastSeen", now)
            .await()
        
        // Update child_profiles path
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION)
            .document(childId)
            .update("lastSeen", now)
        
        // Update children path
        db.collection("children")
            .document(childId)
            .update("lastSeen", now)
    }

    private suspend fun setOfflinePresence(childId: String) {
        try {
            writePresenceToFirestore(childId, false)
            
            // Also update Realtime Database with correct regional URL
            val database = com.example.safe_kids.utils.FirebaseUtils.getRealtimeDatabase()
            database.getReference("child_presence/$childId/isOnline")
                .setValue(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set offline presence", e)
        }
    }

    private fun startStatusSync() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val deviceRepository = com.example.safe_kids.data.DeviceRepository()
        val statusMonitor = com.example.safe_kids.utils.DeviceStatusMonitor(this)
        
        Log.d("SafeKidsDebug", "ForegroundProtectionService: Starting 30-second device status sync for user: ${currentUser.uid}")
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val status = statusMonitor.getCurrentStatus()
                    
                    // Get WiFi SSID if connected
                    var wifiName: String? = null
                    if (status.connectionType == "WiFi") {
                        // Android 10+ requires Location to be ON and Permission granted for SSID
                        if (com.example.safe_kids.utils.PermissionUtils.hasLocationPermissions(this@ForegroundProtectionService)) {
                            try {
                                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                                @Suppress("DEPRECATION") // connectionInfo deprecated in API 31; returns <unknown ssid> without location (already checked above)
                                val info = wifiManager.connectionInfo
                                val ssid = info.ssid
                                if (ssid != null && ssid != "<unknown ssid>") {
                                    wifiName = ssid.replace("\"", "")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to get SSID", e)
                            }
                        } else {
                            wifiName = "Location Permission Required"
                        }
                    }

                    val updates = mutableMapOf<String, Any>(
                        "lastActive" to System.currentTimeMillis(),
                        "deviceName" to status.deviceName,
                        "batteryPercentage" to status.batteryPercentage,
                        "connectionType" to status.connectionType
                    )
                    
                    wifiName?.let { updates["wifiName"] = it }
                    
                    deviceRepository.updateDeviceStatus(currentUser.uid, updates)
                    Log.d("SafeKidsDebug", "ForegroundProtectionService: Device status synced - battery=${status.batteryPercentage}%, network=${status.connectionType}, wifi=${wifiName ?: "N/A"}, device=${status.deviceName}")
                    
                } catch (e: Exception) {
                    Log.e("SafeKidsDebug", "ForegroundProtectionService: Failed to sync device status", e)
                }
                
                delay(30000L) // FIX #4: Sync every 30 seconds as required
            }
        }
    }

    private fun startLocationUpdates() {
        if (!com.example.safe_kids.utils.PermissionUtils.hasLocationPermissions(this)) {
            Log.w(TAG, "Location permission NOT granted. Cannot start updates.")
            updatePermissionStatus(false)
            return
        }

        updatePermissionStatus(true)
        val request = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            30000L // 30 seconds for better real-time feel
        ).setMinUpdateIntervalMillis(15000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { reportLocation(it) }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
            Log.d(TAG, "Fused location updates started successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
            updatePermissionStatus(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start fused location updates", e)
        }
    }

    private fun updatePermissionStatus(locationGranted: Boolean) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        
        val flags = mapOf(
            "location" to locationGranted,
            "backgroundLocation" to com.example.safe_kids.utils.PermissionUtils.hasBackgroundLocationPermission(this),
            "accessibility" to com.example.safe_kids.utils.PermissionUtils.isAccessibilityServiceEnabled(this),
            "usageStats" to com.example.safe_kids.utils.PermissionUtils.isUsageStatsPermissionGranted(this),
            "deviceAdmin" to com.example.safe_kids.utils.PermissionUtils.isDeviceAdminActive(this),
            "notifications" to com.example.safe_kids.utils.PermissionUtils.isNotificationListenerEnabled(this),
            "callLog" to com.example.safe_kids.utils.PermissionUtils.hasCallLogPermission(this),
            "smsLog" to com.example.safe_kids.utils.PermissionUtils.hasSmsPermissions(this),
            "batteryOptimizationIgnored" to com.example.safe_kids.utils.PermissionUtils.isIgnoringBatteryOptimizations(this),
            "overlayPermission" to android.provider.Settings.canDrawOverlays(this),
            "locationPermission" to (com.example.safe_kids.utils.PermissionUtils.hasLocationPermissions(this) && com.example.safe_kids.utils.PermissionUtils.hasBackgroundLocationPermission(this)),
            "accessibilityEnabled" to com.example.safe_kids.utils.PermissionUtils.isAccessibilityServiceEnabled(this),
            "usageAccess" to com.example.safe_kids.utils.PermissionUtils.isUsageStatsPermissionGranted(this),
            "doNotDisturbAccess" to com.example.safe_kids.utils.PermissionUtils.hasNotificationPolicyAccess(this),
            "deviceAdminActive" to com.example.safe_kids.utils.PermissionUtils.isDeviceAdminActive(this),
            "lastUpdate" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION)
            .document(currentUser.uid)
            .update("permissionFlags", flags)
            .addOnFailureListener {
                // If document doesn't exist, try to set it
                db.collection(AuthConfig.CHILD_PROFILES_COLLECTION)
                    .document(currentUser.uid)
                    .set(mapOf("permissionFlags" to flags), com.google.firebase.firestore.SetOptions.merge())
            }
    }

    private fun reportLocation(location: Location) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val deviceRepository = com.example.safe_kids.data.DeviceRepository()
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            deviceRepository.updateLocation(currentUser.uid, com.example.safe_kids.data.LocationData(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = System.currentTimeMillis(),
                accuracy = location.accuracy
            ))
            Log.d(TAG, "Location overwritten in 'locations' collection: ${location.latitude}, ${location.longitude}")
        }
            
        checkGeofences(location, currentUser.uid)
    }

    private fun checkGeofences(location: Location, childId: String) {
        FirebaseFirestore.getInstance()
            .collection("geofences")
            .whereEqualTo("childId", childId)
            .get()
            .addOnSuccessListener { snapshots ->
                for (doc in snapshots.documents) {
                    val lat = doc.getDouble("latitude") ?: continue
                    val lng = doc.getDouble("longitude") ?: continue
                    val radius = doc.getDouble("radius") ?: 100.0
                    val name = doc.getString("name") ?: "Unknown"
                    val type = doc.getString("type") ?: "SAFE_ZONE"
                    
                    val geofenceLocation = Location("").apply {
                        latitude = lat
                        longitude = lng
                    }
                    
                    val distance = location.distanceTo(geofenceLocation)
                    if (type == "RESTRICTED_ZONE") {
                        if (distance <= radius) {
                            logGeofenceAlert(childId, "Entered Restricted Zone: $name", location)
                        }
                    } else { // SAFE_ZONE, SCHOOL, HOME
                        if (distance > radius) {
                            logGeofenceAlert(childId, "Left Safe Zone: $name", location)
                        }
                    }
                }
            }
    }

    private fun logGeofenceAlert(childId: String, alert: String, location: Location) {
        val alertData = mapOf(
            "childId" to childId,
            "alert" to alert,
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "timestamp" to System.currentTimeMillis()
        )
        FirebaseFirestore.getInstance()
            .collection("location_alerts")
            .add(alertData)
    }

    private fun startInstantBlockListener() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: run {
            Log.e(TAG, "startInstantBlockListener: no authenticated user, skipping")
            return
        }
        val childId = currentUser.uid
        

        Log.d(TAG, "startInstantBlockListener: attaching listener for childId=$childId on children/$childId")
        instantBlockListener?.remove()
        instantBlockListener = FirebaseFirestore.getInstance()
            .collection("children")
            .document(childId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "startInstantBlockListener: Firestore error: ${e.message}", e)
                    // Restart listener after 3 seconds on error
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (instantBlockListener == null) {
                            startInstantBlockListener()
                        }
                    }, 3000)
                    return@addSnapshotListener
                }
                if (snapshot == null) {
                    Log.w(TAG, "startInstantBlockListener: snapshot is null")
                    return@addSnapshotListener
                }
                if (!snapshot.exists()) {
                    // Document doesn't exist yet — treat as not blocked, but log it
                    Log.w(TAG, "startInstantBlockListener: document children/$childId does not exist yet. instantBlock=false assumed.")
                    applyInstantBlock(false)
                    return@addSnapshotListener
                }

                // If the instantBlock field is missing, set it explicitly to false (one-time fix)
                if (!snapshot.contains("instantBlock")) {
                    try {
                        FirebaseFirestore.getInstance().collection("children").document(childId)
                            .set(mapOf("instantBlock" to false), com.google.firebase.firestore.SetOptions.merge())
                        Log.d(TAG, "startInstantBlockListener: instantBlock missing on children/$childId, resetting to false")
                    } catch (e: Exception) {
                        Log.e(TAG, "startInstantBlockListener: failed to reset missing instantBlock", e)
                    }
                }

                val blocked = snapshot.getBoolean("instantBlock") ?: false
                Log.d(TAG, "startInstantBlockListener: snapshot received. instantBlock=$blocked")
                val currentBlocked = getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                    .getBoolean("instant_block", false)
                if (blocked != currentBlocked) {
                    applyInstantBlock(blocked)
                }

                // --- New: Study Mode listener on children/{childId} -> studyMode nested map ---
                try {
                    val studyModeObj = snapshot.get("studyMode") as? Map<String, Any>
                    if (studyModeObj != null) {
                        val isActive = studyModeObj["isActive"] as? Boolean ?: false
                        val whitelistAny = studyModeObj["whitelist"] as? Map<String, Any> ?: emptyMap()
                        val blocklistAny = studyModeObj["blocklist"] as? Map<String, Any> ?: emptyMap()

                        // Extract package names where enabled == true
                        val whitelist = whitelistAny.mapNotNull { entry ->
                            val pkg = entry.key
                            val v = entry.value as? Map<*, *>
                            val enabled = v?.get("enabled") as? Boolean ?: false
                            if (enabled) pkg else null
                        }.toSet()

                        val blocklist = blocklistAny.mapNotNull { entry ->
                            val pkg = entry.key
                            val v = entry.value as? Map<*, *>
                            val enabled = v?.get("enabled") as? Boolean ?: false
                            if (enabled) pkg else null
                        }.toSet()

                        // Update in-memory manager and AccessibilityService
                        try {
                            com.example.safe_kids.services.StudyModeManager.update(isActive, whitelist, blocklist)
                            val acc = com.example.safe_kids.services.SafeKidsAccessibilityService.getInstance()
                            if (acc != null) {
                                acc.setStudyModeConfig(isActive, whitelist, blocklist)
                            }

                            // Update lastEnforced timestamp in children/{childId}/studyMode/state
                            FirebaseFirestore.getInstance().collection("children").document(childId)
                                .collection("studyMode").document("state")
                                .update("lastEnforced", com.google.firebase.firestore.FieldValue.serverTimestamp())
                        } catch (e2: Exception) {
                            Log.e(TAG, "Failed to apply study mode config from children/$childId", e2)
                        }
                    }
                } catch (e3: Exception) {
                    Log.e(TAG, "Error parsing studyMode from children/$childId", e3)
                }
            }
    }

    private fun applyInstantBlock(blocked: Boolean) {
        Log.d("InstantBlock", "applyInstantBlock called: $blocked")

        getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("instant_block", blocked)
            .apply()

        // When instant block activates, release screen time lockout
        // so the two features don't conflict
        if (blocked) {
            isGlobalLockoutActive = false
        }

        val accessibilityService = com.example.safe_kids.services.SafeKidsAccessibilityService.getInstance()
        Log.d("InstantBlock", "AccessibilityService instance: $accessibilityService")

        if (accessibilityService != null) {
            accessibilityService.setInstantBlocked(blocked)
            Log.d("InstantBlock", "AccessibilityService notified: $blocked")
        } else {
            Log.w("InstantBlock", "AccessibilityService is null — reading from SharedPrefs on next event")
        }
    }

    private fun startCommandListener() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val childId = currentUser.uid        
        commandListener = FirebaseFirestore.getInstance()
            .collection(AuthConfig.CHILD_COMMANDS_COLLECTION)
            .whereEqualTo("childId", childId)
            .whereIn("status", listOf("PENDING", "pending"))
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                
                for (doc in snapshots.documents) {
                    val type = doc.getString("type")
                    val payload = doc.get("payload") as? Map<String, String>
                    
                    val isRequestLocation = type == com.example.safe_kids.data.CommandType.REQUEST_LOCATION.name || 
                        (type == com.example.safe_kids.data.CommandType.UPDATE_GEOFENCES.name && payload?.get("action") == "request_location")

                    if (isRequestLocation) {
                        Log.d("LOCATION_DEBUG", "REQUEST_LOCATION received")
                        Log.d(TAG, "Requesting fresh real-time location...")
                        try {
                            // Request a fresh high-accuracy location fix
                            val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                                1000L
                            ).setMaxUpdates(1).build()

                            val freshLocationCallback = object : com.google.android.gms.location.LocationCallback() {
                                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                                    result.lastLocation?.let { location ->
                                        Log.d(TAG, "Fresh location received: ${location.latitude}, ${location.longitude}")
                                        reportLocation(location)
                                    }
                                    fusedLocationClient.removeLocationUpdates(this)
                                }
                            }
                            
                            fusedLocationClient.requestLocationUpdates(
                                locationRequest, 
                                freshLocationCallback, 
                                android.os.Looper.getMainLooper()
                            )
                            
                            // Fallback to last location if fresh one takes too long
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                if (location != null) {
                                    reportLocation(location)
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Location permission missing during request", e)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fulfill location request", e)
                        }
                        
                        doc.reference.update("status", com.example.safe_kids.data.CommandStatus.EXECUTED.name)
                    } else if (type == "BLOCK_APP" || type == "UNBLOCK_APP") {
                        val packageName = doc.getString("packageName")
                        val appName = doc.getString("appName")

                        if (packageName != null) {
                            try {
                                val accessibilityService = SafeKidsAccessibilityService.getInstance()
                                if (accessibilityService != null) {
                                    if (type == "BLOCK_APP") {
                                        accessibilityService.updateBlockedApps(
                                            accessibilityService.let {
                                                val field = it.javaClass.getDeclaredField("blockedApps")
                                                field.isAccessible = true
                                                @Suppress("UNCHECKED_CAST")
                                                (field.get(it) as Set<String>) + packageName
                                            }
                                        )
                                        Log.d(TAG, "BLOCK_APP via AccessibilityService: $packageName")
                                    } else {
                                        accessibilityService.updateBlockedApps(
                                            accessibilityService.let {
                                                val field = it.javaClass.getDeclaredField("blockedApps")
                                                field.isAccessible = true
                                                @Suppress("UNCHECKED_CAST")
                                                (field.get(it) as Set<String>) - packageName
                                            }
                                        )
                                        Log.d(TAG, "UNBLOCK_APP via AccessibilityService: $packageName")
                                    }
                                } else {
                                    // Fallback: save to SharedPrefs for AccessibilityService to pick up
                                    val prefs = getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                                    val blockedSet = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                                    if (type == "BLOCK_APP") blockedSet.add(packageName)
                                    else blockedSet.remove(packageName)
                                    prefs.edit().putStringSet("blocked_apps", blockedSet).apply()
                                    Log.d(TAG, "${type} saved to SharedPrefs (AccessibilityService null): $packageName")
                                }
                                doc.reference.update("status", com.example.safe_kids.data.CommandStatus.EXECUTED.name)                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to $type app: $packageName", e)
                                doc.reference.update("status", "failed")
                            }
                        }
                    }

                }
            }
    }

    private var screenTimeLimitJob: Job? = null
    private var limitListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var currentLimitMinutes: Int = 0
    private var currentExtendedMinutes: Int = 0

    private var studyModeListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var studyModeJob: Job? = null
    private var currentStudyModeConfig: com.example.safe_kids.data.StudyModeConfig? = null
    private var activeStudySessionId: String? = null
    private var studySessionStartTime: Long = 0

    private var sleepModeListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var currentSleepModeConfig: com.example.safe_kids.data.SleepModeConfig? = null
    private var activeSleepSessionId: String? = null
    private var sleepSessionStartTime: Long = 0

    private var permissionMonitorJob: Job? = null

    private fun startPermissionMonitor() {
        permissionMonitorJob?.cancel()
        permissionMonitorJob = serviceScope.launch {
            // Write permission status to Firestore every 30 seconds
            while (isActive) {
                try {
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    if (currentUser != null) {
                        val childId = currentUser.uid
                        val db = FirebaseFirestore.getInstance()
                        
                        // Gather all permission states using PermissionUtils
                        val permissionStatusMap = mapOf(
                            "accessibilityEnabled" to com.example.safe_kids.utils.PermissionUtils.isAccessibilityServiceEnabled(this@ForegroundProtectionService),
                            "overlayPermission" to android.provider.Settings.canDrawOverlays(this@ForegroundProtectionService),
                            "usageAccess" to com.example.safe_kids.utils.PermissionUtils.isUsageStatsPermissionGranted(this@ForegroundProtectionService),
                            "doNotDisturbAccess" to com.example.safe_kids.utils.PermissionUtils.hasNotificationPolicyAccess(this@ForegroundProtectionService),
                            "notificationListenerEnabled" to com.example.safe_kids.utils.PermissionUtils.isNotificationListenerEnabled(this@ForegroundProtectionService),
                            "batteryOptimizationIgnored" to com.example.safe_kids.utils.PermissionUtils.isIgnoringBatteryOptimizations(this@ForegroundProtectionService),
                            "deviceAdminActive" to com.example.safe_kids.utils.PermissionUtils.isDeviceAdminActive(this@ForegroundProtectionService),
                            "locationPermission" to (com.example.safe_kids.utils.PermissionUtils.hasLocationPermissions(this@ForegroundProtectionService) && com.example.safe_kids.utils.PermissionUtils.hasBackgroundLocationPermission(this@ForegroundProtectionService)),
                            "cameraPermission" to com.example.safe_kids.utils.PermissionUtils.hasCameraPermission(this@ForegroundProtectionService),
                            "callLogPermission" to com.example.safe_kids.utils.PermissionUtils.hasCallLogPermission(this@ForegroundProtectionService),
                            "smsPermission" to com.example.safe_kids.utils.PermissionUtils.hasSmsPermissions(this@ForegroundProtectionService)
                        )
                        
                        // Write to children/{childId}/permissionStatus/live
                        db.collection("children").document(childId)
                            .collection("permissionStatus").document("live")
                            .set(permissionStatusMap)
                            .await()

                        // Check if any critical permission was revoked and send FCM alert
                        val criticalPermissions = listOf(
                            "accessibilityEnabled" to "Accessibility Service",
                            "overlayPermission" to "Overlay Permission",
                            "usageAccess" to "Usage Access",
                            "deviceAdminActive" to "Device Admin"
                        )
                        val revokedCritical = criticalPermissions.filter {
                            permissionStatusMap[it.first] == false
                        }
                        if (revokedCritical.isNotEmpty()) {
                            val revokedNames = revokedCritical.joinToString(", ") { it.second }
                            Log.w(TAG, "Critical permissions revoked: $revokedNames")
                            // Send notification to parent via FCM function
                            try {
                                val childDoc = db.collection("users").document(childId).get().await()
                                val parentUid = childDoc.getString("parentUid")
                                val childName = childDoc.getString("displayName") ?: childDoc.getString("name") ?: "Your child"
                                if (parentUid != null) {
                                    val parentDoc = db.collection("users").document(parentUid).get().await()
                                    val fcmToken = parentDoc.getString("fcmToken")
                                    if (fcmToken != null) {
                                        com.google.firebase.functions.FirebaseFunctions.getInstance()
                                            .getHttpsCallable("sendParentAlert")
                                            .call(hashMapOf(
                                                "fcmToken" to fcmToken,
                                                "title" to "⚠ Permission Warning",
                                                "body" to "Warning: A required permission was disabled on $childName device: $revokedNames",
                                                "childUid" to childId
                                            ))
                                            .await()
                                        Log.d(TAG, "Permission revocation FCM sent to parent")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send permission revocation alert", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Permission monitor error", e)
                }
                delay(30_000) // Check every 30 seconds
            }
        }
    }

    private fun startUsageMonitoring() {
        Log.d(TAG, "Starting global monitoring loop")
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val childId = currentUser.uid

        // Load from Room immediately for offline recovery
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val db = com.example.safe_kids.data.local.AppDatabase.getDatabase(this@ForegroundProtectionService)
            
            // Study Mode Recovery
            db.studyModeDao().getConfigSync()?.let { entity ->
                currentStudyModeConfig = com.example.safe_kids.data.StudyModeConfig(
                    isEnabled = entity.isEnabled,
                    startTime = entity.startTime,
                    endTime = entity.endTime,
                    activeDays = entity.activeDays.split(",").filter { it.isNotEmpty() }.map { it.toInt() },
                    allowedApps = entity.allowedApps.split(",").filter { it.isNotEmpty() },
                    lastUpdated = entity.lastUpdated
                )
                Log.d(TAG, "Recovered Study Mode from Room: ${currentStudyModeConfig?.isEnabled}")
            }
            
            // Sleep Mode Recovery
            db.sleepModeDao().getConfigSync()?.let { entity ->
                currentSleepModeConfig = com.example.safe_kids.data.SleepModeConfig(
                    isEnabled = entity.isEnabled,
                    startTime = entity.startTime,
                    endTime = entity.endTime,
                    activeDays = entity.activeDays.split(",").filter { it.isNotEmpty() }.map { it.toInt() },
                    suppressNotifications = entity.suppressNotifications,
                    lastUpdated = entity.lastUpdated
                )
                Log.d(TAG, "Recovered Sleep Mode from Room: ${currentSleepModeConfig?.isEnabled}")
            }
        }

        // 1. Screen Time Limits Listener
        limitListener?.remove()
        limitListener = FirebaseFirestore.getInstance().collection("screen_time_limits")
            .document(childId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) {
                    currentLimitMinutes = 0
                    currentExtendedMinutes = 0
                    isGlobalLockoutActive = false
                    return@addSnapshotListener
                }
                val newLimit = snapshot.getLong("dailyLimitMinutes")?.toInt() ?: 0

// If limit was just enabled (changed from 0 to non-zero), record baseline usage
                if (currentLimitMinutes == 0 && newLimit > 0) {
                    val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
                    val calendar = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val usageStats = usm?.queryUsageStats(
                        android.app.usage.UsageStatsManager.INTERVAL_BEST,
                        calendar.timeInMillis,
                        System.currentTimeMillis()
                    ) ?: emptyList()
                    val baselineMs = usageStats
                        .filter { it.packageName != "com.example.safe_kids" && it.totalTimeInForeground > 0 }
                        .sumOf { it.totalTimeInForeground }
                    val baselineMinutes = (baselineMs / (1000 * 60)).toInt()
                    getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putInt("usage_baseline_minutes", baselineMinutes)
                        .putString("usage_baseline_date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()))
                        .apply()
                    Log.d("ScreenTimeDebug", "Baseline usage recorded: $baselineMinutes minutes")
                }

                currentLimitMinutes = newLimit

// If limit is disabled, immediately release lockout
                if (currentLimitMinutes == 0) {
                    isGlobalLockoutActive = false
                }

                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                val lastGrantDate = snapshot.getString("lastGrantDate") ?: ""
                val extendedFromFirestore = snapshot.getLong("extendedMinutes")?.toInt() ?: 0
                currentExtendedMinutes = if (lastGrantDate == today && extendedFromFirestore > 0) {
                    Log.d("ScreenTimeDebug", "Bonus time applied: $extendedFromFirestore minutes")
                    extendedFromFirestore
                } else {
                    0
                }

// If bonus was just granted, immediately release lockout
                if (currentExtendedMinutes > 0 && isGlobalLockoutActive) {
                    isGlobalLockoutActive = false
                    Log.d("ScreenTimeDebug", "Lockout released by bonus time")
                }
            }

        // 2. Study Mode Config Listener
        studyModeListener?.remove()
        studyModeListener = FirebaseFirestore.getInstance().collection("study_mode_configs")
            .document(childId)
            .addSnapshotListener { snapshot, e ->
                if (e == null && snapshot != null && snapshot.exists()) {
                    val newConfig = snapshot.toObject(com.example.safe_kids.data.StudyModeConfig::class.java)
                    currentStudyModeConfig = newConfig
                    // Cache to Room
                    newConfig?.let { cfg ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val db = com.example.safe_kids.data.local.AppDatabase.getDatabase(this@ForegroundProtectionService)
                            db.studyModeDao().insertConfig(com.example.safe_kids.data.local.StudyModeConfigEntity(
                                isEnabled = cfg.isEnabled,
                                startTime = cfg.startTime,
                                endTime = cfg.endTime,
                                activeDays = cfg.activeDays.joinToString(","),
                                allowedApps = cfg.allowedApps.joinToString(","),
                                lastUpdated = cfg.lastUpdated
                            ))
                        }
                    }
                    // Evaluate DND for Study Mode immediately when config changes
                    newConfig?.let { cfg ->
                        applyStudyModeDnd(cfg)
                    }
                }
            }

        // 3. Sleep Mode Config Listener
        sleepModeListener?.remove()
        sleepModeListener = FirebaseFirestore.getInstance().collection("sleep_mode_configs")
            .document(childId)
            .addSnapshotListener { snapshot, e ->
                if (e == null && snapshot != null && snapshot.exists()) {
                    val newConfig = snapshot.toObject(com.example.safe_kids.data.SleepModeConfig::class.java)
                    currentSleepModeConfig = newConfig
                    // Cache to Room
                    newConfig?.let { cfg ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val db = com.example.safe_kids.data.local.AppDatabase.getDatabase(this@ForegroundProtectionService)
                            db.sleepModeDao().insertConfig(com.example.safe_kids.data.local.SleepModeConfigEntity(
                                isEnabled = cfg.isEnabled,
                                startTime = cfg.startTime,
                                endTime = cfg.endTime,
                                activeDays = cfg.activeDays.joinToString(","),
                                suppressNotifications = cfg.suppressNotifications,
                                lastUpdated = cfg.lastUpdated
                            ))
                        }
                    }
                    // Evaluate DND for Sleep Mode immediately when config changes
                    newConfig?.let { cfg ->
                        applySleepModeDnd(cfg)
                    }
                }
            }

        screenTimeLimitJob?.cancel()
        screenTimeLimitJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val usageRepository = com.example.safe_kids.data.UsageRepository(this@ForegroundProtectionService)
            val appBlockingService = com.example.safe_kids.services.blocking.AppBlockingServiceImpl(this@ForegroundProtectionService)
            
            while (isActive) {
                try {
                    // --- 1. Check Screen Time Limits ---
                    // REPLACE with all of this ↓
                    val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
                    val calendar = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val startTime = calendar.timeInMillis
                    val endTime = System.currentTimeMillis()

                    val usageStats = usm?.queryUsageStats(
                        android.app.usage.UsageStatsManager.INTERVAL_BEST,
                        startTime,
                        endTime
                    ) ?: emptyList()

                    Log.d("ScreenTimeDebug", "usageStats size=${usageStats.size} queryRange=${(endTime - startTime)/1000}s")

                    val totalUsageMs = usageStats
                        .filter { it.packageName != "com.example.safe_kids" && it.totalTimeInForeground > 0 }
                        .sumOf { it.totalTimeInForeground }
                    val prefs2 = getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                    val today2 = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    val baselineDate = prefs2.getString("usage_baseline_date", "")
                    val baselineMinutes = if (baselineDate == today2) prefs2.getInt("usage_baseline_minutes", 0) else 0
                    val totalUsageMinutes = ((totalUsageMs / (1000 * 60)).toInt() - baselineMinutes).coerceAtLeast(0)
                    val allowedMinutes = currentLimitMinutes + currentExtendedMinutes

                    Log.d("ScreenTimeDebug", "totalUsageMinutes=$totalUsageMinutes allowedMinutes=$allowedMinutes currentLimitMinutes=$currentLimitMinutes extendedMinutes=$currentExtendedMinutes")
                    val isInstantBlockOn = getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                        .getBoolean("instant_block", false)

                    if (totalUsageMinutes >= allowedMinutes && allowedMinutes > 0 && currentLimitMinutes > 0 && !isInstantBlockOn) {
                        if (!isGlobalLockoutActive) {
                            logActivityAlert(childId, "LIMIT_REACHED", "Daily screen time limit of $allowedMinutes minutes has been reached.")
                            isGlobalLockoutActive = true
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                blockAllAppsInternally()
                            }
                        } else {
                            isGlobalLockoutActive = true
                        }
                    } else if (!isInstantBlockOn) {
                        isGlobalLockoutActive = false
                    }

                    // --- 2. Check Modes (Priority: Study > Sleep) ---
                    val isStudyActive = checkStudyModeSchedule(appBlockingService)
                    if (!isStudyActive) {
                        checkSleepModeSchedule(appBlockingService)
                    } else {
                        // If Study is active, disable Sleep Mode if it was on
                        if (appBlockingService.getCurrentMode() == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
                            stopSleepMode(appBlockingService)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed in monitoring loop", e)
                }
                delay(10_000) // check every 10 seconds
              }
        }
    }

    private suspend fun checkStudyModeSchedule(appBlockingService: com.example.safe_kids.services.blocking.AppBlockingService): Boolean {
        val config = currentStudyModeConfig ?: return false
        if (!config.isEnabled) {
            if (appBlockingService.getCurrentMode() == com.example.safe_kids.services.blocking.AppMode.STUDY_MODE) {
                stopStudyMode(appBlockingService)
            }
            return false
        }

        val now = getAdjustedTime()
        val currentDay = now.get(java.util.Calendar.DAY_OF_WEEK)
        val normalizedDay = if (currentDay == 1) 7 else currentDay - 1
        val currentTime = String.format("%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))

        val isScheduled = config.activeDays.contains(normalizedDay) && 
                         currentTime >= config.startTime && 
                         currentTime < config.endTime

        if (isScheduled) {
            if (appBlockingService.getCurrentMode() != com.example.safe_kids.services.blocking.AppMode.STUDY_MODE) {
                startStudyMode(appBlockingService, config)
            }
            return true
        } else {
            if (appBlockingService.getCurrentMode() == com.example.safe_kids.services.blocking.AppMode.STUDY_MODE) {
                stopStudyMode(appBlockingService)
            }
            return false
        }
    }

    private suspend fun checkSleepModeSchedule(appBlockingService: com.example.safe_kids.services.blocking.AppBlockingService) {
        val config = currentSleepModeConfig ?: return
        if (!config.isEnabled) {
            if (appBlockingService.getCurrentMode() == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
                stopSleepMode(appBlockingService)
            }
            return
        }

        val now = getAdjustedTime()
        val currentDay = now.get(java.util.Calendar.DAY_OF_WEEK)
        val normalizedDay = if (currentDay == 1) 7 else currentDay - 1
        val currentTime = String.format("%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))

        val isScheduled = if (config.activeDays.contains(normalizedDay)) {
            if (config.startTime <= config.endTime) {
                currentTime >= config.startTime && currentTime < config.endTime
            } else {
                currentTime >= config.startTime || currentTime < config.endTime
            }
        } else false

        if (isScheduled) {
            if (appBlockingService.getCurrentMode() != com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
                startSleepMode(appBlockingService, config)
            }
        } else {
            if (appBlockingService.getCurrentMode() == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
                stopSleepMode(appBlockingService)
            }
        }
    }

    private fun startSleepMode(appBlockingService: com.example.safe_kids.services.blocking.AppBlockingService, config: com.example.safe_kids.data.SleepModeConfig) {
        Log.d(TAG, "Activating Sleep Mode")
        appBlockingService.enableSleepMode()
        // Notify AccessibilityService directly
        val acc = SafeKidsAccessibilityService.getInstance()
        if (acc != null) {
            acc.setSleepModeActive(true)
            Log.d(TAG, "AccessibilityService notified: Sleep Mode ON")
        } else {
            // Fallback to SharedPrefs
            getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("sleep_mode_active", true).apply()
            Log.w(TAG, "AccessibilityService null - saved sleep_mode_active to SharedPrefs")
        }
        
        if (config.suppressNotifications) {
            val notificationService = com.example.safe_kids.services.notifications.NotificationServiceImpl(this)
            notificationService.enableDoNotDisturb(0, 0)
        }
        
        sleepSessionStartTime = System.currentTimeMillis()
        activeSleepSessionId = UUID.randomUUID().toString()
        
        // Log locally
        serviceScope.launch {
            val db = com.example.safe_kids.data.local.AppDatabase.getDatabase(this@ForegroundProtectionService)
            db.sleepModeDao().insertSession(com.example.safe_kids.data.local.SleepSessionEntity(
                id = activeSleepSessionId!!,
                date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                startTime = sleepSessionStartTime,
                endTime = 0,
                durationMinutes = 0,
                emergencyUsageMinutes = 0
            ))
        }
    }

    private fun stopSleepMode(appBlockingService: com.example.safe_kids.services.blocking.AppBlockingService) {
        Log.d(TAG, "Deactivating Sleep Mode")
        appBlockingService.disableAllModes()
        // Notify AccessibilityService directly
        val acc = SafeKidsAccessibilityService.getInstance()
        if (acc != null) {
            acc.setSleepModeActive(false)
            Log.d(TAG, "AccessibilityService notified: Sleep Mode OFF")
        } else {
            // Fallback to SharedPrefs
            getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("sleep_mode_active", false).apply()
            Log.w(TAG, "AccessibilityService null - saved sleep_mode_active=false to SharedPrefs")
        }
        
        val notificationService = com.example.safe_kids.services.notifications.NotificationServiceImpl(this)
        notificationService.disableDoNotDisturb()

        if (activeSleepSessionId != null) {
            val endTime = System.currentTimeMillis()
            val durationMinutes = ((endTime - sleepSessionStartTime) / (1000 * 60)).toInt()
            val sessionId = activeSleepSessionId!!
            val startTime = sleepSessionStartTime
            
            serviceScope.launch {
                val db = com.example.safe_kids.data.local.AppDatabase.getDatabase(this@ForegroundProtectionService)
                db.sleepModeDao().insertSession(com.example.safe_kids.data.local.SleepSessionEntity(
                    id = sessionId,
                    date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                    startTime = startTime,
                    endTime = endTime,
                    durationMinutes = durationMinutes,
                    emergencyUsageMinutes = 0 // Update this from state if emergency was used
                ))
                
                // Sync to Firestore
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    FirebaseFirestore.getInstance().collection("sleep_sessions")
                        .document(currentUser.uid)
                        .collection("sessions")
                        .document(sessionId)
                        .set(com.example.safe_kids.data.SleepSession(
                            id = sessionId,
                            date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                            startTime = startTime,
                            endTime = endTime,
                            durationMinutes = durationMinutes
                        ))
                }
            }
            activeSleepSessionId = null
        }
    }
    private var serverTimeOffsetMs: Long = 0L

    private fun syncServerTime() {
        val db = FirebaseFirestore.getInstance()
        val localTimeBefore = System.currentTimeMillis()
        db.collection("server_time")
            .document("now")
            .set(mapOf("timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
            .addOnSuccessListener {
                db.collection("server_time")
                    .document("now")
                    .get()
                    .addOnSuccessListener { doc ->
                        val serverTime = doc.getTimestamp("timestamp")?.toDate()?.time ?: return@addOnSuccessListener
                        val localTimeAfter = System.currentTimeMillis()
                        val localTime = (localTimeBefore + localTimeAfter) / 2
                        serverTimeOffsetMs = serverTime - localTime
                        Log.d(TAG, "Server time offset: ${serverTimeOffsetMs}ms")
                    }
            }
    }

    private fun getAdjustedTime(): Calendar {
        val adjustedMs = System.currentTimeMillis() + serverTimeOffsetMs
        val cal = Calendar.getInstance()
        cal.timeInMillis = adjustedMs
        return cal
    }
    private fun isNowInWindow(startTime: String, endTime: String, activeDays: List<Int>): Boolean {
        // Use UTC offset-corrected time to reduce device clock differences
        // Get time from system but validate against a reasonable range
        val now = Calendar.getInstance()

        // Auto-correct if device time seems wrong (year check)
        if (now.get(Calendar.YEAR) < 2024) {
            Log.w(TAG, "Device time seems incorrect: ${now.time}")
            return false
        }

        val currentDay = now.get(Calendar.DAY_OF_WEEK)
        val normalizedDay = if (currentDay == Calendar.SUNDAY) 7 else currentDay - 1
        if (!activeDays.contains(normalizedDay)) return false
        val currentTime = String.format(
            "%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE)
        )
        Log.d(TAG, "isNowInWindow: currentTime=$currentTime startTime=$startTime endTime=$endTime")
        return if (startTime <= endTime) {
            currentTime >= startTime && currentTime < endTime
        } else {
            currentTime >= startTime || currentTime < endTime
        }
    }

    private fun applySleepModeDnd(config: com.example.safe_kids.data.SleepModeConfig) {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Only apply Do Not Disturb on child devices
                if (!com.example.safe_kids.utils.RoleUtils.isChildDevice(this@ForegroundProtectionService)) {
                    Log.d(TAG, "Skipping SleepMode DND on non-child device")
                    return
                }
                if (manager.isNotificationPolicyAccessGranted && config.isEnabled && config.suppressNotifications && isNowInWindow(config.startTime, config.endTime, config.activeDays)) {
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    Log.d(TAG, "SleepMode DND enabled by config listener")
                } else {
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    Log.d(TAG, "SleepMode DND disabled by config listener")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying SleepMode DND", e)
        }
    }

    private fun applyStudyModeDnd(config: com.example.safe_kids.data.StudyModeConfig) {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Only apply Do Not Disturb on child devices
                if (!com.example.safe_kids.utils.RoleUtils.isChildDevice(this@ForegroundProtectionService)) {
                    Log.d(TAG, "Skipping StudyMode DND on non-child device")
                    return
                }
                if (manager.isNotificationPolicyAccessGranted && config.isEnabled && isNowInWindow(config.startTime, config.endTime, config.activeDays)) {
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    Log.d(TAG, "StudyMode DND enabled by config listener")
                } else {
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    Log.d(TAG, "StudyMode DND disabled by config listener")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying StudyMode DND", e)
        }
    }

    private fun startStudyMode(service: com.example.safe_kids.services.blocking.AppBlockingService, config: com.example.safe_kids.data.StudyModeConfig) {
        Log.d(TAG, "Activating Study Mode")
        service.enableStudyMode(config.allowedApps)
        studySessionStartTime = System.currentTimeMillis()
        activeStudySessionId = java.util.UUID.randomUUID().toString()
        
        // Log override if Downtime is active (check via AccessibilityService instance)
        val accService = com.example.safe_kids.services.SafeKidsAccessibilityService.getInstance()
        if (accService != null && accService.isDowntimeActive()) {
            FirebaseFirestore.getInstance().collection("activity_logs").add(mapOf(
                "childId" to FirebaseAuth.getInstance().currentUser?.uid,
                "type" to "PRIORITY_OVERRIDE",
                "message" to "Study Mode active during Downtime. Whitelisted apps allowed.",
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    private fun stopStudyMode(service: com.example.safe_kids.services.blocking.AppBlockingService) {
        Log.d(TAG, "Deactivating Study Mode")
        service.disableAllModes()
        
        // Record Session
        val startTime = studySessionStartTime
        if (startTime > 0) {
            val endTime = System.currentTimeMillis()
            val durationMinutes = ((endTime - startTime) / 60000).toInt()
            val childId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            
            val session = com.example.safe_kids.data.StudySession(
                id = activeStudySessionId ?: java.util.UUID.randomUUID().toString(),
                date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
                startTime = startTime,
                endTime = endTime,
                durationMinutes = durationMinutes,
                appsUsed = emptyList() // Could be populated by tracking apps during session
            )
            
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val db = com.example.safe_kids.data.local.AppDatabase.getDatabase(this@ForegroundProtectionService)
                db.studyModeDao().insertSession(com.example.safe_kids.data.local.StudySessionEntity(
                    id = session.id,
                    date = session.date,
                    startTime = session.startTime,
                    endTime = session.endTime,
                    durationMinutes = session.durationMinutes,
                    appsUsed = session.appsUsed.joinToString(",")
                ))
                
                // Sync to Firestore
                FirebaseFirestore.getInstance().collection("study_sessions")
                    .document(childId)
                    .collection("sessions")
                    .document(session.id)
                    .set(session)
            }
        }
        studySessionStartTime = 0
        activeStudySessionId = null
    }

    private fun logActivityAlert(childId: String, type: String, message: String) {
        val alertData = mapOf(
            "childId" to childId,
            "type" to type,
            "message" to message,
            "timestamp" to System.currentTimeMillis(),
            "isRead" to false
        )
        FirebaseFirestore.getInstance()
            .collection(AuthConfig.ACTIVITY_ALERTS_COLLECTION)
            .add(alertData)
    }

    private fun blockAllAppsInternally() {
        Log.w(TAG, "Triggering global device lockout")
        val intent = Intent(this, com.example.safe_kids.ui.child.TotalLockoutActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }
    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val instantBlockSyncRunnable = object : Runnable {
        override fun run() {
            val currentUser = FirebaseAuth.getInstance().currentUser ?: return
            val childId = currentUser.uid
            val prefs = getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
            FirebaseFirestore.getInstance()
                .collection("children")
                .document(childId)
                .get()
                .addOnSuccessListener { snapshot ->
                    val blocked = snapshot.getBoolean("instantBlock") ?: false
                    val storedBlocked = prefs.getBoolean("instant_block", false)
                    if (blocked != storedBlocked) {
                        Log.d("InstantBlock", "Sync mismatch fixed: $blocked")
                        applyInstantBlock(blocked)
                    }
                }
            syncHandler.postDelayed(this, 30000)
        }
    }
    override fun onDestroy() {
        try {
            syncHandler.removeCallbacks(instantBlockSyncRunnable)
        } catch (e: Exception) { }
        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: Exception) {
            Log.e("ForegroundService", "locationCallback error: ${e.message}")
        }

        try {
            commandListener?.remove()
        } catch (e: Exception) { }

        try {
            limitListener?.remove()
        } catch (e: Exception) { }

        try {
            screenTimeLimitJob?.cancel()
        } catch (e: Exception) { }

        try {
            presenceUpdateJob?.cancel()
        } catch (e: Exception) { }

        try {
            authStateListener?.let {
                FirebaseAuth.getInstance().removeAuthStateListener(it)
            }
        } catch (e: Exception) { }

        try {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser != null) {
                serviceScope.launch {
                    try {
                        setOfflinePresence(currentUser.uid)
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }

        try {
            realtimePresenceListener?.let {
                com.example.safe_kids.utils.FirebaseUtils
                    .getRealtimeDatabase()
                    .getReference(".info/connected")
                    .removeEventListener(it)
            }
        } catch (e: Exception) { }

        try {
            serviceScope.cancel()
        } catch (e: Exception) { }

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-create notification channel and restart foreground if needed
        createNotificationChannel()
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or 
                                  android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart foreground in onStartCommand", e)
        }
        return START_STICKY // Auto-restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Safe Kids protection active"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Safe Kids Protection Active")
            .setContentText("Monitoring and protection services are running")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun scheduleAutoRestart() {
        val workRequest = PeriodicWorkRequestBuilder<ServiceRestartWorker>(15, TimeUnit.MINUTES)
            .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "service_restart",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

class ServiceRestartWorker(
    context: android.content.Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    override fun doWork(): Result {
        return try {
            // Check if protection service is running, restart if needed
            val intent = Intent(applicationContext, ForegroundProtectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                applicationContext.startService(intent)
            }
            Result.success()
        } catch (e: SecurityException) {
            Log.e("ServiceRestartWorker", "Security exception starting service: ${e.message}")
            Result.failure()
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException) {
                // Android blocked the start — app is in background, skip silently
                Log.w("ServiceRestartWorker", "FGS not allowed in background: ${e.message}")
                return Result.failure()
            }
            Log.e("ServiceRestartWorker", "Failed to start service: ${e.message}")
            Result.failure()
        }
    }
}
