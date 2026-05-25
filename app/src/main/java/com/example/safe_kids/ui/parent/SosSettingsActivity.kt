package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SosSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"
        setContent {
            SAFE_KIDSTheme { SosSettingsScreen(childId = childId, childName = childName, onBack = { finish() }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosSettingsScreen(childId: String, childName: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    var sosEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(childId) {
        if (childId.isEmpty()) return@LaunchedEffect
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
            .addSnapshotListener { snap, _ ->
                val s = snap?.get("settings") as? Map<*, *>
                sosEnabled = s?.get("sosEnabled") as? Boolean ?: false
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SOS Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            // Illustration
            Text("🆘📍", fontSize = 64.sp)
            Spacer(Modifier.height(24.dp))
            Text("SOS Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "With the SOS feature, your child can send you an emergency alert with their location and surrounding sound.",
                textAlign = TextAlign.Center,
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(24.dp))

            // Enable toggle card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ToggleOn, null, tint = Color.Gray, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Enable", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    }
                    Switch(
                        checked = sosEnabled,
                        onCheckedChange = { checked ->
                            sosEnabled = checked
                            scope.launch {
                                db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                                    .set(mapOf("settings" to mapOf("sosEnabled" to checked)),
                                        com.google.firebase.firestore.SetOptions.merge()).await()
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4C66EA))
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FB)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", color = Color.Gray, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                        Text(
                            "When your child sends an SOS, your phone will sound a loud alarm and vibrate for 15 seconds—even if it's on silent.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray, lineHeight = 18.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", color = Color.Gray, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                        Text(
                            "Location may not be exact due to network issues or other factors. For peace of mind, please contact your child to confirm.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray, lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
