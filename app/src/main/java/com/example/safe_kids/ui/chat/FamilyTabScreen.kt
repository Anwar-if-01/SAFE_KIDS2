package com.example.safe_kids.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.UserRole
import com.example.safe_kids.auth.isParentLike
import com.example.safe_kids.data.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyTabScreen(
    viewModel: FamilyViewModel,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Chat", "Requests")
    val currentUser by viewModel.currentUser.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Hub", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> ChatSection(viewModel)
                1 -> RequestsSection(viewModel)
            }
        }
    }
}

@Composable
fun ChatSection(viewModel: FamilyViewModel) {
    val messages by viewModel.messages.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    var messageText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message, isMe = message.senderId == currentUser?.id)
            }
        }

        ChatInputBar(
            text = messageText,
            onTextChange = { messageText = it },
            onSend = {
                viewModel.sendMessage(messageText)
                messageText = ""
            }
        )
    }
}

@Composable
fun MessageBubble(message: ChatMessage, isMe: Boolean) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = timeFormat.format(Date(message.timestamp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMe) 16.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 16.dp
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.message,
                    color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMe) Color.White.copy(alpha = 0.7f) else Color.Gray,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun RequestsSection(viewModel: FamilyViewModel) {
    val requests by viewModel.requests.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (currentUser?.role == UserRole.CHILD) {
            Button(
                onClick = { viewModel.sendRequest(RequestType.PERMISSION, "Can I use YouTube for 30 mins?") },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New Permission Request")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(requests) { request ->
                RequestCard(request, isParent = currentUser?.role?.isParentLike == true) { status ->
                    viewModel.updateRequestStatus(request.id, status)
                }
            }
        }
    }
}

@Composable
fun RequestCard(request: FamilyRequest, isParent: Boolean, onStatusChange: (RequestStatus) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when(request.type) {
                            RequestType.PERMISSION -> Icons.Default.LockOpen
                            RequestType.SCREEN_TIME -> Icons.Default.Timer
                            else -> Icons.Default.QuestionMark
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(request.senderName, fontWeight = FontWeight.Bold)
                    Text(request.type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                }
                
                StatusBadge(request.status)
            }
            
            Spacer(Modifier.height(12.dp))
            Text(request.content, style = MaterialTheme.typography.bodyLarge)
            
            if (isParent && request.status == RequestStatus.PENDING) {
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onStatusChange(RequestStatus.REJECTED) }) {
                        Text("Reject", color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = { onStatusChange(RequestStatus.APPROVED) }) {
                        Text("Approve")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { /* Voice */ }) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = Color.Gray)
            }
            
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
                placeholder = { Text("Type here...", color = Color.Gray) },
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 4
            )
            
            if (text.isNotBlank()) {
                IconButton(onClick = onSend) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Row {
                    IconButton(onClick = { /* Attach */ }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = Color.Gray)
                    }
                }
            }
        }
    }
}


@Composable
fun StatusBadge(status: RequestStatus) {
    val color = when(status) {
        RequestStatus.PENDING -> Color(0xFFFFA000)
        RequestStatus.APPROVED -> Color(0xFF4CAF50)
        RequestStatus.REJECTED -> Color(0xFFF44336)
        RequestStatus.DENIED -> Color(0xFFF44336)
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Text(
            text = status.name,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
