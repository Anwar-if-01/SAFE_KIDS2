package com.example.safe_kids.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!isVerifying) onDismiss()
        },
        title = {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Column {
                Text(text = description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = code,
                    onValueChange = { 
                        if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                            code = it
                            errorMessage = null // Clear error when typing
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
                        color = MaterialTheme.colorScheme.error,
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
                    Text("Verify")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying
            ) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}
