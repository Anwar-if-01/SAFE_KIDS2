package com.example.safe_kids.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.ChildCommand
import com.example.safe_kids.data.CommandStatus
import com.example.safe_kids.data.CommandType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import android.util.Log
import android.os.Build
import android.view.Display
import android.view.WindowManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Button
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.widget.ImageView
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.asExecutor
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback

class SafeKidsAccessibilityService : AccessibilityService() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var blockedApps = setOf<String>()
    private var educationalApps = setOf("com.google.android.apps.docs", "com.duolingo", "com.khanacademy.android", "org.wikipedia")
    private var isMonitoringEnabled = false
    private var isInstantBlocked = false
    private var isProtectionEnabled = false
    private val deviceStateManager by lazy { com.example.safe_kids.data.DeviceStateManager(this) }
    private var currentMode = com.example.safe_kids.services.blocking.AppMode.NORMAL
    private var commandListener: ListenerRegistration? = null
    private var packageReceiver: BroadcastReceiver? = null
    private val TAG = "SafeKidsAccessibility"
    private var isPairedWithGuardian = false
    private var isOnboardingComplete = false
    private var appCategories = mapOf<String, com.example.safe_kids.data.AppCategory>()
    private var downtimeConfig = com.example.safe_kids.data.DowntimeConfig()
    private var appRestrictions = listOf<com.example.safe_kids.data.AppRestriction>()
    private val appUsageToday = mutableMapOf<String, Long>() // Cache for today's usage from Room
    private var lastForegroundApp: String? = null
    private var lastAppStartTime: Long = 0
    private var lastRoomSaveTime: Long = 0
    private var lastUsageLogSyncTime: Long = 0
    private var blockingOverlayView: View? = null

    // --- Study Mode state (kept in-memory and updated by ForegroundProtectionService) ---
    @Volatile
    private var studyModeActive: Boolean = false
    private var studyModeWhitelist: MutableSet<String> = mutableSetOf()
    private var studyModeBlocklist: MutableSet<String> = mutableSetOf()

    fun setStudyModeConfig(active: Boolean, whitelist: Set<String>, blocklist: Set<String>) {
        Log.d(TAG, "setStudyModeConfig(active=$active, whitelist=${whitelist.size}, blocklist=${blocklist.size})")
        studyModeActive = active
        synchronized(this) {
            studyModeWhitelist = whitelist.toMutableSet()
            studyModeBlocklist = blocklist.toMutableSet()
        }
        // Persist a small fallback so other components can inspect quickly if needed
        try {
            getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("study_mode_active", active)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist study_mode_active in SharedPrefs", e)
        }
    }

    fun isWhitelisted(packageName: String): Boolean {
        return synchronized(this) {
            studyModeWhitelist.contains(packageName)
        }
    }

    private fun isInStudyBlocklist(packageName: String): Boolean {
        return synchronized(this) {
            studyModeBlocklist.contains(packageName)
        }
    }

    private fun logStudyBlockedAttempt(packageName: String) {
        try {
            val currentUser = FirebaseAuth.getInstance().currentUser ?: return
            val childId = currentUser.uid
            val db = FirebaseFirestore.getInstance()
            // Increment count and update lastAttempt
            val docRef = db.collection("children").document(childId)
            // Use dot notation to increment nested document fields
            docRef.collection("studyModeBlockedAttempts").document(packageName)
                .set(mapOf(
                    "count" to com.google.firebase.firestore.FieldValue.increment(1),
                    "lastAttempt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ), com.google.firebase.firestore.SetOptions.merge())
            // Also update lastEnforced timestamp on the studyMode map
            docRef.update("studyMode.lastEnforced", com.google.firebase.firestore.FieldValue.serverTimestamp())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log study blocked attempt for $packageName", e)
        }
    }
    
    private val usageRepository by lazy { com.example.safe_kids.data.UsageRepository(this) }
    private val restrictionRepository by lazy { com.example.safe_kids.data.RestrictionRepository(this) }
    private val appBlockingService by lazy { com.example.safe_kids.services.blocking.AppBlockingServiceImpl(this) }
    private val screenTimeChecker by lazy { com.example.safe_kids.services.ScreenTimeLimitChecker(this) }
    
    private val appInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packageName = intent?.data?.encodedSchemeSpecificPart ?: return
            val action = intent.action ?: return
            
            val type = if (action == Intent.ACTION_PACKAGE_ADDED) {
                com.example.safe_kids.data.ActivityType.APP_INSTALLED
            } else {
                com.example.safe_kids.data.ActivityType.APP_UNINSTALLED
            }
            
            logAppActivity(packageName, type)
            syncInstalledAppsWithFirestore()
        }
    }

    private fun logAppActivity(packageName: String, type: com.example.safe_kids.data.ActivityType) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        
        // Try to get a user-friendly app name
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        val activityLog = hashMapOf(
            "childId" to currentUser.uid,
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "activityType" to type.name,
            "details" to mapOf(
                "packageName" to packageName,
                "appName" to appName
            )
        )

        db.collection("activities")
            .add(activityLog)
            .addOnSuccessListener {
                Log.d(TAG, "Logged app activity: $type for $packageName")
            }
    }
    
    companion object {
        private var instance: SafeKidsAccessibilityService? = null
        
        fun getInstance(): SafeKidsAccessibilityService? = instance
        
        fun isServiceEnabled(): Boolean = instance != null
    }

    /** Called by ForegroundProtectionService when Firestore `children/{id}/instantBlock` changes. */
    fun setInstantBlocked(blocked: Boolean) {
        isInstantBlocked = blocked
        Log.d("InstantBlock", "setInstantBlocked: $blocked")

        getSharedPreferences("safe_kids_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("instant_block", blocked)
            .apply()

        if (blocked) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Log.d("InstantBlock", "Kicked to home screen")
        } else {
            Log.d("InstantBlock", "Block released - normal mode")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // Configure accessibility service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        
        isMonitoringEnabled = true

        // Initialize Firebase components in background to avoid blocking main thread
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Listen for protection state changes
                serviceScope.launch {
                    deviceStateManager.deviceState.collect { state ->
                        isProtectionEnabled = state.isProtectionEnabled
                        Log.d(TAG, "Protection state updated: $isProtectionEnabled")
                    }
                }
                
                checkPairingStatus()
                loadBlockedApps()
                loadAppCategories()
                startCommandListener()


                registerPackageReceiver()
                syncInstalledAppsWithFirestore()
                startRestrictionListener()
                // Start listening for per-app limits
                screenTimeChecker.startListening()
                
                // Initialize usage cache from Room and recover gaps
                serviceScope.launch {
                    val gapRecoverer = com.example.safe_kids.utils.UsageGapRecoverer(this@SafeKidsAccessibilityService)
                    gapRecoverer.recoverGaps()
                    
                    usageRepository.getUsageForToday().collect { usageList ->
                        usageList.forEach { usage ->
                            appUsageToday[usage.packageName] = usage.durationMillis
                        }
                        Log.d(TAG, "Usage cache initialized from Room: ${appUsageToday.size} apps")
                    }
                }

                startTimeTrackingLoop()
                UsageSyncWorker.schedule(this@SafeKidsAccessibilityService)
                com.example.safe_kids.workers.ScreenshotCleanupWorker.schedule(this@SafeKidsAccessibilityService)
                com.example.safe_kids.workers.MidnightResetWorker.schedule(this@SafeKidsAccessibilityService)
                
                // Start restriction sync and observation
                serviceScope.launch {
                    val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    if (currentUser != null) {
                        restrictionRepository.startRestrictionSync(currentUser.uid).collect {
                            Log.d(TAG, "Restrictions synced from Firestore to Room")
                        }
                    }
                }

                serviceScope.launch {
                    restrictionRepository.getBlockedAppsFlow().collect { list ->
                        blockedApps = list.map { it.packageName }.toSet()
                        Log.d(TAG, "Blocked apps updated in service: ${blockedApps.size}")
                        
                        // Force check current app
                        lastForegroundApp?.let { pkg ->
                            if (isAppBlocked(pkg)) {
                                withContext(Dispatchers.Main) {
                                    blockCurrentApp(pkg)
                                }
                            }
                        }
                    }
                }
                
                // Auto-return to app after service is connected
                withContext(Dispatchers.Main) {
                    delay(800)
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        try {
                            startActivity(launchIntent)
                            Log.d(TAG, "Service connected, auto-returning to app via launcher intent")
                            if (isProtectionEnabled) {
                                Toast.makeText(this@SafeKidsAccessibilityService, "Safe Kids Protection Enabled", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to auto-return to app", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize service components", e)
            }
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(appInstallReceiver, filter)
        Log.d(TAG, "Registered package receiver")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        try {
            unregisterReceiver(appInstallReceiver)
        } catch (e: Exception) {
            // Might not be registered
        }
        // Stop listening for limits
        screenTimeChecker.stopListening()
        return super.onUnbind(intent)
    }

    private fun startCommandListener() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val childId = currentUser.uid
        
        Log.d(TAG, "Starting command listener for child: $childId")
        
        commandListener = FirebaseFirestore.getInstance()
            .collection(AuthConfig.CHILD_COMMANDS_COLLECTION)
            .whereEqualTo("childId", childId)
            .whereEqualTo("status", CommandStatus.PENDING.name)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    return@addSnapshotListener
                }

                for (doc in snapshots!!.documents) {
                    val command = doc.toObject(ChildCommand::class.java)
                    if (command != null) {
                        handleCommand(command)
                        // Mark command as executed
                        doc.reference.update("status", CommandStatus.EXECUTED.name)
                        
                        if (command.type == com.example.safe_kids.data.CommandType.UPDATE_APP_CATEGORIES) {
                            loadAppCategories()
                        }
                    }
                }
            }
    }


    private fun loadAppCategories() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val childId = currentUser.uid
        
        FirebaseFirestore.getInstance()
            .collection(AuthConfig.CHILD_PROFILES_COLLECTION)
            .document(childId)
            .get()
            .addOnSuccessListener { doc ->
                val profile = doc.toObject(com.example.safe_kids.data.ChildProfile::class.java)
                if (profile != null) {
                    appCategories = profile.appCategories
                    downtimeConfig = profile.downtimeConfig
                }
            }
    }

    private fun handleCommand(command: ChildCommand) {
        Log.d(TAG, "Handling command: ${command.type}")
        when (command.type) {
            CommandType.INSTANT_BLOCK -> {
                setInstantBlocked(true)
            }
            CommandType.UNBLOCK -> {
                setInstantBlocked(false)
            }
            CommandType.TAKE_SCREENSHOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Main.asExecutor(), object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            Log.d(TAG, "Screenshot taken successfully")
                            serviceScope.launch(Dispatchers.IO) {
                                uploadScreenshot(screenshotResult, command)
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Failed to take screenshot: $errorCode")
                            serviceScope.launch(Dispatchers.IO) {
                                reportScreenshotFailure(command, errorCode)
                            }
                        }
                    })
                } else {
                    Log.w(TAG, "Screenshot command received but not supported on Android < 11 (API 30)")
                }
            }
            CommandType.START_AUDIO_MONITOR -> {
                // TODO: Start audio
            }
            CommandType.ACTIVATE_STUDY_MODE -> {
                currentMode = com.example.safe_kids.services.blocking.AppMode.STUDY_MODE
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            CommandType.DEACTIVATE_STUDY_MODE -> {
                if (currentMode == com.example.safe_kids.services.blocking.AppMode.STUDY_MODE) {
                    currentMode = com.example.safe_kids.services.blocking.AppMode.NORMAL
                }
            }
            CommandType.ACTIVATE_SLEEP_MODE -> {
                currentMode = com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            CommandType.DEACTIVATE_SLEEP_MODE -> {
                if (currentMode == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
                    currentMode = com.example.safe_kids.services.blocking.AppMode.NORMAL
                }
            }
            CommandType.LOCK_DEVICE -> {
                setInstantBlocked(true)
            }
            CommandType.UNLOCK_DEVICE -> {
                setInstantBlocked(false)
            }
            else -> {
                Log.d(TAG, "Unhandled command type: ${command.type}")
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun uploadScreenshot(screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult, command: ChildCommand) {
        try {
            val hardwareBuffer = screenshotResult.hardwareBuffer
            val colorSpace = screenshotResult.colorSpace
            
            // Wrap buffer in Bitmap
            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                ?: throw Exception("Failed to wrap HardwareBuffer in Bitmap")
                
            // Compress to JPEG 60%
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, baos)
            val data = baos.toByteArray()
            
            hardwareBuffer.close() // Important: release buffer

            // Upload to Firebase Storage
            val storageRef = com.google.firebase.storage.FirebaseStorage.getInstance().reference
            val imageRef = storageRef.child("screenshots/${command.childId}/latest.jpg")
            
            Log.d(TAG, "Uploading screenshot to Storage...")
            imageRef.putBytes(data).await()
            val downloadUrl = imageRef.downloadUrl.await().toString()
            
            // Write URL to Firestore
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            firestore.collection("screenshots")
                .document(command.childId)
                .collection("latest")
                .document("result")
                .set(mapOf(
                    "url" to downloadUrl,
                    "timestamp" to System.currentTimeMillis(),
                    "childId" to command.childId
                ))
                .await()

            Log.d(TAG, "Screenshot uploaded and Firestore updated. URL: $downloadUrl")
            
            // Delete pending command
            deleteCommand(command)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot upload failed", e)
            reportScreenshotFailure(command, -1)
        }
    }

    private suspend fun reportScreenshotFailure(command: ChildCommand, errorCode: Int) {
        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            firestore.collection("screenshots")
                .document(command.childId)
                .collection("latest")
                .document("result")
                .set(mapOf(
                    "error" to "Failed to capture (Code: $errorCode)",
                    "timestamp" to System.currentTimeMillis(),
                    "childId" to command.childId
                ))
                .await()
                
            deleteCommand(command)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report screenshot error", e)
        }
    }

    private suspend fun deleteCommand(command: ChildCommand) {
        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            firestore.collection("commands")
                .document(command.childId)
                .collection("pending_commands")
                .document(command.id)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete processed command", e)
        }
    }

    override fun onDestroy() {
        try { commandListener?.remove() } catch (e: Exception) { }
        instance = null
        isMonitoringEnabled = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoringEnabled || event == null) return

        // INSTANT BLOCK CHECK - Priority 1
        val packageName = event.packageName?.toString() ?: return
        if (isInstantBlocked) {
            Log.d("InstantBlock", "Event received: $packageName isSystem=${isSystemApp(packageName)}")
            if (!isSystemApp(packageName)) {
                Log.d("InstantBlock", "Blocking app: $packageName")
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
            }
        }
    }

    override fun onInterrupt() {
        // Handle service interruption
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Anti-Uninstall & Protection Check
        if (checkUninstallAttempt(event)) return

        // INSTANT BLOCK — runs even if protection is disabled

        if (isInstantBlocked) {
            if (!isSystemApp(packageName)) {
                Log.d("InstantBlock", "handleWindowStateChanged blocking: $packageName")
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // Protection master switch — but still enforce instant block even if protection is off
        if (!isProtectionEnabled) {
            if (isInstantBlocked && !isSystemApp(packageName)) {
                Log.d("InstantBlock", "Protection off but instant block active — blocking: $packageName")
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            logAppUsage(packageName)
            return
        }



        // Priority 0: Global Screen Time Lockout
        if (com.example.safe_kids.services.ForegroundProtectionService.isGlobalLockoutActive &&
            packageName != "com.example.safe_kids" &&
            packageName != "com.android.dialer" &&
            packageName != "com.google.android.dialer") {
            Log.d(TAG, "Global lockout active. Blocking: $packageName")
            val intent = Intent(
                this,
                com.example.safe_kids.ui.child.TotalLockoutActivity::class.java
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            return
        }

        // --- Usage Tracking ---
        if (packageName != lastForegroundApp) {
            if (!isInstantBlocked) dismissBlockingOverlay()
            val now = System.currentTimeMillis()
            lastForegroundApp?.let { oldPkg ->
                val duration = now - lastAppStartTime
                if (duration > 0) {
                    serviceScope.launch {
                        usageRepository.recordUsage(oldPkg, duration)
                        screenTimeChecker.checkAndEnforce(oldPkg)
                    }
                }
            }
            lastForegroundApp = packageName
            lastAppStartTime = now
        }

        // Priority 1: Instant Block
        if (isInstantBlocked && !isSystemApp(packageName)) {
            Log.d(TAG, "Priority 1 - Instant Block blocking: $packageName")
            blockCurrentApp(packageName)
            return
        }

        // Priority 2: Sleep Mode
        if (currentMode == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
            if (!isAlwaysAllowed(packageName) && !isSystemApp(packageName)) {
                Log.d(TAG, "Priority 2 - Sleep Mode blocking: $packageName")
                blockCurrentApp(packageName)
                return
            }
        }

        // Priority 3: Study Mode
        if (studyModeActive) {
            if (!isSystemApp(packageName) &&
                !isWhitelisted(packageName) &&
                !isAlwaysAllowed(packageName)) {
                Log.d(TAG, "Priority 3 - Study Mode blocking: $packageName")
                logStudyBlockedAttempt(packageName)
                blockCurrentApp(packageName)
                return
            }
        }

        // Priority 4: Always Allowed — never block these
        if (isAlwaysAllowed(packageName)) {
            logAppUsage(packageName)
            return
        }

        // Priority 5: App Blocker (manual blocked apps)
        // isSystemApp guard prevents launcher/system apps from being blocked
        if (blockedApps.contains(packageName) && !isSystemApp(packageName)) {
            Log.d(TAG, "Priority 5 - App Blocker blocking: $packageName")
            blockCurrentApp(packageName)
            return
        }

        // Priority 6: App Limits (time-based)
        // isSystemApp guard prevents launcher/system apps from being blocked
        if (!isSystemApp(packageName) && isAppBlocked(packageName)) {
            Log.d(TAG, "Priority 6 - App Limit reached blocking: $packageName")
            blockCurrentApp(packageName)
            return
        }

        // Log app usage for reporting
        logAppUsage(packageName)
    }

    fun isDowntimeActive(): Boolean {
        if (!downtimeConfig.isEnabled) return false
        
        val now = java.util.Calendar.getInstance()
        val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(java.util.Calendar.MINUTE)
        val currentDay = when (now.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> 1
            java.util.Calendar.TUESDAY -> 2
            java.util.Calendar.WEDNESDAY -> 3
            java.util.Calendar.THURSDAY -> 4
            java.util.Calendar.FRIDAY -> 5
            java.util.Calendar.SATURDAY -> 6
            java.util.Calendar.SUNDAY -> 7
            else -> 1
        }
        
        return downtimeConfig.schedules.any { schedule ->
            if (!schedule.isEnabled) return@any false
            if (currentDay !in schedule.timeRange.daysOfWeek) return@any false
            
            val startTotalMinutes = schedule.timeRange.startHour * 60 + schedule.timeRange.startMinute
            val endTotalMinutes = schedule.timeRange.endHour * 60 + schedule.timeRange.endMinute
            val currentTotalMinutes = currentHour * 60 + currentMinute
            
            if (startTotalMinutes <= endTotalMinutes) {
                // Simple range (e.g., 08:00 - 15:00)
                currentTotalMinutes in startTotalMinutes..endTotalMinutes
            } else {
                // Overnight range (e.g., 22:00 - 07:00)
                currentTotalMinutes >= startTotalMinutes || currentTotalMinutes <= endTotalMinutes
            }
        }
    }

    private fun isAlwaysAllowed(packageName: String): Boolean {
        if (isSystemApp(packageName)) return true
        // Always allow dialer and phone apps during any mode including Sleep Mode
        if (packageName == "com.android.dialer" || packageName == "com.google.android.dialer") return true
        if (packageName == "com.android.phone") return true
        // Allow any package containing "emergency" or "sos" during sleep mode
        if (currentMode == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
            if (packageName.contains("emergency", ignoreCase = true) || 
                packageName.contains("sos", ignoreCase = true)) return true
        }
        return downtimeConfig.alwaysAllowedApps.contains(packageName)
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        // Anti-Uninstall & Protection Check
        if (checkUninstallAttempt(event)) return

        // AI Monitoring is strictly enabled only for Study Mode and Sleep Mode
        if (currentMode != com.example.safe_kids.services.blocking.AppMode.STUDY_MODE && 
            currentMode != com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        
        // Monitor for inappropriate content
        if (shouldMonitorContent(packageName)) {
            analyzeScreenContent(event.source)
        }
    }

    private fun checkUninstallAttempt(event: AccessibilityEvent): Boolean {
        if (!isProtectionEnabled) return false
        val packageName = event.packageName?.toString() ?: return false
        
        // Settings app or Package Installer
        val isTargetApp = packageName == "com.android.settings" || 
                         packageName == "com.google.android.packageinstaller" ||
                         packageName == "com.android.packageinstaller" ||
                         packageName == "com.android.permissioncontroller"

        if (!isTargetApp) return false

        val rootNode = rootInActiveWindow ?: return false
        val screenText = extractTextFromNode(rootNode).lowercase()
        
        // If we are looking at "Safe Kids" in settings or installer
        if (screenText.contains("safe kids")) {
            // If "Uninstall" or "Deactivate" or "Remove" or "Clear data" is visible
            val triggers = listOf("uninstall", "deactivate", "remove", "clear data", "force stop")
            val detectedTrigger = triggers.find { screenText.contains(it) }
            
            if (detectedTrigger != null) {
                Log.d(TAG, "Potential uninstall/tamper attempt detected for Safe Kids: $detectedTrigger")
                
                // Log to Firestore and notify parent
                logTamperingAttempt(detectedTrigger)
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    sendEmergencyNotification(
                        currentUser.uid,
                        "uninstall_attempt",
                        "Potential tampering or uninstall attempt detected on child device.",
                        serviceScope
                    )
                } else {
                    Log.e(TAG, "Cannot send emergency notification - no authenticated user")
                }
                
                // Launch Protection Activity
                val intent = Intent(this, UninstallProtectionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("trigger", detectedTrigger)
                }
                startActivity(intent)
                return true
            }
        }
        return false
    }

    private fun isAppBlocked(packageName: String): Boolean {
        // Only enforce blocking when protection is enabled
        if (!isProtectionEnabled) return false

        // System apps and launchers are never blocked
        if (isSystemApp(packageName)) return false

        // Always allowed apps are never blocked
        if (isAlwaysAllowed(packageName)) return false

        // Instant block check
        if (isInstantBlocked) return true

        // Manual blocked apps from App Blocker
        if (blockedApps.contains(packageName)) return true

        // App Limits (time-based restrictions)
        val restriction = appRestrictions.find { it.packageName == packageName }
        if (restriction != null) {
            // Manual block
            if (restriction.isBlocked) return true

            // Time limit check
            val limitMs = restriction.timeLimit ?: 0L
            if (limitMs > 0) {
                val usedMs = if (restriction.limitType == "TOTAL") {
                    appRestrictions
                        .filter {
                            it.limitType == "TOTAL" &&
                                    it.remark == restriction.remark
                        }
                        .sumOf { appUsageToday[it.packageName] ?: 0L }
                } else {
                    appUsageToday[packageName] ?: 0L
                }
                if (usedMs >= limitMs) {
                    Log.d(TAG, "isAppBlocked: time limit reached for $packageName " +
                            "used=${usedMs}ms limit=${limitMs}ms")
                    return true
                }
            }
        }

        return false
    }

    private fun isEmergencyApp(packageName: String): Boolean {
        return packageName.contains("telephony") || 
               packageName.contains("phone") || 
               packageName.contains("dialer") || 
               packageName.contains("contacts") || 
               packageName.contains("messaging") || 
               packageName.contains("sms")
    }

    internal fun blockCurrentApp(packageName: String) {
        Log.w(TAG, "Blocking application: $packageName")
        sendAppBlockedNotification(packageName)

        // When instant block is active, skip overlay and just go home
        // The overlay can be dismissed, letting the user back in
        if (isInstantBlocked) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        showBlockingOverlay(packageName)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun showBlockingOverlay(packageName: String, reason: String = "") {
        // Don't stack multiple overlays
        if (blockingOverlayView != null) return

        // Check SYSTEM_ALERT_WINDOW permission first
        if (!Settings.canDrawOverlays(this)) {
            // Fallback to AppBlockedActivity if overlay permission not granted
            try {
                // Launch AppBlockedActivity with usage details if available
                serviceScope.launch(Dispatchers.IO) {
                    val today = usageRepository.getUsageForTodaySync().find { it.packageName == packageName }?.durationMillis ?: 0L
                    val week = usageRepository.getWeeklyUsageForPackage(packageName)
                    withContext(Dispatchers.Main) {
                        val intent = Intent(this@SafeKidsAccessibilityService, AppBlockedActivity::class.java).apply {
                            putExtra("blocked_package", packageName)
                            putExtra("block_reason", reason)
                            putExtra("dailyUsageMs", today)
                            putExtra("weeklyUsageMs", week)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e("SafeKidsOverlay", "Fallback activity failed", e)
            }
            return
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Build the overlay layout programmatically
        val overlayLayout = FrameLayout(this).apply {
            setBackgroundColor(0xF2000000.toInt()) // 95% opaque black
        }

        // App icon (optional — loads lazily)
        val appIcon = ImageView(this).apply {
            try {
                val icon = packageManager.getApplicationIcon(packageName)
                setImageDrawable(icon)
            } catch (e: Exception) {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 180
            }
        }

        // Shield icon label
        val shieldLabel = TextView(this).apply {
            text = "🛡"
            textSize = 48f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 320
            }
        }

        // Title
        val tvTitle = TextView(this).apply {
            text = "App Blocked"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 420
            }
        }

        // Reason text
        val tvReason = TextView(this).apply {
            text = if (reason.isNotEmpty()) reason
                   else "Your parent has restricted access to this app."
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            setPadding(60, 0, 60, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 500
            }
        }

        // App name
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) { packageName }

        val tvAppName = TextView(this).apply {
            text = "\"$appName\" is not available right now."
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(60, 0, 60, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 580
            }
        }

        // Go Home button
        val btnGoHome = Button(this).apply {
            text = "Go to Home Screen"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1A73E8.toInt()) // Google blue
            layoutParams = FrameLayout.LayoutParams(
                600,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = 700
            }
            setOnClickListener {
                // Dismiss overlay and go home
                dismissBlockingOverlay()
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(homeIntent)
            }
        }

        overlayLayout.addView(appIcon)
        overlayLayout.addView(shieldLabel)
        overlayLayout.addView(tvTitle)
        overlayLayout.addView(tvReason)
        overlayLayout.addView(tvAppName)
        overlayLayout.addView(btnGoHome)

        // Window params — covers entire screen, non-touchable outside button
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(overlayLayout, params)
            blockingOverlayView = overlayLayout
        } catch (e: Exception) {
            Log.e("SafeKidsOverlay", "Failed to show overlay", e)
            // Fallback to AppBlockedActivity
            serviceScope.launch(Dispatchers.IO) {
                val today = usageRepository.getUsageForTodaySync().find { it.packageName == packageName }?.durationMillis ?: 0L
                val week = usageRepository.getWeeklyUsageForPackage(packageName)
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@SafeKidsAccessibilityService, AppBlockedActivity::class.java).apply {
                        putExtra("blocked_package", packageName)
                        putExtra("block_reason", reason)
                        putExtra("dailyUsageMs", today)
                        putExtra("weeklyUsageMs", week)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun dismissBlockingOverlay() {
        blockingOverlayView?.let {
            try {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("SafeKidsOverlay", "Failed to dismiss overlay", e)
            }
            blockingOverlayView = null
        }
    }

    private fun sendAppBlockedNotification(packageName: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val appName = try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }

        val alertData = mapOf(
            "childId" to currentUser.uid,
            "type" to "APP_BLOCKED",
            "message" to "Attempted to open restricted app: $appName",
            "timestamp" to System.currentTimeMillis(),
            "isRead" to false
        )
        
        FirebaseFirestore.getInstance()
            .collection(AuthConfig.ACTIVITY_ALERTS_COLLECTION)
            .add(alertData)
    }

    private fun sendEmergencyNotification(
        childUid: String,
        alertType: String,
        detail: String,
        serviceScope: CoroutineScope
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = FirebaseFirestore.getInstance()

                val childDoc = db.collection("users")
                    .document(childUid)
                    .get()
                    .await()

                val parentUid = childDoc.getString("parentUid") ?: run {
                    Log.e(TAG, "No parentUid on child user doc $childUid")
                    return@launch
                }
                val childName = childDoc.getString("displayName")
                    ?: childDoc.getString("name") ?: "Your child"

                val fcmToken = db.collection("users")
                    .document(parentUid)
                    .get()
                    .await()
                    .getString("fcmToken") ?: run {
                        Log.e(TAG, "No FCM token for parent $parentUid")
                        return@launch
                    }

                val alertRef = db.collection("alerts")
                    .add(
                        hashMapOf(
                            "childUid" to childUid,
                            "parentUid" to parentUid,
                            "childName" to childName,
                            "type" to alertType,
                            "detail" to detail,
                            "timestamp" to FieldValue.serverTimestamp(),
                            "read" to false
                        )
                    )
                    .await()

                FirebaseFunctions.getInstance()
                    .getHttpsCallable("sendParentAlert")
                    .call(
                        hashMapOf(
                            "fcmToken" to fcmToken,
                            "title" to buildAlertTitle(alertType, childName),
                            "body" to detail,
                            "alertId" to alertRef.id,
                            "alertType" to alertType,
                            "childUid" to childUid
                        )
                    )
                    .await()

                Log.d(TAG, "Emergency notification sent: $alertType")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send emergency notification", e)
            }
        }
    }

    private fun buildAlertTitle(alertType: String, childName: String): String = when (alertType) {
        "uninstall_attempt" -> "⚠ $childName tried to uninstall Safe Kids"
        "inappropriate_content" -> "⚠ Inappropriate content on $childName's device"
        "bedtime_violation" -> "🌙 $childName is using their phone past bedtime"
        "study_mode_bypass" -> "📚 $childName bypassed study mode"
        "emergency_sos" -> "🆘 $childName triggered an SOS"
        else -> "⚠ Safe Kids Alert — $childName"
    }

    private fun logAppUsage(packageName: String) {
        if (packageName.isBlank() || isSystemApp(packageName)) return

        val now = System.currentTimeMillis()
        if (now - lastUsageLogSyncTime < 30_000) return
        lastUsageLogSyncTime = now

        serviceScope.launch(Dispatchers.IO) {
            try {
                UsageSyncWorker.runOnce(this@SafeKidsAccessibilityService)
                Log.d(TAG, "Triggered usage sync for package: $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger usage sync for $packageName", e)
            }
        }
    }

    private fun shouldMonitorContent(packageName: String): Boolean {
        // Monitor content for social media, browsers, etc.
        val monitoredApps = setOf(
            "com.android.chrome",
            "com.instagram.android",
            "com.snapchat.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.facebook.katana",
            "com.twitter.android"
        )
        return monitoredApps.contains(packageName)
    }

    private fun analyzeScreenContent(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return
        
        serviceScope.launch {
            val textContent = extractTextFromNode(rootNode)
            if (textContent.isNotEmpty()) {
                // Analyze text content for inappropriate material
                analyzeTextContent(textContent)
                
                // Track app-specific history
                trackAppSpecificHistory(rootNode)
            }
        }
    }

    private fun trackAppSpecificHistory(rootNode: AccessibilityNodeInfo) {
        val packageName = rootNode.packageName?.toString() ?: return
        
        when (packageName) {
            "com.zhiliaoapp.musically" -> { // TikTok
                // Extract video title/description if possible
                findAndLogTextById(rootNode, "com.zhiliaoapp.musically:id/desc", "TikTok Video")
            }
            "com.google.android.youtube" -> { // YouTube
                findAndLogTextById(rootNode, "com.google.android.youtube:id/video_title", "YouTube Video")
            }
            "com.android.chrome" -> { // Chrome
                // Extract URL from Chrome's URL bar
                val nodes = rootNode.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                if (nodes != null && nodes.isNotEmpty()) {
                    val url = nodes[0].text?.toString() ?: return
                    if (url.isNotEmpty() && url != "Search or type web address") {
                        handleWebVisit(url)
                    }
                }
            }
            "com.whatsapp" -> { // WhatsApp
                // Just log that WhatsApp is active for now, avoid capturing full chats for privacy/complexity
                logActivityLocally("WhatsApp Activity", "User is active on WhatsApp")
            }
        }
    }

    private fun findAndLogTextById(rootNode: AccessibilityNodeInfo, viewId: String, category: String) {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes != null && nodes.isNotEmpty()) {
            val text = nodes[0].text?.toString() ?: return
            if (text.isNotEmpty()) {
                logToFirebase(category, text)
            }
        }
    }

    private fun handleWebVisit(url: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        
        // Basic blocking list (could be fetched from Firestore settings)
        val blockedKeywords = listOf("gambling", "casino", "poker", "porn", "adult", "violence")
        val isBlocked = blockedKeywords.any { url.lowercase().contains(it) }

        val log = mapOf(
            "childId" to currentUser.uid,
            "url" to url,
            "title" to url, // Title could be extracted from rootNode if needed
            "timestamp" to System.currentTimeMillis(),
            "isBlocked" to isBlocked
        )

        FirebaseFirestore.getInstance()
            .collection(AuthConfig.WEB_HISTORY_COLLECTION)
            .add(log)

        if (isBlocked) {
            Log.d(TAG, "Blocked web visit to: $url")
            // Launch Blocked Activity
            serviceScope.launch(Dispatchers.IO) {
                val today = usageRepository.getUsageForTodaySync().find { it.packageName == "com.android.chrome" }?.durationMillis ?: 0L
                val week = usageRepository.getWeeklyUsageForPackage("com.android.chrome")
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@SafeKidsAccessibilityService, AppBlockedActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("blocked_package", "com.android.chrome")
                        putExtra("reason", "Inappropriate web content detected")
                        putExtra("blocked_url", url)
                        putExtra("dailyUsageMs", today)
                        putExtra("weeklyUsageMs", week)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun logToFirebase(category: String, detail: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val log = mapOf(
            "childId" to currentUser.uid,
            "category" to category,
            "detail" to detail,
            "timestamp" to System.currentTimeMillis()
        )
        
        FirebaseFirestore.getInstance()
            .collection(AuthConfig.CONTENT_SAFETY_LOGS_COLLECTION)
            .add(log)
    }

    private fun logActivityLocally(category: String, detail: String) {
        Log.d(TAG, "[$category] $detail")
    }

    private fun logTamperingAttempt(trigger: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val sharedPrefs = getSharedPreferences("tampering_prefs", Context.MODE_PRIVATE)
        val attemptCount = sharedPrefs.getInt("attempt_count", 0) + 1
        val lastAttemptTime = sharedPrefs.getLong("last_attempt_time", 0)
        
        val now = System.currentTimeMillis()
        val isConsecutive = (now - lastAttemptTime) < 5 * 60 * 1000 // 5 minutes
        
        sharedPrefs.edit()
            .putInt("attempt_count", if (isConsecutive) attemptCount else 1)
            .putLong("last_attempt_time", now)
            .apply()

        val alert = mapOf(
            "childId" to currentUser.uid,
            "trigger" to trigger,
            "timestamp" to now,
            "attemptNumber" to (if (isConsecutive) attemptCount else 1),
            "isHighPriority" to (attemptCount >= 3 && isConsecutive),
            "isResolved" to false
        )

        FirebaseFirestore.getInstance()
            .collection(AuthConfig.TAMPERING_ALERTS_COLLECTION)
            .add(alert)
            .addOnSuccessListener {
                Log.d(TAG, "Tampering alert logged to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to log tampering alert", e)
            }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val textBuilder = StringBuilder()
        
        fun traverseNode(currentNode: AccessibilityNodeInfo) {
            currentNode.text?.let { text ->
                textBuilder.append(text).append(" ")
            }
            
            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { child ->
                    traverseNode(child)
                    child.recycle()
                }
            }
        }
        
        traverseNode(node)
        return textBuilder.toString().trim()
    }

    private suspend fun analyzeTextContent(content: String) {
        if (content.isBlank()) return
        
        val aiService = com.example.safe_kids.services.ai.AIContentAnalysisServiceImpl(this)
        
        if (currentMode == com.example.safe_kids.services.blocking.AppMode.STUDY_MODE) {
            val studyResult = aiService.analyzeStudyContent(content)
            if (!studyResult.isEducational) {
                logActivityLocally("Study Mode Violation", "Non-educational content detected")
                // Note: AI Monitoring can trigger a block or alert parents here
            }
        } else if (currentMode == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE) {
            logActivityLocally("Sleep Mode Violation", "Active usage detected during sleep mode")
        }
    }

    private fun checkPairingStatus() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: run {
            isPairedWithGuardian = false
            return
        }
        FirebaseFirestore.getInstance()
            .collection(AuthConfig.USERS_COLLECTION)
            .document(currentUser.uid)
            .get()
            .addOnSuccessListener { document ->
                // parentIds is the array written by linkChildToParent()
                @Suppress("UNCHECKED_CAST")
                val parentIds = document.get("parentIds") as? List<String>
                isPairedWithGuardian = !parentIds.isNullOrEmpty()
                isOnboardingComplete = document.getBoolean("isOnboardingComplete") ?: false
                Log.d(TAG, "Pairing status checked: isPairedWithGuardian=$isPairedWithGuardian, isOnboardingComplete=$isOnboardingComplete (parentIds=$parentIds)")
            }
            .addOnFailureListener {
                // Default to NOT paired if check fails, so we don't block the child
                isPairedWithGuardian = false
                Log.w(TAG, "Could not check pairing status, defaulting to unpaired", it)
            }
    }

    private fun loadBlockedApps() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        
        serviceScope.launch {
            FirebaseFirestore.getInstance()
                .collection(AuthConfig.APP_USAGE_LIMITS_COLLECTION)
                .document(currentUser.uid)
                .get()
                .addOnSuccessListener { document ->
                    val apps = document.get("blockedApps") as? List<String>
                    if (apps != null) {
                        blockedApps = apps.toSet()
                    }
                }
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        val explicitWhitelist = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.providers.downloads",
            "com.android.downloadmanager",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.microsoft.launcher",
            "com.teslacoil.app.novalauncher",
            "com.anddoes.launcher",
            "com.action.launcher",
            "net.oneplus.launcher",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.infinix.launcher",
            "com.transsion.launcher",
            "com.example.safe_kids"
        )
        if (packageName in explicitWhitelist) return true
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }

    // Public methods for external control
    fun updateBlockedApps(apps: Set<String>) {
        blockedApps = apps
    }

    fun enableMonitoring() {
        isMonitoringEnabled = true
    }

    fun disableMonitoring() {
        isMonitoringEnabled = false
    }

    fun forceStopApp(packageName: String) {
        if (isAppBlocked(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun syncInstalledAppsWithFirestore() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val childId = currentUser.uid
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                val packageManager = this@SafeKidsAccessibilityService.packageManager
                val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                
                val appsList = installedPackages.mapNotNull { packageInfo ->
                    val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                    val name = packageManager.getApplicationLabel(appInfo).toString()
                    val packageName = packageInfo.packageName ?: ""
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getCategoryString(appInfo.category)
                    } else {
                        "Other"
                    }
                    
                    com.example.safe_kids.data.InstalledApp(
                        packageName = packageName,
                        appName = name,
                        category = category,
                        installTime = packageInfo.firstInstallTime,
                        isSystemApp = isSystem,
                        iconBase64 = com.example.safe_kids.utils.IconUtils.drawableToBase64(packageManager.getApplicationIcon(appInfo))
                    )
                }.filter { !it.isSystemApp || it.packageName == "com.android.chrome" || it.packageName.contains("browser") }

                FirebaseFirestore.getInstance()
                    .collection(AuthConfig.CHILD_PROFILES_COLLECTION)
                    .document(childId)
                    .set(
                        mapOf("installedApps" to appsList.map {
                            mapOf(
                                "packageName" to it.packageName,
                                "appName" to it.appName,
                                "category" to it.category,
                                "installTime" to it.installTime,
                                "isSystemApp" to it.isSystemApp,
                                "iconBase64" to it.iconBase64
                            )
                        }),
                        com.google.firebase.firestore.SetOptions.merge()
                    )

                
                Log.d(TAG, "Successfully synced ${appsList.size} apps to Firestore")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync installed apps", e)
            }
        }
    }

    private fun getCategoryString(category: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (category) {
                ApplicationInfo.CATEGORY_GAME -> "Games"
                ApplicationInfo.CATEGORY_AUDIO -> "Audio"
                ApplicationInfo.CATEGORY_VIDEO -> "Video"
                ApplicationInfo.CATEGORY_IMAGE -> "Images"
                ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                ApplicationInfo.CATEGORY_NEWS -> "News"
                ApplicationInfo.CATEGORY_MAPS -> "Maps"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                else -> "Other"
            }
        } else {
            "Other"
        }
    }
    private fun startRestrictionListener() {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection(com.example.safe_kids.auth.AuthConfig.CHILD_PROFILES_COLLECTION)
            .document(currentUser.uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    // Update Downtime Config
                    val downtimeData = snapshot.get("downtimeConfig") as? Map<String, Any>
                    if (downtimeData != null) {
                        val schedulesData = downtimeData["schedules"] as? List<Map<String, Any>> ?: emptyList()
                        downtimeConfig = com.example.safe_kids.data.DowntimeConfig(
                            isEnabled = downtimeData["enabled"] as? Boolean ?: false,
                            alwaysAllowedApps = downtimeData["alwaysAllowedApps"] as? List<String> ?: listOf("com.android.dialer", "com.google.android.dialer"),
                            schedules = schedulesData.map { s ->
                                val tr = s["timeRange"] as? Map<String, Any>
                                com.example.safe_kids.data.DowntimeSchedule(
                                    id = s["id"] as? String ?: "",
                                    name = s["name"] as? String ?: "",
                                    isEnabled = s["enabled"] as? Boolean ?: false,
                                    timeRange = com.example.safe_kids.data.TimeRange(
                                        (tr?.get("startHour") as? Long)?.toInt() ?: 0,
                                        (tr?.get("startMinute") as? Long)?.toInt() ?: 0,
                                        (tr?.get("endHour") as? Long)?.toInt() ?: 0,
                                        (tr?.get("endMinute") as? Long)?.toInt() ?: 0,
                                        (tr?.get("daysOfWeek") as? List<Long>)?.map { it.toInt() } ?: (1..7).toList()
                                    )
                                )
                            }
                        )
                    }

                    // Update App Restrictions
                    val restrictionsData = snapshot.get("restrictions") as? List<Map<String, Any>>
                    if (restrictionsData != null) {
                        appRestrictions = restrictionsData.map {
                            com.example.safe_kids.data.AppRestriction(
                                packageName = it["packageName"] as? String ?: "",
                                appName = it["appName"] as? String ?: "",
                                isBlocked = it["isBlocked"] as? Boolean ?: false,
                                timeLimit = it["timeLimit"] as? Long,
                                limitType = it["limitType"] as? String ?: "INDIVIDUAL",
                                remark = it["remark"] as? String
                            )
                        }
                    }
                }
            }
    }

    private fun startTimeTrackingLoop() {
        serviceScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000) // Check every second
                
                val now = System.currentTimeMillis()
                
                // Update currently running app usage
                lastForegroundApp?.let { pkg ->
                    val duration = now - lastAppStartTime
                    if (duration > 0) {
                        // Update in-memory cache for instant blocking checks
                        appUsageToday[pkg] = (appUsageToday[pkg] ?: 0L) + duration
                        lastAppStartTime = now

                        // Save to Room every 30 seconds to avoid heavy DB writes
                        if (now - lastRoomSaveTime > 30_000) {
                            usageRepository.recordUsage(pkg, now - lastRoomSaveTime) // This is a bit simplified, but recordUsage handles increments
                            // Actually, recordUsage(pkg, duration) is safer if called every loop, 
                            // but for performance, we should accumulate and save.
                            // Let's stick to recording small chunks frequently for now as Room handles it well.
                            lastRoomSaveTime = now
                            
                            // Trigger a sync attempt in background
                            UsageSyncWorker.runOnce(this@SafeKidsAccessibilityService)
                        }
                    }
                    
                    // Periodically check if the current app should be blocked now
                    if (isAppBlocked(pkg)) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
            }
        }
    }
}

