package com.example.safe_kids.ui.parent

import android.os.Bundle
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class CallsSmsMonitoringActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        setContent {
            SAFE_KIDSTheme {
                CallsSmsMonitoringScreen(childId = childId, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsSmsMonitoringScreen(childId: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }

    // Settings state
    var callBlacklist by remember { mutableStateOf<List<String>>(emptyList()) }
    var smsKeywords by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAddCallDialog by remember { mutableStateOf(false) }
    var showAddKeywordDialog by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }

    // Real log data from Firestore
    var callLogs by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var smsLogs by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoadingCalls by remember { mutableStateOf(true) }
    var isLoadingSms by remember { mutableStateOf(true) }

    // Load settings (blacklist, keywords)
    DisposableEffect(childId) {
        if (childId.isEmpty()) {
            onDispose { }
        } else {
            val reg = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .addSnapshotListener { snap, _ ->
                    @Suppress("UNCHECKED_CAST")
                    callBlacklist = snap?.get("callBlacklist") as? List<String> ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    smsKeywords = snap?.get("smsKeywords") as? List<String> ?: emptyList()
                }
            onDispose { reg.remove() }
        }
    }

    // Load real call logs from Firestore
    DisposableEffect(childId) {
        if (childId.isEmpty()) {
            isLoadingCalls = false
            onDispose { }
        } else {
            val reg: ListenerRegistration = db.collection("calls_logs")
                .whereEqualTo("childId", childId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        android.util.Log.e("CallsSmsMonitoring", "calls_logs error", error)
                    }
                    callLogs = snap?.documents?.mapNotNull { it.data } ?: emptyList()
                    isLoadingCalls = false
                }
            onDispose { reg.remove() }
        }
    }

    // Load real SMS logs from Firestore
    DisposableEffect(childId) {
        if (childId.isEmpty()) {
            isLoadingSms = false
            onDispose { }
        } else {
            val reg: ListenerRegistration = db.collection("sms_logs")
                .whereEqualTo("childId", childId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        android.util.Log.e("CallsSmsMonitoring", "sms_logs error", error)
                    }
                    smsLogs = snap?.documents?.mapNotNull { it.data } ?: emptyList()
                    isLoadingSms = false
                }
            onDispose { reg.remove() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calls & SMS Monitoring", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(24.dp))
                    .padding(4.dp)
            ) {
                listOf("Calls", "SMS", "Settings").forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selectedTab == index) Color.White else Color.Transparent,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { selectedTab = index }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) Color(0xFF4C66EA) else Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> CallLogsTab(callLogs, isLoadingCalls)
                1 -> SmsLogsTab(smsLogs, isLoadingSms)
                2 -> CallSmsSettingsTab(
                    childId = childId,
                    callBlacklist = callBlacklist,
                    smsKeywords = smsKeywords,
                    onAddCallClick = { addText = ""; showAddCallDialog = true },
                    onRemoveCall = { number ->
                        scope.launch {
                            val updated = callBlacklist.filter { it != number }
                            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                .update("callBlacklist", updated).await()
                        }
                    },
                    onAddKeywordClick = { addText = ""; showAddKeywordDialog = true },
                    onRemoveKeyword = { kw ->
                        scope.launch {
                            val updated = smsKeywords.filter { it != kw }
                            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                .update("smsKeywords", updated).await()
                        }
                    }
                )
            }
        }
    }

    if (showAddCallDialog) {
        AlertDialog(
            onDismissRequest = { showAddCallDialog = false },
            title = { Text("Add to Call Blacklist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = addText, onValueChange = { addText = it },
                    label = { Text("Phone number") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (addText.isNotBlank()) {
                        scope.launch {
                            val updated = callBlacklist + addText.trim()
                            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                .set(mapOf("callBlacklist" to updated), com.google.firebase.firestore.SetOptions.merge()).await()
                        }
                        showAddCallDialog = false
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA))) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddCallDialog = false }) { Text("Cancel") } },
            containerColor = Color.White, shape = RoundedCornerShape(16.dp)
        )
    }

    if (showAddKeywordDialog) {
        AlertDialog(
            onDismissRequest = { showAddKeywordDialog = false },
            title = { Text("Add SMS Keyword", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = addText, onValueChange = { addText = it },
                    label = { Text("Keyword") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (addText.isNotBlank()) {
                        scope.launch {
                            val updated = smsKeywords + addText.trim()
                            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                .set(mapOf("smsKeywords" to updated), com.google.firebase.firestore.SetOptions.merge()).await()
                        }
                        showAddKeywordDialog = false
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA))) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddKeywordDialog = false }) { Text("Cancel") } },
            containerColor = Color.White, shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun CallLogsTab(callLogs: List<Map<String, Any>>, isLoading: Boolean) {
    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (callLogs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("📞", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("No call logs yet", color = Color.Gray, textAlign = TextAlign.Center)
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        items(callLogs) { log ->
            CallLogItem(log)
        }
    }
}

@Composable
private fun CallLogItem(log: Map<String, Any>) {
    val number = log["number"] as? String ?: log["phoneNumber"] as? String ?: "Unknown"
    val name = log["contactName"] as? String ?: log["name"] as? String
    val type = log["callType"] as? String ?: log["type"] as? String ?: "INCOMING"
    val duration = (log["duration"] as? Number)?.toLong() ?: 0L
    val ts = (log["timestamp"] as? Number)?.toLong() ?: 0L
    val isBlocked = log["isBlocked"] as? Boolean ?: false

    val typeIcon = when (type.uppercase()) {
        "OUTGOING" -> Icons.Default.CallMade
        "MISSED" -> Icons.Default.CallMissed
        else -> Icons.Default.CallReceived
    }
    val typeColor = when (type.uppercase()) {
        "OUTGOING" -> Color(0xFF4C66EA)
        "MISSED" -> Color.Red
        else -> Color(0xFF4CAF50)
    }
    val durationText = if (duration > 0) {
        val mins = duration / 60
        val secs = duration % 60
        if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    } else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = typeColor.copy(alpha = 0.1f), shape = CircleShape, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name ?: number, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (name != null) Text(number, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                    if (durationText.isNotEmpty()) {
                        Text(" · $durationText", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }
            }
            if (isBlocked) {
                Surface(color = Color(0xFFFFEBEE), shape = RoundedCornerShape(8.dp)) {
                    Text("Blocked", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SmsLogsTab(smsLogs: List<Map<String, Any>>, isLoading: Boolean) {
    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (smsLogs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("💬", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("No SMS logs yet", color = Color.Gray, textAlign = TextAlign.Center)
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        items(smsLogs) { log ->
            SmsLogItem(log)
        }
    }
}

@Composable
private fun SmsLogItem(log: Map<String, Any>) {
    val number = log["number"] as? String ?: log["phoneNumber"] as? String ?: "Unknown"
    val name = log["contactName"] as? String ?: log["name"] as? String
    val body = log["body"] as? String ?: log["message"] as? String ?: ""
    val type = log["smsType"] as? String ?: log["type"] as? String ?: "INBOX"
    val ts = (log["timestamp"] as? Number)?.toLong() ?: 0L
    val flagged = log["flagged"] as? Boolean ?: false

    val isOutgoing = type.uppercase() == "SENT" || type.uppercase() == "OUTBOX"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Surface(
                color = if (isOutgoing) Color(0xFFE8EAF6) else Color(0xFFF3E5F5),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isOutgoing) Icons.Default.Send else Icons.Default.Message,
                        null,
                        tint = if (isOutgoing) Color(0xFF4C66EA) else Color(0xFF9C27B0),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name ?: number, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (flagged) {
                        Surface(color = Color(0xFFFFF3E0), shape = RoundedCornerShape(8.dp)) {
                            Text("⚠️ Flagged", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                if (name != null) Text(number, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
private fun CallSmsSettingsTab(
    childId: String,
    callBlacklist: List<String>,
    smsKeywords: List<String>,
    onAddCallClick: () -> Unit,
    onRemoveCall: (String) -> Unit,
    onAddKeywordClick: () -> Unit,
    onRemoveKeyword: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        // Call Blacklist
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Call Blacklist", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Add", color = Color(0xFF4C66EA), fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onAddCallClick() })
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                if (callBlacklist.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No blocked numbers", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Column {
                        callBlacklist.forEachIndexed { index, number ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Phone, null, tint = Color(0xFF4C66EA), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(number, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Icon(Icons.Default.RemoveCircle, null, tint = Color.Red,
                                    modifier = Modifier.size(22.dp).clickable { onRemoveCall(number) })
                            }
                            if (index < callBlacklist.size - 1)
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }

        // SMS Keywords
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SMS Keyword Detection", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Add", color = Color(0xFF4C66EA), fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onAddKeywordClick() })
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                if (smsKeywords.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No keywords added", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Column {
                        smsKeywords.forEachIndexed { index, kw ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.TextFields, null, tint = Color(0xFF4C66EA), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(kw, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Icon(Icons.Default.RemoveCircle, null, tint = Color.Red,
                                    modifier = Modifier.size(22.dp).clickable { onRemoveKeyword(kw) })
                            }
                            if (index < smsKeywords.size - 1)
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
