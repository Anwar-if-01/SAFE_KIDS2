package com.example.safe_kids.ui.child

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.MainActivity
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.data.ScreenTimeRequest
import com.example.safe_kids.ui.components.AdminCodeVerificationDialog
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar

class TotalLockoutActivity : ComponentActivity() {

    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent dismissal from recent apps / lock task mode if possible
        
        val authManager = AuthManagerImpl(this)
        
        setContent {
            SAFE_KIDSTheme {
                val scope = rememberCoroutineScope()
                val currentUser = authManager.getCurrentUser()
                
                var showPinDialog by remember { mutableStateOf(false) }
                var clockClicks by remember { mutableIntStateOf(0) }
                var isRequestingTime by remember { mutableStateOf(false) }
                var requestSent by remember { mutableStateOf(false) }
                var timeRemaining by remember { mutableStateOf(calculateTimeUntilMidnight()) }

                // Countdown Timer & Auto-Dismiss
                LaunchedEffect(Unit) {
                    while (true) {
                        timeRemaining = calculateTimeUntilMidnight()
                        if (!com.example.safe_kids.services.ForegroundProtectionService.isGlobalLockoutActive) {
                            finish()
                        }
                        delay(1000)
                    }
                }
                
                // Prevent Back Navigation
                BackHandler(enabled = true) {
                    // Do nothing
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassBottom,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            text = "Daily Screen Time Limit Reached",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Hidden Trigger: Tap 3 times on the clock
                        val interactionSource = remember { MutableInteractionSource() }
                        Text(
                            text = "Resets in: $timeRemaining",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            modifier = Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                clockClicks++
                                if (clockClicks >= 3) {
                                    showPinDialog = true
                                    clockClicks = 0
                                }
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(48.dp))

                        if (requestSent) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Request sent! Waiting for parent to approve...",
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (currentUser != null) {
                                        scope.launch {
                                            isRequestingTime = true
                                            try {
                                                // We need the parent ID. Assume child role and get parentIds
                                                val userDoc = firestore.collection("users").document(currentUser.id).get().await()
                                                val parentIds = userDoc.get("parentIds") as? List<*> ?: emptyList<Any>()
                                                
                                                if (parentIds.isNotEmpty()) {
                                                    val parentId = parentIds[0].toString()
                                                    val request = ScreenTimeRequest(childId = currentUser.id)
                                                    
                                                    firestore.collection("screen_time_requests")
                                                        .document(parentId)
                                                        .collection("requests")
                                                        .document(currentUser.id) // 1 pending request at a time
                                                        .set(request)
                                                        .await()
                                                        
                                                    requestSent = true
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(this@TotalLockoutActivity, "Failed to send request", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                isRequestingTime = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer),
                                enabled = !isRequestingTime
                            ) {
                                if (isRequestingTime) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(24.dp))
                                } else {
                                    Text("Ask for More Time", color = MaterialTheme.colorScheme.errorContainer, fontSize = 18.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Emergency Dial
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL)
                                startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Phone Calls Allowed")
                        }
                    }
                }
                
                if (showPinDialog) {
                    AdminCodeVerificationDialog(
                        authManager = authManager,
                        title = "Parental PIN Unlock",
                        description = "Enter your Parental PIN to instantly unlock the device.",
                        onDismiss = { showPinDialog = false },
                        onSuccess = {
                            showPinDialog = false
                            Toast.makeText(this@TotalLockoutActivity, "Device Unlocked by Parent", Toast.LENGTH_SHORT).show()
                            // Finish and return to launcher/Safe Kids
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun calculateTimeUntilMidnight(): String {
        val now = Calendar.getInstance()
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diff = midnight.timeInMillis - now.timeInMillis
        val hours = (diff / (1000 * 60 * 60)).toInt()
        val minutes = ((diff / (1000 * 60)) % 60).toInt()
        val seconds = ((diff / 1000) % 60).toInt()
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onBackPressed() {
        // Prevent back button
    }
}
