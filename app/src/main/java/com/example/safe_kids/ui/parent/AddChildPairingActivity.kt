package com.example.safe_kids.ui.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import kotlinx.coroutines.launch

class AddChildPairingActivity : ComponentActivity() {
    private lateinit var authManager: AuthManagerImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = AuthManagerImpl(this)
        lifecycleScope.launch { authManager.initialize() }

        setContent {
            SAFE_KIDSTheme {
                AddChildPairingScreen(authManager = authManager, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChildPairingScreen(authManager: AuthManagerImpl, onBack: () -> Unit) {
    var pairingCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var enteredCode by remember { mutableStateOf("") }
    var isLinking by remember { mutableStateOf(false) }
    var successMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Child Device", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Icon
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("👶", fontSize = 48.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Link a Child Device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "On the child's device, open Safe Kids, choose Child Mode, and tap \"Pair Device\" to get a 9-digit code. Enter it below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            // Code entry
            OutlinedTextField(
                value = enteredCode,
                onValueChange = {
                    if (it.length <= 9 && it.all { c -> c.isDigit() }) {
                        enteredCode = it
                        errorMsg = null
                    }
                },
                label = { Text("9-Digit Pairing Code") },
                placeholder = { Text("e.g. 123 456 789") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = errorMsg != null,
                supportingText = errorMsg?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (enteredCode.length != 9) {
                        errorMsg = "Code must be exactly 9 digits"
                        return@Button
                    }
                    isLinking = true
                    errorMsg = null
                    scope.launch {
                        try {
                            val currentUser = authManager.getCurrentUser()
                            val result = kotlinx.coroutines.withTimeout(60_000) {
                                authManager.usePairingCode(enteredCode, currentUser?.id ?: "")
                            }
                            if (result.isSuccess) {
                                successMsg = "Child device linked successfully!"
                                enteredCode = ""
                            } else {
                                errorMsg = result.exceptionOrNull()?.message ?: "Invalid or expired code"
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            errorMsg = "Request timed out. Check your connection and try again."
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "An unexpected error occurred"
                        } finally {
                            isLinking = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = enteredCode.length == 9 && !isLinking,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isLinking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Link Device", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            if (successMsg != null) {
                Spacer(Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✅", fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            successMsg!!,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBack) {
                    Text("Done")
                }
            }

            Spacer(Modifier.height(32.dp))

            // Instructions card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("How to pair", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "1. Install Safe Kids on the child's device",
                        "2. Open the app and choose \"Child Mode\"",
                        "3. Tap \"Pair Device\" to generate a code",
                        "4. Enter the 9-digit code above"
                    ).forEach { step ->
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
