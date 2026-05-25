package com.example.safe_kids.services

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
// import com.example.safe_kids.R

class AppBlockedActivity : ComponentActivity() {
    private var remoteUnlockListener: com.google.firebase.firestore.ListenerRegistration? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make activity full-screen and non-dismissible
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setFinishOnTouchOutside(false)
        
        val blockedPackage = intent.getStringExtra("blocked_package") ?: "Unknown"
        val appName = getAppName(blockedPackage)
        val reason = intent.getStringExtra("reason") ?: "This app has been blocked by your parent or guardian."
        
        val dailyUsed = intent.getLongExtra("dailyUsageMs", -1L)
        val weeklyUsed = intent.getLongExtra("weeklyUsageMs", -1L)

        setContent {
            val shouldDismiss = remember { mutableStateOf(false) }
            
            // Remote parental unlock listener
            LaunchedEffect(blockedPackage) {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    val firestore = FirebaseFirestore.getInstance()
                    remoteUnlockListener = firestore
                        .collection("users")
                        .document(currentUser.uid)
                        .collection("app_limits")
                        .document(blockedPackage)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null || snapshot == null || !snapshot.exists()) {
                                // If limit is removed, dismiss
                                shouldDismiss.value = true
                                return@addSnapshotListener
                            }
                            
                            val instantBlock = snapshot.getBoolean("instantBlock") ?: true
                            val isEnabled = snapshot.getBoolean("enabled") ?: true
                            
                            // If instantBlock is false or limit is disabled, dismiss
                            if (!instantBlock || !isEnabled) {
                                shouldDismiss.value = true
                            }
                        }
                }
            }
            
            // Dismiss activity when remote unlock is triggered
            DisposableEffect(shouldDismiss.value) {
                if (shouldDismiss.value) {
                    finish()
                }
                onDispose {}
            }

            AppBlockedScreen(
                appName = appName,
                reason = reason,
                dailyUsed = dailyUsed,
                weeklyUsed = weeklyUsed
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        remoteUnlockListener?.remove()
    }
    
    // Suppress Home key by preventing the activity from being stopped
    override fun onUserLeaveHint() {
        // Called when user presses Home key
        // Bring the activity back to front
        val intent = android.content.Intent(this, AppBlockedActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("blocked_package", intent.getStringExtra("blocked_package"))
        intent.putExtra("reason", intent.getStringExtra("reason"))
        intent.putExtra("dailyUsageMs", intent.getLongExtra("dailyUsageMs", -1L))
        intent.putExtra("weeklyUsageMs", intent.getLongExtra("weeklyUsageMs", -1L))
        startActivity(intent)
    }
    
    // Also override onBackPressed to prevent back button
    override fun onBackPressed() {
        // Do nothing - prevent back button
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}

@Composable
fun AppBlockedScreen(
    appName: String,
    reason: String,
    dailyUsed: Long,
    weeklyUsed: Long
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🚫",
                style = MaterialTheme.typography.displayLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "App Blocked",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "$appName has been blocked",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (dailyUsed >= 0 && weeklyUsed >= 0) {
                    "Used today: ${formatMillis(dailyUsed)} • Used this week: ${formatMillis(weeklyUsed)}"
                } else if (dailyUsed >= 0L) {
                    "Used today: ${formatMillis(dailyUsed)}"
                } else "",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Contact parent to unlock",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
            val context = androidx.compose.ui.platform.LocalContext.current
            TextButton(
                onClick = {
                    val intent = android.content.Intent(context, com.example.safe_kids.ui.admin.AdminCodeActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enter Parental PIN to Unlock")
            }
        }
    }
}

private fun formatMillis(ms: Long): String {
    val minutes = (ms / 1000) / 60
    val hours = minutes / 60
    val remMinutes = minutes % 60
    return if (hours > 0) "${hours}h ${remMinutes}m" else "${remMinutes}m"
}
