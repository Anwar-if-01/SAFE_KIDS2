package com.example.safe_kids.ui.parent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.auth.User
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import kotlinx.coroutines.launch

class ManageCoParentsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authManager = AuthManagerImpl(this)
        lifecycleScope.launch {
            authManager.initialize()
        }
        setContent {
            SAFE_KIDSTheme {
                ManageCoParentsScreen(authManager = authManager, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageCoParentsScreen(authManager: AuthManagerImpl, onBack: () -> Unit) {
    var coParents by remember { mutableStateOf<List<User>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRemoving by remember { mutableStateOf<String?>(null) }
    var showInviteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val refreshCoParents = {
        scope.launch {
            val result = authManager.getCoParents()
            if (result.isSuccess) {
                coParents = result.getOrNull() ?: emptyList()
            } else {
                Toast.makeText(context, "Failed to load co-parents", Toast.LENGTH_SHORT).show()
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshCoParents()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Members", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showInviteDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, "Invite Co-parent")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (coParents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No co-parents linked yet.", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(coParents) { coParent ->
                    CoParentItem(
                        coParent = coParent,
                        isRemoving = isRemoving == coParent.id,
                        onRemove = {
                            scope.launch {
                                isRemoving = coParent.id
                                val result = authManager.removeCoParent(coParent.id)
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Co-parent removed successfully", Toast.LENGTH_SHORT).show()
                                    refreshCoParents()
                                } else {
                                    Toast.makeText(context, "Failed to remove co-parent", Toast.LENGTH_SHORT).show()
                                }
                                isRemoving = null
                            }
                        }
                    )
                }
            }
        }

        if (showInviteDialog) {
            InviteCoParentDialog(
                authManager = authManager,
                onDismiss = { showInviteDialog = false }
            )
        }
    }
}

@Composable
private fun CoParentItem(
    coParent: User,
    isRemoving: Boolean,
    onRemove: () -> Unit
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove Co-parent") },
            text = { Text("Are you sure you want to remove ${coParent.displayName ?: "this user"}? This will revoke their access to your children's dashboard and location data.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    onRemove()
                }) {
                    Text("Remove", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
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
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(coParent.displayName ?: "Co-parent", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(coParent.email, color = Color.Gray, fontSize = 14.sp)
                Text("Role: Co-parent", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            
            Spacer(Modifier.width(8.dp))
            
            if (isRemoving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { showRemoveDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
@Composable
fun InviteCoParentDialog(
    authManager: com.example.safe_kids.auth.AuthManager,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var inviteEmail by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite Co-Parent") },
        text = {
            Column {
                if (inviteCode != null) {
                    Text("Invitation Code:")
                    Text(inviteCode!!, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    Text("Share this code with the co-parent. It expires in 24 hours.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Enter the email address of the person you want to invite.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        label = { Text("Email Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (inviteCode != null) {
                Button(onClick = onDismiss) { Text("Done") }
            } else {
                Button(
                    onClick = {
                        if (inviteEmail.isNotBlank()) {
                            isGenerating = true
                            coroutineScope.launch {
                                val result = authManager.generateCoParentInviteCode(inviteEmail.trim())
                                isGenerating = false
                                if (result.isSuccess) {
                                    inviteCode = result.getOrNull()
                                }
                            }
                        }
                    },
                    enabled = !isGenerating && inviteEmail.isNotBlank()
                ) {
                    if (isGenerating) CircularProgressIndicator(modifier = Modifier.size(24.dp)) else Text("Generate Code")
                }
            }
        },
        dismissButton = {
            if (inviteCode == null) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
