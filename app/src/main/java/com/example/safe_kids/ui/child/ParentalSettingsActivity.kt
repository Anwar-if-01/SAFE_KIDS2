package com.example.safe_kids.ui.child

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.PermissionUtils
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver

class ParentalSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SAFE_KIDSTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "menu") {
                    composable("menu") { ParentalSettingsScreen(navController) }
                    composable("protection") { FullProtectionScreen(navController) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentalSettingsScreen(navController: NavController) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parental Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsMenuItem(
                title = "Activate Full Protection",
                description = "With your child's permission, you can enable more comprehensive protection features.",
                icon = Icons.Default.CheckCircle,
                iconColor = Color(0xFF22C55E),
                onClick = { navController.navigate("protection") }
            )
        }
    }
}

@Composable
fun SettingsMenuItem(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullProtectionScreen(navController: NavController) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activate Full Protection", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "With your child's permission, you can enable more comprehensive protection features.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )

            ProtectionGroup(
                title = "Calls & SMS Monitoring",
                description = "Spot risks in your child's communication activity.",
                icon = Icons.Default.Phone,
                iconColor = Color(0xFF22C55E),
                permissions = listOf(
                    PermissionStatus("Phone Permission", PermissionUtils.hasPhonePermissions(context)),
                    PermissionStatus("Contacts Permission", PermissionUtils.hasContactsPermissions(context)),
                    PermissionStatus("SMS Permission", PermissionUtils.hasSmsPermissions(context))
                )
            )

            ProtectionGroup(
                title = "Inappropriate Image Detection",
                description = "Get alerted to inappropriate photos in your child's album.",
                icon = Icons.Default.Image,
                iconColor = Color(0xFF5C6BC0),
                permissions = listOf(
                    PermissionStatus("Storage permission", PermissionUtils.hasStoragePermissions(context))
                )
            )
        }
    }
}

@Composable
fun ProtectionGroup(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    permissions: List<PermissionStatus>
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        
        Column(modifier = Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            permissions.forEach { permission ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = permission.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Icon(
                        imageVector = if (permission.isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (permission.isGranted) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

data class PermissionStatus(val name: String, val isGranted: Boolean)
