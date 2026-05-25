package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class InappropriateImageDetectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        setContent {
            SAFE_KIDSTheme {
                InappropriateImageDetectionScreen(childId = childId, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InappropriateImageDetectionScreen(childId: String, onBack: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var isEnabled by remember { mutableStateOf(false) }
    var blurredThumbnails by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(childId) {
        if (childId.isEmpty()) { isLoading = false; return@LaunchedEffect }
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
            .addSnapshotListener { snap, _ ->
                val s = snap?.get("settings") as? Map<*, *>
                val img = s?.get("imageDetection") as? Map<*, *>
                isEnabled = img?.get("enabled") as? Boolean ?: false
                blurredThumbnails = img?.get("blurredThumbnails") as? Boolean ?: true
                isLoading = false
            }
    }

    fun save(enabled: Boolean = isEnabled, blurred: Boolean = blurredThumbnails) {
        scope.launch {
            db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
                .set(mapOf("settings" to mapOf("imageDetection" to mapOf(
                    "enabled" to enabled, "blurredThumbnails" to blurred
                ))), com.google.firebase.firestore.SetOptions.merge()).await()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inappropriate Image Detection", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF4F6F9)
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        if (!isEnabled) {
            // Onboarding state
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                Text("🖼️", fontSize = 80.sp)
                Spacer(Modifier.height(24.dp))
                Text(
                    "Automatically scans new photos in Photos to identify inappropriate content, such as nudity or adult material.",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
                Spacer(Modifier.height(32.dp))
                FeatureRow(Color(0xFFFF9800), Icons.Default.Notifications, "Inappropriate image detection and alerts")
                Spacer(Modifier.height(16.dp))
                FeatureRow(Color(0xFF4C66EA), Icons.Default.Image, "Offline image detection powered by ML Kit")
                Spacer(Modifier.height(16.dp))
                FeatureRow(Color(0xFF43A047), Icons.Default.Shield, "Secure on-device image detection")
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { isEnabled = true; save(enabled = true) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C66EA)),
                    shape = RoundedCornerShape(27.dp)
                ) { Text("Enable", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        } else {
            // Settings state
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ToggleOn, null, tint = Color.Gray, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Enable", fontWeight = FontWeight.Medium)
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { isEnabled = it; save(enabled = it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4C66EA))
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BlurOn, null, tint = Color.Gray, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Blurred Thumbnails", fontWeight = FontWeight.Medium)
                            }
                            Switch(
                                checked = blurredThumbnails,
                                onCheckedChange = { blurredThumbnails = it; save(blurred = it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4C66EA))
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showClearDialog = true }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Delete, null, tint = Color.Gray, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Clear Detection History", color = Color.Gray, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(48.dp))
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🖼️", fontSize = 48.sp, color = Color.LightGray)
                    Spacer(Modifier.height(12.dp))
                    Text("No inappropriate images detected", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to clear all detection history?") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            val docs = db.collection(AuthConfig.CHILD_PROFILES_COLLECTION)
                                .document(childId).collection("imageDetectionHistory").get().await()
                            val batch = db.batch()
                            docs.documents.forEach { batch.delete(it.reference) }
                            batch.commit().await()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } },
            containerColor = Color.White, shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun FeatureRow(color: Color, icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(40.dp).background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
