package com.example.safe_kids.ui.parent

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.FirestoreQueryUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class SocialContentDetectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        setContent {
            SAFE_KIDSTheme {
                SocialContentDetectionScreen(childId = childId, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialContentDetectionScreen(childId: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var screenshotOnDetection by remember { mutableStateOf(false) }
    var detectionLogs by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(childId) {
        if (childId.isEmpty()) {
            onDispose { }
        } else {
            var profileReg: ListenerRegistration? = null
            var logsReg: ListenerRegistration? = null
            profileReg = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        FirestoreQueryUtils.logQueryError("SocialContentSettings", error)
                        return@addSnapshotListener
                    }
                    val settings = snap?.get("settings") as? Map<*, *>
                    val social = settings?.get("socialContentDetection") as? Map<*, *>
                    screenshotOnDetection = social?.get("screenshotOnDetection") as? Boolean ?: false
                }
            logsReg = db.collection(AuthConfig.CONTENT_SAFETY_LOGS_COLLECTION)
                .whereEqualTo("childId", childId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        FirestoreQueryUtils.logQueryError("SocialContentLogs", error)
                        loadError = FirestoreQueryUtils.userMessage(error)
                        return@addSnapshotListener
                    }
                    loadError = null
                    detectionLogs = snap?.documents?.mapNotNull { it.data } ?: emptyList()
                }
            onDispose {
                profileReg?.remove()
                logsReg?.remove()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Social Content Detection", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab selector
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp).background(Color(0xFFEEEEEE), RoundedCornerShape(24.dp)).padding(4.dp)
            ) {
                listOf("Keyword Detection", "Activity").forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier.weight(1f)
                            .background(if (selectedTab == index) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
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

            if (selectedTab == 0) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(
                            "Manage the apps and keywords to be monitored for sensitive content. You will receive alerts if any is detected.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                                    }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ManageSearch, null, tint = Color.DarkGray, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Text("App Detection Management", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.4f))
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                                    }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.TextFields, null, tint = Color.DarkGray, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Text("Keyword Management", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.4f))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Screenshot, null, tint = Color.DarkGray, modifier = Modifier.size(22.dp))

                                        Spacer(Modifier.width(14.dp))
                                        Text("Screenshot on Detection", fontWeight = FontWeight.Medium)
                                    }
                                    Switch(
                                        checked = screenshotOnDetection,
                                        onCheckedChange = { checked ->
                                            screenshotOnDetection = checked
                                            scope.launch {
                                                db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                                    .set(mapOf("settings" to mapOf("socialContentDetection" to mapOf("screenshotOnDetection" to checked))),
                                                        com.google.firebase.firestore.SetOptions.merge()).await()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4C66EA))
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text("Recent Detection Records", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (detectionLogs.isEmpty()) {
                        item { EmptyHistoryState("No detection history yet") }
                    } else {
                        items(detectionLogs) { log -> DetectionLogCard(log) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            } else {
                // Activity tab
                if (detectionLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyHistoryState("No detection history yet")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(detectionLogs) { log -> DetectionLogCard(log) }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionLogCard(log: Map<String, Any>) {
    val ts = log["timestamp"] as? Long ?: 0L
    val formatted = remember(ts) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(log["category"] as? String ?: "Detection", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(formatted, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Spacer(Modifier.height(4.dp))
            Text(log["detail"] as? String ?: "", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
        }
    }
}

@Composable
private fun EmptyHistoryState(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(48.dp)) {
        Text("📋", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color.Gray, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}
