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
import com.example.safe_kids.ui.components.DetailedCard
import com.example.safe_kids.ui.components.OptionRow
import com.example.safe_kids.ui.components.TimeRangeWheelPickerSheet
import java.util.UUID

class AddDowntimeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        val scheduleId = intent.getStringExtra("SCHEDULE_ID")
        val scheduleName = intent.getStringExtra("SCHEDULE_NAME")
        val startHour = intent.getIntExtra("START_HOUR", 18)
        val startMinute = intent.getIntExtra("START_MINUTE", 0)
        val endHour = intent.getIntExtra("END_HOUR", 7)
        val endMinute = intent.getIntExtra("END_MINUTE", 10)
        val daysOfWeek = intent.getIntArrayExtra("DAYS_OF_WEEK")?.toList()
        val isEnabled = intent.getBooleanExtra("IS_ENABLED", true)
        
        setContent {
            SAFE_KIDSTheme {
                AddDowntimeScreen(
                    childId = childId,
                    childName = childName,
                    scheduleId = scheduleId,
                    initialName = scheduleName,
                    initialStartH = startHour,
                    initialStartM = startMinute,
                    initialEndH = endHour,
                    initialEndM = endMinute,
                    initialDays = daysOfWeek,
                    initialEnabled = isEnabled,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDowntimeScreen(
    childId: String, 
    childName: String, 
    scheduleId: String?,
    initialName: String?,
    initialStartH: Int,
    initialStartM: Int,
    initialEndH: Int,
    initialEndM: Int,
    initialDays: List<Int>?,
    initialEnabled: Boolean,
    onBack: () -> Unit
) {
    var remark by remember { mutableStateOf(initialName ?: "") }
    
    val defaultDaysOption = when {
        initialDays == null || initialDays.size == 7 -> "Every day"
        initialDays.size == 5 && initialDays.containsAll(listOf(1, 2, 3, 4, 5)) -> "Monday to Friday"
        initialDays.size == 2 && initialDays.containsAll(listOf(6, 7)) -> "Saturday and Sunday"
        else -> "Customize"
    }
    
    var selectedDaysOption by remember { mutableStateOf(defaultDaysOption) }
    var startHour by remember { mutableStateOf(initialStartH) }
    var startMinute by remember { mutableStateOf(initialStartM) }
    var endHour by remember { mutableStateOf(initialEndH) }
    var endMinute by remember { mutableStateOf(initialEndM) }
    var isGeofenceEnabled by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    
    val hasChanges = remember(
        remark, selectedDaysOption, startHour, startMinute, endHour, endMinute, isGeofenceEnabled
    ) {
        remark != (initialName ?: "") ||
        selectedDaysOption != defaultDaysOption ||
        startHour != initialStartH ||
        startMinute != initialStartM ||
        endHour != initialEndH ||
        endMinute != initialEndM ||
        isGeofenceEnabled != false
    }
    
    val handleBackPress = {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }
    
    // Intercept hardware back button
    androidx.activity.compose.BackHandler(enabled = hasChanges) {
        showUnsavedDialog = true
    }
    
    val dayOptions = listOf("Every day", "Monday to Friday", "Saturday and Sunday", "Customize")
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downtime", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = handleBackPress) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            val profileRef = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                            val snapshot = profileRef.get().await()
                            val downtimeConfig = snapshot.get("downtimeConfig") as? Map<String, Any>
                            val currentSchedules = (downtimeConfig?.get("schedules") as? List<Map<String, Any>> ?: emptyList()).toMutableList()

                            val daysOfWeek = when (selectedDaysOption) {
                                "Every day" -> (1..7).toList()
                                "Monday to Friday" -> (1..5).toList()
                                "Saturday and Sunday" -> listOf(6, 7)
                                else -> (1..7).toList()
                            }

                            val scheduleObject = mapOf(
                                "id" to (scheduleId ?: UUID.randomUUID().toString()),
                                "name" to if (remark.isBlank()) "New Schedule" else remark,
                                "enabled" to initialEnabled,
                                "timeRange" to mapOf(
                                    "startHour" to startHour.toLong(),
                                    "startMinute" to startMinute.toLong(),
                                    "endHour" to endHour.toLong(),
                                    "endMinute" to endMinute.toLong(),
                                    "daysOfWeek" to daysOfWeek.map { it.toLong() }
                                ),
                                "isGeofenceEnabled" to isGeofenceEnabled
                            )

                            if (scheduleId != null) {
                                val index = currentSchedules.indexOfFirst { it["id"] == scheduleId }
                                if (index != -1) currentSchedules[index] = scheduleObject
                                else currentSchedules.add(scheduleObject)
                            } else {
                                currentSchedules.add(scheduleObject)
                            }

                            // Use set+merge so the document/field is created if it doesn't exist yet
                            profileRef.set(
                                mapOf("downtimeConfig" to mapOf(
                                    "enabled" to (downtimeConfig?.get("enabled") as? Boolean ?: false),
                                    "schedules" to currentSchedules
                                )),
                                com.google.firebase.firestore.SetOptions.merge()
                            ).await()
                            onBack()
                        }
                    }) {
                        Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            item {
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    placeholder = { Text("Remark", color = Color.Gray) },
                    trailingIcon = { Text("Optional", color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            item {
                Text("Customize Days", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                DetailedCard {
                    Column {
                        dayOptions.forEachIndexed { index, option ->
                            OptionRow(
                                title = option,
                                isSelected = selectedDaysOption == option,
                                onSelect = { selectedDaysOption = option }
                            )
                            if (index < dayOptions.size - 1) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }

            item {
                DetailedCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Downtime", fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val timeStr = formatTimeRangeAmPm(startHour, startMinute, endHour, endMinute)
                            Text(timeStr, color = Color.Gray)
                            Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                        }
                    }
                }
            }

            item {
                Text("Geofence", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Turn on \"Geofence Area\" to apply the limit in the specific area.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }
            }
            
            item { Spacer(Modifier.height(32.dp)) }
        }

        if (showTimePicker) {
            TimeRangeWheelPickerSheet(
                initialStartHour = startHour,
                initialStartMinute = startMinute,
                initialEndHour = endHour,
                initialEndMinute = endMinute,
                onDismiss = { showTimePicker = false },
                onConfirm = { sh, sm, eh, em ->
                    startHour = sh
                    startMinute = sm
                    endHour = eh
                    endMinute = em
                    showTimePicker = false
                }
            )
        }
        
        if (showUnsavedDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedDialog = false },
                title = { 
                    Text("You have Unsaved Changes", fontWeight = FontWeight.Bold, color = Color.Black) 
                },
                text = { 
                    Text("Are you sure you want to return?", color = Color.Gray) 
                },
                confirmButton = {
                    Button(
                        onClick = { 
                            showUnsavedDialog = false
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showUnsavedDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}


