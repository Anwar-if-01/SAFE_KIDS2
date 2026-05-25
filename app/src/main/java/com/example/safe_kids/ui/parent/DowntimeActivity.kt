package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.example.safe_kids.data.DowntimeSchedule
import com.example.safe_kids.data.TimeRange
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class DowntimeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        setContent {
            SAFE_KIDSTheme {
                DowntimeScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DowntimeScreen(childId: String, childName: String, onBack: () -> Unit) {
    var isDowntimeEnabled by remember { mutableStateOf(false) }
    var schedules by remember { mutableStateOf<List<DowntimeSchedule>>(listOf(
        DowntimeSchedule(name = "School Hours", timeRange = TimeRange(8, 0, 15, 15, (1..5).toList()), isEnabled = false),
        DowntimeSchedule(name = "Bedtime", timeRange = TimeRange(22, 0, 7, 0, (1..7).toList()), isEnabled = false)
    )) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSchedule by remember { mutableStateOf<DowntimeSchedule?>(null) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    // Load from Firestore
    LaunchedEffect(childId) {
        if (childId.isNotEmpty()) {
            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION)
                .document(childId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val downtimeData = snapshot.get("downtimeConfig") as? Map<String, Any>
                        if (downtimeData != null) {
                            isDowntimeEnabled = downtimeData["enabled"] as? Boolean ?: false
                            val schedulesData = downtimeData["schedules"] as? List<Map<String, Any>>
                            if (schedulesData != null) {
                                schedules = schedulesData.map {
                                    val tr = it["timeRange"] as? Map<String, Any>
                                    DowntimeSchedule(
                                        id = it["id"] as? String ?: "",
                                        name = it["name"] as? String ?: "",
                                        timeRange = TimeRange(
                                            startHour = (tr?.get("startHour") as? Long)?.toInt() ?: 0,
                                            startMinute = (tr?.get("startMinute") as? Long)?.toInt() ?: 0,
                                            endHour = (tr?.get("endHour") as? Long)?.toInt() ?: 0,
                                            endMinute = (tr?.get("endMinute") as? Long)?.toInt() ?: 0,
                                            daysOfWeek = (tr?.get("daysOfWeek") as? List<Long>)?.map { l -> l.toInt() } ?: emptyList()
                                        ),
                                        isEnabled = it["enabled"] as? Boolean ?: false
                                    )
                                }
                            }
                        }
                    }
                    isLoading = false
                }
        }
    }

    // Helper: update schedules list in Firestore
    suspend fun updateSchedules(newSchedules: List<DowntimeSchedule>) {
        val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
        val snapshot = profileRef.get().await()
        val downtimeConfig = snapshot.get("downtimeConfig") as? Map<String, Any>
        profileRef.set(
            mapOf("downtimeConfig" to mapOf(
                "enabled" to (downtimeConfig?.get("enabled") as? Boolean ?: false),
                "schedules" to newSchedules.map { s ->
                    mapOf(
                        "id" to s.id,
                        "name" to s.name,
                        "enabled" to s.isEnabled,
                        "timeRange" to mapOf(
                            "startHour" to s.timeRange.startHour.toLong(),
                            "startMinute" to s.timeRange.startMinute.toLong(),
                            "endHour" to s.timeRange.endHour.toLong(),
                            "endMinute" to s.timeRange.endMinute.toLong(),
                            "daysOfWeek" to s.timeRange.daysOfWeek.map { it.toLong() }
                        )
                    )
                }
            )),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downtime", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = {
            val context = androidx.compose.ui.platform.LocalContext.current
            Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding()) {
                Button(
                    onClick = {
                        val intent = android.content.Intent(context, AddDowntimeActivity::class.java).apply {
                            putExtra("CHILD_ID", childId)
                            putExtra("CHILD_NAME", childName)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Add Schedule", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "During downtime, only phone calls and the apps that you add to the Always Allowed will be available.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column {
                    // Master Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enable Downtime", fontWeight = FontWeight.Bold)
                        Switch(
                            checked = isDowntimeEnabled,
                            onCheckedChange = { checked ->
                                isDowntimeEnabled = checked
                                scope.launch {
                                    val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                    val snapshot = profileRef.get().await()
                                    val downtimeConfig = snapshot.get("downtimeConfig") as? Map<String, Any>
                                    profileRef.set(
                                        mapOf("downtimeConfig" to mapOf(
                                            "enabled" to checked,
                                            "schedules" to (downtimeConfig?.get("schedules") ?: emptyList<Any>())
                                        )),
                                        com.google.firebase.firestore.SetOptions.merge()
                                    ).await()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = Color.LightGray.copy(alpha = 0.5f)
                    )

                    // Schedules List
                    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                        items(schedules) { schedule ->
                            ScheduleItemRow(
                                childId = childId,
                                schedule = schedule,
                                onTap = {
                                    selectedSchedule = schedule
                                    showOptionsSheet = true
                                }
                            )
                            if (schedule != schedules.last()) {
                                HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // ---- Options Bottom Sheet ----
    if (showOptionsSheet && selectedSchedule != null) {
        val s = selectedSchedule!!
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            containerColor = Color.White
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                // Header
                Text(
                    s.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.4f))

                val currentContext = androidx.compose.ui.platform.LocalContext.current
                ScheduleOptionItem(
                    icon = Icons.Default.Edit,
                    iconColor = MaterialTheme.colorScheme.primary,
                    label = "Edit Schedule",
                    onClick = {
                        showOptionsSheet = false
                        val intent = android.content.Intent(currentContext, AddDowntimeActivity::class.java).apply {
                            putExtra("CHILD_ID", childId)
                            putExtra("CHILD_NAME", childName)
                            putExtra("SCHEDULE_ID", s.id)
                            putExtra("SCHEDULE_NAME", s.name)
                            putExtra("START_HOUR", s.timeRange.startHour)
                            putExtra("START_MINUTE", s.timeRange.startMinute)
                            putExtra("END_HOUR", s.timeRange.endHour)
                            putExtra("END_MINUTE", s.timeRange.endMinute)
                            putExtra("DAYS_OF_WEEK", s.timeRange.daysOfWeek.toIntArray())
                            putExtra("IS_ENABLED", s.isEnabled)
                        }
                        currentContext.startActivity(intent)
                    }
                )
                ScheduleOptionItem(
                    icon = if (s.isEnabled) Icons.Default.ToggleOff else Icons.Default.ToggleOn,
                    iconColor = MaterialTheme.colorScheme.primary,
                    label = if (s.isEnabled) "Disable Schedule" else "Enable Schedule",
                    onClick = {
                        showOptionsSheet = false
                        scope.launch {
                            val updated = schedules.map { if (it.id == s.id) it.copy(isEnabled = !s.isEnabled) else it }
                            updateSchedules(updated)
                        }
                    }
                )
                ScheduleOptionItem(
                    icon = Icons.Default.ContentCopy,
                    iconColor = MaterialTheme.colorScheme.primary,
                    label = "Duplicate",
                    onClick = {
                        showOptionsSheet = false
                        scope.launch {
                            val duplicate = s.copy(id = java.util.UUID.randomUUID().toString(), name = "${s.name} (Copy)")
                            val updated = schedules + duplicate
                            updateSchedules(updated)
                        }
                    }
                )
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.4f))
                ScheduleOptionItem(
                    icon = Icons.Default.Delete,
                    iconColor = Color.Red,
                    label = "Delete",
                    labelColor = Color.Red,
                    onClick = {
                        showOptionsSheet = false
                        showDeleteDialog = true
                    }
                )
            }
        }
    }

    // ---- Delete Confirm Dialog ----
    if (showDeleteDialog && selectedSchedule != null) {
        val s = selectedSchedule!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Schedule", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete \"${s.name}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            val updated = schedules.filter { it.id != s.id }
                            updateSchedules(updated)
                            selectedSchedule = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun ScheduleOptionItem(
    icon: ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    label: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, fontSize = 16.sp, color = labelColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ScheduleItemRow(childId: String, schedule: DowntimeSchedule, onTap: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable { onTap() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(schedule.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Block, null, tint = Color.Red, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    val timeStr = formatTimeRangeAmPm(
                        schedule.timeRange.startHour, schedule.timeRange.startMinute,
                        schedule.timeRange.endHour, schedule.timeRange.endMinute
                    )
                    val daysStr = when (schedule.timeRange.daysOfWeek.size) {
                        7 -> "Every day"
                        5 -> "Mon – Fri"
                        2 -> "Sat & Sun"
                        else -> "Custom"
                    }
                    Text("$timeStr · $daysStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }

        Switch(
            checked = schedule.isEnabled,
            onCheckedChange = { checked ->
                scope.launch {
                    val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                    val snapshot = profileRef.get().await()
                    val downtimeConfig = snapshot.get("downtimeConfig") as? Map<String, Any>
                    val schedulesData = downtimeConfig?.get("schedules") as? List<Map<String, Any>>
                    if (schedulesData != null) {
                        val newSchedules = schedulesData.map {
                            if (it["id"] == schedule.id) {
                                it.toMutableMap().apply { put("enabled", checked) }
                            } else it
                        }
                        profileRef.set(
                            mapOf("downtimeConfig" to mapOf(
                                "enabled" to (downtimeConfig["enabled"] as? Boolean ?: false),
                                "schedules" to newSchedules
                            )),
                            com.google.firebase.firestore.SetOptions.merge()
                        ).await()
                    }
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF4C66EA)
            )
        )
    }
}

