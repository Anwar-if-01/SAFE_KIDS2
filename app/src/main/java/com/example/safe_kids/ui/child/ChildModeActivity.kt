package com.example.safe_kids.ui.child

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import android.provider.Settings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.utils.DeviceStatusMonitor
import com.example.safe_kids.utils.DeviceStatus
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.auth.AuthManager
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.ui.auth.AuthActivity
import com.example.safe_kids.data.ChildProfile
import com.example.safe_kids.services.blocking.AppBlockingServiceImpl
import com.example.safe_kids.services.blocking.AppMode
import com.example.safe_kids.services.location.LocationServiceImpl
import com.example.safe_kids.services.notifications.NotificationServiceImpl
import com.example.safe_kids.ui.components.AdminCodeVerificationDialog
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.PermissionUtils
import com.example.safe_kids.ui.child.PermissionSetupActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import com.google.firebase.firestore.FirebaseFirestore
import com.example.safe_kids.data.*
import java.text.SimpleDateFormat
import java.util.*

private fun hasRequiredChildPermissions(context: android.content.Context): Boolean {
    val locationGranted = PermissionUtils.hasLocationPermissions(context) && PermissionUtils.hasBackgroundLocationPermission(context)
    val notificationAccessGranted = PermissionUtils.isNotificationListenerEnabled(context)
    val dndGranted = PermissionUtils.hasNotificationPolicyAccess(context)
    val usageGranted = PermissionUtils.isUsageStatsPermissionGranted(context)
    val accessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(context)
    val deviceAdminGranted = PermissionUtils.isDeviceAdminActive(context)
    val overlayGranted = Settings.canDrawOverlays(context)
    return locationGranted && notificationAccessGranted && dndGranted && usageGranted && accessibilityGranted && deviceAdminGranted && overlayGranted
}

class ChildModeActivity : ComponentActivity() {
    private lateinit var authManager: AuthManagerImpl
    private lateinit var locationService: LocationServiceImpl
    private lateinit var appBlockingService: AppBlockingServiceImpl
    private lateinit var notificationService: NotificationServiceImpl
    private lateinit var deviceStatusMonitor: DeviceStatusMonitor
    private lateinit var restrictionRepository: com.example.safe_kids.data.RestrictionRepository


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        
        // Initialize services
        authManager = AuthManagerImpl(this)
        
        // Initialize AuthManager to verify session
        lifecycleScope.launch {
            authManager.initialize()
        }
        
        locationService = LocationServiceImpl(this, authManager)
        appBlockingService = AppBlockingServiceImpl(this)
        notificationService = NotificationServiceImpl(this)
        deviceStatusMonitor = DeviceStatusMonitor(this)
        restrictionRepository = com.example.safe_kids.data.RestrictionRepository(this)


        
        // FIX: Location sharing is started inside ChildModeScreen's LaunchedEffect using the
        // real authenticated user ID. No call here to avoid the hardcoded-ID race condition.
        
        setContent {
            var initialized by remember { mutableStateOf(false) }
            
            LaunchedEffect(Unit) {
                authManager.initialize()
                initialized = true
            }

            SAFE_KIDSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!initialized) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        ChildModeScreen(
                            authManager = authManager,
                            locationService = locationService,
                            appBlockingService = appBlockingService,
                            notificationService = notificationService,
                            deviceStatusMonitor = deviceStatusMonitor,
                            onEmergencyAlert = {
                                val currentChildId = authManager.getCurrentUser()?.id
                                if (currentChildId != null && currentChildId.isNotEmpty()) {
                                    locationService.sendEmergencyLocation(currentChildId)
                                    // Also send emergency SMS using last known location
                                    lifecycleScope.launch {
                                        val location = try {
                                            locationService.getCurrentLocation().firstOrNull()
                                        } catch (e: Exception) { null }
                                        val locationStr = location?.let { loc -> "${loc.latitude},${loc.longitude}" }
                                        com.example.safe_kids.utils.SmsUtils.sendEmergencySms(this@ChildModeActivity, currentChildId, locationStr)
                                    }
                                }
                            },
                            restrictionRepository = restrictionRepository
                        )
                    }
                }
            }
        }
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Return task to back (exit/minimize app) to prevent returning to mode selection
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        if (::locationService.isInitialized) {
            try { locationService.stopLocationTracking() } catch (e: Exception) { }
        }
        // RestrictionRepository uses callbackFlow and will remove its listener when collectors are cancelled.
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildModeScreen(
    authManager: AuthManagerImpl,
    locationService: LocationServiceImpl,
    appBlockingService: AppBlockingServiceImpl,
    notificationService: NotificationServiceImpl,
    deviceStatusMonitor: DeviceStatusMonitor,
    onEmergencyAlert: () -> Unit,
    restrictionRepository: com.example.safe_kids.data.RestrictionRepository
) {

    val isAuthenticated by authManager.isAuthenticated.collectAsState()
    val currentUserState = authManager.currentUser.collectAsState()
    val currentUser = currentUserState.value
    val isInitializing by authManager.isInitializing.collectAsState()
    
    var currentMode by remember { mutableStateOf(AppMode.NORMAL) }
    var studyModeConfig by remember { mutableStateOf<com.example.safe_kids.data.StudyModeConfig?>(null) }
    var sleepModeConfig by remember { mutableStateOf<com.example.safe_kids.data.SleepModeConfig?>(null) }
    var allowedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var screenTimeToday by remember { mutableStateOf("0h 0m") }
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showSetupDialog by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var isGeneratingCode by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var showAdminCodeDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var permissionsMissing by remember { mutableStateOf(false) }
    var autoRedirectedToPermissions by rememberSaveable { mutableStateOf(false) }
    val deviceStatus by deviceStatusMonitor.getStatusFlow().collectAsState(initial = deviceStatusMonitor.getCurrentStatus())
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val childViewModel: ChildModeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ChildModeViewModel(context, restrictionRepository) as T
            }
        }
    )
    
    val dynamicAllowedApps by childViewModel.allowedApps.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionsMissing = hasRequiredChildPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isAuthenticated, currentUser, permissionsMissing, isInitializing) {
        if (!autoRedirectedToPermissions && isAuthenticated && currentUser != null && permissionsMissing && !isInitializing) {
            autoRedirectedToPermissions = true
            context.startActivity(Intent(context, PermissionSetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    
    var connectedParents by remember { mutableStateOf<List<com.example.safe_kids.auth.User>>(emptyList()) }
    
    val isPaired = currentUser?.parentIds?.isNotEmpty() == true
    
    val currentAppMode by AppBlockingServiceImpl.currentModeState.collectAsState()
    var showStudyCompletionDialog by remember { mutableStateOf(false) }
    var studyDurationResult by remember { mutableIntStateOf(0) }

    // Listen for Study Mode Config on child side
    LaunchedEffect(currentUser) {
        currentUser?.let { user ->
            FirebaseFirestore.getInstance().collection("study_mode_configs").document(user.id)
                .addSnapshotListener { snapshot: com.google.firebase.firestore.DocumentSnapshot?, _: com.google.firebase.firestore.FirebaseFirestoreException? ->
                    if (snapshot != null && snapshot.exists()) {
                        studyModeConfig = snapshot.toStudyModeConfigCompat()
                    }
                }
            FirebaseFirestore.getInstance().collection("sleep_mode_configs").document(user.id)
                .addSnapshotListener { snapshot: com.google.firebase.firestore.DocumentSnapshot?, _: com.google.firebase.firestore.FirebaseFirestoreException? ->
                    if (snapshot != null && snapshot.exists()) {
                        sleepModeConfig = snapshot.toSleepModeConfigCompat()
                    }
                }
        }
    }

    // Monitor for Study Mode completion to show dialog
    LaunchedEffect(currentAppMode) {
        if (currentAppMode == AppMode.NORMAL && studyModeConfig != null) {
             // Logic to detect if a session just ended
             // We can check if we were in Study Mode just before
        }
    }

    LaunchedEffect(isAuthenticated, currentUser) {
        if (isAuthenticated && currentUser != null) {
            currentMode = appBlockingService.getCurrentMode()
            
            // Real info from local service
            val usageMillis = appBlockingService.getAppUsageToday("Total")
            screenTimeToday = formatScreenTime(usageMillis)
            // allowedApps = listOf("Calculator", "Dictionary", "School App") // REMOVED MOCK
            
            // Refresh connection status and mode whenever authentication or user data changes
            currentUser?.let { user ->
                // Start restriction sync
                scope.launch {
                    restrictionRepository.startRestrictionSync(user.id).collect {}
                }
                
                locationService.setChildId(user.id)

                locationService.startLocationTracking()
                // FIX: Start location sharing with the real user ID (was previously "current_child_id")
                locationService.enableLocationSharing(user.id)
                // Start the always-on protection service
                val serviceIntent = Intent(context, com.example.safe_kids.services.ForegroundProtectionService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                
                scope.launch {
                    val result = authManager.getParentsForChild(user.id)
                    if (result.isSuccess) {
                        connectedParents = result.getOrDefault(emptyList())
                    }
                }
            }
        } else {
            currentMode = AppMode.NORMAL
            screenTimeToday = "0h 0m"
            allowedApps = emptyList()
            connectedParents = emptyList()
        }
    }

    // Redirect to pairing if unlinked while app is open
    LaunchedEffect(isPaired) {
        if (isAuthenticated && currentUser != null && currentUser?.role == com.example.safe_kids.auth.UserRole.CHILD && !isPaired) {
            Log.w("ChildModeActivity", "Child unlinked! Redirecting to pairing...")
            val intent = Intent(context, com.example.safe_kids.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        }
    }
    
    LaunchedEffect(deviceStatus) {
        val user = authManager.getCurrentUser()
        if (user != null && isAuthenticated) {
            scope.launch {
                val updates = mutableMapOf<String, Any>(
                    "deviceName" to deviceStatus.deviceName,
                    "batteryPercentage" to deviceStatus.batteryPercentage,
                    "connectionType" to deviceStatus.connectionType
                )
                authManager.updateUserProfile(updates)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    titleContentColor = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            // User Info Header
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = currentUser?.displayName?.take(1)?.uppercase() ?: "C",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = currentUser?.displayName ?: "Child User",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Connected as Child",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            
                            HorizontalDivider()
                            
                            DropdownMenuItem(
                                text = { Text("Edit Child Profile") },
                                onClick = {
                                    showMenu = false
                                    showProfileEditor = true
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Parental Settings") },
                                onClick = {
                                    showMenu = false
                                    pendingAction = { 
                                        context.startActivity(Intent(context, ParentalSettingsActivity::class.java))
                                    }
                                    showAdminCodeDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Chat with Parent") },
                                onClick = {
                                    showMenu = false
                                    val parentId = connectedParents.firstOrNull()?.id ?: ""
                                    val parentName = connectedParents.firstOrNull()?.displayName ?: "Parent"
                                    val intent = Intent(context, com.example.safe_kids.ui.chat.ChatActivity::class.java).apply {
                                        putExtra("OTHER_USER_ID", parentId)
                                        putExtra("OTHER_USER_NAME", parentName)
                                        putExtra("IS_PARENT", false)
                                    }
                                    context.startActivity(intent)
                                },
                                leadingIcon = { Icon(Icons.Default.Message, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Switch Mode") },
                                onClick = {
                                    showMenu = false
                                    pendingAction = {
                                        authManager.sessionManager.clearModeState()
                                        val intent = Intent(context, com.example.safe_kids.MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                    showAdminCodeDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Sync, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Sign Out") },
                                onClick = {
                                    showMenu = false
                                    pendingAction = {
                                        com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(context, authManager, scope)
                                    }
                                    showAdminCodeDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Logout, null) }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Group, contentDescription = "Family") },
                    label = { Text("Family") }
                )
            }
        }
    ) { paddingValues ->
        if (selectedTab == 0) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (permissionsMissing) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clickable {
                                context.startActivity(Intent(context, PermissionSetupActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "⚠️ Important permission missing - tap to fix",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                ChildHomeScreen(
                    paddingValues = paddingValues,
                    deviceName = deviceStatus.deviceName,
                    guardianEmail = connectedParents.firstOrNull()?.email ?: "guardian",
                    isFocusMode = currentMode == AppMode.FOCUS_MODE,
                    onFocusModeToggle = { enabled ->
                        scope.launch {
                            if (enabled) appBlockingService.setMode(AppMode.FOCUS_MODE)
                            else appBlockingService.setMode(AppMode.NORMAL)
                            currentMode = appBlockingService.getCurrentMode()
                        }
                    },
                    onParentalSettingsClick = {
                        pendingAction = {
                            context.startActivity(Intent(context, ParentalSettingsActivity::class.java))
                        }
                        showAdminCodeDialog = true
                    },
                    onPairDevice = {
                        showSetupDialog = true
                    },
                    onEmergency = {
                        showEmergencyDialog = true
                    },
                    allowedApps = dynamicAllowedApps,
                    currentAppMode = currentMode,
                    studyModeConfig = studyModeConfig,
                    sleepModeConfig = sleepModeConfig
                )
            } // close Column
        } else {
            // Family tab
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                ChildProfileSection(
                    authManager = authManager
                )
            }
        }
    } // end Scaffold content lambda

    if (showEmergencyDialog) {
        EmergencyDialog(
            onConfirm = {
                onEmergencyAlert()
                showEmergencyDialog = false
            },
            onDismiss = { showEmergencyDialog = false }
        )
    }
    
    if (showAdminCodeDialog) {
        AdminCodeVerificationDialog(
            authManager = authManager,
            onDismiss = { 
                showAdminCodeDialog = false
                pendingAction = null
            },
            onSuccess = {
                showAdminCodeDialog = false
                if (pendingAction != null) {
                    pendingAction?.invoke()
                } else {
                    // Default action if no pending action (e.g. Parental Settings)
                    context.startActivity(Intent(context, ParentalSettingsActivity::class.java))
                }
                pendingAction = null
            }
        )
    }
    
    if (showSetupDialog) {
        DeviceSetupDialog(
            onDismiss = { showSetupDialog = false },
            onComplete = { 
                showSetupDialog = false
                showPairingDialog = true
                // Auto-generate pairing code after setup
                scope.launch {
                    isGeneratingCode = true
                    pairingError = null
                    try {
                        val result = authManager.generatePairingCode()
                        if (result.isSuccess) {
                            pairingCode = result.getOrNull() ?: ""
                        } else {
                            pairingError = result.exceptionOrNull()?.message ?: "Failed to generate code"
                        }
                    } catch (e: Exception) {
                        pairingError = e.message ?: "An unexpected error occurred"
                    } finally {
                        isGeneratingCode = false
                    }
                }
            }
        )
    }
        
    if (showPairingDialog) {
        PairingDialog(
            pairingCode = pairingCode,
            isGenerating = isGeneratingCode, 
            errorMessage = pairingError,
            onGenerateCode = {
                scope.launch {
                    isGeneratingCode = true
                    pairingError = null
                    try {
                        val result = authManager.generatePairingCode()
                        if (result.isSuccess) {
                            pairingCode = result.getOrNull() ?: ""
                        } else {
                            pairingError = result.exceptionOrNull()?.message ?: "Failed to generate code"
                        }
                    } catch (e: Exception) {
                        pairingError = e.message ?: "An unexpected error occurred"
                    } finally {
                        isGeneratingCode = false
                    }
                }
            },
            onUseCode = { code ->
                scope.launch {
                    val result = authManager.usePairingCode(code, authManager.getCurrentUser()?.id ?: "")
                    if (result.isSuccess) {
                        showPairingDialog = false
                        pairingCode = ""
                    } else {
                        pairingError = result.exceptionOrNull()?.message ?: "Invalid code"
                    }
                }
            },
            onDismiss = { 
                showPairingDialog = false
                pairingCode = ""
                pairingError = null
            }
        )
    }

    if (isInitializing) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Connecting to Safe Kids...")
            }
        }
    }
    if (showProfileEditor) {
        EditChildProfileDialog(
            authManager = authManager,
            onDismiss = { showProfileEditor = false }
        )
    }

    if (showStudyCompletionDialog) {
        StudyModeCompletionDialog(
            duration = 45, // Placeholder or fetch real duration from session log
            onDismiss = { showStudyCompletionDialog = false }
        )
    }
}

@Composable
fun ChildHomeScreen(
    paddingValues: PaddingValues,
    deviceName: String,
    guardianEmail: String,
    isFocusMode: Boolean,
    onFocusModeToggle: (Boolean) -> Unit,
    onParentalSettingsClick: () -> Unit,
    onPairDevice: () -> Unit,
    onEmergency: () -> Unit,
    allowedApps: List<String>,
    currentAppMode: com.example.safe_kids.services.blocking.AppMode,
    studyModeConfig: com.example.safe_kids.data.StudyModeConfig?,
    sleepModeConfig: com.example.safe_kids.data.SleepModeConfig?
) {

    var showMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = paddingValues.calculateBottomPadding())
    ) {
        if (currentAppMode == com.example.safe_kids.services.blocking.AppMode.STUDY_MODE && studyModeConfig != null) {
            StudyModeBanner(studyModeConfig)
        } else if (currentAppMode == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE && sleepModeConfig != null) {
            SleepModeBanner(sleepModeConfig)
        }
        
        // Blue Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(MaterialTheme.colorScheme.primary)
                .padding(16.dp)
        ) {
            // Space for the TopAppBar
            Spacer(modifier = Modifier.statusBarsPadding().height(56.dp))

            // Cloud Shape and Illustration - Now inside the header Box to support Alignment.BottomCenter
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .offset(y = 60.dp)
                    .fillMaxWidth(0.9f)
                    .height(200.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Illustration Placeholder
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👨‍👩‍👧‍👦", fontSize = 64.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Supervision Mode enabled",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "The guardian ($guardianEmail) can manage how this device is used now.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))

        // Action Items
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeActionItem(
                title = "Focus Mode",
                icon = Icons.Default.Face,
                iconColor = Color(0xFF22C55E),
                action = {
                    Switch(
                        checked = isFocusMode,
                        onCheckedChange = onFocusModeToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = Color(0xFF22C55E)
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            
            AllowedAppsCard(allowedApps = allowedApps)
        }
    }
}

@Composable
fun HomeActionItem(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit
) {
    Card(
        onClick = onClick ?: {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = onClick != null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
            action()
        }
    }
}

@Composable
fun CurrentStatusCard(
    mode: AppMode,
    screenTime: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (mode) {
                AppMode.STUDY_MODE -> MaterialTheme.colorScheme.primaryContainer
                AppMode.SLEEP_MODE -> MaterialTheme.colorScheme.secondaryContainer
                AppMode.FOCUS_MODE -> MaterialTheme.colorScheme.tertiaryContainer
                AppMode.NORMAL -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Icon
            val (icon, title, description) = when (mode) {
                AppMode.STUDY_MODE -> Triple(
                    "📚",
                    "Study Mode Active",
                    "Only educational apps are available"
                )
                AppMode.SLEEP_MODE -> Triple(
                    "😴",
                    "Sleep Mode Active", 
                    "Time to rest! Most apps are blocked"
                )
                AppMode.FOCUS_MODE -> Triple(
                    "🎯",
                    "Focus Mode Active",
                    "Stay focused on your current task"
                )
                AppMode.NORMAL -> Triple(
                    "🛡️",
                    "Safe Mode",
                    "You're protected and monitored"
                )
            }
            
            Text(
                text = icon,
                style = MaterialTheme.typography.displayMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Screen Time Today
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = "Screen Time",
                    modifier = Modifier.size(20.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "Screen time today: $screenTime",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun QuickActionsCard(
    isPaired: Boolean,
    onPairDevice: () -> Unit,
    onEmergency: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pair with Parent
                Button(
                    onClick = onPairDevice,
                    modifier = Modifier.weight(1f),
                    colors = if (isPaired) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            if (isPaired) Icons.Default.CheckCircle else Icons.Default.ConnectedTv, 
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(if (isPaired) "Connected" else "Pair Device")
                    }
                }
                
                // Emergency Button
                Button(
                    onClick = onEmergency,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Emergency")
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveModeCard(mode: AppMode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = when (mode) {
                    AppMode.STUDY_MODE -> "Study Mode Guidelines"
                    AppMode.SLEEP_MODE -> "Sleep Mode Guidelines"
                    AppMode.FOCUS_MODE -> "Focus Mode Guidelines"
                    AppMode.NORMAL -> "Normal Mode"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val guidelines = when (mode) {
                AppMode.STUDY_MODE -> listOf(
                    "📖 Educational apps are available",
                    "🚫 Games and social media are blocked",
                    "⏰ Focus on your studies",
                    "📱 Limited notifications"
                )
                AppMode.SLEEP_MODE -> listOf(
                    "😴 Time to sleep and rest",
                    "🚫 Most apps are blocked",
                    "🆘 Emergency features still work",
                    "🌙 Good night!"
                )
                AppMode.FOCUS_MODE -> listOf(
                    "🎯 Stay focused on your task",
                    "⏱️ Timer is running",
                    "🚫 Distracting apps are blocked",
                    "💪 You can do this!"
                )
                AppMode.NORMAL -> emptyList()
            }
            
            guidelines.forEach { guideline ->
                Text(
                    text = guideline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun AllowedAppsCard(allowedApps: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Available Apps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (allowedApps.isEmpty()) {
                Text(
                    text = "Loading available apps...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Show allowed apps
                allowedApps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Text(
                            text = app,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TodayActivityCard(
    screenTime: String,
    appsUsed: String = "0",
    studyTime: String = "0h 0m"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Today's Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActivityStat(
                    icon = Icons.Default.Schedule,
                    label = "Screen Time",
                    value = screenTime
                )
                
                ActivityStat(
                    icon = Icons.Default.GridView,
                    label = "Apps Used",
                    value = appsUsed
                )
                
                ActivityStat(
                    icon = Icons.Default.MenuBook,
                    label = "Study Time",
                    value = studyTime
                )
            }
        }
    }
}

@Composable
fun ActivityStat(
    icon: ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SafetyFeaturesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Safety Features Active",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val features = listOf(
                "🛡️ Content filtering active",
                "📍 Location sharing with parents",
                "🚫 App restrictions enforced",
                "⏰ Screen time monitoring",
                "🆘 Emergency features available"
            )
            
            features.forEach { feature ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Text(
                        text = feature,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ParentConnectionCard(parents: List<com.example.safe_kids.auth.User>, isPaired: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Parent Connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (!isPaired) {
                 Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Text(
                        text = "Not connected to any parent device.\nTap 'Pair Device' to link.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                parents.forEach { parent ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column {
                            Text(
                                text = "Connected to ${parent.displayName ?: "Parent"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmergencyDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "🆘 Emergency Alert",
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Text("This will immediately alert your parents with your current location. Use only in real emergencies.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Send Emergency Alert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PairingDialog(
    pairingCode: String,
    isGenerating: Boolean = false,
    errorMessage: String? = null,
    onGenerateCode: () -> Unit,
    onUseCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var codeToEnter by remember { mutableStateOf("") }
    var selectedTabIndex by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with Parent") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Generate a code to connect with your parent's device:",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isGenerating) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Generating code...", style = MaterialTheme.typography.bodySmall)
                } else if (pairingCode.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = pairingCode,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Show this code to your parent to link this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Tap 'Generate Code' to start")
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
             Button(onClick = onGenerateCode, enabled = !isGenerating) {
                 Text(if (isGenerating) "Generating..." else "Generate Code")
             }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun ChildProfileSection(
    authManager: AuthManagerImpl
) {
    val currentUser by authManager.currentUser.collectAsState()
    val context = LocalContext.current
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Actions
        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column {
                SettingsItem(
                    title = "Family Chat",
                    description = "Chat with your parents",
                    icon = Icons.Default.Chat,
                    onClick = { context.startActivity(Intent(context, com.example.safe_kids.ui.chat.FamilyChatActivity::class.java)) }
                )
            }
        }
        
    }
}

@Composable
fun SettingsItem(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
@Composable
fun SettingsToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSetupDialog(
    onDismiss: () -> Unit,
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf(0) }
    var deviceName by remember { mutableStateOf("Child's Device") }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var locationEnabled by remember { mutableStateOf(true) }
    var usageMonitoringEnabled by remember { mutableStateOf(true) }
    val context = LocalContext.current
    
    val steps = listOf(
        "Welcome",
        "Device Name", 
        "Permissions",
        "Complete Setup"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = { 
            Text(
                text = "Device Setup",
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Progress indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(steps.size) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (index <= currentStep) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Step content
                when (currentStep) {
                    0 -> WelcomeStep()
                    1 -> DeviceNameStep(
                        deviceName = deviceName,
                        onDeviceNameChange = { deviceName = it }
                    )
                    2 -> PermissionsStep(
                        notificationsEnabled = notificationsEnabled,
                        onNotificationsChange = { notificationsEnabled = it },
                        locationEnabled = locationEnabled,
                        onLocationChange = { locationEnabled = it },
                        usageMonitoringEnabled = usageMonitoringEnabled,
                        onUsageMonitoringChange = { usageMonitoringEnabled = it }
                    )
                    3 -> CompleteSetupStep(
                        deviceName = deviceName,
                        notificationsEnabled = notificationsEnabled,
                        locationEnabled = locationEnabled,
                        usageMonitoringEnabled = usageMonitoringEnabled
                    )
                }
            }
        },
        confirmButton = {
            when (currentStep) {
                steps.lastIndex -> {
                    Button(
                        onClick = {
                            // Save settings and complete setup
                            onComplete()
                        }
                    ) {
                        Text("Complete Setup")
                    }
                }
                else -> {
                    Button(
                        onClick = { currentStep++ }
                    ) {
                        Text("Next")
                    }
                }
            }
        },
        dismissButton = {
            if (currentStep > 0) {
                TextButton(onClick = { currentStep-- }) {
                    Text("Back")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun WelcomeStep() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "👋",
            fontSize = 64.sp
        )
        
        Text(
            text = "Welcome to Safe Kids!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "Let's set up your device to keep you safe and connected with your parents.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "What we'll set up:",
                    fontWeight = FontWeight.Bold
                )
                Text("• Device name and profile", fontSize = 14.sp)
                Text("• Important permissions", fontSize = 14.sp)
                Text("• Safety and monitoring features", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun DeviceNameStep(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "📱",
            fontSize = 64.sp
        )
        
        Text(
            text = "Name Your Device",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "Choose a name for this device so your parents can easily identify it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceNameChange,
            label = { Text("Device Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        val suggestions = listOf("Emma's Tablet", "Liam's Phone", "Sophie's Device")
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Suggestions:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            suggestions.forEach { suggestion ->
                TextButton(
                    onClick = { onDeviceNameChange(suggestion) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(suggestion)
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep(
    notificationsEnabled: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    locationEnabled: Boolean,
    onLocationChange: (Boolean) -> Unit,
    usageMonitoringEnabled: Boolean,
    onUsageMonitoringChange: (Boolean) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🔐",
            fontSize = 64.sp
        )
        
        Text(
            text = "Permissions & Safety",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "Enable these features for the best protection and experience.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PermissionToggleItem(
                    title = "Notifications",
                    description = "Get alerts from parents and safety notifications",
                    icon = Icons.Default.Notifications,
                    enabled = notificationsEnabled,
                    onToggle = onNotificationsChange
                )
                
                HorizontalDivider()
                
                PermissionToggleItem(
                    title = "Location Services",
                    description = "Allow parents to know your location for safety",
                    icon = Icons.Default.LocationOn,
                    enabled = locationEnabled,
                    onToggle = onLocationChange
                )
                
                HorizontalDivider()
                
                PermissionToggleItem(
                    title = "Usage Monitoring",
                    description = "Monitor app usage and screen time for healthy habits",
                    icon = Icons.Default.AccessTime,
                    enabled = usageMonitoringEnabled,
                    onToggle = onUsageMonitoringChange
                )
            }
        }
    }
}

@Composable
private fun PermissionToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun CompleteSetupStep(
    deviceName: String,
    notificationsEnabled: Boolean,
    locationEnabled: Boolean,
    usageMonitoringEnabled: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "✅",
            fontSize = 64.sp
        )
        
        Text(
            text = "Setup Complete!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "Your device is ready to connect with your parents.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Setup Summary:",
                    fontWeight = FontWeight.Bold
                )
                
                Text("Device: $deviceName", fontSize = 14.sp)
                Text("Notifications: ${if (notificationsEnabled) "Enabled" else "Disabled"}", fontSize = 14.sp)
                Text("Location: ${if (locationEnabled) "Enabled" else "Disabled"}", fontSize = 14.sp)
                Text("Usage Monitoring: ${if (usageMonitoringEnabled) "Enabled" else "Disabled"}", fontSize = 14.sp)
            }
        }
        
        Text(
            text = "Click 'Complete Setup' to generate your pairing code and connect with your parents.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditChildProfileDialog(
    authManager: AuthManagerImpl,
    onDismiss: () -> Unit
) {
    val currentUser by authManager.currentUser.collectAsState()
    var name by remember { mutableStateOf(currentUser?.displayName ?: "") }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        isSaving = true
                        scope.launch {
                            val result = authManager.updateUserProfile(mapOf("displayName" to name))
                            isSaving = false
                            if (result.isSuccess) {
                                android.widget.Toast.makeText(context, "Profile updated", android.widget.Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                android.widget.Toast.makeText(context, "Failed to update profile", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                enabled = !isSaving && name.isNotBlank()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("Cancel")
            }
        }
    )
}

private fun formatScreenTime(millis: Long): String {
    val totalMinutes = millis / (1000 * 60)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "${hours}h ${minutes}m"
}

@Composable
fun StudyModeBanner(config: com.example.safe_kids.data.StudyModeConfig) {
    var timeRemaining by remember { mutableStateOf("") }
    
    LaunchedEffect(config) {
        while (true) {
            val now = Calendar.getInstance()
            try {
                val parts = config.endTime.split(":")
                val endCalendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    set(Calendar.MINUTE, parts[1].toInt())
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                
                var diff = endCalendar.timeInMillis - now.timeInMillis
                
                // If diff is negative, it might be for tomorrow, but Study Mode is active 
                // only during scheduled hours. So if we are here, it should be today.
                if (diff <= 0) {
                    timeRemaining = "Ending..."
                    break
                }
                
                val h = diff / 3600000
                val m = (diff % 3600000) / 60000
                val s = (diff % 60000) / 1000
                timeRemaining = String.format("%02d:%02d:%02d", h, m, s)
            } catch (e: Exception) {
                timeRemaining = "--:--:--"
            }
            delay(1000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Study Mode Active", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Time Remaining: $timeRemaining", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                }
                Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
            
            if (config.allowedApps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Allowed Apps:", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    config.allowedApps.take(4).forEach { pkg ->
                        Surface(
                            modifier = Modifier.size(24.dp),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.5f)
                        ) {
                           Box(contentAlignment = Alignment.Center) {
                               Icon(Icons.Default.Apps, null, modifier = Modifier.size(14.dp))
                           }
                        }
                    }
                    if (config.allowedApps.size > 4) {
                        Text("+${config.allowedApps.size - 4} more", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun StudyModeCompletionDialog(duration: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Study Session Complete!") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Great job! You studied for $duration minutes.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Normal application access has been restored.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Awesome!") }
        }
    )
}

@Composable
fun SleepModeBanner(config: com.example.safe_kids.data.SleepModeConfig) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appBlockingService = remember { com.example.safe_kids.services.blocking.AppBlockingServiceImpl(context) }
    var emergencyTimeLeft by remember { mutableStateOf(appBlockingService.getSleepEmergencyRemainingMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            emergencyTimeLeft = appBlockingService.getSleepEmergencyRemainingMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = if (emergencyTimeLeft > 0) Color(0xFFFF9800) else Color(0xFF3F51B5)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (emergencyTimeLeft > 0) "Emergency Usage Active" else "Sleep Mode Active", fontWeight = FontWeight.Bold, color = Color.White)
                    if (emergencyTimeLeft > 0) {
                        val minutes = (emergencyTimeLeft / 60000).toInt()
                        val seconds = ((emergencyTimeLeft % 60000) / 1000).toInt()
                        Text("Ends in: ${String.format("%d:%02d", minutes, seconds)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    } else {
                        Text("Unlocks at: ${config.endTime}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                }
                Icon(if (emergencyTimeLeft > 0) Icons.Default.Warning else Icons.Default.Bedtime, null, modifier = Modifier.size(32.dp), tint = Color.White)
            }
            
            if (emergencyTimeLeft <= 0) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { 
                        appBlockingService.activateSleepEmergencyOverride()
                        logSleepEmergency(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Emergency Override (5 min)", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun logSleepEmergency(context: android.content.Context) {
    val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
    
    db.collection("child_profiles").document(currentUser.uid).get()
        .addOnSuccessListener { snapshot ->
            val parentId = snapshot.getString("parentId") ?: return@addOnSuccessListener
            val eventId = java.util.UUID.randomUUID().toString()
            val event = mapOf(
                "childId" to currentUser.uid,
                "timestamp" to System.currentTimeMillis(),
                "durationMinutes" to 5,
                "reason" to "Child initiated emergency override"
            )
            db.collection("sleep_mode_emergency").document(parentId)
                .collection("events").document(eventId).set(event)
        }
}
