package com.example.safe_kids.ui.parent

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.example.safe_kids.utils.PermissionUtils
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.InstalledApp
import com.example.safe_kids.data.*
import com.example.safe_kids.ui.components.AddAppsSheet
import com.example.safe_kids.ui.components.AppIcon
import com.example.safe_kids.ui.components.TimePickerDialog
import com.example.safe_kids.ui.components.toDisplayTime
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class StudyModeActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: return finish()
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"

        setContent {
            SAFE_KIDSTheme {
                StudyModeScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyModeScreen(childId: String, childName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var config by remember { mutableStateOf(StudyModeConfig()) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAppPicker by remember { mutableStateOf(false) }
    var hasDndPermission by remember { mutableStateOf(PermissionUtils.hasNotificationPolicyAccess(context)) }
    var showDndPermissionDialog by remember { mutableStateOf(false) }
    // Determine role (from SharedPreferences). Only children should be prompted for DND.
    val isChildDevice = remember {
        com.example.safe_kids.utils.RoleUtils.isChildDevice(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasDndPermission = PermissionUtils.hasNotificationPolicyAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Time picker states
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(childId) {
        // Load Config
        db.collection("study_mode_configs").document(childId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    config = snapshot.toStudyModeConfigCompat()
                }
                isLoading = false
            }
            
        // Load Installed Apps for icons/names
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
            .get().addOnSuccessListener { snapshot ->
                val apps = snapshot.get("installedApps") as? List<Map<String, Any>>
                if (apps != null) {
                    installedApps = apps.map {
                        InstalledApp(
                            packageName = it["packageName"] as? String ?: "",
                            appName = it["appName"] as? String ?: "",
                            iconBase64 = it["iconBase64"] as? String
                        )
                    }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Study Mode Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                item {
                    Spacer(Modifier.height(16.dp))
                    
                    // Status Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (config.isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (config.isEnabled) "Study Mode is Enabled" else "Study Mode is Disabled",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "When enabled, distracting apps will be blocked during study hours.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = config.isEnabled,
                                onCheckedChange = { checked ->
                                    config = config.copy(isEnabled = checked)
                                    saveConfig(db, childId, config)
                                    if (checked && !hasDndPermission) {
                                        // Only request DND when running on a child device
                                        if (isChildDevice) {
                                            showDndPermissionDialog = true
                                        } else {
                                            // Parent device: silently skip requesting DND
                                            // Update UI state without prompting
                                        }
                                    }
                                }
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    
                    Text("Session Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    
                    // Time Selectors
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeCard("Start Time", config.startTime, Modifier.weight(1f)) { showStartTimePicker = true }
                        TimeCard("End Time", config.endTime, Modifier.weight(1f)) { showEndTimePicker = true }
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    
                    Text("Active Days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    
                    DaySelector(config.activeDays) { selectedDays ->
                        config = config.copy(activeDays = selectedDays)
                        saveConfig(db, childId, config)
                    }
                    
                    Spacer(Modifier.height(32.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Whitelisted Apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showAppPicker = true }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Text(" Add Apps")
                        }
                    }
                    Text(
                        "Educational apps selected here will remain accessible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                
                if (config.allowedApps.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No apps whitelisted", color = Color.Gray)
                        }
                    }
                } else {
                    items(config.allowedApps) { pkg ->
                        val app = installedApps.find { it.packageName == pkg }
                        AppWhitelistItem(
                            name = app?.appName ?: pkg,
                            packageName = pkg,
                            iconBase64 = app?.iconBase64,
                            onRemove = {
                                val updated = config.allowedApps.filter { it != pkg }
                                config = config.copy(allowedApps = updated)
                                saveConfig(db, childId, config)
                            }
                        )
                    }
                }
                
                item {
                    Spacer(Modifier.height(32.dp))
                    
                    // Danger Zone / End Now
                    if (config.isEnabled) {
                        Button(
                            onClick = {
                                // For "End Now", we temporarily disable and save
                                config = config.copy(isEnabled = false)
                                saveConfig(db, childId, config)
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("End Study Mode Now")
                        }
                    }
                    
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
        
        if (showAppPicker) {
            AddAppsSheet(
                installedApps = installedApps,
                onDismiss = { showAppPicker = false },
                onConfirm = { selected ->
                    val updated = (config.allowedApps + selected).distinct()
                    config = config.copy(allowedApps = updated)
                    saveConfig(db, childId, config)
                    showAppPicker = false
                }
            )
        }
        
        if (showStartTimePicker || showEndTimePicker) {
            val isStart = showStartTimePicker
            val time = if (isStart) config.startTime else config.endTime
            TimePickerDialog(
                initialTime = time,
                onDismiss = { showStartTimePicker = false; showEndTimePicker = false },
                onConfirm = { timeStr ->
                    config = if (isStart) config.copy(startTime = timeStr) else config.copy(endTime = timeStr)
                    saveConfig(db, childId, config)
                    showStartTimePicker = false
                    showEndTimePicker = false
                }
            )
        }

        if (showDndPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showDndPermissionDialog = false },
                title = { Text("Allow Do Not Disturb Access") },
                text = {
                    Text("To silence notifications during Study Mode, please grant Do Not Disturb access in Settings")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDndPermissionDialog = false
                        PermissionUtils.requestNotificationPolicyAccess(context)
                    }) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDndPermissionDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun TimeCard(label: String, time: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text(toDisplayTime(time), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DaySelector(selectedDays: List<Int>, onSelectionChange: (List<Int>) -> Unit) {
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { index, day ->
            val dayId = index + 1
            val isSelected = selectedDays.contains(dayId)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
                    .clickable {
                        val newList = if (isSelected) selectedDays.filter { it != dayId } else selectedDays + dayId
                        onSelectionChange(newList)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    day,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AppWhitelistItem(name: String, packageName: String, iconBase64: String?, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName, Modifier.size(40.dp), iconBase64)
        Spacer(Modifier.width(16.dp))
        Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
    }
}

private fun saveConfig(db: FirebaseFirestore, childId: String, config: StudyModeConfig) {
    val data = mapOf(
        "isEnabled" to config.isEnabled,
        "enabled" to config.isEnabled,
        "startTime" to config.startTime,
        "endTime" to config.endTime,
        "activeDays" to config.activeDays,
        "allowedApps" to config.allowedApps,
        "lastUpdated" to System.currentTimeMillis()
    )
    db.collection("study_mode_configs").document(childId).set(data)
}
