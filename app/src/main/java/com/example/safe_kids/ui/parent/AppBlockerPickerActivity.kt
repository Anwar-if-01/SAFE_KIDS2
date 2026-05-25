package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.ui.components.AppIcon
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AppBlockerPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        setContent {
            SAFE_KIDSTheme {
                AppBlockerPickerScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBlockerPickerScreen(childId: String, childName: String, onBack: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var blockedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()

    // Load installed apps from child's profile
    LaunchedEffect(childId) {
        if (childId.isNotEmpty()) {
            isLoading = true
            try {
                val snapshot = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId).get().await()
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
                    }.filter { !it.isSystemApp && it.packageName != "com.example.safe_kids" }
                }
                
                // Load currently blocked apps
                val restrictions = snapshot.get("restrictions") as? List<Map<String, Any>>
                if (restrictions != null) {
                    blockedApps = restrictions
                        .filter { it["isBlocked"] == true && it["timeLimit"] == null }
                        .map { it["packageName"] as? String ?: "" }
                        .toSet()
                }
            } catch (e: Exception) {
                // Handle error
            } finally {
                isLoading = false
            }
        }
    }

    val filteredApps = installedApps.filter { 
        it.appName.contains(searchQuery, ignoreCase = true) || 
        it.packageName.contains(searchQuery, ignoreCase = true) 
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Block Apps", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (installedApps.isEmpty()) "No apps found on child's device" else "No apps match your search",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps) { app ->
                        val isBlocked = blockedApps.contains(app.packageName)
                        AppBlockerItem(
                            app = app,
                            isBlocked = isBlocked,
                            onToggle = { blocked ->
                                scope.launch {
                                    val parentId = auth.currentUser?.uid ?: ""
                                    
                                    // 1. Send command to child_commands collection
                                    val command = mapOf(
                                        "type" to if (blocked) "BLOCK_APP" else "UNBLOCK_APP",
                                        "childId" to childId,
                                        "parentId" to parentId,
                                        "packageName" to app.packageName,
                                        "appName" to app.appName,
                                        "status" to "pending",
                                        "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                    )
                                    db.collection("child_commands").add(command).await()
                                    
                                    // 2. Update child profile restrictions for real-time display
                                    val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                    val snapshot = profileRef.get().await()
                                    val current = snapshot.get("restrictions") as? List<Map<String, Any>> ?: emptyList()
                                    
                                    if (blocked) {
                                        val newList = current.toMutableList()
                                        val existingIndex = current.indexOfFirst { it["packageName"] == app.packageName }
                                        if (existingIndex >= 0) {
                                            val updatedRestriction = current[existingIndex].toMutableMap().apply {
                                            put("isBlocked", true)
                                            remove("timeLimit")
                                            put("limitType", "INDIVIDUAL")
                                        }
                                        newList[existingIndex] = updatedRestriction
                                        } else {
                                            newList.add(mapOf(
                                                "packageName" to app.packageName,
                                                "appName" to app.appName,
                                                "isBlocked" to true,
                                                "limitType" to "INDIVIDUAL"
                                            ))
                                        }
                                        profileRef.set(
                                            mapOf("restrictions" to newList),
                                            com.google.firebase.firestore.SetOptions.merge()
                                        ).await()
                                    } else {
                                        // Remove from restrictions
                                        val updated = current.filter { it["packageName"] != app.packageName }
                                        profileRef.set(
                                            mapOf("restrictions" to updated),
                                            com.google.firebase.firestore.SetOptions.merge()
                                        ).await()
                                    }
                                    
                                    // Update local state immediately for UI feedback
                                    blockedApps = if (blocked) {
                                        blockedApps + app.packageName
                                    } else {
                                        blockedApps - app.packageName
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppBlockerItem(
    app: InstalledApp,
    isBlocked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isBlocked) },
        colors = CardDefaults.cardColors(
            containerColor = if (isBlocked) Color(0xFFFFF5F5) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = app.packageName,
                modifier = Modifier.size(48.dp),
                iconBase64 = app.iconBase64
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.appName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Switch(
                checked = isBlocked,
                onCheckedChange = { onToggle(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Red,
                    checkedTrackColor = Color.Red.copy(alpha = 0.5f)
                )
            )
        }
    }
}
