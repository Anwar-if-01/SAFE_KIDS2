package com.example.safe_kids.ui.parent

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.safe_kids.data.GlobalScreenTimeConfig
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class GlobalScreenTimeViewModel : ViewModel() {
    private val firestore = FirebaseFirestore.getInstance()
    
    private val _config = MutableStateFlow(GlobalScreenTimeConfig())
    val config: StateFlow<GlobalScreenTimeConfig> = _config
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var currentChildId = ""

    fun loadConfig(childId: String) {
        currentChildId = childId
        viewModelScope.launch {
            try {
                val snapshot = firestore.collection("screen_time_limits").document(childId).get().await()
                if (snapshot.exists()) {
                    _config.value = GlobalScreenTimeConfig(
                        childId = childId,
                        dailyLimitMinutes = snapshot.getLong("dailyLimitMinutes")?.toInt() ?: 120,
                        extendedMinutes = snapshot.getLong("extendedMinutes")?.toInt() ?: 0,
                        lastUpdated = snapshot.getLong("lastUpdated") ?: System.currentTimeMillis()
                    )
                } else {
                    _config.value = GlobalScreenTimeConfig(childId = childId)
                }
            } catch (e: Exception) {
                // Ignore errors
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveConfig(minutes: Int, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val newConfig = _config.value.copy(
                    dailyLimitMinutes = minutes,
                    lastUpdated = System.currentTimeMillis()
                )
                firestore.collection("screen_time_limits").document(currentChildId)
                    .set(newConfig).await()
                _config.value = newConfig
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save configuration")
            }
        }
    }

    fun extendTime(minutesToAdd: Int, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val newExtended = _config.value.extendedMinutes + minutesToAdd
                firestore.collection("screen_time_limits").document(currentChildId)
                    .update(
                        "extendedMinutes", newExtended,
                        "lastUpdated", System.currentTimeMillis()
                    ).await()
                _config.value = _config.value.copy(extendedMinutes = newExtended)
                
                // If there's a pending request, we mark it as approved
                firestore.collection("screen_time_requests")
                    .document(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")
                    .collection("requests")
                    .document(currentChildId)
                    .delete() // just delete it once handled
                    
                onSuccess()
            } catch (e: Exception) {
                // Handle
            }
        }
    }
}

class GlobalScreenTimeActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: return finish()
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"

        setContent {
            SAFE_KIDSTheme {
                val viewModel: GlobalScreenTimeViewModel = viewModel()
                LaunchedEffect(childId) {
                    viewModel.loadConfig(childId)
                }
                
                val config by viewModel.config.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                
                var selectedHours by remember(config) { mutableIntStateOf(config.dailyLimitMinutes / 60) }
                var selectedMinutes by remember(config) { mutableIntStateOf(config.dailyLimitMinutes % 60) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("$childName's Screen Time") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    if (isLoading) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(24.dp)
                        ) {
                            Text(
                                "Daily Screen Time Limit",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Set the maximum amount of time $childName can spend on their device each day.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            // Wheel Picker or simple buttons to pick hours/minutes
                            // For simplicity, we'll use a Row with OutlinedTextFields or custom +/- controls
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Hours
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Hours", style = MaterialTheme.typography.labelMedium)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { if (selectedHours > 0) selectedHours-- }) {
                                                Text("-", style = MaterialTheme.typography.headlineMedium)
                                            }
                                            Text("$selectedHours", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                            IconButton(onClick = { if (selectedHours < 23) selectedHours++ }) {
                                                Text("+", style = MaterialTheme.typography.headlineMedium)
                                            }
                                        }
                                    }
                                    
                                    Text(":", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                    
                                    // Minutes
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Minutes", style = MaterialTheme.typography.labelMedium)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { if (selectedMinutes > 0) selectedMinutes -= 15 }) {
                                                Text("-", style = MaterialTheme.typography.headlineMedium)
                                            }
                                            Text(String.format("%02d", selectedMinutes), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                            IconButton(onClick = { if (selectedMinutes < 45) selectedMinutes += 15 }) {
                                                Text("+", style = MaterialTheme.typography.headlineMedium)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Button(
                                onClick = {
                                    val totalMinutes = selectedHours * 60 + selectedMinutes
                                    if (totalMinutes == 0) {
                                        Toast.makeText(this@GlobalScreenTimeActivity, "Limit must be at least 15 minutes", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    viewModel.saveConfig(
                                        minutes = totalMinutes,
                                        onSuccess = {
                                            Toast.makeText(this@GlobalScreenTimeActivity, "Limit saved successfully", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = {
                                            Toast.makeText(this@GlobalScreenTimeActivity, it, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text("Save Limit", fontSize = MaterialTheme.typography.titleMedium.fontSize)
                            }
                            
                            Divider(modifier = Modifier.padding(vertical = 32.dp))
                            
                            // Extend Time Section
                            Text(
                                "Grant Bonus Time",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Instantly unlock the device and add extra time for today.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            if (config.extendedMinutes > 0) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                                ) {
                                    Text(
                                        "Bonus time granted today: ${config.extendedMinutes} minutes",
                                        modifier = Modifier.padding(16.dp),
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.extendTime(15) { Toast.makeText(this@GlobalScreenTimeActivity, "Added 15 minutes", Toast.LENGTH_SHORT).show() } },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Text(" 15m")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.extendTime(30) { Toast.makeText(this@GlobalScreenTimeActivity, "Added 30 minutes", Toast.LENGTH_SHORT).show() } },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Text(" 30m")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.extendTime(60) { Toast.makeText(this@GlobalScreenTimeActivity, "Added 1 hour", Toast.LENGTH_SHORT).show() } },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Text(" 1h")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
