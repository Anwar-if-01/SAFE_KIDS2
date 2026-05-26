package com.example.safe_kids.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.safe_kids.auth.AuthManager
import com.example.safe_kids.ui.parent.AdminCodeState
import com.example.safe_kids.ui.parent.AdminCodeViewModel
import com.example.safe_kids.ui.parent.AdminCodeViewModelFactory
import kotlinx.coroutines.launch

@Composable
fun AdminCodeVerificationDialog(
    authManager: AuthManager,
    title: String = "Parental PIN Required",
    description: String = "Please enter your 4-digit parental PIN to proceed.",
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val viewModel: AdminCodeViewModel = viewModel(factory = AdminCodeViewModelFactory(authManager))
    val hasPin by viewModel.hasAdminCode.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!isVerifying) onDismiss()
        },
        title = {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF1A1A1A))
        },
        text = {
            Column {
                if (!hasPin) {
                    // FIX 5: Show message when PIN is not set
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, null, tint = Color(0xFFFF9800), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Parental PIN has not been set. Please set it in parent mode first.",
                                color = Color(0xFF1A1A1A),
                                fontSize = 13.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                Text(text = description, color = Color(0xFF666666), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = code,
                    onValueChange = { 
                        if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                            code = it
                            errorMessage = null
                        }
                    },
                    label = { Text("4-Digit Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )
                
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFD32F2F),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedCode = code.trim()
                    if (trimmedCode.length == 4) {
                        isVerifying = true
                        errorMessage = null
                        scope.launch {
                            val isVerified = authManager.verifyAdminCode(trimmedCode)
                            android.util.Log.d("AdminCodeDialog", "verifyAdminCode('$trimmedCode') returned: $isVerified")
                            isVerifying = false
                            if (isVerified) {
                                onSuccess()
                            } else {
                                errorMessage = "Incorrect PIN"
                            }
                        }
                    } else {
                        errorMessage = "Please enter 4 digits"
                    }
                },
                enabled = !isVerifying && code.length == 4
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Verify", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying
            ) {
                Text("Cancel", color = Color(0xFF666666))
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}
