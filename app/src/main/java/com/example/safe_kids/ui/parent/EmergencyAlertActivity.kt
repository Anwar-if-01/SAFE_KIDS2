package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class EmergencyAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val alertId = intent.getStringExtra("alert_id") ?: ""
        val childName = intent.getStringExtra("child_name") ?: "Child"
        val latitude = intent.getDoubleExtra("latitude", 0.0)
        val longitude = intent.getDoubleExtra("longitude", 0.0)

        setContent {
            SAFE_KIDSTheme {
                EmergencyAlertScreen(
                    alertId = alertId,
                    childName = childName,
                    latitude = latitude,
                    longitude = longitude,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
fun EmergencyAlertScreen(
    alertId: String,
    childName: String,
    latitude: Double,
    longitude: Double,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isResolving by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Red.copy(alpha = 0.9f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "SOS",
            tint = Color.White,
            modifier = Modifier.size(100.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "SOS EMERGENCY",
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
            fontWeight = FontWeight.Black
        )
        
        Text(
            text = childName,
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Current Location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Lat: $latitude\nLng: $longitude",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = {
                        // TODO: Open in Google Maps
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View on Map")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                isResolving = true
                scope.launch {
                    try {
                        FirebaseFirestore.getInstance()
                            .collection("sos_alerts")
                            .document(alertId)
                            .update("isResolved", true)
                        onDismiss()
                    } catch (e: Exception) {
                        isResolving = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Red),
            enabled = !isResolving
        ) {
            if (isResolving) {
                CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(24.dp))
            } else {
                Text("MARK AS RESOLVED", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}
