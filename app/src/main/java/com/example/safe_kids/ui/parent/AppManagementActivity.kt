package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.InstalledApp
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.example.safe_kids.ui.components.AddAppsSheet
import com.example.safe_kids.ui.components.AppIcon

class AppManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        setContent {
            SAFE_KIDSTheme {
                AppManagementScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagementScreen(childId: String, childName: String, onBack: () -> Unit) {
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAddAppsSheet by remember { mutableStateOf(false) }
    var appsSelectionType by remember { mutableStateOf("ALLOWED") }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var alwaysAllowedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var appRestrictions by remember { mutableStateOf<List<com.example.safe_kids.data.AppRestriction>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    LaunchedEffect(childId) {
        if (childId.isNotEmpty()) {
            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val apps = snapshot.get("installedApps") as? List<Map<String, Any>>
                        if (apps != null) {
                            installedApps = apps.map {
                                InstalledApp(
                                    packageName = it["packageName"] as? String ?: "",
                                    appName = it["appName"] as? String ?: "",
                                    category = it["category"] as? String ?: "Other",
                                    installTime = it["installTime"] as? Long ?: 0L,
                                    isSystemApp = it["isSystemApp"] as? Boolean ?: false,
                                    iconBase64 = it["iconBase64"] as? String
                                )
                            }

                        }
                        // installedApps comes from child profile; allowed/blocked apps come from centralized app_usage_limits
                        android.util.Log.d("AppManagement", "Read installedApps from ${AuthConfig.CHILD_PROFILES_COLLECTION}/$childId")
                    }
                }
            // Listen for allowed and blocked apps in app_usage_limits collection
            db.collection(AuthConfig.APP_USAGE_LIMITS_COLLECTION).document(childId)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        android.util.Log.e("AppManagement", "Failed to listen app_usage_limits/$childId", e)
                        alwaysAllowedApps = emptyList()
                        appRestrictions = emptyList()
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        android.util.Log.d("AppManagement", "Read app_usage_limits/$childId: ${snapshot.data}")
                        alwaysAllowedApps = snapshot.get("alwaysAllowedApps") as? List<String> ?: emptyList()
                        val blocked = snapshot.get("blockedApps") as? List<String> ?: emptyList()
                        appRestrictions = blocked.map { pkg ->
                            val appName = installedApps.find { it.packageName == pkg }?.appName ?: pkg
                            com.example.safe_kids.data.AppRestriction(packageName = pkg, appName = appName, isBlocked = true, timeLimit = null, limitType = "INDIVIDUAL", geofenceId = null)
                        }
                    } else {
                        alwaysAllowedApps = emptyList()
                        appRestrictions = emptyList()
                    }
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Management", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ---- App Limits ----
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                ManagementSectionHeader("App Limits", onAdd = {
                    val intent = android.content.Intent(context, AddAppLimitActivity::class.java).apply {
                        putExtra("CHILD_ID", childId)
                        putExtra("CHILD_NAME", childName)
                    }
                    context.startActivity(intent)
                })
                ManagementCard {
                    val limits = appRestrictions.filter { it.timeLimit != null }
                    if (limits.isEmpty()) {
                        EmptySectionState(
                            icon = Icons.Default.Timer,
                            text = "Set time limits or schedule downtime to make apps or categories inaccessible within a certain time frame."
                        )
                    } else {
                        Column {
                            limits.forEachIndexed { index, limit ->
                                AppManagementItem(
                                    name = limit.appName,
                                    packageName = limit.packageName,
                                    installedApps = installedApps,
                                    subtitle = buildString {
                                        val mins = (limit.timeLimit ?: 0) / 60000
                                        if (mins >= 60) append("${mins / 60}h ${mins % 60}m")
                                        else append("${mins}m")
                                        if (!limit.remark.isNullOrBlank()) append(" · ${limit.remark}")
                                    },
                                    onRemove = {
                                        scope.launch {
                                            val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                            val snapshot = profileRef.get().await()
                                            val current = snapshot.get("restrictions") as? List<Map<String, Any>> ?: emptyList()
                                            val updated = current.filter { it["packageName"] != limit.packageName || it["timeLimit"] == null }
                                            profileRef.update("restrictions", updated).await()
                                        }
                                    }
                                )

                                if (index < limits.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- Always Allowed ----
            item {
                ManagementSectionHeader("Always Allowed", onAdd = {
                    appsSelectionType = "ALLOWED"
                    showAddAppsSheet = true
                })
                Text(
                    "Apps added here are always available during downtime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ManagementCard {
                    Column {
                        if (alwaysAllowedApps.isEmpty()) {
                            EmptySectionState(icon = Icons.Default.CheckCircle, text = "No apps have been approved yet")
                        } else {
                            alwaysAllowedApps.forEachIndexed { index, pkg ->
                                val appName = installedApps.find { it.packageName == pkg }?.appName ?: pkg
                                AppManagementItem(
                                    name = appName,
                                    packageName = pkg,
                                    installedApps = installedApps,
                                    onRemove = {
                                        scope.launch {
                                            val limitsRef = db.collection(AuthConfig.APP_USAGE_LIMITS_COLLECTION).document(childId)
                                            val snapshot = limitsRef.get().await()
                                            val current = snapshot.get("alwaysAllowedApps") as? List<String> ?: emptyList()
                                            val updated = current.filter { it != pkg }
                                            limitsRef.set(mapOf("alwaysAllowedApps" to updated), com.google.firebase.firestore.SetOptions.merge()).await()
                                            android.util.Log.d("AppManagement", "Wrote app_usage_limits/$childId alwaysAllowedApps: $updated")
                                        }
                                    }
                                )
                                if (index < alwaysAllowedApps.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- App Blocker ----
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                ManagementSectionHeader("App Blocker", onAdd = {
                    val intent = android.content.Intent(context, com.example.safe_kids.ui.parent.AppBlockerPickerActivity::class.java).apply {
                        putExtra("CHILD_ID", childId)
                        putExtra("CHILD_NAME", childName)
                    }
                    context.startActivity(intent)
                })
                ManagementCard {
                    val blocked = appRestrictions.filter { it.isBlocked && it.timeLimit == null }
                    if (blocked.isEmpty()) {
                        EmptySectionState(icon = Icons.Default.Block, text = "No apps blocked yet. Tap Add to block apps.")
                    } else {
                        Column {
                            blocked.forEachIndexed { index, restriction ->
                                AppManagementItem(
                                    name = restriction.appName,
                                    packageName = restriction.packageName,
                                    installedApps = installedApps,
                                    onRemove = {
                                        scope.launch {
                                            val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                            val snapshot = profileRef.get().await()
                                            val current = snapshot.get("restrictions") as? List<Map<String, Any>> ?: emptyList()
                                            val updated = current.filter { it["packageName"] != restriction.packageName }
                                            profileRef.update("restrictions", updated).await()
                                        }
                                    }
                                )
                                if (index < blocked.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }


                if (showAddAppsSheet) {
            AddAppsSheet(
                installedApps = installedApps,
                onDismiss = { showAddAppsSheet = false },
                onConfirm = { selectedApps ->
                    scope.launch {
                                val limitsRef = db.collection(AuthConfig.APP_USAGE_LIMITS_COLLECTION).document(childId)
                                if (appsSelectionType == "ALLOWED") {
                                    val snapshot = limitsRef.get().await()
                                    val current = snapshot.get("alwaysAllowedApps") as? List<String> ?: emptyList()
                                    val updated = (current.toSet() + selectedApps.toSet()).toList()
                                    limitsRef.set(mapOf("alwaysAllowedApps" to updated), com.google.firebase.firestore.SetOptions.merge()).await()
                                    android.util.Log.d("AppManagement", "Wrote app_usage_limits/$childId alwaysAllowedApps: $updated")
                                } else {
                                    val snapshot = limitsRef.get().await()
                                    val current = snapshot.get("blockedApps") as? List<String> ?: emptyList()
                                    val updated = (current.toSet() + selectedApps.toSet()).toList()
                                    limitsRef.set(mapOf("blockedApps" to updated), com.google.firebase.firestore.SetOptions.merge()).await()
                                    android.util.Log.d("AppManagement", "Wrote app_usage_limits/$childId blockedApps: $updated")
                                    // Notify child device to enforce new restrictions immediately
                                    val parentId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                    if (parentId.isNotEmpty()) {
                                        val actionsManager = com.example.safe_kids.auth.ParentActionsManager(
                                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                        )
                                        actionsManager.sendCommand(
                                            childId, parentId,
                                            com.example.safe_kids.data.CommandType.UPDATE_APP_CATEGORIES
                                        )
                                    }
                        }
                        showAddAppsSheet = false
                    }
                }
            )
        }

        if (showSettingsSheet) {
            AppManagementSettingsSheet(
                childId = childId,
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

@Composable
fun ManagementSectionHeader(title: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Add",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onAdd() }
        )
    }
}

@Composable
fun ManagementCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        content()
    }
}

@Composable
fun EmptySectionState(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.LightGray.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

// App row with real icon + optional red-minus remove button
@Composable
fun AppManagementItem(
    name: String,
    packageName: String? = null,
    installedApps: List<InstalledApp> = emptyList(),
    subtitle: String? = null,
    onRemove: (() -> Unit)?
) {

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onRemove != null) {
            Icon(
                Icons.Default.RemoveCircle,
                contentDescription = "Remove",
                tint = Color.Red,
                modifier = Modifier.size(22.dp).clickable { onRemove() }
            )
            Spacer(Modifier.width(12.dp))
        }
        if (packageName != null) {
            val app = installedApps.find { it.packageName == packageName }
            AppIcon(
                packageName = packageName,
                modifier = Modifier.size(38.dp),
                iconBase64 = app?.iconBase64
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
        if (onRemove == null) {
            Icon(Icons.Default.Lock, null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagementSettingsSheet(childId: String, onDismiss: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    var blockNewApps by remember { mutableStateOf(false) }

    // Load current setting
    LaunchedEffect(childId) {
        if (childId.isNotEmpty()) {
            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .get().addOnSuccessListener { snapshot ->
                    blockNewApps = snapshot.getBoolean("settings.blockNewAppsAutomatically") ?: false
                }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text("App Management Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

            // Block new apps toggle
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Block new apps automatically", fontWeight = FontWeight.Medium)
                        Text(
                            "Any newly installed app on the child's device will be blocked until you allow it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = blockNewApps,
                        onCheckedChange = { checked ->
                            blockNewApps = checked
                            scope.launch {
                                db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                    .set(
                                        mapOf("settings" to mapOf("blockNewAppsAutomatically" to checked)),
                                        com.google.firebase.firestore.SetOptions.merge()
                                    ).await()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}
