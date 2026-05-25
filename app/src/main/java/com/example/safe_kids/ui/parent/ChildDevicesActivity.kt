package com.example.safe_kids.ui.parent

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.auth.User
import com.example.safe_kids.auth.UserRole
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

class ChildDevicesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        val authManager = AuthManagerImpl(this)
        lifecycleScope.launch {
            authManager.initialize()
        }
        setContent {
            SAFE_KIDSTheme {
                ChildDevicesScreen(authManager = authManager, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildDevicesScreen(authManager: AuthManagerImpl, onBack: () -> Unit) {
    val currentUser by authManager.currentUser.collectAsState()
    var children by remember { mutableStateOf<List<User>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isUnbinding by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val refreshChildren = {
        scope.launch {
            if (currentUser != null) {
                val result = authManager.getChildrenForParent(currentUser!!.id)
                if (result.isSuccess) {
                    children = result.getOrNull() ?: emptyList()
                }
            }
        }
    }

    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            refreshChildren()
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Child Devices", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (children.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No child devices connected.", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(children) { child ->
                    ChildDeviceItem(
                        child = child,
                        authManager = authManager,
                        currentUser = currentUser,
                        scope = scope,
                        onRefresh = { refreshChildren() },
                        onUnbind = {
                            scope.launch {
                                isUnbinding = child.id
                                val result = authManager.unbindChild(currentUser!!.id, child.id)
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Unbound successfully", Toast.LENGTH_SHORT).show()
                                    refreshChildren()
                                } else {
                                    Toast.makeText(context, "Failed to unbind device", Toast.LENGTH_SHORT).show()
                                }
                                isUnbinding = null
                            }
                        },
                        isUnbinding = isUnbinding == child.id
                    )
                }
            }
        }
    }
}

@Composable
private fun ChildDeviceItem(
    child: User,
    authManager: AuthManagerImpl,
    currentUser: User?,
    scope: kotlinx.coroutines.CoroutineScope,
    onRefresh: () -> Unit,
    onUnbind: () -> Unit,
    isUnbinding: Boolean
) {
    val context = LocalContext.current
    var showUnbindDialog by remember { mutableStateOf(false) }
    val isCoParent = currentUser?.role == UserRole.CO_PARENT

    fun checkRestriction(action: () -> Unit) {
        if (isCoParent) {
            Toast.makeText(context, "Co-parents cannot modify device settings.", Toast.LENGTH_SHORT).show()
        } else {
            action()
        }
    }


    if (showUnbindDialog) {
        AlertDialog(
            onDismissRequest = { showUnbindDialog = false },
            title = { Text("Unbind Device") },
            text = { Text("Are you sure you want to unbind ${child.displayName ?: "this device"}? You will no longer be able to monitor or control it.") },
            confirmButton = {
                TextButton(onClick = {
                    showUnbindDialog = false
                    onUnbind()
                }) {
                    Text("Unbind", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnbindDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar: real Firebase Storage image, emoji preset, or initial fallback
            val isNetworkUrl = child.profileImageUrl?.startsWith("http") == true
            val emojiAvatars = mapOf(
                "boy1" to "👦", "girl1" to "👧",
                "boy2" to "🧒", "girl2" to "👩",
                "boy3" to "👦", "girl3" to "👧",
                "boy4" to "🧑"
            )
            val emojiForAvatar = if (!isNetworkUrl) emojiAvatars[child.profileImageUrl] else null
            val initial = (child.displayName ?: "C").take(1).uppercase()

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isNetworkUrl -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(child.profileImageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "${child.displayName}'s avatar",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    emojiForAvatar != null -> {
                        Text(emojiForAvatar, fontSize = 24.sp)
                    }
                    else -> {
                        Text(
                            text = initial,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(child.displayName ?: "Child Device", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (child.deviceName != null) {
                    Text(child.deviceName, color = Color.Gray, fontSize = 14.sp)
                } else {
                    Text(child.email, color = Color.Gray, fontSize = 14.sp)
                }
            }
            
            // Protection Toggle
            var isProtectionChanging by remember { mutableStateOf(false) }
            Switch(
                checked = child.isProtectionEnabled,
                onCheckedChange = { enabled ->
                    checkRestriction {
                        isProtectionChanging = true
                        scope.launch {
                            val result = authManager.updateUserProfile(mapOf("isProtectionEnabled" to enabled), child.id)
                            if (result.isSuccess) {
                                onRefresh()
                            } else {
                                Toast.makeText(context, "Failed to update protection", Toast.LENGTH_SHORT).show()
                            }
                            isProtectionChanging = false
                        }
                    }
                },
                enabled = !isProtectionChanging && !isUnbinding,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.surface,
                    checkedTrackColor = Color(0xFF22C55E)
                )
            )
            
            Spacer(Modifier.width(8.dp))
            
            if (isUnbinding) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { checkRestriction { showUnbindDialog = true } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Unbind", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
