package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.ActivityType
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.FirestoreQueryUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.auth.User
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PlanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        setContent {
            SAFE_KIDSTheme {
                PlanScreen(childId = childId, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanScreen(childId: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    var alwaysAllowedCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(childId) {
        if (childId.isBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        loadError = null
        try {
            val snap = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId).get().await()
            val downtime = snap.get("downtimeConfig") as? Map<*, *>
            val apps = downtime?.get("alwaysAllowedApps") as? List<*>
            alwaysAllowedCount = apps?.size ?: 0
        } catch (e: Exception) {
            FirestoreQueryUtils.logQueryError("PlanScreen", e)
            loadError = FirestoreQueryUtils.userMessage(e)
            alwaysAllowedCount = 0
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plan", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.Refresh, null) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loadError != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(loadError!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Always Allowed", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("$alwaysAllowedCount", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                    }
                }
            }
        }
    }
}

class TodaysEventActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        setContent {
            SAFE_KIDSTheme {
                TodaysEventScreen(childId = childId, onBack = { finish() })
            }
        }
    }
}

private data class UsageItem(val packageName: String, val appName: String, val millis: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodaysEventScreen(childId: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    var weeklyTab by remember { mutableStateOf(false) }
    var screenTimeMillis by remember { mutableLongStateOf(0L) }
    var usageItems by remember { mutableStateOf<List<UsageItem>>(emptyList()) }
    var notificationCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(childId, weeklyTab) {
        if (childId.isBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        loadError = null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = if (weeklyTab) cal.apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis else cal.timeInMillis

        try {
            val activities = db.collection("activities")
                .whereEqualTo("childId", childId)
                .whereGreaterThanOrEqualTo("timestamp", start)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(400)
                .get()
                .await()
                .documents

            var total = 0L
            val appMap = mutableMapOf<String, UsageItem>()
            activities.forEach { doc ->
                val type = doc.getString("activityType")
                if (type == ActivityType.APP_USAGE.name || type == ActivityType.SCREEN_TIME.name) {
                    val duration = (doc.getLong("duration") ?: 0L).coerceAtLeast(0L)
                    total += duration
                    val details = doc.get("details") as? Map<*, *>
                    val pkg = details?.get("packageName") as? String ?: return@forEach
                    val name = details["appName"] as? String ?: pkg
                    val current = appMap[pkg]
                    appMap[pkg] = UsageItem(pkg, name, (current?.millis ?: 0L) + duration)
                }
            }
            screenTimeMillis = total
            usageItems = appMap.values.sortedByDescending { it.millis }.take(5)

            notificationCount = db.collection("child_notifications")
                .whereEqualTo("childId", childId)
                .whereGreaterThanOrEqualTo("timestamp", start)
                .get()
                .await()
                .size()
        } catch (e: Exception) {
            FirestoreQueryUtils.logQueryError("TodaysEvent", e)
            loadError = FirestoreQueryUtils.userMessage(e)
            screenTimeMillis = 0L
            usageItems = emptyList()
            notificationCount = 0
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today's Event", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.Refresh, null) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loadError != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(loadError!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                        EventTabChip("Daily", !weeklyTab) { weeklyTab = false }
                        EventTabChip("Weekly", weeklyTab) { weeklyTab = true }
                    }
                }
            }
            item {
                Text(
                    "Updated: ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date())}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Screen Time", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(formatMillis(screenTimeMillis), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Most Used Apps", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        if (usageItems.isEmpty()) {
                            Text("No app usage records yet", color = Color.Gray)
                        } else {
                            usageItems.forEachIndexed { index, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.appName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    Text(formatMillis(item.millis), color = MaterialTheme.colorScheme.onSurface)
                                }
                                if (index < usageItems.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Number of Notifications", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("$notificationCount", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun RowScope.EventTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.weight(1f).padding(2.dp).clickable { onClick() },
        color = if (selected) Color.White else Color.Transparent,
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
    }
}

class ParentAdminCodeActivity : ComponentActivity() {
    private lateinit var authManager: com.example.safe_kids.auth.AuthManagerImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            authManager = com.example.safe_kids.auth.AuthManagerImpl(this)
            lifecycleScope.launch {
                authManager.initialize()
            }
            setContent {
                SAFE_KIDSTheme {
                    AdminCodeScreen(authManager = authManager, onBack = { finish() })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminCodeScreen(authManager: com.example.safe_kids.auth.AuthManager, onBack: () -> Unit) {
    val viewModel: AdminCodeViewModel = viewModel(
        factory = AdminCodeViewModelFactory(authManager)
    )
    val uiState by viewModel.uiState.collectAsState()
    val hasAdminCode by viewModel.hasAdminCode.collectAsState()

    var currentInput by remember { mutableStateOf("") }
    var newCodeInput by remember { mutableStateOf("") }
    var activeMode by remember { mutableStateOf("IDLE") } // IDLE, SET, VERIFY_CHANGE, SET_NEW, VERIFY_DISABLE
    val context = LocalContext.current

    LaunchedEffect(uiState) {
        if (uiState is com.example.safe_kids.ui.parent.AdminCodeState.Success) {
            Toast.makeText(context, "Success!", Toast.LENGTH_SHORT).show()
            currentInput = ""
            newCodeInput = ""
            activeMode = "IDLE"
            viewModel.resetState()
            if (!hasAdminCode) onBack() // If disabled, can go back or stay
        } else if (uiState is com.example.safe_kids.ui.parent.AdminCodeState.Error) {
            Toast.makeText(context, (uiState as AdminCodeState.Error).message, Toast.LENGTH_SHORT).show()
            viewModel.resetState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parental PIN", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            
            if (!hasAdminCode || activeMode == "SET") {
                Text("Set Parental PIN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Create a 4-digit parental PIN. This code will be required to bypass restrictions on child devices.", 
                     color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                
                OutlinedTextField(
                    value = currentInput,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) currentInput = it },
                    label = { Text("4-Digit Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                
                Spacer(Modifier.height(40.dp))
                
                Button(
                    onClick = { viewModel.setAdminCode(currentInput) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = currentInput.length == 4 && uiState !is com.example.safe_kids.ui.parent.AdminCodeState.Loading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (uiState is com.example.safe_kids.ui.parent.AdminCodeState.Loading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save Parental PIN", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (activeMode == "IDLE") {
                Text("Manage Parental PIN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(32.dp))
                
                Button(
                    onClick = { activeMode = "VERIFY_CHANGE" },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Change Parental PIN", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { activeMode = "VERIFY_DISABLE" },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disable Parental PIN", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else if (activeMode == "VERIFY_CHANGE" || activeMode == "VERIFY_DISABLE") {
                Text("Verification Required", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Please enter your current parental PIN to proceed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                
                OutlinedTextField(
                    value = currentInput,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) currentInput = it },
                    label = { Text("Current Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                
                if (activeMode == "VERIFY_CHANGE") {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newCodeInput,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) newCodeInput = it },
                        label = { Text("New Code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                
                Spacer(Modifier.height(40.dp))
                
                Button(
                    onClick = {
                        if (activeMode == "VERIFY_CHANGE") {
                            viewModel.changeAdminCode(currentInput, newCodeInput)
                        } else {
                            viewModel.disableAdminCode(currentInput)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = currentInput.length == 4 && (activeMode != "VERIFY_CHANGE" || newCodeInput.length == 4) && uiState !is com.example.safe_kids.ui.parent.AdminCodeState.Loading,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = if (activeMode == "VERIFY_DISABLE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                ) {
                    if (uiState is com.example.safe_kids.ui.parent.AdminCodeState.Loading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (activeMode == "VERIFY_CHANGE") "Update Code" else "Disable Code", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { activeMode = "IDLE"; currentInput = ""; newCodeInput = "" }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


private fun formatMillis(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours} h ${minutes} m"
        minutes > 0 -> "${minutes} min ${seconds} s"
        else -> "${seconds} s"
    }
}
