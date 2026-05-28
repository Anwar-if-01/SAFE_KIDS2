package com.example.safe_kids.ui.parent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.safe_kids.MainActivity
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.auth.UserRole
import com.example.safe_kids.auth.isParentLike
import com.example.safe_kids.auth.ParentActionsManager
import com.example.safe_kids.data.ChildProfile
import com.example.safe_kids.services.blocking.AppBlockingServiceImpl
import com.example.safe_kids.services.location.LocationServiceImpl
import com.example.safe_kids.services.notifications.NotificationServiceImpl
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.DeviceStatusMonitor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import com.example.safe_kids.ui.components.DailyUsage
import com.example.safe_kids.ui.components.AppUsageInfo
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

// Colors
private val PrimaryBlue = Color(0xFF3B4FBF)
private val AccentOrange = Color(0xFFF5A623)
private val SuccessGreen = Color(0xFF4CAF50)
private val WarningRed = Color(0xFFFF5722)
private val PurpleIndigo = Color(0xFF5C3BBF)

class ParentDashboardActivity : ComponentActivity() {
    private lateinit var authManager: AuthManagerImpl
    private lateinit var locationService: LocationServiceImpl
    private lateinit var appBlockingService: AppBlockingServiceImpl
    private lateinit var notificationService: NotificationServiceImpl
    private lateinit var parentActionsManager: ParentActionsManager
    private lateinit var deviceStatusMonitor: DeviceStatusMonitor
    private lateinit var settingsManager: com.example.safe_kids.data.SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = AuthManagerImpl(this)
        locationService = LocationServiceImpl(this, authManager)
        appBlockingService = AppBlockingServiceImpl(this)
        notificationService = NotificationServiceImpl(this)
        parentActionsManager = ParentActionsManager(FirebaseFirestore.getInstance())
        deviceStatusMonitor = DeviceStatusMonitor(this)
        settingsManager = com.example.safe_kids.data.SettingsManager(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        lifecycleScope.launch { authManager.initialize() }

        setContent {
            val themeMode by settingsManager.themeMode.collectAsState(initial = com.example.safe_kids.data.ThemeMode.SYSTEM)
            val textSize by settingsManager.textSize.collectAsState(initial = 1.0f)
            val displaySize by settingsManager.displaySize.collectAsState(initial = 1.0f)
            val useDarkTheme = when (themeMode) {
                com.example.safe_kids.data.ThemeMode.LIGHT -> false
                com.example.safe_kids.data.ThemeMode.DARK -> true
                com.example.safe_kids.data.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            SAFE_KIDSTheme(darkTheme = useDarkTheme, textSize = textSize, displaySize = displaySize) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ParentDashboardScreen(
                        authManager = authManager,
                        locationService = locationService,
                        appBlockingService = appBlockingService,
                        notificationService = notificationService,
                        parentActionsManager = parentActionsManager,
                        deviceStatusMonitor = deviceStatusMonitor,
                        onNavigateToChildSetup = {
                            startActivity(Intent(this@ParentDashboardActivity, ChildSetupActivity::class.java))
                        },
                        onNavigateToReports = { childId, childName ->
                            startActivity(Intent(this@ParentDashboardActivity, ReportsActivity::class.java).apply {
                                putExtra("CHILD_ID", childId); putExtra("CHILD_NAME", childName)
                            })
                        }
                    )
                }
            }
        }
    }
    override fun onDestroy() {
        if (::locationService.isInitialized) try { locationService.stopLocationTracking() } catch (_: Exception) {}
        super.onDestroy()
    }
}

data class ParentNotification(val title: String, val body: String, val timestamp: Long?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboardScreen(
    authManager: AuthManagerImpl,
    locationService: LocationServiceImpl,
    appBlockingService: AppBlockingServiceImpl,
    notificationService: NotificationServiceImpl,
    parentActionsManager: ParentActionsManager,
    deviceStatusMonitor: DeviceStatusMonitor,
    onNavigateToChildSetup: () -> Unit,
    onNavigateToReports: (String, String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var children by remember { mutableStateOf<List<ChildProfile>>(emptyList()) }
    var selectedChild by remember { mutableStateOf<ChildProfile?>(null) }
    val viewModel: ParentDashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val vmDeviceStatus by viewModel.deviceStatus.collectAsState()
    var isLoading by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var notificationsHistory by remember { mutableStateOf<List<ParentNotification>>(emptyList()) }
    var pendingInvitation by remember { mutableStateOf<com.example.safe_kids.auth.InviteCode?>(null) }
    var isAcceptingInvite by remember { mutableStateOf(false) }
    val firebaseUid = remember { FirebaseAuth.getInstance().currentUser?.uid }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentUser by authManager.currentUser.collectAsState()
    val isAuthenticated by authManager.isAuthenticated.collectAsState()

    DisposableEffect(firebaseUid) {
        val uid = firebaseUid ?: return@DisposableEffect onDispose {}
        isLoading = true
        val db = FirebaseFirestore.getInstance()
        val registration = db.collection(com.example.safe_kids.auth.AuthConfig.USERS_COLLECTION).document(uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) { isLoading = false; return@addSnapshotListener }
                val childIds = snapshot.get("childIds") as? List<String> ?: emptyList()
                scope.launch {
                    val childrenList = mutableListOf<ChildProfile>()
                    try {
                        if (childIds.isEmpty()) { children = emptyList(); selectedChild = null; return@launch }
                        kotlinx.coroutines.withTimeoutOrNull(15_000) {
                            for (childId in childIds) {
                                try {
                                    val childDoc = db.collection(com.example.safe_kids.auth.AuthConfig.USERS_COLLECTION).document(childId).get().await()
                                    val childUser = childDoc.toObject(com.example.safe_kids.auth.User::class.java)
                                    if (childUser != null) childrenList.add(ChildProfile(
                                        id = childUser.id, name = childUser.displayName ?: "Child", age = 0,
                                        parentIds = childUser.parentIds, profileImageUrl = childUser.profileImageUrl,
                                        deviceName = childUser.deviceName, isActive = true
                                    ))
                                } catch (_: Exception) {}
                            }
                        }
                        children = childrenList
                        if (selectedChild == null || !childrenList.any { it.id == selectedChild?.id }) selectedChild = childrenList.firstOrNull()
                    } catch (_: Exception) {} finally { isLoading = false }
                }
            }
        onDispose { registration.remove() }
    }
    DisposableEffect(selectedChild?.id) { val childId = selectedChild?.id; if (childId != null) viewModel.selectChild(childId); onDispose {} }

    // Notification history listener
    DisposableEffect(selectedChild?.id) {
        val childId = selectedChild?.id ?: return@DisposableEffect onDispose {}
        val db = FirebaseFirestore.getInstance()
        val registration = db.collection("child_notifications").whereEqualTo("childId", childId)
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(20)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                notificationsHistory = snapshot.documents.mapNotNull { doc ->
                    ParentNotification(
                        title = doc.getString("title")?.takeIf { it.isNotBlank() } ?: doc.getString("appName") ?: "Notification",
                        body = doc.getString("text") ?: "",
                        timestamp = doc.getLong("timestamp") ?: doc.getLong("postTime")
                    )
                }
            }
        onDispose { registration.remove() }
    }

    // Pending invitation is now code-only — no email matching needed
// Co-parent enters the code manually in InviteCodeScreen during onboarding

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(
                    Triple(0, Icons.Default.Dashboard, "Dashboard"),
                    Triple(1, Icons.Default.PhoneAndroid, "Device"),
                    Triple(2, Icons.Default.LocationOn, "Location"),
                    Triple(3, Icons.Default.FavoriteBorder, "Family"),
                    Triple(4, Icons.Default.PersonOutline, "My")
                ).forEach { (index, icon, label) ->
                    NavigationBarItem(
                        selected = selectedTab == index, onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent, selectedTextColor = MaterialTheme.colorScheme.primary, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> {
                    if (isLoading) ShimmerLoadingScreen()
                    else if (children.isNotEmpty() && selectedChild != null) DashboardTab(
                        child = selectedChild!!, viewModel = viewModel, currentUser = currentUser,
                        parentActionsManager = parentActionsManager, childDeviceStatus = vmDeviceStatus,
                        onNotificationsClick = { showNotificationsDialog = true }, onNavigateToReports = onNavigateToReports
                    ) else NoChildLinkedScreen(context)
                }
                1 -> {
                    if (children.isNotEmpty() && selectedChild != null) DeviceManagementTab(
                        child = selectedChild!!, authManager = authManager, appBlockingService = appBlockingService,
                        notificationService = notificationService, parentActionsManager = parentActionsManager,
                        isAuthenticated = isAuthenticated, childDeviceStatus = vmDeviceStatus, currentUser = currentUser,
                        recentAppInstalls = remember { mutableStateListOf() },
                        permissionIssueCount = 0, onNotificationsClick = { showNotificationsDialog = true },
                        onAddChildClick = { context.startActivity(Intent(context, AddChildPairingActivity::class.java).apply { putExtra("PARENT_ID", currentUser?.id ?: "") }) }
                    ) else Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { EmptyStateCard("No Device Linked", "Connect a child device to manage restrictions.", Icons.Default.PhoneAndroid) }
                }
                2 -> LocationTab(selectedChild, viewModel, locationService, parentActionsManager)
                3 -> FamilyTab(children = children, onAddChild = { context.startActivity(Intent(context, MainActivity::class.java).apply { putExtra("FORCE_SHOW_PAIRING", true); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }) })
                4 -> MyTab(authManager = authManager, currentUser = currentUser, isAuthenticated = isAuthenticated, children = children, onSignOut = { com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(context, authManager, scope) })
            }
        }
    }
    if (showNotificationsDialog) NotificationsDialog(notifications = notificationsHistory, onDismiss = { showNotificationsDialog = false })
    if (pendingInvitation != null) InviteAlertDialog(pendingInvitation!!, isAcceptingInvite, onAccept = {
        isAcceptingInvite = true; scope.launch { val result = authManager.useCoParentInviteCode(pendingInvitation!!.code); isAcceptingInvite = false; if (result.isSuccess) { pendingInvitation = null; Toast.makeText(context, "Invitation accepted!", Toast.LENGTH_LONG).show() } else Toast.makeText(context, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show() }
    }, onDecline = {
        FirebaseFirestore.getInstance().collection(com.example.safe_kids.auth.AuthConfig.INVITE_CODES_COLLECTION).document(pendingInvitation!!.inviterId).update("isUsed", true)
        pendingInvitation = null
    })
}

@Composable
private fun InviteAlertDialog(invite: com.example.safe_kids.auth.InviteCode, isLoading: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(onDismissRequest = {}, title = { Text("Co-parent Invitation", fontWeight = FontWeight.Bold) },
        text = { Text("You have been invited by another parent to manage their child as a co-parent. Would you like to accept?") },
        confirmButton = { Button(onClick = onAccept, enabled = !isLoading) { if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White) else Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline, enabled = !isLoading) { Text("Decline") } }
    )
}

// ====================== NEW DASHBOARD TAB ======================
@Composable
fun DashboardTab(
    child: ChildProfile, viewModel: ParentDashboardViewModel, currentUser: com.example.safe_kids.auth.User?,
    parentActionsManager: ParentActionsManager, childDeviceStatus: com.example.safe_kids.data.ChildDeviceStatus,
    onNotificationsClick: () -> Unit, onNavigateToReports: (String, String) -> Unit
) {
    val dashboardSummary by viewModel.dashboardSummary.collectAsState()
    val weeklyUsage by viewModel.weeklyUsage.collectAsState()
    val todayTopApps by viewModel.todayTopApps.collectAsState()
    val lastActiveFormatted by viewModel.lastActiveFormatted.collectAsState()
    val isLoadingData by remember { derivedStateOf { dashboardSummary == DashboardSummary() && weeklyUsage.isEmpty() } }

    LazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Gradient Header
        item {
            GradientHeader(childName = child.name, profileImageUrl = child.profileImageUrl, deviceName = childDeviceStatus.deviceName,
                isOnline = childDeviceStatus.isOnline, batteryPercentage = childDeviceStatus.batteryPercentage,
                connectionType = childDeviceStatus.connectionType, wifiName = childDeviceStatus.wifiName,
                lastActiveFormatted = lastActiveFormatted, onNotificationsClick = onNotificationsClick)
        }

        // Stats Grid 2x2
        item {
            StatsGrid(dashboardSummary, child, onNavigateToReports)        }

        // Weekly Activity Chart
        item {
            WeeklyChartCard(weeklyUsage)
        }

        // Quick Actions
        item {
            SectionTitle("Quick Actions")
            QuickActionGrid(child = child, currentUser = currentUser, parentActionsManager = parentActionsManager,
                scope = rememberCoroutineScope(), isCoParent = currentUser?.role == UserRole.CO_PARENT)
        }

        // Today's Event Card
        item {
            SectionTitle("Activity & Reports")
            TodaysEventCard(topApps = todayTopApps, onClick = { })
        }

        // View Weekly Report
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = PrimaryBlue),
                onClick = { onNavigateToReports(child.id, child.name) }
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.BarChart, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text("View Weekly Report", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        // Device Status Card
        item {
            SectionTitle("Device Status")
            DeviceStatusCard(status = childDeviceStatus, lastActiveFormatted = lastActiveFormatted, batteryPercentage = childDeviceStatus.batteryPercentage,
                wifiName = childDeviceStatus.wifiName)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ===== GRADIENT HEADER =====
@Composable
fun GradientHeader(childName: String, profileImageUrl: String?, deviceName: String, isOnline: Boolean,
                   batteryPercentage: Int?, connectionType: String?, wifiName: String?, lastActiveFormatted: String,
                   onNotificationsClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(
            brush = Brush.verticalGradient(colors = listOf(PrimaryBlue, PurpleIndigo)),
            shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
        ).padding(24.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    if (profileImageUrl?.startsWith("http") == true) {
                        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(profileImageUrl).crossfade(true).build(),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(childName.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Hello, Parent", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Text(childName, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                }
            }
            Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                IconButton(onClick = onNotificationsClick, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Notifications, null, tint = Color.White)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // Status chip
        Surface(color = Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isOnline) SuccessGreen else Color.Gray))
                Spacer(Modifier.width(8.dp))
                Text("$deviceName • $lastActiveFormatted", color = Color.White, fontSize = 12.sp)
                if (batteryPercentage != null) {
                    Spacer(Modifier.width(14.dp))
                    Icon(if (batteryPercentage > 20) Icons.Default.BatteryFull else Icons.Default.BatteryAlert, null,
                        modifier = Modifier.size(14.dp), tint = if (batteryPercentage > 20) SuccessGreen else WarningRed)
                    Spacer(Modifier.width(3.dp))
                    Text("$batteryPercentage%", color = Color.White, fontSize = 12.sp)
                }
                if (connectionType == "WiFi" && wifiName != null) {
                    Spacer(Modifier.width(14.dp))
                    Icon(Icons.Default.Wifi, null, modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(Modifier.width(3.dp))
                    Text(wifiName, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

// ===== QUICK ACTIONS =====
@Composable
fun QuickActionGrid(child: ChildProfile, currentUser: com.example.safe_kids.auth.User?, parentActionsManager: ParentActionsManager,
                    scope: kotlinx.coroutines.CoroutineScope, isCoParent: Boolean = false) {
    val context = LocalContext.current
    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 8.dp)) {
        item { QuickActionBtn("Instant Block", Icons.Default.Lock, WarningRed, onClick = {
            if (isCoParent) { Toast.makeText(context, "Co-parents cannot modify settings.", Toast.LENGTH_SHORT).show(); return@QuickActionBtn }
            scope.launch { val db = FirebaseFirestore.getInstance(); db.collection("children").document(child.id).set(mapOf("instantBlock" to true), com.google.firebase.firestore.SetOptions.merge())
                parentActionsManager.sendInstantBlock(child.id, currentUser?.id ?: ""); Toast.makeText(context, "Device blocked", Toast.LENGTH_SHORT).show() }
        })}
        item { QuickActionBtn("App Mgmt", Icons.Default.Apps, AccentOrange, onClick = { context.startActivity(Intent(context, AppManagementActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) })}
        item { QuickActionBtn("Location", Icons.Default.LocationOn, SuccessGreen, onClick = { context.startActivity(Intent(context, ChildLocationMapActivity::class.java).apply { putExtra("childId", child.id); putExtra("childName", child.name) }) })}
        item { QuickActionBtn("Study Mode", Icons.Default.MenuBook, PrimaryBlue, onClick = { context.startActivity(Intent(context, StudyModeActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) })}
        item {
            var hasScreenTimeRequest by remember { mutableStateOf(false) }
            DisposableEffect(child.id) {
                val parentId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val listener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("screen_time_requests")
                    .document(parentId)
                    .collection("requests")
                    .document(child.id)
                    .addSnapshotListener { snapshot, _ ->
                        hasScreenTimeRequest = snapshot != null && snapshot.exists()
                    }
                onDispose { listener.remove() }
            }
            Box(contentAlignment = Alignment.TopEnd) {
                QuickActionBtn("Screen Time", Icons.Default.Timer, PurpleIndigo, onClick = {
                    context.startActivity(Intent(context, GlobalScreenTimeActivity::class.java).apply {
                        putExtra("CHILD_ID", child.id)
                        putExtra("CHILD_NAME", child.name)
                    })
                })
                if (hasScreenTimeRequest) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(WarningRed, CircleShape)
                    )
                }
            }
        }
    }
}

// ===== STATS GRID 2x2 =====
@Composable
fun StatsGrid(summary: DashboardSummary, child: ChildProfile, onNavigateToReports: (String, String) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatsCard(
                title = "Screen Time",
                value = formatMillis(summary.screenTimeMillis),
                icon = Icons.Default.Timer,
                color = PrimaryBlue,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToReports(child.id, child.name) }
            )
            StatsCard(
                title = "Apps Used",
                value = "${summary.appsUsedCount}",
                icon = Icons.Default.GridView,
                color = AccentOrange,
                modifier = Modifier.weight(1f),
                onClick = { context.startActivity(Intent(context, AppManagementActivity::class.java).apply {
                    putExtra("CHILD_ID", child.id)
                    putExtra("CHILD_NAME", child.name)
                })}
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatsCard(
                title = "Notifications",
                value = "${summary.notificationCount}",
                icon = Icons.Default.Notifications,
                color = SuccessGreen,
                modifier = Modifier.weight(1f),
                onClick = { context.startActivity(Intent(context, NotificationMonitoringActivity::class.java).apply {
                    putExtra("CHILD_ID", child.id)
                    putExtra("CHILD_NAME", child.name)
                })}
            )
            StatsCard(
                title = "Safety Status",
                value = if (summary.permissionIssues > 0) "Alert" else "Safe",
                icon = if (summary.permissionIssues > 0) Icons.Default.Warning else Icons.Default.Shield,
                color = if (summary.permissionIssues > 0) WarningRed else PurpleIndigo,
                modifier = Modifier.weight(1f),
                onClick = { context.startActivity(Intent(context, RequiredPermissionsActivity::class.java).apply {
                    putExtra("CHILD_ID", child.id)
                    putExtra("CHILD_NAME", child.name)
                })}
            )
        }
    }
}

@Composable
fun StatsCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(color = color.copy(alpha = 0.12f), shape = CircleShape, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ===== WEEKLY CHART CARD =====
@Composable
fun WeeklyChartCard(weeklyUsage: List<DailyUsage>) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Weekly Activity", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            if (weeklyUsage.isNotEmpty() && weeklyUsage.any { it.durationHours > 0f }) {
                BarChart(weeklyUsage, modifier = Modifier.fillMaxWidth().height(160.dp))
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Timeline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("No activity this week", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BarChart(data: List<DailyUsage>, modifier: Modifier = Modifier) {
    val maxHours = remember(data) { (data.maxOfOrNull { it.durationHours } ?: 1f).coerceAtLeast(0.5f) }
    val barColor = PrimaryBlue
    Canvas(modifier = modifier) {
        val barCount = data.size
        if (barCount == 0) return@Canvas
        val barWidth = size.width / barCount * 0.6f
        val gap = size.width / barCount * 0.4f
        val chartHeight = size.height - 20f
        data.forEachIndexed { index, item ->
            val barHeight = (item.durationHours / maxHours) * chartHeight
            val x = index * (barWidth + gap) + gap / 2
            val y = size.height - 20f - barHeight
            drawRoundRect(
                color = barColor, topLeft = Offset(x, y),
                size = Size(barWidth, barHeight.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
            // Day label
            drawContext.canvas.nativeCanvas.drawText(
                item.dayName.take(3), x + barWidth / 2, size.height - 2f,
                android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textAlign = android.graphics.Paint.Align.CENTER; textSize = 22f }
            )
        }
    }
}

@Composable
fun QuickActionBtn(title: String, icon: ImageVector, color: Color, onClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable { onClick() }) {
        Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.12f), modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(26.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ===== TODAY'S EVENT CARD =====
@Composable
fun TodaysEventCard(topApps: List<AppUsageInfo>, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Today's Event", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            if (topApps.isNotEmpty()) {
                topApps.forEach { app ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = PrimaryBlue.copy(alpha = 0.1f), modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(app.appName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = PrimaryBlue, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(app.appName, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(formatMillis(app.durationMillis), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (app != topApps.last()) HorizontalDivider(modifier = Modifier.padding(start = 50.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("No app activity today", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

// ===== DEVICE STATUS CARD =====
@Composable
fun DeviceStatusCard(status: com.example.safe_kids.data.ChildDeviceStatus, lastActiveFormatted: String, batteryPercentage: Int?, wifiName: String?) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if (status.isOnline) SuccessGreen else Color.Gray))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.deviceName.ifBlank { "Device" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(lastActiveFormatted, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (batteryPercentage != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Battery", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    val batteryColor = when { batteryPercentage > 60 -> SuccessGreen; batteryPercentage > 20 -> AccentOrange; else -> WarningRed }
                    Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(fraction = batteryPercentage / 100f).clip(RoundedCornerShape(4.dp)).background(batteryColor))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("$batteryPercentage%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = batteryColor)
                }
            }
            if (wifiName != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(wifiName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ===== SECTION TITLE =====
@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface)
}

// ===== SHIMMER LOADING =====
@Composable
fun ShimmerLoadingScreen() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha = transition.animateFloat(initialValue = 0.3f, targetValue = 0.7f, animationSpec = infiniteRepeatable(
        animation = tween(1000), repeatMode = RepeatMode.Reverse
    ), label = "shimmerAlpha")
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        repeat(3) {
            Box(modifier = Modifier.fillMaxWidth().height(180.dp).padding(vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha.value)))
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ===== HELPER: formatMillis =====
private fun formatMillis(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600; val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// ===== NO CHILD =====
@Composable
fun NoChildLinkedScreen(context: android.content.Context) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.ChildCare, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("No Child Device Linked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Link your child's device to start monitoring.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { context.startActivity(Intent(context, MainActivity::class.java).apply { putExtra("FORCE_SHOW_PAIRING", true); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }) },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Child Device", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ===== EMPTY STATE CARD =====
@Composable
fun EmptyStateCard(title: String, description: String, icon: ImageVector) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp)); Text(description, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============ BELOW: UNCHANGED SECONDARY COMPOSABLES (DeviceManagementTab, LocationTab, FamilyTab, MyTab, Dialogs) ============

@Composable
fun DeviceManagementTab(child: ChildProfile, authManager: AuthManagerImpl, appBlockingService: AppBlockingServiceImpl,
    notificationService: NotificationServiceImpl, parentActionsManager: ParentActionsManager, isAuthenticated: Boolean,
    childDeviceStatus: com.example.safe_kids.data.ChildDeviceStatus, currentUser: com.example.safe_kids.auth.User?,
    recentAppInstalls: List<Map<String, Any>>, permissionIssueCount: Int, onNotificationsClick: () -> Unit,
    onAddChildClick: () -> Unit = {}, onActionBlocked: () -> Unit = {},
    viewModel: ParentDashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current; val isCoParent = currentUser?.role == UserRole.CO_PARENT
    fun checkRestriction(action: () -> Unit) { if (currentUser?.role?.isParentLike == true) action() else Toast.makeText(context, "Unauthorized access", Toast.LENGTH_SHORT).show() }
    val scope = rememberCoroutineScope()
    val studyModeConfig by viewModel.studyModeConfig.collectAsState(); val sleepModeConfig by viewModel.sleepModeConfig.collectAsState(); val hasSleepEmergency by viewModel.hasSleepEmergency.collectAsState()
    fun getStudyModeStatus(config: com.example.safe_kids.data.StudyModeConfig): String {
        if (!config.isEnabled) return "Disabled"; val now = Calendar.getInstance(); val currentDay = now.get(Calendar.DAY_OF_WEEK); val normalizedDay = if (currentDay == 1) 7 else currentDay - 1
        if (!config.activeDays.contains(normalizedDay)) return "Next: ${config.startTime}"
        val currentTime = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        return if (currentTime >= config.startTime && currentTime <= config.endTime) "Active Now" else "Today at ${config.startTime}"
    }
    fun getSleepModeStatus(config: com.example.safe_kids.data.SleepModeConfig): String {
        if (!config.isEnabled) return "Disabled"; val now = Calendar.getInstance(); val currentDay = now.get(Calendar.DAY_OF_WEEK); val normalizedDay = if (currentDay == 1) 7 else currentDay - 1
        if (!config.activeDays.contains(normalizedDay)) return "Scheduled"; val currentTime = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val isActive = if (config.startTime <= config.endTime) currentTime >= config.startTime && currentTime <= config.endTime else currentTime >= config.startTime || currentTime <= config.endTime
        return if (isActive) "Active Now" else "Tonight at ${config.startTime}"
    }
    var instantBlockState by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(modifier = Modifier.fillMaxWidth().background(brush = Brush.verticalGradient(listOf(PrimaryBlue, PurpleIndigo)), shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)).padding(24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Manage ${childDeviceStatus.deviceName}'s restrictions.", color = Color.White.copy(alpha = 0.8f))
                        }
                        IconButton(onClick = onAddChildClick, modifier = Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape)) { Icon(Icons.Default.Add, null, tint = Color.White) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(color = Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(if (childDeviceStatus.isOnline) SuccessGreen else Color.Gray, CircleShape))
                            Spacer(Modifier.width(8.dp)); Text("${childDeviceStatus.deviceName} • ${if (childDeviceStatus.isOnline) "Online" else "Offline"}", color = Color.White, fontSize = 12.sp)
                            if (childDeviceStatus.batteryPercentage != null) {
                                Spacer(Modifier.width(16.dp))
                                Icon(if (childDeviceStatus.batteryPercentage > 20) Icons.Default.BatteryFull else Icons.Default.BatteryAlert, null, modifier = Modifier.size(14.dp), tint = if (childDeviceStatus.batteryPercentage > 20) SuccessGreen else WarningRed)
                                Spacer(Modifier.width(4.dp)); Text("${childDeviceStatus.batteryPercentage}%", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SettingsCategoryCard("Screen Time & Apps", "Plan >", listOf(
                        SettingsMenuAction("Instant Block", Icons.Default.Lock, hasSwitch = true, switchState = instantBlockState, hasWarning = true, onToggle = { active ->
                            checkRestriction { instantBlockState = active; scope.launch { val db = FirebaseFirestore.getInstance(); db.collection("children").document(child.id).set(mapOf("instantBlock" to active), com.google.firebase.firestore.SetOptions.merge())
                                if (active) parentActionsManager.sendInstantBlock(child.id, currentUser?.id ?: "") else parentActionsManager.sendUnblock(child.id, currentUser?.id ?: "") } }
                        }),
                        SettingsMenuAction("Daily Screen Time Limit", Icons.Default.HourglassEmpty, hasArrow = true, hasWarning = true, onClick = { checkRestriction { context.startActivity(Intent(context, GlobalScreenTimeActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) }}),
                        SettingsMenuAction("Sleep Mode", Icons.Default.Bedtime, hasArrow = true, hasWarning = true, subtitle = getSleepModeStatus(sleepModeConfig), hasBadge = hasSleepEmergency, onClick = { checkRestriction { context.startActivity(Intent(context, SleepModeActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) }}),
                        SettingsMenuAction("Study Mode", Icons.Default.MenuBook, hasArrow = true, hasWarning = true, subtitle = getStudyModeStatus(studyModeConfig), onClick = { checkRestriction { context.startActivity(Intent(context, StudyModeActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) }}),
                        SettingsMenuAction("App Management", Icons.Default.Apps, hasArrow = true, hasWarning = true, onClick = { checkRestriction { context.startActivity(Intent(context, AppManagementActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) }})
                    ))
                    Spacer(Modifier.height(16.dp))
                    SettingsCategoryCard("Content Safety", "", listOf(
                        SettingsMenuAction("Notification Monitoring", Icons.Default.FactCheck, hasArrow = true, onClick = { context.startActivity(Intent(context, NotificationMonitoringActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) }),
                        SettingsMenuAction("Calls & SMS Monitoring", Icons.Default.Message, hasArrow = true, onClick = { context.startActivity(Intent(context, CallsSmsMonitoringActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) })
                    ))
                    Spacer(Modifier.height(16.dp))
                    SettingsCategoryCard("Safe Browsing", "", listOf(
                        SettingsMenuAction("Browsing History", Icons.Default.List, hasArrow = true, hasWarning = true, onClick = { context.startActivity(Intent(context, BrowsingHistoryActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) })
                    ))
                    Spacer(Modifier.height(16.dp))
                    SettingsCategoryCard("Devices & Support", "", listOf(
                        SettingsMenuAction("Check the Required Permissions", Icons.Default.Rule, hasArrow = true, badgeCount = permissionIssueCount, onClick = { context.startActivity(Intent(context, RequiredPermissionsActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name); putExtra("DEVICE_NAME", childDeviceStatus.deviceName) }) }),
                        SettingsMenuAction("SOS Settings", Icons.Default.Emergency, hasArrow = true, onClick = { context.startActivity(Intent(context, SosSettingsActivity::class.java).apply { putExtra("CHILD_ID", child.id); putExtra("CHILD_NAME", child.name) }) })
                    ))
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

data class SettingsMenuAction(val title: String, val icon: ImageVector, val hasSwitch: Boolean = false, val switchState: Boolean = false,
    val hasArrow: Boolean = false, val badgeCount: Int = 0, val hasBadgeDot: Boolean = false, val hasWarning: Boolean = false,
    val subtitle: String = "", val hasBadge: Boolean = false, val onClick: () -> Unit = {}, val onToggle: (Boolean) -> Unit = {})

@Composable
fun SettingsCategoryCard(title: String, trailingText: String = "", items: List<SettingsMenuAction>) {
    Column {
        Row(modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(4.dp, 16.dp).background(PrimaryBlue, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            if (trailingText.isNotEmpty()) Text(trailingText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
            Column { items.forEachIndexed { index, item ->
                Surface(onClick = { if (item.hasSwitch) item.onToggle(!item.switchState) else item.onClick() }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(item.icon, null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                            if (item.hasWarning) Box(modifier = Modifier.align(Alignment.BottomEnd).size(12.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.dp).background(AccentOrange, CircleShape), contentAlignment = Alignment.Center) { Text("!", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.width(16.dp)); Column(modifier = Modifier.weight(1f)) { Text(item.title, style = MaterialTheme.typography.bodyMedium); if (item.subtitle.isNotEmpty()) Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                        if (item.hasSwitch) Switch(checked = item.switchState, onCheckedChange = item.onToggle)
                        else if (item.badgeCount > 0) Surface(shape = CircleShape, color = WarningRed, modifier = Modifier.size(20.dp)) { Box(contentAlignment = Alignment.Center) { Text(item.badgeCount.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) } }
                        else if (item.hasBadgeDot || item.hasBadge) Box(modifier = Modifier.size(8.dp).background(WarningRed, CircleShape))
                        if (item.hasArrow) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.LightGray) }
                    }
                }
                if (index < items.size - 1) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }}
        }
    }
}

@Composable
fun LocationTab(child: ChildProfile?, viewModel: ParentDashboardViewModel, locationService: LocationServiceImpl, parentActionsManager: ParentActionsManager) {
    val context = LocalContext.current
    if (child == null) { EmptyStateCard("Select a Child", "Please select a child to view their location.", Icons.Default.LocationOn); return }
    val childLocationData by viewModel.childLocation.collectAsState(); val isOffline by viewModel.isChildOffline.collectAsState()
    val childLocation = remember(childLocationData) { childLocationData?.let { com.google.android.gms.maps.model.LatLng(it.latitude, it.longitude) } }
    Box(modifier = Modifier.fillMaxSize()) {
        val safeLocation = childLocation ?: com.google.android.gms.maps.model.LatLng(9.0, 38.7)
        val cameraPositionState = rememberCameraPositionState { position = com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(safeLocation, if (childLocation != null) 15f else 10f) }
        LaunchedEffect(childLocation) { if (childLocation != null) cameraPositionState.animate(com.google.android.gms.maps.CameraUpdateFactory.newLatLng(childLocation), 1000) }
        
        GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = cameraPositionState, uiSettings = MapUiSettings(zoomControlsEnabled = false)) {
                if (childLocation != null) {
                    Marker(state = MarkerState(position = childLocation), title = child.name, snippet = if (isOffline) "Last seen" else "Online", alpha = if (isOffline) 0.6f else 1.0f)
                }
            }
        Card(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(4.dp)) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Text("👤", fontSize = 20.sp) } }
                    Box(modifier = Modifier.align(Alignment.BottomEnd).size(12.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(if (isOffline) Color.Gray else SuccessGreen, CircleShape))
                }
                Spacer(Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) {
                    Text(child.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(if (childLocationData == null) "Waiting for location..." else if (isOffline) "Offline" else "Online Now", color = if (isOffline) MaterialTheme.colorScheme.onSurfaceVariant else SuccessGreen, fontSize = 12.sp)
                }
                val scope = rememberCoroutineScope(); val parentUid = remember { FirebaseAuth.getInstance().currentUser?.uid }
                IconButton(onClick = { scope.launch { if (parentUid != null) { parentActionsManager.requestCurrentLocation(child.id, parentUid); Toast.makeText(context, "Requesting location...", Toast.LENGTH_SHORT).show() } } }) { Icon(Icons.Default.Refresh, null, tint = Color.Gray) }
            }
        }
    }
}

@Composable
fun FamilyTab(children: List<ChildProfile>, onAddChild: () -> Unit) {
    val context = LocalContext.current; val currentUser = remember { FirebaseAuth.getInstance().currentUser }
    var parentDisplayName by remember { mutableStateOf("") }; var parentEmail by remember { mutableStateOf("") }; var parentPhotoUrl by remember { mutableStateOf<String?>(null) }
    val presenceStates = remember { mutableStateMapOf<String, Pair<com.example.safe_kids.utils.PresenceStatus, Long?>>() }
    LaunchedEffect(currentUser?.uid) {
        val uid = currentUser?.uid ?: return@LaunchedEffect
        FirebaseFirestore.getInstance().collection(com.example.safe_kids.auth.AuthConfig.USERS_COLLECTION).document(uid).get().addOnSuccessListener { doc ->
            parentDisplayName = doc.getString("displayName") ?: currentUser.displayName ?: "Parent"; parentEmail = doc.getString("email") ?: currentUser.email ?: ""; parentPhotoUrl = doc.getString("profileImageUrl")
        }
    }
    LaunchedEffect(children) {
        children.forEach { child ->
            val presenceRef = com.example.safe_kids.utils.FirebaseUtils.getRealtimeDatabase().getReference("child_presence/${child.id}")
            val listener = object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                    val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                    val now = System.currentTimeMillis(); val diff = now - lastSeen
                    val status = when { isOnline && diff < 180_000 -> com.example.safe_kids.utils.PresenceStatus.ONLINE; diff < 600_000 -> com.example.safe_kids.utils.PresenceStatus.IDLE; else -> com.example.safe_kids.utils.PresenceStatus.OFFLINE }
                    presenceStates[child.id] = Pair(status, lastSeen)
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            }
            presenceRef.addValueEventListener(listener)
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Family", fontWeight = FontWeight.Bold, fontSize = 22.sp) }
        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChildAvatarImage(profileImageUrl = parentPhotoUrl, childName = parentDisplayName.ifBlank { "P" }, size = 48.dp, shape = CircleShape)
            Spacer(Modifier.width(14.dp)); Column(modifier = Modifier.weight(1f)) { Text(parentDisplayName.ifBlank { "Parent" }, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text(parentEmail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Text("You", color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp) }
        }
        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFFE8EAF6), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp)) { Box(contentAlignment = Alignment.Center) { Text("🏠", fontSize = 24.sp) } }
            Spacer(Modifier.width(14.dp)); Text("Family Group (${children.size + 1})", fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }
        HorizontalDivider(modifier = Modifier.padding(start = 78.dp), color = Color.LightGray.copy(alpha = 0.3f))
        children.forEach { child ->
            val (presenceStatus, lastSeen) = presenceStates[child.id] ?: Pair(com.example.safe_kids.utils.PresenceStatus.OFFLINE, null)
            val statusColor = when (presenceStatus) { com.example.safe_kids.utils.PresenceStatus.ONLINE -> SuccessGreen; com.example.safe_kids.utils.PresenceStatus.IDLE -> AccentOrange; com.example.safe_kids.utils.PresenceStatus.OFFLINE -> Color.Gray }
            val statusText = when (presenceStatus) { com.example.safe_kids.utils.PresenceStatus.ONLINE -> "Active now"; com.example.safe_kids.utils.PresenceStatus.IDLE -> { val m = ((System.currentTimeMillis() - (lastSeen ?: 0)) / 60_000L).toInt(); "Last seen $m min ago" }
                com.example.safe_kids.utils.PresenceStatus.OFFLINE -> { if (lastSeen != null && lastSeen > 0) { val h = ((System.currentTimeMillis() - lastSeen) / 3_600_000L).toInt(); if (h < 24) "Last seen $h hours ago" else "Offline" } else "Offline" } }
            Row(modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(context, com.example.safe_kids.ui.chat.ChatActivity::class.java).apply { putExtra("OTHER_USER_ID", child.id); putExtra("OTHER_USER_NAME", child.name); putExtra("IS_PARENT", true) }) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box { ChildAvatarImage(profileImageUrl = child.profileImageUrl, childName = child.name, size = 48.dp, shape = RoundedCornerShape(12.dp))
                    Box(modifier = Modifier.align(Alignment.BottomEnd).size(14.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(statusColor, CircleShape)) }
                Spacer(Modifier.width(14.dp)); Column(modifier = Modifier.weight(1f)) { Text(child.name, fontWeight = FontWeight.Medium, fontSize = 15.sp); Text("${child.deviceName?.ifBlank { "" }?.let { "$it • " } ?: ""}$statusText", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                Icon(Icons.Default.Message, null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
            }
            HorizontalDivider(modifier = Modifier.padding(start = 78.dp), color = Color.LightGray.copy(alpha = 0.3f))
        }
    }
}

@Composable
fun MyTab(authManager: AuthManagerImpl, currentUser: com.example.safe_kids.auth.User?, isAuthenticated: Boolean, children: List<ChildProfile>, onSignOut: () -> Unit) {
    val context = LocalContext.current; val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxWidth().background(brush = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background))).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = Color(0xFFBDBDBD), modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(currentUser?.displayName?.take(1)?.uppercase() ?: "U", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
                Spacer(Modifier.height(12.dp)); Text(currentUser?.displayName ?: "User", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(currentUser?.email ?: "", color = Color.Gray, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        val isParentMode = authManager.sessionManager.isParentMode()
        MyMenuCard(items = mutableListOf<Pair<Triple<ImageVector, String, String>, () -> Unit>>().apply {
            add(Triple(Icons.Default.Group, "Family Members", "") to { if (currentUser?.role == UserRole.PARENT) context.startActivity(Intent(context, ManageCoParentsActivity::class.java)) else Toast.makeText(context, "Only primary parents can manage family members", Toast.LENGTH_SHORT).show() })
            add(Triple(Icons.Default.PhoneAndroid, "Child's Devices", "${children.size}") to { context.startActivity(Intent(context, ChildDevicesActivity::class.java)) })
            add(Triple(Icons.Default.Lock, "Parental PIN", "") to { try { if (currentUser != null) context.startActivity(Intent(context, ParentAdminCodeActivity::class.java)) else Toast.makeText(context, "Please sign in", Toast.LENGTH_SHORT).show() } catch (_: Exception) { Toast.makeText(context, "Error opening Parental PIN", Toast.LENGTH_SHORT).show() }})
            if (!isParentMode) { add(Triple(Icons.Default.Badge, "Edit Parent Profile", "") to {}); add(Triple(Icons.Default.ChildCare, "Edit Child Profile", "") to {}) }
        })
        Spacer(Modifier.height(12.dp))
        MyMenuCard(items = listOf(Triple(Icons.Default.Settings, "Settings", "") to { context.startActivity(Intent(context, com.example.safe_kids.ui.self.SettingsActivity::class.java)) }, Triple(Icons.Default.CleaningServices, "Clear Cache", "") to { Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show() }, Triple(Icons.Default.Info, "About Us", "V1.0.0") to { Toast.makeText(context, "Safe Kids V1.0.0", Toast.LENGTH_SHORT).show() }))
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
            Box(modifier = Modifier.fillMaxWidth().clickable { onSignOut() }.padding(16.dp), contentAlignment = Alignment.Center) { Text("Sign Out", color = WarningRed, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MyMenuCard(items: List<Pair<Triple<ImageVector, String, String>, () -> Unit>>) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column { items.forEachIndexed { index, (triple, onClick) ->
            val (icon, title, trailing) = triple
            Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = PrimaryBlue, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(16.dp)); Text(title, modifier = Modifier.weight(1f)); if (trailing.isNotEmpty()) Text(trailing, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp)); Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            }
            if (index < items.size - 1) HorizontalDivider(modifier = Modifier.padding(start = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }}
    }
}

@Composable
fun ChildAvatarImage(profileImageUrl: String?, childName: String, size: Dp = 48.dp, shape: androidx.compose.ui.graphics.Shape = CircleShape) {
    val context = LocalContext.current; val emojiAvatars = mapOf("boy1" to "👦", "girl1" to "👧", "boy2" to "🧒", "girl2" to "👩"); val isNetworkUrl = profileImageUrl?.startsWith("http") == true; val emojiForAvatar = if (!isNetworkUrl) emojiAvatars[profileImageUrl] else null; val initial = childName.take(1).uppercase().ifBlank { "?" }
    Box(modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        when { isNetworkUrl -> AsyncImage(model = ImageRequest.Builder(context).data(profileImageUrl).crossfade(true).build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            emojiForAvatar != null -> Text(emojiForAvatar, fontSize = (size.value * 0.5f).sp)
            else -> Text(initial, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@Composable
fun NotificationsDialog(notifications: List<ParentNotification>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Notifications, null); Spacer(Modifier.width(8.dp)); Text("Notifications History") } },
        text = { if (notifications.isEmpty()) { Column(modifier = Modifier.height(200.dp).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text("No notifications yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) } }
        else { LazyColumn(modifier = Modifier.height(300.dp)) { itemsIndexed(notifications) { index, item ->
            Column(modifier = Modifier.padding(vertical = 8.dp)) { Text(item.title, fontWeight = FontWeight.Bold); if (item.body.isNotBlank()) Text(item.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), fontSize = 12.sp)
                Text(formatRelativeNotifTime(item.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), fontSize = 11.sp) }
            if (index < notifications.size - 1) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }}}},
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun formatRelativeNotifTime(timestamp: Long?): String {
    val ts = timestamp ?: return "Unknown time"; val diff = System.currentTimeMillis() - ts; val minutes = diff / 60000
    return when { minutes < 1 -> "Just now"; minutes < 60 -> "${minutes} min ago"; minutes < 1440 -> "${minutes / 60} hours ago"; else -> "${minutes / 1440} days ago" }
}

@OptIn(ExperimentalMaterial3Api::class)
class ChildSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SAFE_KIDSTheme { Scaffold(topBar = { TopAppBar(title = { Text("Child Setup") }, navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Default.ArrowBack, null) } }) }) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Child Setup – Coming Soon", style = MaterialTheme.typography.titleLarge) } } } } }
}
