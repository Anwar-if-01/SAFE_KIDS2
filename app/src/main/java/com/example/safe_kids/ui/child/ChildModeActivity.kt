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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import android.provider.Settings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import coil.compose.AsyncImage
import coil.request.ImageRequest

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
        Log.d("SafeKidsDebug", "ChildModeActivity: onCreate started")
        
        // FIX #6: Check if ALL permissions are granted before allowing child dashboard
        if (!com.example.safe_kids.utils.PermissionManager.areAllPermissionsGranted(this)) {
            Log.w("SafeKidsDebug", "ChildModeActivity: Permissions not granted! Redirecting to RequiredPermissionsActivity")
            val missingPermissions = com.example.safe_kids.utils.PermissionManager.getMissingPermissions(this)
            Log.w("SafeKidsDebug", "ChildModeActivity: Missing permissions: $missingPermissions")
            
            val intent = Intent(this, RequiredPermissionsActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }
        
        Log.d("SafeKidsDebug", "ChildModeActivity: All permissions granted, proceeding with dashboard")
        
        // Initialize services
        authManager = AuthManagerImpl(this)
        
        lifecycleScope.launch {
            authManager.initialize()
        }
        
        locationService = LocationServiceImpl(this, authManager)
        appBlockingService = AppBlockingServiceImpl(this)
        notificationService = NotificationServiceImpl(this)
        deviceStatusMonitor = DeviceStatusMonitor(this)
        restrictionRepository = com.example.safe_kids.data.RestrictionRepository(this)

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
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        if (::locationService.isInitialized) {
            try { locationService.stopLocationTracking() } catch (e: Exception) { }
        }
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
    // FIX 1: Firestore-approved apps
    val firestoreAllowedApps by childViewModel.firestoreAllowedApps.collectAsState()
    val hasParentSetApps by childViewModel.hasParentSetApps.collectAsState()
    
    val scope = rememberCoroutineScope()

    // FIX 2: Focus Mode state sync to Firestore
    // FIX 7: Sign Out state for Parental Settings
    var showSignOutDialog by remember { mutableStateOf(false) }
    var signOutPinsVerified by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val allPermissionsGranted = com.example.safe_kids.utils.PermissionManager.areAllPermissionsGranted(context)
                if (!allPermissionsGranted) {
                    Log.w("SafeKidsDebug", "ChildModeScreen: Permissions revoked! Redirecting to RequiredPermissionsActivity")
                    context.startActivity(Intent(context, RequiredPermissionsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                }
                permissionsMissing = !allPermissionsGranted
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

    // FIX 1: Observe Firestore allowed apps when child ID is available
    LaunchedEffect(currentUser) {
        currentUser?.let { user ->
            childViewModel.observeFirestoreAllowedApps(user.id)
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

    LaunchedEffect(currentAppMode) {
        if (currentAppMode == AppMode.NORMAL && studyModeConfig != null) {
             // Logic to detect if a session just ended
        }
    }

    LaunchedEffect(isAuthenticated, currentUser) {
        if (isAuthenticated && currentUser != null) {
            currentMode = appBlockingService.getCurrentMode()
            
            val usageMillis = appBlockingService.getAppUsageToday("Total")
            screenTimeToday = formatScreenTime(usageMillis)
            
            currentUser?.let { user ->
                scope.launch {
                    restrictionRepository.startRestrictionSync(user.id).collect {}
                }

                locationService.setChildId(user.id)
                locationService.startLocationTracking()
                locationService.enableLocationSharing(user.id)

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

    var isInstantBlockedState by remember { mutableStateOf(false) }

    LaunchedEffect(currentUser) {
        currentUser?.let { user ->
            try {
                FirebaseFirestore.getInstance()
                    .collection("children")
                    .document(user.id)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                        val instantBlock = snapshot.getBoolean("instantBlock") ?: false
                        Log.d("PermissionFlow", "Firestore instantBlock = $instantBlock")
                        isInstantBlockedState = instantBlock
                    }
            } catch (e: Exception) {
                Log.e("PermissionFlow", "Failed to listen for instantBlock changes", e)
            }
        }
    }

    // FIX 7: Sign out handler (requires PIN via ParentalSettings)
    val handleSignOut: () -> Unit = {
        com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(context, authManager, scope)
    }

    // Determine which apps to show: Firestore-approved ones, or local restrictions
    val displayApps = if (hasParentSetApps) {
        firestoreAllowedApps
    } else {
        dynamicAllowedApps
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
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
                                    // FIX 9: Profile image with Coil
                                    if (!currentUser?.profileImageUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(currentUser?.profileImageUrl)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = "Profile",
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF7C4DFF),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = currentUser?.displayName?.take(1)?.uppercase() ?: "C",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
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
                                text = { Text("Edit Profile") },
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
                            // FIX 3: "Chat with Parent" REMOVED from 3-dots menu (already in Family tab)
                            // FIX 8: "Switch Mode" REMOVED from 3-dots menu entirely
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                contentColor = Color(0xFF1A1A1A)
            ) {
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
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFD32F2F))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "⚠️ Important permission missing - tap to fix",
                                color = Color(0xFFD32F2F),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                // FIX 6: Redesigned child home screen
                ChildHomeScreen(
                    paddingValues = paddingValues,
                    deviceName = deviceStatus.deviceName,
                    guardianEmail = connectedParents.firstOrNull()?.email ?: "guardian",
                    isInstantBlocked = isInstantBlockedState,
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
                    // FIX 1: Pass Firestore-approved apps
                    allowedApps = displayApps,
                    hasParentSetApps = hasParentSetApps,
                    currentAppMode = currentMode,
                    studyModeConfig = studyModeConfig,
                    sleepModeConfig = sleepModeConfig,
                    // FIX 9: Pass child info for avatar
                    childName = currentUser?.displayName ?: "Child",
                    childPhotoUrl = currentUser?.profileImageUrl ?: ""
                )
            }
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
    }

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
            duration = 45,
            onDismiss = { showStudyCompletionDialog = false }
        )
    }
}

// FIX 6: REDESIGNED CHILD HOME SCREEN
@Composable
fun ChildHomeScreen(
    paddingValues: PaddingValues,
    deviceName: String,
    guardianEmail: String,
    isInstantBlocked: Boolean,
    onParentalSettingsClick: () -> Unit,
    onPairDevice: () -> Unit,
    onEmergency: () -> Unit,
    allowedApps: List<String>,
    hasParentSetApps: Boolean,
    currentAppMode: com.example.safe_kids.services.blocking.AppMode,
    studyModeConfig: com.example.safe_kids.data.StudyModeConfig?,
    sleepModeConfig: com.example.safe_kids.data.SleepModeConfig?,
    childName: String,
    childPhotoUrl: String
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (currentAppMode == com.example.safe_kids.services.blocking.AppMode.STUDY_MODE && studyModeConfig != null) {
            StudyModeBanner(studyModeConfig)
        } else if (currentAppMode == com.example.safe_kids.services.blocking.AppMode.SLEEP_MODE && sleepModeConfig != null) {
            SleepModeBanner(sleepModeConfig)
        }
        
        // FIX 6: Gradient header with avatar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF5C6BC0), Color(0xFF7C4DFF))
                    )
                )
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // FIX 9: Profile image - Coil or letter avatar
                if (childPhotoUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(childPhotoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Child profile",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.3f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = childName.take(1).uppercase(),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Hi, $childName!",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Supervision status chip
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "🛡️ Under Supervision",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // FIX 2: Instant Block read-only status indicator
        // Child CANNOT toggle — only parent controls it via instantBlock
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isInstantBlocked) Color(0xFFFFF3E0) else Color(0xFFE8F5E9)
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isInstantBlocked) Color(0xFFFF9800) else Color(0xFF4CAF50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isInstantBlocked) Icons.Default.Lock else Icons.Default.Face,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isInstantBlocked) "Instant Block is Active" else "Instant Block is Off",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF1A1A1A)
                    )
                    // Read-only status description
                    Text(
                        text = if (isInstantBlocked) "Set by your parent — only approved apps are accessible"
                              else "Your parent can enable this to restrict access",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
                
                // Read-only lock icon instead of toggle switch
                Icon(
                    imageVector = if (isInstantBlocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isInstantBlocked) "Locked by parent" else "Unlocked",
                    tint = if (isInstantBlocked) Color(0xFFFF9800) else Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Allowed Apps section - FIX 6: Colorful grid
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Approved Apps",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF1A1A1A),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            if (!hasParentSetApps && allowedApps.isEmpty()) {
                // FIX 1: Show "No apps approved" message when parent hasn't set any
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Apps,
                            contentDescription = null,
                            tint = Color(0xFFBDBDBD),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No apps have been approved by your parent yet",
                            color = Color(0xFF757575),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Ask your parent to approve apps from their dashboard",
                            color = Color(0xFF9E9E9E),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                // Colorful 2-column grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(allowedApps.take(12)) { appName ->
                        AppGridCard(appName = appName)
                    }
                    if (allowedApps.size > 12) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "+${allowedApps.size - 12} more",
                                        color = Color(0xFF757575),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Quick Actions row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onEmergency,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF9A9A))
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Emergency")
            }
            OutlinedButton(
                onClick = onPairDevice,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5C6BC0)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC5CAE9))
            ) {
                Icon(Icons.Default.ConnectedTv, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pair Device")
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// FIX 6: App grid card with pastel colors
@Composable
fun AppGridCard(appName: String) {
    val pastelColors = listOf(
        Color(0xFFE3F2FD), Color(0xFFFCE4EC), Color(0xFFE8F5E9),
        Color(0xFFFFF3E0), Color(0xFFF3E5F5), Color(0xFFE0F7FA),
        Color(0xFFFFFDE7), Color(0xFFFBE9E7), Color(0xFFE8EAF6),
        Color(0xFFFFF8E1), Color(0xFFF1F8E9), Color(0xFFFFF0F0)
    )
    val bgColor = pastelColors[appName.hashCode().let { if (it < 0) -it else it } % pastelColors.size]
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App icon placeholder with first letter
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.7f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = appName.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF555555)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = appName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
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
                fontSize = 16.sp,
                color = Color(0xFF1A1A1A)
            )
            action()
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
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (allowedApps.isEmpty()) {
                Text(
                    text = "Loading available apps...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF757575)
                )
            } else {
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF1A1A1A)
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
            tint = Color(0xFF5C6BC0)
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A)
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF757575)
        )
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
                color = Color(0xFFD32F2F)
            )
        },
        text = {
            Text("This will immediately alert your parents with your current location. Use only in real emergencies.", color = Color(0xFF1A1A1A))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F)
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
        title = { Text("Pair with Parent", color = Color(0xFF1A1A1A)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Generate a code to connect with your parent's device:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1A1A1A)
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
                            containerColor = Color(0xFFE8EAF6)
                        )
                    ) {
                        Text(
                            text = pairingCode,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF1A1A1A),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Show this code to your parent to link this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF757575),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Tap 'Generate Code' to start", color = Color(0xFF757575))
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD32F2F),
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
            Icon(icon, contentDescription = null, tint = Color(0xFF5C6BC0))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                Text(description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF757575))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF757575))
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
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
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
                                        Color(0xFF5C6BC0) 
                                    else 
                                        Color(0xFFE0E0E0)
                                )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
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
                        onClick = onComplete,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
                    ) {
                        Text("Complete Setup", color = Color.White)
                    }
                }
                else -> {
                    Button(
                        onClick = { currentStep++ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
                    ) {
                        Text("Next", color = Color.White)
                    }
                }
            }
        },
        dismissButton = {
            if (currentStep > 0) {
                TextButton(onClick = { currentStep-- }) {
                    Text("Back", color = Color(0xFF757575))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF757575))
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
            textAlign = TextAlign.Center,
            color = Color(0xFF1A1A1A)
        )
        
        Text(
            text = "Let's set up your device to keep you safe and connected with your parents.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color(0xFF757575)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFE8EAF6)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "What we'll set up:",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                Text("• Device name and profile", fontSize = 14.sp, color = Color(0xFF1A1A1A))
                Text("• Important permissions", fontSize = 14.sp, color = Color(0xFF1A1A1A))
                Text("• Safety and monitoring features", fontSize = 14.sp, color = Color(0xFF1A1A1A))
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
            textAlign = TextAlign.Center,
            color = Color(0xFF1A1A1A)
        )
        
        Text(
            text = "Choose a name for this device so your parents can easily identify it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color(0xFF757575)
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
                color = Color(0xFF757575)
            )
            suggestions.forEach { suggestion ->
                TextButton(
                    onClick = { onDeviceNameChange(suggestion) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(suggestion, color = Color(0xFF5C6BC0))
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
            textAlign = TextAlign.Center,
            color = Color(0xFF1A1A1A)
        )
        
        Text(
            text = "Enable these features for the best protection and experience.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color(0xFF757575)
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
            tint = Color(0xFF5C6BC0),
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF757575)
            )
        }
        
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF5C6BC0)
            )
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
            textAlign = TextAlign.Center,
            color = Color(0xFF1A1A1A)
        )
        
        Text(
            text = "Your device is ready to connect with your parents.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color(0xFF757575)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFE8EAF6)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Setup Summary:",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                
                Text("Device: $deviceName", fontSize = 14.sp, color = Color(0xFF1A1A1A))
                Text("Notifications: ${if (notificationsEnabled) "Enabled" else "Disabled"}", fontSize = 14.sp, color = Color(0xFF1A1A1A))
                Text("Location: ${if (locationEnabled) "Enabled" else "Disabled"}", fontSize = 14.sp, color = Color(0xFF1A1A1A))
                Text("Usage Monitoring: ${if (usageMonitoringEnabled) "Enabled" else "Disabled"}", fontSize = 14.sp, color = Color(0xFF1A1A1A))
            }
        }
        
        Text(
            text = "Click 'Complete Setup' to generate your pairing code and connect with your parents.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color(0xFF757575)
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
        title = { Text("Edit Profile", color = Color(0xFF1A1A1A)) },
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
                enabled = !isSaving && name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Save", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("Cancel", color = Color(0xFF757575))
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6)),
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
                    Text("Study Mode Active", fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                    Text("Time Remaining: $timeRemaining", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1A1A))
                }
                Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(32.dp), tint = Color(0xFF5C6BC0))
            }
            
            if (config.allowedApps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Allowed Apps:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF757575))
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    config.allowedApps.take(4).forEach { pkg ->
                        Surface(
                            modifier = Modifier.size(24.dp),
                            shape = CircleShape,
                            color = Color(0xFF5C6BC0).copy(alpha = 0.2f)
                        ) {
                           Box(contentAlignment = Alignment.Center) {
                               Icon(Icons.Default.Apps, null, modifier = Modifier.size(14.dp), tint = Color(0xFF5C6BC0))
                           }
                        }
                    }
                    if (config.allowedApps.size > 4) {
                        Text("+${config.allowedApps.size - 4} more", style = MaterialTheme.typography.labelSmall, color = Color(0xFF757575))
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
        title = { Text("Study Session Complete!", color = Color(0xFF1A1A1A)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Great job! You studied for $duration minutes.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF1A1A1A)
                )
                Text(
                    "Normal application access has been restored.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF757575)
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                Text("Awesome!", color = Color.White)
            }
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
        colors = CardDefaults.cardColors(containerColor = if (emergencyTimeLeft > 0) Color(0xFFFFF3E0) else Color(0xFFE8EAF6)),
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
                    Text(if (emergencyTimeLeft > 0) "Emergency Usage Active" else "Sleep Mode Active", fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                    if (emergencyTimeLeft > 0) {
                        val minutes = (emergencyTimeLeft / 60000).toInt()
                        val seconds = ((emergencyTimeLeft % 60000) / 1000).toInt()
                        Text("Ends in: ${String.format("%d:%02d", minutes, seconds)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1A1A))
                    } else {
                        Text("Unlocks at: ${config.endTime}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1A1A))
                    }
                }
                Icon(if (emergencyTimeLeft > 0) Icons.Default.Warning else Icons.Default.Bedtime, null, modifier = Modifier.size(32.dp), tint = Color(0xFF5C6BC0))
            }
            
            if (emergencyTimeLeft <= 0) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { 
                        appBlockingService.activateSleepEmergencyOverride()
                        logSleepEmergency(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0)),
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