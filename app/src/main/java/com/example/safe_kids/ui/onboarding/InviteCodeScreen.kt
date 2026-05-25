package com.example.safe_kids.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteCodeScreen(
    authManager: AuthManager,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var verifyJob by remember { mutableStateOf<Job?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join Family") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Cancel any ongoing verification before going back
                            verifyJob?.cancel()
                            onBack()
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Enter Invitation Code",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Ask the primary parent for the 6-character invitation code to link your account.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = code,
                onValueChange = { 
                    if (it.length <= 6 && it.all { char -> char.isLetterOrDigit() }) {
                        code = it.uppercase()
                        // Clear error as soon as user starts typing a new code
                        if (error != null) error = null
                    }
                },
                label = { Text("6-Character Code") },
                placeholder = { Text("A1B2C3") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
                enabled = !isLoading,
                supportingText = {
                    if (error != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Primary action button
            Button(
                onClick = {
                    if (code.length == 6) {
                        isLoading = true
                        error = null
                        verifyJob = scope.launch {
                            try {
                                // 30-second timeout prevents infinite loading
                                withTimeout(30_000L) {
                                    val result = authManager.useCoParentInviteCode(code)
                                    if (result.isSuccess) {
                                        onComplete()
                                    } else {
                                        val msg = result.exceptionOrNull()?.message
                                            ?: "Failed to verify code"
                                        error = msg
                                        // Clear the code so the user can immediately
                                        // type a fresh one without manually deleting
                                        code = ""
                                    }
                                }
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                error = "Request timed out. Please check your connection and try again."
                                code = ""
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // Job was cancelled (e.g. user pressed Cancel), do nothing
                            } catch (e: Exception) {
                                error = e.message ?: "An unexpected error occurred"
                                code = ""
                            } finally {
                                isLoading = false
                            }
                        }
                    } else {
                        error = "Please enter a complete 6-character code"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = code.length == 6 && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Join Family", fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Cancel button shown while loading — prevents the user from being stuck
            if (isLoading) {
                OutlinedButton(
                    onClick = {
                        verifyJob?.cancel()
                        isLoading = false
                        error = "Cancelled. You can enter the code again."
                        code = ""
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
            }
            
            // "Change Role" link — only shown when not loading, so the user can
            // go back to role selection without losing their place on an error
            if (!isLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        verifyJob?.cancel()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Not a co-parent? Change role",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
