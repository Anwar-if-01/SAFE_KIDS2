package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.data.SleepModeConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.example.safe_kids.data.*
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.ui.components.toDisplayTime
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import android.provider.Settings
import com.example.safe_kids.utils.PermissionUtils
import kotlinx.coroutines.launch
import java.util.*

class SleepModeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"

        setContent {
            SAFE_KIDSTheme {
                SleepModeScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepModeScreen(childId: String, childName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SleepModeConfig()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasDndPermission by remember { mutableStateOf(PermissionUtils.hasNotificationPolicyAccess(context)) }
    var showDndPermissionDialog by remember { mutableStateOf(false) }
    // Only child devices should be prompted for DND permission
    val isChildDevice = remember { com.example.safe_kids.utils.RoleUtils.isChildDevice(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasDndPermission = PermissionUtils.hasNotificationPolicyAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(childId) {
        if (childId.isNotEmpty()) {
            db.collection("sleep_mode_configs").document(childId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        config = snapshot.toSleepModeConfigCompat()
                    }
                    isLoading = false
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sleep Mode", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Main Toggle
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Active Status", fontWeight = FontWeight.Bold)
                            Text(if (config.Enabled) "Scheduled" else "Disabled", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = config.Enabled,
                            onCheckedChange = { 
                                config = config.copy(Enabled = it)
                                saveConfig(db, childId, config)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("Bedtime Schedule", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))

                // Time Pickers
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TimePickerCard("Start Time", config.startTime, Modifier.weight(1f)) {
                        config = config.copy(startTime = it)
                    }
                    TimePickerCard("End Time", config.endTime, Modifier.weight(1f)) {
                        config = config.copy(endTime = it)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Day Selector
                Text("Active Days", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                DaySelector(config.activeDays) {
                    config = config.copy(activeDays = it)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Notification Suppression Toggle
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.NotificationsOff, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Silence Notifications", fontWeight = FontWeight.Bold)
                            Text("Suppresses all non-emergency alerts during sleep", style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(
                            checked = config.suppressNotifications,
                            onCheckedChange = { 
                                config = config.copy(suppressNotifications = it)
                                saveConfig(db, childId, config)
                                if (it && !hasDndPermission) {
                                    if (isChildDevice) {
                                        showDndPermissionDialog = true
                                    } else {
                                        // Parent device: silently skip requesting DND
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Manual End Button
                if (config.Enabled) {
                    Button(
                        onClick = {
                            config = config.copy(Enabled = false)
                            saveConfig(db, childId, config)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("End Sleep Mode Now", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = { saveConfig(db, childId, config); onBack() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Configuration", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showDndPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showDndPermissionDialog = false },
            title = { Text("Allow Do Not Disturb Access") },
            text = {
                Text("To silence notifications during Sleep Mode, please grant Do Not Disturb access in Settings")
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

@Composable
fun TimePickerCard(label: String, time: String, modifier: Modifier = Modifier, onTimeSelected: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier.clickable { showDialog = true },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text(toDisplayTime(time), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }

    if (showDialog) {
        // Simplified TimePickerDialog using basic Compose state for now
        // In a real app, use Material 3 TimePicker
        com.example.safe_kids.ui.components.TimePickerDialog(
            initialTime = time,
            onDismiss = { showDialog = false },
            onConfirm = { 
                onTimeSelected(it)
                showDialog = false
            }
        )
    }
}

private fun saveConfig(db: FirebaseFirestore, childId: String, config: SleepModeConfig) {
    val data = mapOf(
        "enabled" to config.Enabled,
        "startTime" to config.startTime,
        "endTime" to config.endTime,
        "activeDays" to config.activeDays,
        "suppressNotifications" to config.suppressNotifications,
        "lastUpdated" to System.currentTimeMillis()
    )
    db.collection("sleep_mode_configs").document(childId).set(data)
}
