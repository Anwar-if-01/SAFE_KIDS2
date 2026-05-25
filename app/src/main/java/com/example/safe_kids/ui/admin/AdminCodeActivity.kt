package com.example.safe_kids.ui.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.services.blocking.AppBlockingServiceImpl
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AdminCodeActivity : ComponentActivity() {
    private lateinit var authManager: AuthManagerImpl
    private lateinit var appBlockingService: AppBlockingServiceImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = AuthManagerImpl(this)
        appBlockingService = AppBlockingServiceImpl(this)

        setContent {
            SAFE_KIDSTheme {
                AdminCodeScreen(
                    authManager = authManager,
                    appBlockingService = appBlockingService,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCodeScreen(
    authManager: AuthManagerImpl,
    appBlockingService: AppBlockingServiceImpl,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Box(modifier = Modifier.size(8.dp).align(Alignment.TopEnd).offset((-10).dp, 10.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFD54F)))
                Box(modifier = Modifier.size(6.dp).align(Alignment.BottomStart).offset(15.dp, (-15).dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF81C784)))
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Enter Parental PIN to extend the time",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(48.dp))

            BasicTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                        pin = it
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                ),
                decorationBox = {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(4) { index ->
                            val char = when {
                                index >= pin.length -> ""
                                else -> pin[index].toString()
                            }
                            val isFocused = index == pin.length
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        width = 2.dp,
                                        color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = char,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            )

            // Verify PIN when 4 digits entered — checks Firestore first, falls back to local
            LaunchedEffect(pin) {
                if (pin.length == 4) {
                    scope.launch {
                        val childId = authManager.getCurrentUser()?.id ?: ""
                        var isValid = false

                        // Primary: check parentalPin from Firestore children collection
                        if (childId.isNotEmpty()) {
                            try {
                                val doc = FirebaseFirestore.getInstance()
                                    .collection("children")
                                    .document(childId)
                                    .get()
                                    .await()
                                val firestorePin = doc.getString("parentalPin")
                                if (!firestorePin.isNullOrEmpty()) {
                                    isValid = firestorePin == pin
                                } else {
                                    // Fallback: check local session / user doc adminCode
                                    isValid = authManager.verifyAdminCode(pin)
                                }
                            } catch (e: Exception) {
                                // Network error — fall back to local
                                isValid = authManager.verifyAdminCode(pin)
                            }
                        } else {
                            isValid = authManager.verifyAdminCode(pin)
                        }

                        if (isValid) {
                            appBlockingService.extendTime(30)
                            android.widget.Toast.makeText(context, "Access Granted", android.widget.Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            error = "Invalid PIN"
                            pin = ""
                        }
                    }
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = error!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}
