package com.example.safe_kids.services

import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class StudyModeBlockActivity : ComponentActivity() {
    private var remoteListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setFinishOnTouchOutside(false)

        val blockedPackage = intent.getStringExtra("blocked_package") ?: "Unknown"
        val allowed = intent.getStringExtra("allowed_apps") ?: ""
        val allowedList = if (allowed.isBlank()) listOf<String>() else allowed.split(",").filter { it.isNotBlank() }

        setContent {
            val shouldDismiss = remember { mutableStateOf(false) }

            // Listen for studyMode.isActive changes to auto-dismiss
            LaunchedEffect(Unit) {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    val firestore = FirebaseFirestore.getInstance()
                    val childId = currentUser.uid
                    remoteListener = firestore.collection("children").document(childId)
                        .addSnapshotListener { snap, err ->
                            if (err != null) return@addSnapshotListener
                            if (snap == null) return@addSnapshotListener
                            val study = snap.get("studyMode") as? Map<String, Any>
                            val isActive = study?.get("isActive") as? Boolean ?: false
                            if (!isActive) {
                                shouldDismiss.value = true
                            }
                        }
                }
            }

            DisposableEffect(shouldDismiss.value) {
                if (shouldDismiss.value) finish()
                onDispose { }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(text = "📚 Study Mode is Active", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Blocked app: ${getAppName(blockedPackage)}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Only educational apps are allowed right now.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Allowed apps:", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    for (a in allowedList) {
                        Text(text = "• $a", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Contact parent to change Study Mode settings", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        remoteListener?.remove()
    }

    override fun onUserLeaveHint() {
        // Prevent Home key by bringing activity back to front
        val intent = android.content.Intent(this, StudyModeBlockActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    override fun onBackPressed() {
        // Do nothing to suppress back button
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
