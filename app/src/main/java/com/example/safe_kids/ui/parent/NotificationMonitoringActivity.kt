package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.InstalledApp
import com.example.safe_kids.ui.components.AppIcon
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.FirestoreQueryUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationMonitoringActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        setContent {
            SAFE_KIDSTheme {
                NotificationMonitoringScreen(childId = childId, childName = childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationMonitoringScreen(childId: String, childName: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var syncEnabled by remember { mutableStateOf(true) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    // packageName -> enabled
    var appToggles by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var notificationRecords by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(childId) {
        if (childId.isEmpty()) {
            isLoading = false
            onDispose { }
        } else {
            var profileReg: ListenerRegistration? = null
            var notifReg: ListenerRegistration? = null
            profileReg = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        FirestoreQueryUtils.logQueryError("NotificationMonitoringProfile", error)
                        loadError = FirestoreQueryUtils.userMessage(error)
                        isLoading = false
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val apps = snapshot.get("installedApps") as? List<Map<String, Any>>
                        if (apps != null) {
                            installedApps = apps.map {
                                InstalledApp(
                                    packageName = it["packageName"] as? String ?: "",
                                    appName = it["appName"] as? String ?: "",
                                    category = it["category"] as? String ?: "Other",
                                    isSystemApp = it["isSystemApp"] as? Boolean ?: false,
                                    iconBase64 = it["iconBase64"] as? String
                                )
                            }.filter { it.packageName.isNotEmpty() }
                        }
                        val notifSettings = snapshot.get("notificationMonitoring") as? Map<String, Any>
                        syncEnabled = notifSettings?.get("syncEnabled") as? Boolean ?: true
                        @Suppress("UNCHECKED_CAST")
                        val savedToggles = notifSettings?.get("appToggles") as? Map<String, Boolean> ?: emptyMap()
                        val merged = installedApps.associate { app ->
                            app.packageName to (savedToggles[app.packageName] ?: true)
                        }
                        appToggles = merged
                    }
                    isLoading = false
                }
            notifReg = db.collection("child_notifications")
                .whereEqualTo("childId", childId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        FirestoreQueryUtils.logQueryError("NotificationMonitoringHistory", error)
                        loadError = FirestoreQueryUtils.userMessage(error)
                        return@addSnapshotListener
                    }
                    loadError = null
                    notificationRecords = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
                }
            onDispose {
                profileReg?.remove()
                notifReg?.remove()
            }
        }
    }

    // Save helper
    fun saveSettings(newSyncEnabled: Boolean = syncEnabled, newToggles: Map<String, Boolean> = appToggles) {
        scope.launch {
            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .set(
                    mapOf("notificationMonitoring" to mapOf(
                        "syncEnabled" to newSyncEnabled,
                        "appToggles" to newToggles
                    )),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()
        }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
    }

    val allEnabled = appToggles.values.all { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Monitoring", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { innerPadding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (loadError != null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(loadError!!, color = Color.Gray, modifier = Modifier.padding(24.dp))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Search bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
            }

            // Master sync toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Sync Notifications", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Switch(
                                checked = syncEnabled,
                                onCheckedChange = { checked ->
                                    syncEnabled = checked
                                    saveSettings(newSyncEnabled = checked)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF4C66EA)
                                )
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "When turned on, you'll receive the notifications from your child's device",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            if (syncEnabled) {
                // Section header + "All" toggle
                item {
                    Text(
                        "Sync App Notifications",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column {
                            // "All" row
                            AppNotifRow(
                                packageName = null,
                                appName = "All",
                                isEnabled = allEnabled,
                                installedApps = installedApps,
                                onToggle = { checked ->
                                    val updated = appToggles.mapValues { checked }
                                    appToggles = updated
                                    saveSettings(newToggles = updated)
                                }
                            )

                            if (filteredApps.isNotEmpty()) {
                                HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                            }
                            filteredApps.forEachIndexed { index, app ->
                                AppNotifRow(
                                    packageName = app.packageName,
                                    appName = app.appName,
                                    isEnabled = appToggles[app.packageName] ?: true,
                                    installedApps = installedApps,
                                    onToggle = { checked ->
                                        val updated = appToggles.toMutableMap().apply { put(app.packageName, checked) }
                                        appToggles = updated
                                        saveSettings(newToggles = updated)
                                    }
                                )

                                if (index < filteredApps.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 72.dp),
                                        thickness = 0.5.dp,
                                        color = Color.LightGray.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Notification Records",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            if (notificationRecords.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(color = Color(0xFFF1F5F9), shape = CircleShape, modifier = Modifier.size(48.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Notifications, null, tint = Color(0xFF94A3B8))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("No notifications", color = Color(0xFF64748B))
                        }
                    }
                }
            } else {
                items(notificationRecords.take(20)) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val packageName = item["packageName"] as? String ?: ""
                            val iconBase64 = item["iconBase64"] as? String
                            AppIcon(packageName = packageName, modifier = Modifier.size(32.dp), iconBase64 = iconBase64)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(item["title"]?.toString().orEmpty().ifBlank { "New notification" }, fontWeight = FontWeight.SemiBold)

                            val message = item["text"]?.toString().orEmpty()
                            if (message.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                                    .format(Date((item["timestamp"] as? Number)?.toLong() ?: 0L)),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AppNotifRow(
    packageName: String?,
    appName: String,
    isEnabled: Boolean,
    installedApps: List<InstalledApp> = emptyList(),
    onToggle: (Boolean) -> Unit
) {

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (packageName != null) {
            val app = installedApps.find { it.packageName == packageName }
            AppIcon(packageName = packageName, modifier = Modifier.size(40.dp), iconBase64 = app?.iconBase64)
        } else {
            // "All" row — grid icon
            Surface(
                color = Color(0xFFE8EAF6),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Search, // placeholder — replaced visually by text
                        contentDescription = null,
                        tint = Color(0xFF4C66EA),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = appName,
            fontWeight = if (packageName == null) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF4C66EA),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.LightGray
            )
        )
    }
}
