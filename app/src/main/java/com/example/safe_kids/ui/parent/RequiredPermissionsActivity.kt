package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.ChildCommand
import com.example.safe_kids.data.CommandType
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.Locale

data class ChildPermissionStatus(
    val feature: String,
    val icon: ImageVector,
    val issues: List<String>
)

class RequiredPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        val deviceName = intent.getStringExtra("DEVICE_NAME") ?: "Child Device"
        setContent {
            SAFE_KIDSTheme {
                RequiredPermissionsScreen(childId = childId, childName = childName, deviceName = deviceName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequiredPermissionsScreen(childId: String, childName: String, deviceName: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    var permissionFlags by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // Read from children/{childId}/permissionStatus path
    var permissionStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var firestoreWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showReminderSent by remember { mutableStateOf(false) }

    fun Map<String, Boolean>.getFlag(vararg keys: String): Boolean {
        return keys.mapNotNull { this[it] }.firstOrNull() ?: false
    }

    LaunchedEffect(childId, refreshKey) {
        if (childId.isEmpty()) return@LaunchedEffect
        // Listen to permissionFlags on child profile (legacy)
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION)
            .document(childId)
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    val rawFlags = (snap.get("permissionFlags") as? Map<*, *>)
                        ?.mapNotNull { (key, value) ->
                            if (key is String && value is Boolean) key to value else null
                        }
                        ?.toMap() ?: emptyMap()

                    permissionFlags = mapOf(
                        "accessibility" to rawFlags.getFlag("accessibility", "accessibilityEnabled"),
                        "location" to rawFlags.getFlag("location", "locationPermission", "backgroundLocation"),
                        "notifications" to rawFlags.getFlag("notifications", "notificationAccess", "notificationListenerEnabled"),
                        "doNotDisturb" to rawFlags.getFlag("doNotDisturb", "doNotDisturbAccess"),
                        "usageStats" to rawFlags.getFlag("usageStats", "usageAccess"),
                        "deviceAdmin" to rawFlags.getFlag("deviceAdmin", "deviceAdminActive"),
                        "overlay" to rawFlags.getFlag("overlay", "overlayPermission"),
                        "camera" to rawFlags.getFlag("camera", "cameraPermission"),
                        "callLog" to rawFlags.getFlag("callLog", "readCallLogPermission"),
                        "batteryOptimization" to rawFlags.getFlag("batteryOptimization", "batteryOptimizationIgnored")
                    )
                    firestoreWarnings = (snap.get("permissionWarnings") as? List<*>)
                        ?.mapNotNull { it as? String } ?: emptyList()
                    lastUpdated = snap.getLong("lastUpdate") ?: snap.getLong("lastUpdated")
                }
            }
        // NEW: Listen to children/{childId}/permissionStatus for live monitoring
        if (childId.isNotEmpty()) {
            db.collection("children").document(childId).collection("permissionStatus").document("live")
                .addSnapshotListener { snap, _ ->
                    if (snap != null && snap.exists()) {
                        val statusMap = mutableMapOf<String, Boolean>()
                        snap.data?.forEach { (key, value) ->
                            if (value is Boolean) statusMap[key] = value
                        }
                        permissionStatus = statusMap
                    }
                }
        }
    }

    // Merge permission status from both sources
    val mergedStatus = mapOf(
        "accessibilityEnabled" to (permissionStatus["accessibilityEnabled"] ?: permissionFlags.getFlag("accessibility", "accessibilityEnabled")),
        "overlayPermission" to (permissionStatus["overlayPermission"] ?: permissionFlags.getFlag("overlay", "overlayPermission")),
        "usageAccess" to (permissionStatus["usageAccess"] ?: permissionFlags.getFlag("usageStats", "usageAccess")),
        "doNotDisturbAccess" to (permissionStatus["doNotDisturbAccess"] ?: permissionFlags.getFlag("doNotDisturb", "doNotDisturbAccess")),
        "notificationListenerEnabled" to (permissionStatus["notificationListenerEnabled"] ?: permissionFlags.getFlag("notifications", "notificationAccess", "notificationListenerEnabled")),
        "batteryOptimizationIgnored" to (permissionStatus["batteryOptimizationIgnored"] ?: permissionFlags.getFlag("batteryOptimization", "batteryOptimizationIgnored")),
        "deviceAdminActive" to (permissionStatus["deviceAdminActive"] ?: permissionFlags.getFlag("deviceAdmin", "deviceAdminActive")),
        "locationPermission" to (permissionStatus["locationPermission"] ?: permissionFlags.getFlag("location", "locationPermission", "backgroundLocation")),
        "cameraPermission" to (permissionStatus["cameraPermission"] ?: permissionFlags.getFlag("camera", "cameraPermission")),
        "callLogPermission" to (permissionStatus["callLogPermission"] ?: permissionFlags.getFlag("callLog", "readCallLogPermission")),
        "smsPermission" to (permissionStatus["smsPermission"] ?: permissionFlags.getFlag("phone", "readSmsPermission", "sendSmsPermission"))
    )

    // Determine if any critical permission is revoked
    val anyRevoked = mergedStatus.any { !it.value }

    fun sendReminder() {
        val parentId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val reminderCommand = ChildCommand(
            childId = childId,
            parentId = parentId,
            type = CommandType.SEND_REMINDER,
            payload = mapOf("message" to "Please open Safe Kids and grant missing permissions", "childName" to childName)
        )
        db.collection(AuthConfig.CHILD_COMMANDS_COLLECTION).document(reminderCommand.id)
            .set(reminderCommand)
            .addOnSuccessListener { showReminderSent = true }
            .addOnFailureListener { showReminderSent = false }
    }

    val acc = mergedStatus["accessibilityEnabled"] == true
    val loc = mergedStatus["locationPermission"] == true
    val overlay = mergedStatus["overlayPermission"] == true
    val usage = mergedStatus["usageAccess"] == true
    val dnd = mergedStatus["doNotDisturbAccess"] == true
    val notif = mergedStatus["notificationListenerEnabled"] == true
    val battery = mergedStatus["batteryOptimizationIgnored"] == true
    val admin = mergedStatus["deviceAdminActive"] == true
    val cam = mergedStatus["cameraPermission"] == true
    val callLog = mergedStatus["callLogPermission"] == true
    val sms = mergedStatus["smsPermission"] == true

    // Build permission items list for display
    val permissionItems = listOf(
        PermissionItem("Accessibility Service", "accessibilityEnabled", acc),
        PermissionItem("Draw Over Other Apps", "overlayPermission", overlay),
        PermissionItem("Usage Access", "usageAccess", usage),
        PermissionItem("Do Not Disturb Access", "doNotDisturbAccess", dnd),
        PermissionItem("Notification Listener", "notificationListenerEnabled", notif),
        PermissionItem("Battery Optimization Ignored", "batteryOptimizationIgnored", battery),
        PermissionItem("Device Admin", "deviceAdminActive", admin),
        PermissionItem("Location Permission", "locationPermission", loc),
        PermissionItem("Camera Permission", "cameraPermission", cam),
        PermissionItem("Call Log Permission", "callLogPermission", callLog),
        PermissionItem("SMS Permission", "smsPermission", sms)
    )

    val notGranted = permissionItems.filter { !it.granted }
    val granted = permissionItems.filter { it.granted }
    val allGranted = notGranted.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Required Permissions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary) },
                navigationIcon = { 
                    IconButton(onClick = onBack) { 
                        Icon(Icons.Default.ArrowBack, null, tint = MaterialTheme.colorScheme.onPrimary) 
                    } 
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) { 
                        Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onPrimary) 
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            // Warning banner if any permission is revoked
            if (anyRevoked) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFE65100), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Some permissions are disabled on $childName device",
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Device header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📱", fontSize = 48.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(deviceName, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
                        
                        if (allGranted) {
                            Spacer(Modifier.height(8.dp))
                            Surface(color = Color(0xFF22C55E).copy(alpha = 0.1f), shape = RoundedCornerShape(24.dp)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("All Permissions Granted", color = Color(0xFF22C55E), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Safe Kids is functioning properly on your child's device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    "${notGranted.size} permission(s) need attention",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (firestoreWarnings.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Permission warnings", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            firestoreWarnings.forEach { warning ->
                                Text("• $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            // Live permission status items - show all
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Box(Modifier.size(4.dp, 16.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("Permission Status", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column {
                        permissionItems.forEachIndexed { index, item ->
                            LivePermissionRow(item, childName)
                            if (index < permissionItems.size - 1)
                                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }

            item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { sendReminder() }) {
                        Text("Send Permission Reminder")
                    }
                    if (showReminderSent) {
                        Spacer(Modifier.height(8.dp))
                        Text("Reminder sent to child app.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    lastUpdated?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Last reported: ${java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(it))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LivePermissionRow(item: PermissionItem, childName: String) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!item.granted) showDialog = true
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (item.granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            null,
            tint = if (item.granted) Color(0xFF22C55E) else Color(0xFFE53935),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            item.name,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (!item.granted) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(item.name) },
            text = { Text("Ask your child to open SafeKids to re-enable this permission.") },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

private data class PermissionItem(val name: String, val key: String, val granted: Boolean)
