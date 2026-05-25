package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.InstalledApp
import com.example.safe_kids.ui.components.AddAppsSheet
import com.example.safe_kids.ui.components.DetailedCard
import com.example.safe_kids.ui.components.OptionRow
import java.util.UUID

class AddAppLimitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        setContent {
            SAFE_KIDSTheme {
                AddAppLimitScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppLimitScreen(childId: String, childName: String, onBack: () -> Unit) {
    var remark by remember { mutableStateOf("") }
    var selectedLimitType by remember { mutableStateOf("Total Time Limit") }
    var selectedDays by remember { mutableStateOf("Every day") }
    var durationHours by remember { mutableStateOf(1) }
    var durationMinutes by remember { mutableStateOf(0) }
    var downtimeStartHour by remember { mutableStateOf(22) }
    var downtimeStartMinute by remember { mutableStateOf(0) }
    var downtimeEndHour by remember { mutableStateOf(7) }
    var downtimeEndMinute by remember { mutableStateOf(0) }
    var isGeofenceEnabled by remember { mutableStateOf(false) }
    val selectedPackages = remember { mutableStateListOf<String>() }
    var showAddAppsSheet by remember { mutableStateOf(false) }
    var showDurationPicker by remember { mutableStateOf(false) }
    var showDowntimePicker by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    LaunchedEffect(childId) {
        if (childId.isNotEmpty()) {
            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .get().addOnSuccessListener { snapshot ->
                    val apps = snapshot.get("installedApps") as? List<Map<String, Any>>
                    if (apps != null) {
                        installedApps = apps.map {
                            InstalledApp(
                                packageName = it["packageName"] as? String ?: "",
                                appName = it["appName"] as? String ?: "",
                                category = it["category"] as? String ?: "Other",
                                isSystemApp = it["isSystemApp"] as? Boolean ?: false
                            )
                        }
                    }
                }
        }
    }

    val limitTypes = listOf(
        "Total Time Limit" to "Limit the total daily usage for the selected apps. Once the time is up, your approval is needed for more time.",
        "Individual App Limits" to "Set a custom time limit for each selected app. Approval is required when an app's time is up.",
        "Downtime" to "Schedule a downtime, during which access to the app requires your permission."
    )
    val dayOptions = listOf("Every day", "Monday to Friday", "Saturday and Sunday", "Customize")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Limits", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            if (selectedPackages.isEmpty()) return@launch
                            val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                            val snapshot = profileRef.get().await()
                            val currentRestrictions = snapshot.get("restrictions") as? List<Map<String, Any>> ?: emptyList()
                            val newRestrictions = currentRestrictions.toMutableList()
                            val type = when (selectedLimitType) {
                                "Total Time Limit" -> "TOTAL"
                                "Downtime" -> "DOWNTIME"
                                else -> "INDIVIDUAL"
                            }
                            val days = when (selectedDays) {
                                "Monday to Friday" -> (1..5).toList()
                                "Saturday and Sunday" -> listOf(6, 7)
                                else -> (1..7).toList()
                            }
                            selectedPackages.forEach { pkg ->
                                val app = installedApps.find { it.packageName == pkg }
                                val entry = mutableMapOf<String, Any>(
                                    "id" to UUID.randomUUID().toString(),
                                    "packageName" to pkg,
                                    "appName" to (app?.appName ?: pkg),
                                    "isBlocked" to false,
                                    "limitType" to type,
                                    "remark" to remark,
                                    "isGeofenceEnabled" to isGeofenceEnabled,
                                    "daysOfWeek" to days
                                )
                                if (type == "DOWNTIME") {
                                    entry["downtimeStart"] = mapOf("hour" to downtimeStartHour.toLong(), "minute" to downtimeStartMinute.toLong())
                                    entry["downtimeEnd"] = mapOf("hour" to downtimeEndHour.toLong(), "minute" to downtimeEndMinute.toLong())
                                } else {
                                    entry["timeLimit"] = ((durationHours * 60 + durationMinutes) * 60000).toLong()
                                }
                                newRestrictions.add(entry)
                            }
                            profileRef.set(
                                mapOf("restrictions" to newRestrictions),
                                com.google.firebase.firestore.SetOptions.merge()
                            ).await()
                            onBack()
                        }
                    }) {
                        Text("Done", color = Color(0xFF4C66EA), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = Color(0xFFF8F9FB)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // Remark
            item {
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    placeholder = { Text("Remark", color = Color.Gray) },
                    trailingIcon = {
                        Text("Optional", color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 12.dp))
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Limit Type
            item {
                Text("Limit Type", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                DetailedCard {
                    Column {
                        limitTypes.forEachIndexed { index, (title, desc) ->
                            LimitTypeRow(
                                title = title,
                                description = desc,
                                isSelected = selectedLimitType == title,
                                onSelect = { selectedLimitType = title }
                            )
                            if (index < limitTypes.size - 1) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }

            // Customize Days
            item {
                Text("Customize Days", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                DetailedCard {
                    Column {
                        dayOptions.forEachIndexed { index, option ->
                            OptionRow(
                                title = option,
                                isSelected = selectedDays == option,
                                onSelect = { selectedDays = option }
                            )
                            if (index < dayOptions.size - 1) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }

            // Time row — duration or downtime range depending on limit type
            item {
                DetailedCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (selectedLimitType == "Downtime") showDowntimePicker = true
                            else showDurationPicker = true
                        }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Time", fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val label = if (selectedLimitType == "Downtime") {
                                "${formatTimeAmPm(downtimeStartHour, downtimeStartMinute)} - ${formatTimeAmPm(downtimeEndHour, downtimeEndMinute)}"
                            } else {
                                buildString {
                                    if (durationHours > 0) append("${durationHours}h ")
                                    if (durationMinutes > 0) append("${durationMinutes}m")
                                    if (durationHours == 0 && durationMinutes == 0) append("0m")
                                }.trim()
                            }
                            Text(label, color = Color.Gray)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                        }
                    }
                }
            }

            // Geofence
            item {
                Text("Geofence", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                DetailedCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Geofence Area", fontWeight = FontWeight.Bold)
                            Switch(
                                checked = isGeofenceEnabled,
                                onCheckedChange = { isGeofenceEnabled = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.surface, checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Turn on \"Geofence Area\" to apply the limit in the specific area.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }
            }

            // Category and App
            item {
                Text("Category and App", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                DetailedCard {
                    Column {
                        if (selectedPackages.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No apps selected", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            selectedPackages.forEachIndexed { index, pkg ->
                                val app = installedApps.find { it.packageName == pkg }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.RemoveCircle,
                                        contentDescription = "Remove",
                                        tint = Color.Red,
                                        modifier = Modifier.size(22.dp).clickable { selectedPackages.remove(pkg) }
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Surface(
                                        color = Color(0xFFE8EAF6),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Apps, null, tint = Color(0xFF4C66EA), modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(app?.appName ?: pkg, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                }
                                if (index < selectedPackages.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                        Box(
                            modifier = Modifier.fillMaxWidth().clickable { showAddAppsSheet = true }.padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Add Apps", color = Color(0xFF4C66EA), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        // Duration picker dialog (for Total / Individual)
        if (showDurationPicker) {
            DurationPickerDialog(
                initialHours = durationHours,
                initialMinutes = durationMinutes,
                onDismiss = { showDurationPicker = false },
                onConfirm = { h, m ->
                    durationHours = h
                    durationMinutes = m
                    showDurationPicker = false
                }
            )
        }

        // Downtime time-range picker
        if (showDowntimePicker) {
            DowntimeRangePickerDialog(
                initialStartHour = downtimeStartHour,
                initialStartMinute = downtimeStartMinute,
                initialEndHour = downtimeEndHour,
                initialEndMinute = downtimeEndMinute,
                onDismiss = { showDowntimePicker = false },
                onConfirm = { sh, sm, eh, em ->
                    downtimeStartHour = sh; downtimeStartMinute = sm
                    downtimeEndHour = eh; downtimeEndMinute = em
                    showDowntimePicker = false
                }
            )
        }

        if (showAddAppsSheet) {
            AddAppsSheet(
                installedApps = installedApps,
                onDismiss = { showAddAppsSheet = false },
                onConfirm = { selected ->
                    selected.forEach { if (!selectedPackages.contains(it)) selectedPackages.add(it) }
                    showAddAppsSheet = false
                }
            )
        }
    }
}

@Composable
fun LimitTypeRow(title: String, description: String, isSelected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        }
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4C66EA))
        )
    }
}

// ---- Duration picker (hours + minutes) ----
@Composable
fun DurationPickerDialog(
    initialHours: Int,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var hours by remember { mutableStateOf(initialHours) }
    var minutes by remember { mutableStateOf(initialMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Set Time Limit", fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Hours", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { if (hours > 0) hours-- }) {
                        Icon(Icons.Default.Remove, null, tint = Color(0xFF4C66EA))
                    }
                    Text("$hours", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { if (hours < 23) hours++ }) {
                        Icon(Icons.Default.Add, null, tint = Color(0xFF4C66EA))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Minutes", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { if (minutes >= 15) minutes -= 15 }) {
                        Icon(Icons.Default.Remove, null, tint = Color(0xFF4C66EA))
                    }
                    Text("$minutes", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { if (minutes <= 44) minutes += 15 }) {
                        Icon(Icons.Default.Add, null, tint = Color(0xFF4C66EA))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(hours, minutes) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("OK", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

// ---- Downtime range picker (AM/PM time pickers) ----
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DowntimeRangePickerDialog(
    initialStartHour: Int,
    initialStartMinute: Int,
    initialEndHour: Int,
    initialEndMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int, Int) -> Unit
) {
    // Use Material3 TimePickerState
    val startState = rememberTimePickerState(initialHour = initialStartHour, initialMinute = initialStartMinute, is24Hour = false)
    val endState = rememberTimePickerState(initialHour = initialEndHour, initialMinute = initialEndMinute, is24Hour = false)
    var pickingEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                if (!pickingEnd) "Start Time" else "End Time",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = if (!pickingEnd) startState else endState)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!pickingEnd) {
                        pickingEnd = true
                    } else {
                        onConfirm(startState.hour, startState.minute, endState.hour, endState.minute)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (!pickingEnd) "Next: End Time" else "OK", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                onClick = { if (pickingEnd) pickingEnd = false else onDismiss() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (pickingEnd) "Back" else "Cancel", color = Color.Gray) }
        }
    )
}
