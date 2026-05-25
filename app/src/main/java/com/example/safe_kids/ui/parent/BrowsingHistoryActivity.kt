package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.FirestoreQueryUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.*

class BrowsingHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        setContent {
            SAFE_KIDSTheme {
                BrowsingHistoryScreen(childId = childId, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowsingHistoryScreen(childId: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    var selectedTab by remember { mutableIntStateOf(0) }
    var allHistory by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(childId) {
        if (childId.isEmpty()) {
            isLoading = false
            onDispose { }
        } else {
            var registration: ListenerRegistration? = null
            var registration2: ListenerRegistration? = null
            // Try both collection names: "browsing_history" (child device writes) and
            // "web_history" (AuthConfig.WEB_HISTORY_COLLECTION) for backwards compatibility
            registration = db.collection("browsing_history")
                .whereEqualTo("childId", childId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        com.example.safe_kids.utils.FirestoreQueryUtils.logQueryError("BrowsingHistory_primary", error)
                        // Don't set loadError here — fallback collection may work
                        return@addSnapshotListener
                    }
                    loadError = null
                    val primary = snap?.documents?.mapNotNull { it.data } ?: emptyList()
                    // Merge with any results from the secondary collection
                    val secondary = allHistory.filter { it["_source"] == "web_history" }
                    allHistory = (primary.map { it + mapOf("_source" to "browsing_history") } + secondary)
                        .sortedByDescending { (it["timestamp"] as? Number)?.toLong() ?: 0L }
                    isLoading = false
                }
            registration2 = db.collection(AuthConfig.WEB_HISTORY_COLLECTION)
                .whereEqualTo("childId", childId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        com.example.safe_kids.utils.FirestoreQueryUtils.logQueryError("BrowsingHistory_fallback", error)
                        loadError = com.example.safe_kids.utils.FirestoreQueryUtils.userMessage(error)
                        isLoading = false
                        return@addSnapshotListener
                    }
                    loadError = null
                    val secondary = snap?.documents?.mapNotNull { it.data }?.map { it + mapOf("_source" to "web_history") } ?: emptyList()
                    val primary = allHistory.filter { it["_source"] == "browsing_history" }
                    allHistory = (primary + secondary)
                        .sortedByDescending { (it["timestamp"] as? Number)?.toLong() ?: 0L }
                    isLoading = false
                }
            onDispose {
                registration?.remove()
                registration2?.remove()
            }
        }
    }

    val displayedHistory = remember(allHistory, selectedTab) {
        if (selectedTab == 0) allHistory
        else allHistory.filter { it["isBlocked"] as? Boolean == true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browsing History", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab selector
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(24.dp)).padding(4.dp)
            ) {
                listOf("All", "Blocked").forEachIndexed { index, label ->
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

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (loadError != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
                        Text(loadError!!, color = Color.Gray, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (displayedHistory.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
                        Text("📋", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No browsing history", color = Color.Gray, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(displayedHistory) { entry ->
                        BrowsingHistoryItem(entry)
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BrowsingHistoryItem(entry: Map<String, Any>) {
    val url = entry["url"] as? String ?: entry["title"] as? String ?: "Unknown"
    val title = entry["title"] as? String ?: url
    val ts = entry["timestamp"] as? Long ?: 0L
    val isBlocked = entry["isBlocked"] as? Boolean ?: false

    val formattedTime = remember(ts) {
        val now = Calendar.getInstance()
        val entryDate = Calendar.getInstance().apply { timeInMillis = ts }
        if (now.get(Calendar.DATE) == entryDate.get(Calendar.DATE) &&
            now.get(Calendar.MONTH) == entryDate.get(Calendar.MONTH)) {
            "Today " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
        } else {
            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color(0xFFE8EAF6), shape = RoundedCornerShape(8.dp), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Language, null, tint = Color(0xFF4C66EA), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(formattedTime, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (isBlocked) {
                Spacer(Modifier.width(8.dp))
                Surface(color = Color(0xFFFFEBEE), shape = RoundedCornerShape(8.dp)) {
                    Text("Blocked", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}
