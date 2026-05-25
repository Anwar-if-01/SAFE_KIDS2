package com.example.safe_kids.services

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import kotlinx.coroutines.launch

class UninstallProtectionActivity : ComponentActivity() {
    private lateinit var authManager: AuthManagerImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = AuthManagerImpl(this)

        setContent {
            SAFE_KIDSTheme {
                UninstallProtectionScreen(
                    authManager = authManager,
                    onSuccess = {
                        // Correct code entered - disable Device Admin to allow uninstall
                        DeviceAdminService.disableDeviceAdmin(this)
                        android.widget.Toast.makeText(this, "Protection disabled. You can now proceed.", android.widget.Toast.LENGTH_LONG).show()
                        finish()
                    },
                    onCancel = {
                        // Cancelled or failed - go to home to block uninstall
                        val homeIntent = Intent(Intent.ACTION_MAIN)
                        homeIntent.addCategory(Intent.CATEGORY_HOME)
                        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(homeIntent)
                        finish()
                    }
                )
            }
        }
    }
    
    override fun onBackPressed() {
        // Prevent backing out - user must enter code or app will go to home
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
        super.onBackPressed()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallProtectionScreen(
    authManager: AuthManagerImpl,
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentUser by authManager.currentUser.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(48.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Uninstall Protection",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "This app is protected by Safe Kids. Please enter the Parental PIN provided by your parent to proceed.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                OutlinedTextField(
                    value = code,
                    onValueChange = { 
                        if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                            code = it
                            error = null
                        }
                    },
                    label = { Text("Parental PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null
                )
                
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    
                    Button(
                        onClick = {
                            if (code.length == 4) {
                                // Default fallback code is "0000" if parent hasn't set one yet
                                val requiredCode = currentUser?.adminCode ?: "0000"
                                if (code == requiredCode) {
                                    onSuccess()
                                } else {
                                    error = "Incorrect PIN"
                                }
                            } else {
                                error = "Enter 4 digits"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
                    ) {
                        Text("Verify")
                    }
                }
            }
        }
    }
}
