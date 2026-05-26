package com.example.safe_kids.ui.child

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.services.DeviceAdminService
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.PermissionManager
import com.example.safe_kids.utils.PermissionUtils
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

/**
 * RequiredPermissionsActivity
 * 
 * Enforces that child must grant ALL required permissions before proceeding.
 * This activity is shown immediately after child selects "Child Mode".
 * 
 * Enhanced with:
 * - Auto-refresh on resume
 * - Safe intent handling with fallbacks
 * - OEM-specific support
 * - Permission state tracking (Granted/NotGranted/Unsupported/Skipped)
 * - "Continue Anyway" option for unsupported permissions
 * - No permanent blocking
 */
class RequiredPermissionsActivity : ComponentActivity() {

    private lateinit var authManager: AuthManagerImpl
    private var refreshTrigger = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("PermissionFlow", "=== RequiredPermissionsActivity onCreate ===")
        Log.d("PermissionFlow", "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}")
        Log.d("PermissionFlow", "OEM: ${PermissionManager.getOemName()}, Problematic: ${PermissionManager.isProblematicOEM()}")
        
        authManager = AuthManagerImpl(this)

        setContent {
            SAFE_KIDSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RequiredPermissionsScreen(
                        refreshTrigger = refreshTrigger.value,
                        onAllPermissionsGranted = { navigateToProfileSetup() }
                    )
                }
            }
        }

        // CRITICAL: Add LifecycleEventObserver as SECONDARY refresh mechanism
        // onResume() can be unreliable on some OEMs when activity is recreated
        // LifecycleEventObserver triggers on ANY lifecycle event, including
        // when returning from settings screens
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            Log.d("PermissionFlow", "LifecycleEventObserver: event=$event")
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                Log.d("PermissionFlow", "=== LifecycleEventObserver: ON_RESUME - refreshing permissions ===")
                refreshTrigger.value++
                Log.d("PermissionFlow", "Lifecycle refreshTrigger incremented to ${refreshTrigger.value}")
            }
        })
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("PermissionFlow", "=== onResume - User returned to permission screen ===")
        Log.d("PermissionFlow", "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.d("PermissionFlow", "Triggering permission state refresh...")
        
        // Force immediate refresh of permission states
        refreshTrigger.value++
        
        Log.d("PermissionFlow", "refreshTrigger incremented to ${refreshTrigger.value}")
    }
    
    override fun onPause() {
        super.onPause()
        Log.d("PermissionFlow", "=== onPause - User leaving permission screen (likely going to settings) ===")
    }

    private fun navigateToProfileSetup() {
        Log.d("RequiredPermissionsActivity", "All permissions granted! Navigating to EditChildProfileActivity...")
        
        lifecycleScope.launch {
            // Update Firestore to mark permissions as complete
            val currentUser = authManager.currentUser.value
            if (currentUser != null) {
                try {
                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(currentUser.id)
                        .update(mapOf(
                            "onboardingStep" to "PROFILE_SETUP",
                            "lastUpdated" to System.currentTimeMillis()
                        ))
                    Log.d("RequiredPermissionsActivity", "Updated Firestore: onboardingStep=PROFILE_SETUP")
                } catch (e: Exception) {
                    Log.e("RequiredPermissionsActivity", "Failed to update Firestore", e)
                }
            }
        }
        
        val intent = Intent(this, EditChildProfileActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}

@Composable
private fun RequiredPermissionsScreen(
    refreshTrigger: Int,
    onAllPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    
    Log.d("PermissionFlow", "=== RequiredPermissionsScreen recomposing, refreshTrigger=$refreshTrigger ===")
    
    // Get all required permissions
    val requiredPermissions = remember {
        PermissionManager.getAllRequiredPermissions()
    }
    
    // CRITICAL: Add a local skip trigger to force immediate UI refresh
    // when user skips a permission (without leaving the screen)
    var localSkipTrigger by remember { mutableIntStateOf(0) }
    val effectiveRefreshKey = refreshTrigger + localSkipTrigger
    
    // CRITICAL: Re-check permission states directly from system APIs every time
    // Do NOT cache - some OEMs return stale states
    val permissionStates = remember(effectiveRefreshKey) {
        Log.d("PermissionFlow", "Re-checking all permission states from system APIs...")
        requiredPermissions.associateWith { permission ->
            val state = PermissionManager.getPermissionState(context, permission)
            Log.d("PermissionFlow", "  ${PermissionManager.getPermissionName(permission)}: $state")
            state
        }
    }
    
    val grantedCount = permissionStates.count { it.value == PermissionManager.PermissionState.GRANTED }
    val unsupportedCount = permissionStates.count { it.value == PermissionManager.PermissionState.UNSUPPORTED }
    val skippedCount = permissionStates.count { it.value == PermissionManager.PermissionState.SKIPPED }
    val completedCount = grantedCount + unsupportedCount + skippedCount
    
    Log.d("PermissionFlow", "Progress: granted=$grantedCount, unsupported=$unsupportedCount, skipped=$skippedCount, completed=$completedCount, total=${requiredPermissions.size}")
    
    // Re-check these directly (not cached) to ensure fresh state
    val allGranted = PermissionManager.areAllPermissionsGranted(context)
    val essentialGranted = PermissionManager.areEssentialPermissionsGranted(context)
    
    Log.d("PermissionFlow", "allGranted=$allGranted, essentialGranted=$essentialGranted")
    
    // Show "Continue Anyway" if essential permissions are granted but some optional ones are not
    val canContinueAnyway = essentialGranted && !allGranted
    
    Log.d("PermissionFlow", "canContinueAnyway=$canContinueAnyway")
    
    // Request launchers for runtime permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d("PermissionFlow", "Runtime permissions result: $results")
        results.forEach { (permission, granted) ->
            Log.d("PermissionFlow", "  $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }
        // Trigger will be updated in onResume automatically
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column {
            Text(
                text = "Essential Permissions Required",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your device needs these permissions to protect you. Tap each permission to grant it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color.Gray
            )
            
            // OEM warning
            if (PermissionManager.isProblematicOEM()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF856404),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Some settings may look different on your device. We'll guide you through it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF856404)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        // Progress indicator
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Progress: $completedCount/${requiredPermissions.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (unsupportedCount > 0 || skippedCount > 0) {
                        Text(
                            text = "($grantedCount granted, $unsupportedCount unsupported, $skippedCount skipped)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { if (requiredPermissions.isEmpty()) 0f else completedCount.toFloat() / requiredPermissions.size },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                        .height(6.dp),
                    color = if (allGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    trackColor = Color.LightGray
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Permissions list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(requiredPermissions) { permission ->
                val state = permissionStates[permission] ?: PermissionManager.PermissionState.NOT_GRANTED
                PermissionListItem(
                    permission = permission,
                    state = state,
                    onRequestPermission = {
                        handlePermissionRequest(context, permission, permissionLauncher)
                    },
                    onSkipPermission = {
                        Log.d("PermissionFlow", "User skipping permission: ${PermissionManager.getPermissionName(permission)}")
                        PermissionManager.markPermissionAsSkipped(context, permission)
                        // Force immediate recomposition so UI updates right away
                        localSkipTrigger++
                        Log.d("PermissionFlow", "localSkipTrigger incremented to $localSkipTrigger (force UI refresh)")
                    }
                )
            }
        }
        
        // Continue buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Primary continue button
            Button(
                onClick = { onAllPermissionsGranted() },
                enabled = allGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (allGranted) Color(0xFF4CAF50) else Color.LightGray,
                    disabledContainerColor = Color.LightGray
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (allGranted) "Continue to Profile Setup" else "Grant All Permissions to Continue",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            
            // "Continue Anyway" button for when essential permissions are granted
            if (canContinueAnyway) {
                OutlinedButton(
                    onClick = { onAllPermissionsGranted() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
                ) {
                    Text(
                        text = "Continue Anyway (Some permissions skipped)",
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionListItem(
    permission: PermissionManager.RequiredPermission,
    state: PermissionManager.PermissionState,
    onRequestPermission: () -> Unit,
    onSkipPermission: () -> Unit
) {
    val isGranted = state == PermissionManager.PermissionState.GRANTED
    val isUnsupported = state == PermissionManager.PermissionState.UNSUPPORTED
    val isSkipped = state == PermissionManager.PermissionState.SKIPPED
    
    val backgroundColor = when (state) {
        PermissionManager.PermissionState.GRANTED -> Color(0xFFF1F5FE)
        PermissionManager.PermissionState.UNSUPPORTED -> Color(0xFFFFF3CD)
        PermissionManager.PermissionState.SKIPPED -> Color(0xFFE8E8E8)
        else -> Color.White
    }
    
    val iconColor = when (state) {
        PermissionManager.PermissionState.GRANTED -> Color(0xFF4CAF50)
        PermissionManager.PermissionState.UNSUPPORTED -> Color(0xFFFF9800)
        PermissionManager.PermissionState.SKIPPED -> Color.Gray
        else -> Color(0xFF3B4FBF)
    }
    
    val statusText = when (state) {
        PermissionManager.PermissionState.GRANTED -> "✓ Granted"
        PermissionManager.PermissionState.UNSUPPORTED -> "⚠ Not supported on this device"
        PermissionManager.PermissionState.SKIPPED -> "⊘ Skipped"
        PermissionManager.PermissionState.NOT_GRANTED -> "Not yet granted"
        PermissionManager.PermissionState.CHECKING -> "Checking..."
    }
    
    val statusColor = when (state) {
        PermissionManager.PermissionState.GRANTED -> Color(0xFF4CAF50)
        PermissionManager.PermissionState.UNSUPPORTED -> Color(0xFFFF9800)
        PermissionManager.PermissionState.SKIPPED -> Color.Gray
        else -> Color.Red
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted && !isUnsupported && !isSkipped) { onRequestPermission() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(iconColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (state) {
                                PermissionManager.PermissionState.GRANTED -> Icons.Default.CheckCircle
                                PermissionManager.PermissionState.UNSUPPORTED -> Icons.Default.Warning
                                PermissionManager.PermissionState.SKIPPED -> Icons.Default.Close
                                else -> Icons.Default.Circle
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = PermissionManager.getPermissionName(permission),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontSize = 12.sp
                        )
                    }
                }
                
                if (!isGranted && !isUnsupported && !isSkipped) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Grant permission",
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            // Show skip button for non-essential permissions
            if (!isGranted && !isUnsupported && !isSkipped && !isEssentialPermission(permission)) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        Log.d("PermissionFlow", "User clicked skip for ${PermissionManager.getPermissionName(permission)}")
                        onSkipPermission()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Skip this permission",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

private fun isEssentialPermission(permission: PermissionManager.RequiredPermission): Boolean {
    return when (permission) {
        PermissionManager.RequiredPermission.ACCESSIBILITY_SERVICE,
        PermissionManager.RequiredPermission.USAGE_ACCESS,
        PermissionManager.RequiredPermission.LOCATION -> true
        else -> false
    }
}

private fun handlePermissionRequest(
    context: Context,
    permission: PermissionManager.RequiredPermission,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    Log.d("PermissionFlow", "=== handlePermissionRequest: ${PermissionManager.getPermissionName(permission)} ===")
    Log.d("PermissionFlow", "OEM: ${Build.MANUFACTURER}, Problematic: ${PermissionManager.isProblematicOEM()}")
    
    when (permission) {
        PermissionManager.RequiredPermission.LOCATION -> {
            Log.d("PermissionFlow", "Requesting runtime location permissions")
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        PermissionManager.RequiredPermission.BACKGROUND_LOCATION -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d("PermissionFlow", "Requesting background location permission (Android 10+)")
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            } else {
                Log.d("PermissionFlow", "Background location not needed on Android < 10")
            }
        }
        PermissionManager.RequiredPermission.CAMERA -> {
            Log.d("PermissionFlow", "Requesting runtime camera permission")
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        PermissionManager.RequiredPermission.CALL_LOG -> {
            Log.d("PermissionFlow", "Requesting runtime call log permission")
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
        }
        PermissionManager.RequiredPermission.SMS -> {
            Log.d("PermissionFlow", "Requesting runtime SMS permissions")
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS
                )
            )
        }
        PermissionManager.RequiredPermission.ACCESSIBILITY_SERVICE -> {
            openAccessibilitySettings(context)
        }
        PermissionManager.RequiredPermission.DRAW_OVER_APPS -> {
            openOverlaySettings(context)
        }
        PermissionManager.RequiredPermission.USAGE_ACCESS -> {
            openUsageAccessSettings(context)
        }
        PermissionManager.RequiredPermission.DO_NOT_DISTURB -> {
            openDNDSettings(context)
        }
        PermissionManager.RequiredPermission.NOTIFICATION_LISTENER -> {
            openNotificationListenerSettings(context)
        }
        PermissionManager.RequiredPermission.BATTERY_OPTIMIZATION -> {
            openBatteryOptimizationSettings(context)
        }
        PermissionManager.RequiredPermission.DEVICE_ADMIN -> {
            openDeviceAdminSettings(context)
        }
    }
}

/**
 * Safe intent launchers with fallbacks for each permission type
 * All intents wrapped in try-catch with comprehensive logging
 */

private fun openAccessibilitySettings(context: Context) {
    Log.d("PermissionFlow", "Opening Accessibility Settings...")
    try {
        // Primary intent
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Successfully opened Accessibility Settings")
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Primary Accessibility intent failed", e)
        try {
            // Fallback 1: Try app details (some OEMs have accessibility inside app settings)
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            Log.d("PermissionFlow", "✓ Opened app details as fallback for Accessibility")
            Toast.makeText(context, "Please enable 'SafeKids Accessibility Service' in these settings", Toast.LENGTH_LONG).show()
        } catch (e2: Exception) {
            Log.e("PermissionFlow", "✗ All Accessibility intents failed - marking as likely unsupported", e2)
            // Mark as unsupported - this device likely doesn't support standard accessibility
            PermissionManager.markPermissionAsUnsupported(context, PermissionManager.RequiredPermission.ACCESSIBILITY_SERVICE)
            Log.d("PermissionFlow", "Marked Accessibility as unsupported (all intents failed)")
            Toast.makeText(context, "Accessibility settings not available on this device. You may need to skip or continue.", Toast.LENGTH_LONG).show()
            openGeneralSettings(context, "Accessibility Service")
        }
    }
}

private fun openOverlaySettings(context: Context) {
    Log.d("PermissionFlow", "Opening Overlay Permission Settings...")
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Successfully opened Overlay Settings")
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Primary overlay intent failed, trying fallback", e)
        try {
            // Fallback: Open app details settings
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            Log.d("PermissionFlow", "✓ Opened app details settings as fallback")
        } catch (e2: Exception) {
            Log.e("PermissionFlow", "✗ Fallback also failed", e2)
            openGeneralSettings(context, "Overlay Permission")
        }
    }
}

private fun openUsageAccessSettings(context: Context) {
    Log.d("PermissionFlow", "Opening Usage Access Settings...")
    try {
        // Primary intent
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Successfully opened Usage Access Settings")
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Primary Usage Access intent failed", e)
        try {
            // Fallback: Try app details (Xiaomi MIUI redirects here)
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            Log.d("PermissionFlow", "✓ Opened app details as fallback for Usage Access")
            Toast.makeText(context, "Please find 'Usage Access' permission for SafeKids in these settings", Toast.LENGTH_LONG).show()
        } catch (e2: Exception) {
            Log.e("PermissionFlow", "✗ All Usage Access intents failed - marking as unsupported", e2)
            // This is extremely rare but handle gracefully
            PermissionManager.markPermissionAsUnsupported(context, PermissionManager.RequiredPermission.USAGE_ACCESS)
            Log.d("PermissionFlow", "Marked Usage Access as unsupported (all intents failed)")
            Toast.makeText(context, "Usage Access not available on this device. This is an essential permission.", Toast.LENGTH_LONG).show()
            openGeneralSettings(context, "Usage Access")
        }
    }
}

private fun openDNDSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        Log.d("PermissionFlow", "DND not available on Android < 6, marking as unsupported")
        Toast.makeText(context, "DND not available on this Android version", Toast.LENGTH_SHORT).show()
        return
    }
    
    Log.d("PermissionFlow", "Opening DND Settings...")
    try {
        // Primary intent: Open DND access settings
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Successfully opened DND Settings")
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Primary DND intent failed", e)
        // Mark as unsupported since device cannot open DND settings
        PermissionManager.markPermissionAsUnsupported(context, PermissionManager.RequiredPermission.DO_NOT_DISTURB)
        Log.d("PermissionFlow", "Marked DND as unsupported (intent failed at runtime)")
        Toast.makeText(context, "Do Not Disturb not available on this device. You can skip it.", Toast.LENGTH_LONG).show()
        openGeneralSettings(context, "Do Not Disturb")
    }
}

private fun openNotificationListenerSettings(context: Context) {
    Log.d("PermissionFlow", "Opening Notification Listener Settings...")
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Successfully opened Notification Listener Settings")
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Notification Listener not supported on this device", e)
        // Mark as unsupported - both runtime AND persisted
        PermissionUtils.isNotificationListenerNotSupported = true
        PermissionManager.markPermissionAsUnsupported(context, PermissionManager.RequiredPermission.NOTIFICATION_LISTENER)
        Log.d("PermissionFlow", "Marked Notification Listener as unsupported (runtime + persisted)")
        Toast.makeText(
            context,
            "Notification Listener not supported on this device. You can skip it.",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    Log.d("PermissionFlow", "Opening Battery Optimization Settings...")
    try {
        // Primary: Direct request dialog (best case - just a dialog)
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Successfully opened Battery Optimization Dialog")
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Primary battery intent failed, trying fallback", e)
        try {
            // Fallback 1: Open battery optimization list
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            Log.d("PermissionFlow", "✓ Opened Battery Optimization List as fallback")
        } catch (e2: Exception) {
            Log.e("PermissionFlow", "✗ Fallback battery intent also failed", e2)
            try {
                // Fallback 2: Open power settings (some OEMs like Xiaomi have it here)
                val powerIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(powerIntent)
                Log.d("PermissionFlow", "✓ Opened Battery Saver Settings as 2nd fallback")
                Toast.makeText(context, "Please exclude SafeKids from battery optimization in these settings", Toast.LENGTH_LONG).show()
            } catch (e3: Exception) {
                Log.e("PermissionFlow", "✗ All battery intents failed", e3)
                openGeneralSettings(context, "Battery Optimization")
            }
        }
    }
}

private fun openDeviceAdminSettings(context: Context) {
    Log.d("PermissionFlow", "Opening Device Admin Settings...")
    try {
        // Primary: Direct intent to add device admin
        val componentName = ComponentName(context, DeviceAdminService::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "SafeKids needs Device Admin to protect your device"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Successfully opened Device Admin Settings")
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Failed to open Device Admin Settings", e)
        try {
            // Fallback: Open security settings (some OEMs have device admin here)
            val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            Log.d("PermissionFlow", "✓ Opened Security settings as fallback for Device Admin")
            Toast.makeText(context, "Please enable 'SafeKids' as a Device Admin in these settings", Toast.LENGTH_LONG).show()
        } catch (e2: Exception) {
            Log.e("PermissionFlow", "✗ All Device Admin intents failed", e2)
            openGeneralSettings(context, "Device Admin")
        }
    }
}

/**
 * Ultimate fallback: Open general settings screen
 * This should NEVER crash
 */
private fun openGeneralSettings(context: Context, settingName: String) {
    Log.d("PermissionFlow", "Opening general settings as ultimate fallback for $settingName")
    try {
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d("PermissionFlow", "✓ Opened general settings")
        Toast.makeText(
            context,
            "$settingName: Please find the setting manually. You can also skip it.",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Log.e("PermissionFlow", "✗ Even general settings failed!", e)
        // This should be impossible, but handle it gracefully
        Toast.makeText(
            context,
            "$settingName not available on this device. You can skip it.",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun showSettingsError(context: Context, settingName: String) {
    Log.d("PermissionFlow", "Showing error message for $settingName")
    Toast.makeText(
        context,
        "$settingName not available on this device. You can skip it.",
        Toast.LENGTH_LONG
    ).show()
}
